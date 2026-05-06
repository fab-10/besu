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
import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.HardforkId;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineCallListener;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.engine.ExecutionPayloadV3;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.feemarket.ExcessBlobGasCalculator;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Vertx;

/**
 * {@code engine_newPayloadV3} — Cancun (Blobs and Beacon Block Root).
 *
 * <p>Accepts {@link ExecutionPayloadV3} (V2 + {@code blobGasUsed}, {@code excessBlobGas}). Requires
 * the JSON-RPC {@code versionedHashes} list and {@code parentBeaconBlockRoot} parameters; rejects
 * the {@code requests} parameter that V4 introduces. Adds full blob validation against the
 * transactions, header and parent; later versions inherit this implementation.
 *
 * <p>Parameterised so V4 can extend this class while keeping the same payload type.
 */
public sealed class EngineNewPayloadV3<EP extends ExecutionPayloadV3> extends EngineNewPayloadV2<EP>
    permits EngineNewPayloadV4 {

  EngineNewPayloadV3(
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
    return RpcMethod.ENGINE_NEW_PAYLOAD_V3.getMethodName();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Class<EP> getEnginePayloadParameterClass() {
    return (Class<EP>) ExecutionPayloadV3.class;
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
    } else if (maybeRequestsParam.isPresent()) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_EXECUTION_REQUESTS_PARAMS,
          "Unexpected execution requests field present");
    } else {
      return ValidationResult.valid();
    }
  }

  @Override
  protected Long payloadBlobGasUsed(final EP payload) {
    return payload.getBlobGasUsed();
  }

  @Override
  protected BlobGas payloadExcessBlobGas(final EP payload) {
    return payload.getExcessBlobGas() == null
        ? null
        : BlobGas.fromHexString(payload.getExcessBlobGas());
  }

  @Override
  protected ValidationResult<RpcErrorType> validateBlobs(
      final List<Transaction> blobTransactions,
      final BlockHeader header,
      final Optional<BlockHeader> maybeParentHeader,
      final Optional<List<VersionedHash>> maybeVersionedHashes,
      final ProtocolSpec protocolSpec) {

    final List<VersionedHash> transactionVersionedHashes = new ArrayList<>();
    long transactionBlobGasLimitCap =
        protocolSpec.getGasLimitCalculator().transactionBlobGasLimitCap();
    long blockBlobGasLimit = protocolSpec.getGasLimitCalculator().currentBlobGasLimit();
    for (Transaction transaction : blobTransactions) {
      var versionedHashes = transaction.getVersionedHashes();
      // blob transactions must have at least one blob
      if (versionedHashes.isEmpty()) {
        return ValidationResult.invalid(
            RpcErrorType.INVALID_BLOB_COUNT, "There must be at least one blob");
      }
      int totalBlobCount = versionedHashes.get().size();
      long transactionBlobGasUsed = protocolSpec.getGasCalculator().blobGasCost(totalBlobCount);
      // Check if blob gas used by tx exceeds block blob gas limit
      if (transactionBlobGasUsed > blockBlobGasLimit) {
        return ValidationResult.invalid(
            RpcErrorType.INVALID_BLOB_COUNT,
            String.format(
                "Blob transaction %s exceeds block blob gas limit: %d > %d",
                transaction.getHash(), transactionBlobGasUsed, blockBlobGasLimit));
      }
      // Check if blob gas used by tx exceeds transaction cap
      if (transactionBlobGasUsed > transactionBlobGasLimitCap) {
        return ValidationResult.invalid(
            RpcErrorType.INVALID_BLOB_COUNT,
            String.format("Blob transaction has too many blobs: %d", totalBlobCount));
      }
      transactionVersionedHashes.addAll(versionedHashes.get());
    }

    if (maybeVersionedHashes.isEmpty() && !transactionVersionedHashes.isEmpty()) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_VERSIONED_HASH_PARAMS,
          "Payload must contain versioned hashes for transactions");
    }

    if (maybeVersionedHashes.isPresent()
        && !maybeVersionedHashes.get().equals(transactionVersionedHashes)) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_VERSIONED_HASH_PARAMS,
          "Versioned hashes from blob transactions do not match expected values");
    }

    if (maybeParentHeader.isPresent()) {
      Optional<BlobGas> maybeCalculatedExcess =
          validateExcessBlobGas(header, maybeParentHeader.get(), protocolSpec);
      if (maybeCalculatedExcess.isPresent()) {
        BlobGas calculated = maybeCalculatedExcess.get();
        BlobGas actual = header.getExcessBlobGas().orElse(BlobGas.ZERO);
        return ValidationResult.invalid(
            RpcErrorType.INVALID_EXCESS_BLOB_GAS_PARAMS,
            String.format(
                "Payload excessBlobGas does not match calculated excessBlobGas. Expected %s, got %s",
                calculated, actual));
      }
    }

    if (header.getBlobGasUsed().isPresent() && maybeVersionedHashes.isPresent()) {
      Optional<Long> maybeCalculatedBlobGas =
          validateBlobGasUsed(header, maybeVersionedHashes.get(), protocolSpec);
      if (maybeCalculatedBlobGas.isPresent()) {
        long calculated = maybeCalculatedBlobGas.get();
        long actual = header.getBlobGasUsed().orElse(0L);
        return ValidationResult.invalid(
            RpcErrorType.INVALID_BLOB_GAS_USED_PARAMS,
            String.format(
                "Payload BlobGasUsed does not match calculated BlobGasUsed. Expected %s, got %s",
                calculated, actual));
      }
    }

    if (protocolSpec.getGasCalculator().blobGasCost(transactionVersionedHashes.size())
        > protocolSpec.getGasLimitCalculator().currentBlobGasLimit()) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_BLOB_COUNT,
          String.format("Invalid Blob Count: %d", transactionVersionedHashes.size()));
    }
    return ValidationResult.valid();
  }

  /**
   * Validates that the excessBlobGas in the header matches the calculated value from the parent
   * header. Returns Optional.of(calculated) if mismatched, otherwise Optional.empty().
   *
   * @param header the new block header
   * @param parentHeader the parent header
   * @param protocolSpec the protocol spec for the new header
   * @return the expected excessBlobGas if it differs from the header's value, otherwise empty
   */
  @VisibleForTesting
  Optional<BlobGas> validateExcessBlobGas(
      final BlockHeader header, final BlockHeader parentHeader, final ProtocolSpec protocolSpec) {
    BlobGas calculated =
        ExcessBlobGasCalculator.calculateExcessBlobGasForParent(protocolSpec, parentHeader);
    BlobGas actual = header.getExcessBlobGas().orElse(BlobGas.ZERO);

    return calculated.equals(actual) ? Optional.empty() : Optional.of(calculated);
  }

  /**
   * Validates that blobGasUsed in the header matches the calculated value from the versioned
   * hashes. Returns Optional.of(calculated) if mismatched, otherwise Optional.empty().
   *
   * @param header the new block header
   * @param versionedHashes the versioned hashes from the payload
   * @param protocolSpec the protocol spec for the new header
   * @return the expected blobGasUsed if it differs from the header's value, otherwise empty
   */
  @VisibleForTesting
  Optional<Long> validateBlobGasUsed(
      final BlockHeader header,
      final List<VersionedHash> versionedHashes,
      final ProtocolSpec protocolSpec) {
    long calculated = protocolSpec.getGasCalculator().blobGasCost(versionedHashes.size());
    long actual = header.getBlobGasUsed().orElse(0L);

    return calculated == actual ? Optional.empty() : Optional.of(calculated);
  }
}
