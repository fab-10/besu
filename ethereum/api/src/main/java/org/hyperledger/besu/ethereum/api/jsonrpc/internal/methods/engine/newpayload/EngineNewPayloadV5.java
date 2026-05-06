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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.newpayload;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.datatypes.HardforkId;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineCallListener;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.engine.ExecutionPayloadV4;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.core.encoding.BlockAccessListDecoder;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.List;
import java.util.Optional;

import io.vertx.core.Vertx;
import org.apache.tuweni.bytes.Bytes;

/**
 * {@code engine_newPayloadV5} — Amsterdam (EIP-7843 Slot Number, Block Access List).
 *
 * <p>Bumps the payload type to {@link ExecutionPayloadV4} (V3 + {@code slotNumber}, {@code
 * blockAccessList}). Note: the spec breaks the symmetry between method version and payload version
 * here — payload V4 ships as part of method V5, not method V4.
 *
 * <h3>Adding V6</h3>
 *
 * <ol>
 *   <li>Mark this class {@code sealed permits EngineNewPayloadV6} (drop {@code final}).
 *   <li>If V6 introduces new payload fields, add {@code ExecutionPayloadV5 extends
 *       ExecutionPayloadV4} and bump V6's bound. Otherwise V6 reuses {@link ExecutionPayloadV4}.
 *   <li>Create {@code EngineNewPayloadV6 extends EngineNewPayloadV5}.
 *   <li>Update the wiring in {@code ExecutionEngineJsonRpcMethods.createEngineNewPayloadMethods}
 *       to chain {@code .thenFrom(NEXT_FORK, EngineNewPayloadV6.class)}.
 * </ol>
 *
 * @param <EP> the {@link ExecutionPayloadV4} (or subtype) this version accepts as RPC parameter
 *     index 0
 */
public final class EngineNewPayloadV5<EP extends ExecutionPayloadV4>
    extends EngineNewPayloadV4<EP> {

  EngineNewPayloadV5(
      final Vertx vertx,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final MergeMiningCoordinator mergeCoordinator,
      final EthPeers ethPeers,
      final EngineCallListener engineCallListener,
      final MetricsSystem metricsSystem,
      final HardforkId minSupportedFork,
      final HardforkId firstUnsupportedFork) {
    super(
        vertx,
        protocolSchedule,
        protocolContext,
        mergeCoordinator,
        ethPeers,
        engineCallListener,
        metricsSystem,
        minSupportedFork,
        firstUnsupportedFork);
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_NEW_PAYLOAD_V5.getMethodName();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Class<EP> getEnginePayloadParameterClass() {
    return (Class<EP>) ExecutionPayloadV4.class;
  }

  @Override
  protected ValidationResult<RpcErrorType> validateParameters(
      final EP payloadParameter,
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
  protected Long payloadSlotNumber(final EP payload) {
    return payload.getSlotNumber();
  }

  @Override
  protected Optional<BlockAccessList> extractBlockAccessList(final EP payloadParameter)
      throws InvalidBlockAccessListException {
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
}
