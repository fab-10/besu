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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.engine;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.LogsBloomFilter;
import org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.WithdrawalParameter;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Engine API {@code ExecutionPayloadV4} — Amsterdam (EIP-7843 Slot Number, Block Access List).
 *
 * <p>Extends V3 with the {@code slotNumber} and {@code blockAccessList} fields. Used by {@code
 * engine_newPayloadV5} per the Amsterdam spec (the spec breaks the symmetry between method version
 * and payload version: payload V4 ships as part of method V5, not V4).
 */
public final class ExecutionPayloadV4 extends ExecutionPayloadV3 {

  private final String blockAccessList;
  private final Long slotNumber;

  /**
   * Creates an instance of {@code ExecutionPayloadV4}.
   *
   * @param blockHash DATA, 32 Bytes
   * @param parentHash DATA, 32 Bytes
   * @param feeRecipient DATA, 20 Bytes
   * @param stateRoot DATA, 32 Bytes
   * @param blockNumber QUANTITY, 64 Bits
   * @param baseFeePerGas QUANTITY, 256 Bits
   * @param gasLimit QUANTITY, 64 Bits
   * @param gasUsed QUANTITY, 64 Bits
   * @param timestamp QUANTITY, 64 Bits
   * @param extraData DATA, 0 to 32 Bytes
   * @param receiptsRoot DATA, 32 Bytes
   * @param logsBloom DATA, 256 Bytes
   * @param prevRandao DATA, 32 Bytes
   * @param transactions Array of DATA
   * @param withdrawals Array of {@link WithdrawalParameter}
   * @param blobGasUsed QUANTITY, 64 Bits
   * @param excessBlobGas QUANTITY, 64 Bits
   * @param blockAccessList DATA, RLP-encoded block access list
   * @param slotNumber QUANTITY, 64 Bits
   */
  @JsonCreator
  public ExecutionPayloadV4(
      @JsonProperty("blockHash") final Hash blockHash,
      @JsonProperty("parentHash") final Hash parentHash,
      @JsonProperty("feeRecipient") final Address feeRecipient,
      @JsonProperty("stateRoot") final Hash stateRoot,
      @JsonProperty("blockNumber") final UnsignedLongParameter blockNumber,
      @JsonProperty("baseFeePerGas") final String baseFeePerGas,
      @JsonProperty("gasLimit") final UnsignedLongParameter gasLimit,
      @JsonProperty("gasUsed") final UnsignedLongParameter gasUsed,
      @JsonProperty("timestamp") final UnsignedLongParameter timestamp,
      @JsonProperty("extraData") final String extraData,
      @JsonProperty("receiptsRoot") final Hash receiptsRoot,
      @JsonProperty("logsBloom") final LogsBloomFilter logsBloom,
      @JsonProperty("prevRandao") final String prevRandao,
      @JsonProperty("transactions") final List<String> transactions,
      @JsonProperty("withdrawals") final List<WithdrawalParameter> withdrawals,
      @JsonProperty("blobGasUsed") final UnsignedLongParameter blobGasUsed,
      @JsonProperty("excessBlobGas") final String excessBlobGas,
      @JsonProperty("blockAccessList") final String blockAccessList,
      @JsonProperty("slotNumber") final UnsignedLongParameter slotNumber) {
    super(
        blockHash,
        parentHash,
        feeRecipient,
        stateRoot,
        blockNumber,
        baseFeePerGas,
        gasLimit,
        gasUsed,
        timestamp,
        extraData,
        receiptsRoot,
        logsBloom,
        prevRandao,
        transactions,
        withdrawals,
        blobGasUsed,
        excessBlobGas);
    this.blockAccessList = blockAccessList;
    this.slotNumber = slotNumber == null ? null : slotNumber.getValue();
  }

  /**
   * Returns the hex-encoded RLP-encoded block access list, or {@code null} when omitted.
   *
   * @return the block access list, hex-encoded
   */
  public String getBlockAccessList() {
    return blockAccessList;
  }

  /**
   * Returns the slot number, or {@code null} when omitted.
   *
   * @return the slot number
   */
  public Long getSlotNumber() {
    return slotNumber;
  }
}
