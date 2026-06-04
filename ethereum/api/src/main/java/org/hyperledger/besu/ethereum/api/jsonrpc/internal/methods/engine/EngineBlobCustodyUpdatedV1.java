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

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.eth.transactions.CellMask;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import io.vertx.core.Vertx;
import org.apache.tuweni.bytes.Bytes;

public class EngineBlobCustodyUpdatedV1 extends ExecutionEngineJsonRpcMethod {

  private final TransactionPool transactionPool;

  public EngineBlobCustodyUpdatedV1(
      final Vertx vertx,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final EngineCallListener engineCallListener,
      final TransactionPool transactionPool) {
    super(vertx, protocolSchedule, protocolContext, engineCallListener);
    this.transactionPool = transactionPool;
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_BLOB_CUSTODY_UPDATED_V1.getMethodName();
  }

  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    transactionPool.updateBlobCustodyCellMask(extractCellMask(requestContext));
    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
  }

  private CellMask extractCellMask(final JsonRpcRequestContext requestContext) {
    try {
      final Bytes maskBytes =
          Bytes.fromHexString(requestContext.getRequiredParameter(0, String.class));
      if (maskBytes.size() != CellMask.BYTE_LENGTH) {
        throw new JsonRpcParameter.JsonRpcParameterException(
            "Invalid custody bitarray length %s, expected %s"
                .formatted(maskBytes.size(), CellMask.BYTE_LENGTH));
      }
      return CellMask.fromBytes(maskBytes);
    } catch (IllegalArgumentException | JsonRpcParameter.JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid custody bitarray parameter (index 0)",
          RpcErrorType.INVALID_PARAMS,
          e);
    }
  }
}
