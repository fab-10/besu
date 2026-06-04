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

import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.OSAKA;

import org.hyperledger.besu.datatypes.BlobType;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlobCellsAndProofsV1;
import org.hyperledger.besu.ethereum.core.kzg.BlobProofBundle;
import org.hyperledger.besu.ethereum.eth.transactions.CellMask;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.util.HexUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import io.vertx.core.Vertx;
import org.apache.tuweni.bytes.Bytes;

public class EngineGetBlobsV4 extends ExecutionEngineJsonRpcMethod {
  public static final int REQUEST_MAX_VERSIONED_HASHES = 128;

  private final TransactionPool transactionPool;
  private final Optional<Long> osakaMilestone;

  public EngineGetBlobsV4(
      final Vertx vertx,
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule,
      final EngineCallListener engineCallListener,
      final TransactionPool transactionPool) {
    super(vertx, protocolSchedule, protocolContext, engineCallListener);
    this.transactionPool = transactionPool;
    this.osakaMilestone = protocolSchedule.milestoneFor(OSAKA);
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_GET_BLOBS_V4.getMethodName();
  }

  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    final VersionedHash[] versionedHashes = extractVersionedHashes(requestContext);
    final CellMask cellMask = extractCellMask(requestContext);
    if (versionedHashes.length > REQUEST_MAX_VERSIONED_HASHES) {
      return new JsonRpcErrorResponse(
          requestContext.getRequest().getId(),
          RpcErrorType.INVALID_ENGINE_GET_BLOBS_TOO_LARGE_REQUEST);
    }
    if (mergeContext.get().isSyncing()) {
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
    }
    final long timestamp = protocolContext.getBlockchain().getChainHeadHeader().getTimestamp();
    final ValidationResult<RpcErrorType> forkValidationResult = validateForkSupported(timestamp);
    if (!forkValidationResult.isValid()) {
      return new JsonRpcErrorResponse(requestContext.getRequest().getId(), forkValidationResult);
    }

    final List<BlobCellsAndProofsV1> result =
        Arrays.stream(versionedHashes).map(hash -> getBlobCellsAndProofs(hash, cellMask)).toList();
    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), result);
  }

  private VersionedHash[] extractVersionedHashes(final JsonRpcRequestContext requestContext) {
    try {
      return requestContext.getRequiredParameter(0, VersionedHash[].class);
    } catch (JsonRpcParameter.JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid versioned hashes parameter (index 0)",
          RpcErrorType.INVALID_VERSIONED_HASHES_PARAMS,
          e);
    }
  }

  private CellMask extractCellMask(final JsonRpcRequestContext requestContext) {
    try {
      final Bytes maskBytes =
          Bytes.fromHexString(requestContext.getRequiredParameter(1, String.class));
      if (maskBytes.size() != CellMask.BYTE_LENGTH) {
        throw new JsonRpcParameter.JsonRpcParameterException(
            "Invalid indices bitarray length %s, expected %s"
                .formatted(maskBytes.size(), CellMask.BYTE_LENGTH));
      }
      return CellMask.fromBytes(maskBytes);
    } catch (IllegalArgumentException | JsonRpcParameter.JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid indices bitarray parameter (index 1)",
          RpcErrorType.INVALID_PARAMS,
          e);
    }
  }

  private BlobCellsAndProofsV1 getBlobCellsAndProofs(
      final VersionedHash versionedHash, final CellMask cellMask) {
    final BlobProofBundle bundle = transactionPool.getBlobProofBundle(versionedHash);
    if (bundle == null || bundle.getBlobType() != BlobType.KZG_CELL_PROOFS) {
      return null;
    }
    final Bytes blobCells = bundle.getBlobCellsBytes().orElse(null);
    if (blobCells == null || blobCells.size() != CellMask.CELL_COUNT * CellMask.CELL_SIZE) {
      return null;
    }

    final List<String> cells =
        cellMask.indexes().stream()
            .map(index -> blobCells.slice(index * CellMask.CELL_SIZE, CellMask.CELL_SIZE))
            .map(cell -> HexUtils.toFastHex(cell, true))
            .toList();
    final List<String> proofs =
        cellMask.indexes().stream()
            .map(index -> bundle.getKzgProof().get(index))
            .map(proof -> HexUtils.toFastHex(proof.getData(), true))
            .toList();
    return new BlobCellsAndProofsV1(cells, proofs);
  }

  @Override
  protected ValidationResult<RpcErrorType> validateForkSupported(final long currentTimestamp) {
    return ForkSupportHelper.validateForkSupported(OSAKA, osakaMilestone, currentTimestamp);
  }
}
