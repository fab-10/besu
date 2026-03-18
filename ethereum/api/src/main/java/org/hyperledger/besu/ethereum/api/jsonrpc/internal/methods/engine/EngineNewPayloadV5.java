/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.AMSTERDAM;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.EnginePayloadParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadStatusResult;
import org.hyperledger.besu.ethereum.core.InclusionListValidationResult;
import org.hyperledger.besu.ethereum.core.InclusionListValidator;
import org.hyperledger.besu.ethereum.core.encoding.BlockAccessListDecoder;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.vertx.core.Vertx;
import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineNewPayloadV5 extends AbstractEngineNewPayload {

  private static final Logger LOG = LoggerFactory.getLogger(EngineNewPayloadV5.class);

  private final InclusionListValidator inclusionListValidator;
  private final Counter validationFailuresCounter;

  public EngineNewPayloadV5(
      final Vertx vertx,
      final ProtocolSchedule timestampSchedule,
      final ProtocolContext protocolContext,
      final MergeMiningCoordinator mergeCoordinator,
      final EthPeers ethPeers,
      final EngineCallListener engineCallListener,
      final MetricsSystem metricsSystem,
      final InclusionListValidator inclusionListValidator) {
    super(
        vertx,
        timestampSchedule,
        protocolContext,
        mergeCoordinator,
        ethPeers,
        engineCallListener,
        metricsSystem);
    this.inclusionListValidator = inclusionListValidator;
    this.validationFailuresCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.RPC,
            "engine_inclusion_list_validation_failures",
            "Total number of inclusion list validation failures");
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_NEW_PAYLOAD_V5.getMethodName();
  }

  /**
   * Validates inclusion list constraints before processing the payload. Per engine-api-eip7805.md:
   * returns INCLUSION_LIST_UNSATISFIED with latestValidHash=null and validationError=null if the
   * payload does not satisfy the inclusion list constraints.
   */
  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    final List<String> inclusionListTxHexStrings = extractInclusionListTransactions(requestContext);

    if (!inclusionListTxHexStrings.isEmpty()) {
      final EnginePayloadParameter blockParam;
      try {
        blockParam = requestContext.getRequiredParameter(0, EnginePayloadParameter.class);
      } catch (final JsonRpcParameterException e) {
        return super.syncResponse(requestContext);
      }

      final List<Bytes> inclusionListTxBytes =
          inclusionListTxHexStrings.stream().map(Bytes::fromHexString).toList();
      final List<Bytes> payloadTxBytes =
          blockParam.getTransactions().stream().map(Bytes::fromHexString).toList();

      LOG.atDebug()
          .setMessage("IL validation: block={}, IL txs={}, payload txs={}, validator={}")
          .addArgument(blockParam.getBlockHash())
          .addArgument(inclusionListTxBytes.size())
          .addArgument(payloadTxBytes.size())
          .addArgument(inclusionListValidator.getClass().getSimpleName())
          .log();

      final Set<Bytes> payloadTxSet = new HashSet<>(payloadTxBytes);
      final boolean allPresent = inclusionListTxBytes.stream().allMatch(payloadTxSet::contains);

      if (!allPresent) {
        final InclusionListValidationResult ilResult =
            inclusionListValidator.validate(payloadTxBytes, inclusionListTxBytes);

        if (ilResult.getStatus() == InclusionListValidationResult.Status.UNSATISFIED) {
          validationFailuresCounter.inc();
          LOG.warn(
              "Inclusion list unsatisfied for block {}: {}",
              blockParam.getBlockHash(),
              ilResult.getErrorMessage().orElse("unknown"));
          // Per spec: latestValidHash MUST be null, validationError MUST be null
          return new JsonRpcSuccessResponse(
              requestContext.getRequest().getId(),
              new EnginePayloadStatusResult(
                  EngineStatus.INCLUSION_LIST_UNSATISFIED, null, Optional.empty()));
        }

        if (ilResult.getStatus() == InclusionListValidationResult.Status.INVALID) {
          validationFailuresCounter.inc();
          LOG.warn(
              "Inclusion list invalid for block {}: {}",
              blockParam.getBlockHash(),
              ilResult.getErrorMessage().orElse("unknown"));
        }
      } else {
        LOG.atInfo()
            .setMessage("IL validation passed for block {}: {} IL transactions present")
            .addArgument(blockParam.getBlockHash())
            .addArgument(inclusionListTxBytes.size())
            .log();
      }
    }

    return super.syncResponse(requestContext);
  }

  @Override
  protected ValidationResult<RpcErrorType> validateParameters(
      final EnginePayloadParameter payloadParameter,
      final Optional<List<String>> maybeVersionedHashParam,
      final Optional<String> maybeBeaconBlockRootParam,
      final Optional<List<String>> maybeRequestsParam) {
    if (payloadParameter.getBlobGasUsed() == null) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_BLOB_GAS_USED_PARAMS, "Missing blob gas used field");
    } else if (payloadParameter.getExcessBlobGas() == null) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_EXCESS_BLOB_GAS_PARAMS, "Missing excess blob gas field");
    } else if (maybeVersionedHashParam == null || maybeVersionedHashParam.isEmpty()) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_VERSIONED_HASH_PARAMS, "Missing versioned hashes field");
    } else if (maybeBeaconBlockRootParam.isEmpty()) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_PARENT_BEACON_BLOCK_ROOT_PARAMS,
          "Missing parent beacon block root field");
    } else if (maybeRequestsParam.isEmpty()) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_EXECUTION_REQUESTS_PARAMS, "Missing execution requests field");
    } else if (payloadParameter.getSlotNumber() == null) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_SLOT_NUMBER_PARAMS, "Missing slot number field");
    }
    return ValidationResult.valid();
  }

  @Override
  protected Optional<BlockAccessList> extractBlockAccessList(
      final EnginePayloadParameter payloadParameter) throws InvalidBlockAccessListException {
    final String blockAccessList = payloadParameter.getBlockAccessList();
    if (blockAccessList == null || blockAccessList.isEmpty()) {
      throw new InvalidBlockAccessListException("Missing block access list field");
    }
    final Bytes encoded;
    try {
      encoded = Bytes.fromHexString(blockAccessList);
    } catch (final IllegalArgumentException e) {
      throw new InvalidBlockAccessListException("Invalid block access list encoding", e);
    }
    try {
      return Optional.of(BlockAccessListDecoder.decode(new BytesValueRLPInput(encoded, false)));
    } catch (final RuntimeException e) {
      throw new InvalidBlockAccessListException("Invalid block access list encoding", e);
    }
  }

  @Override
  protected ValidationResult<RpcErrorType> validateForkSupported(final long blockTimestamp) {
    return ForkSupportHelper.validateForkSupported(AMSTERDAM, amsterdamMilestone, blockTimestamp);
  }

  private List<String> extractInclusionListTransactions(
      final JsonRpcRequestContext requestContext) {
    try {
      final Optional<List<String>> maybeILTxs = requestContext.getOptionalList(4, String.class);
      return maybeILTxs.orElse(Collections.emptyList());
    } catch (final JsonRpcParameterException e) {
      LOG.debug("No inclusion list transactions parameter provided", e);
      return Collections.emptyList();
    }
  }
}
