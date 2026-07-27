/*
 * Copyright contributors to Besu.
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

import org.hyperledger.besu.consensus.merge.blockcreation.PreparePayloadArgsBuilder;
import org.hyperledger.besu.datatypes.HardforkId;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.PayloadAttributesV4;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code engine_forkchoiceUpdatedV4} — Amsterdam (EIP-7843 Slot Number).
 *
 * <p>Extends V3 with {@link PayloadAttributesV4}, adding the mandatory {@code slotNumber} field.
 *
 * <p>Parameterized so that a future V5 can extend this class without modifying it (beyond updating
 * the upper-bound fork in the public constructor below).
 *
 * <h3>Adding V5</h3>
 *
 * <ol>
 *   <li>Create {@code PayloadAttributesV5 extends PayloadAttributesV4} with the new field.
 *   <li>Create {@code EngineForkchoiceUpdatedV5 extends
 *       EngineForkchoiceUpdatedV4<PayloadAttributesV5>}.
 *   <li>Update the public constructor below: change {@code Optional.empty()} to {@code
 *       Optional.of(V5_FORK)}.
 * </ol>
 */
public final class EngineForkchoiceUpdatedV4<PA extends PayloadAttributesV4>
    extends EngineForkchoiceUpdatedV3<PA> {

  private static final Logger LOG = LoggerFactory.getLogger(EngineForkchoiceUpdatedV4.class);
  private static final int CUSTODY_COLUMNS_BYTE_LENGTH = 16;

  private final TransactionPool transactionPool;

  @Override
  protected Logger logger() {
    return LOG;
  }

  public EngineForkchoiceUpdatedV4(
      final ConstructorArguments constructorArguments,
      final HardforkId minFork,
      final HardforkId maxFork) {
    super(constructorArguments, minFork, maxFork);
    this.transactionPool = constructorArguments.transactionPool();
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_FORKCHOICE_UPDATED_V4.getMethodName();
  }

  /**
   * V4 adds an optional {@code custodyColumns} 3rd parameter (Amsterdam / EIP-8070). Its validation
   * and adoption is handled here, independently of and before the rest of the FCU flow, then
   * delegates to V1's {@code syncResponse} unchanged.
   */
  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    final ValidationResult<RpcErrorType> custodyColumnsResult =
        validateAndApplyCustodyColumns(requestContext);
    if (!custodyColumnsResult.isValid()) {
      return new JsonRpcErrorResponse(requestContext.getRequest().getId(), custodyColumnsResult);
    }
    return super.syncResponse(requestContext);
  }

  private ValidationResult<RpcErrorType> validateAndApplyCustodyColumns(
      final JsonRpcRequestContext requestContext) {
    final Optional<Bytes> custodyColumns;
    try {
      custodyColumns = requestContext.getOptionalParameter(2, Bytes.class);
    } catch (JsonRpcParameter.JsonRpcParameterException e) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_CUSTODY_COLUMNS_PARAMS,
          "Invalid custodyColumns parameter (index 2)");
    }
    if (custodyColumns.isEmpty()) {
      return ValidationResult.valid();
    }
    final Bytes bytes = custodyColumns.get();
    if (bytes.size() != CUSTODY_COLUMNS_BYTE_LENGTH) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_CUSTODY_COLUMNS_PARAMS,
          "custodyColumns must be %d bytes, got %d"
              .formatted(CUSTODY_COLUMNS_BYTE_LENGTH, bytes.size()));
    }
    // Custody set adoption MUST NOT affect the main forkchoice-update processing flow.
    try {
      transactionPool.updateBlobCustodyColumns(bytes);
    } catch (final RuntimeException e) {
      logger().warn("Failed to adopt updated blob custody columns", e);
    }
    return ValidationResult.valid();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Class<PA> getPayloadAttributesClass() {
    return (Class<PA>) PayloadAttributesV4.class;
  }

  /**
   * V4 requires {@code slotNumber} in addition to everything V3 requires. Delegates to V3 first
   * (which checks {@code parentBeaconBlockRoot} and timestamp), then adds its own check.
   *
   * <p>{@code PA} is bounded to {@link PayloadAttributesV4}, so {@code getSlotNumber()} is
   * available without a cast.
   */
  @Override
  protected ValidationResult<RpcErrorType> validatePayloadAttributes(
      final BlockHeader newHead, final PA attrs) {
    final ValidationResult<RpcErrorType> r = super.validatePayloadAttributes(newHead, attrs);
    return r.isValid() ? validatePayloadAttributesV4(attrs) : r;
  }

  private ValidationResult<RpcErrorType> validatePayloadAttributesV4(final PA attrs) {
    if (attrs.getSlotNumber() == null || attrs.getSlotNumber() < 0) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_SLOT_NUMBER_PARAMS, "Invalid slotNumber");
    }
    if (attrs.getTargetGasLimit() == null) {
      return ValidationResult.invalid(
          RpcErrorType.INVALID_TARGET_GAS_LIMIT_PARAMS, "Missing target gas limit field");
    }
    return ValidationResult.valid();
  }

  @Override
  protected void setPreparePayloadArgs(
      final PreparePayloadArgsBuilder preparePayloadArgsBuilder, final PA attrs) {
    super.setPreparePayloadArgs(preparePayloadArgsBuilder, attrs);
    preparePayloadArgsBuilder.slotNumber(attrs.getSlotNumber());
    preparePayloadArgsBuilder.targetGasLimit(attrs.getTargetGasLimit());
  }
}
