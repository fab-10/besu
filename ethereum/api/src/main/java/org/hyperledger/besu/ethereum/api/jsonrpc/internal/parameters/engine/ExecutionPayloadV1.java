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
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Engine API {@code ExecutionPayloadV1} — Paris (The Merge).
 *
 * <p>Base of the {@code engine_newPayload} payload sealed hierarchy. Holds the fields shared by
 * every version. Subclasses add only the fields introduced by their fork.
 */
public sealed class ExecutionPayloadV1 permits ExecutionPayloadV2 {

  private final Hash blockHash;
  private final Hash parentHash;
  private final Address feeRecipient;
  private final Hash stateRoot;
  private final long blockNumber;
  private final Wei baseFeePerGas;
  private final long gasLimit;
  private final long gasUsed;
  private final long timestamp;
  private final String extraData;
  private final Hash receiptsRoot;
  private final LogsBloomFilter logsBloom;
  private final Bytes32 prevRandao;
  private final List<String> transactions;

  /**
   * Creates an instance of {@code ExecutionPayloadV1}.
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
   */
  @JsonCreator
  public ExecutionPayloadV1(
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
      @JsonProperty("transactions") final List<String> transactions) {
    this.blockHash = blockHash;
    this.parentHash = parentHash;
    this.feeRecipient = feeRecipient;
    this.stateRoot = stateRoot;
    this.blockNumber = blockNumber.getValue();
    this.baseFeePerGas = Wei.fromHexString(baseFeePerGas);
    this.gasLimit = gasLimit.getValue();
    this.gasUsed = gasUsed.getValue();
    this.timestamp = timestamp.getValue();
    this.extraData = extraData;
    this.receiptsRoot = receiptsRoot;
    this.logsBloom = logsBloom;
    this.prevRandao = Bytes32.fromHexString(prevRandao);
    this.transactions = transactions;
  }

  /**
   * Returns the block hash.
   *
   * @return the block hash
   */
  public Hash getBlockHash() {
    return blockHash;
  }

  /**
   * Returns the parent hash.
   *
   * @return the parent hash
   */
  public Hash getParentHash() {
    return parentHash;
  }

  /**
   * Returns the fee recipient.
   *
   * @return the fee recipient
   */
  public Address getFeeRecipient() {
    return feeRecipient;
  }

  /**
   * Returns the state root.
   *
   * @return the state root
   */
  public Hash getStateRoot() {
    return stateRoot;
  }

  /**
   * Returns the block number.
   *
   * @return the block number
   */
  public long getBlockNumber() {
    return blockNumber;
  }

  /**
   * Returns the base fee per gas.
   *
   * @return the base fee per gas
   */
  public Wei getBaseFeePerGas() {
    return baseFeePerGas;
  }

  /**
   * Returns the gas limit.
   *
   * @return the gas limit
   */
  public long getGasLimit() {
    return gasLimit;
  }

  /**
   * Returns the gas used.
   *
   * @return the gas used
   */
  public long getGasUsed() {
    return gasUsed;
  }

  /**
   * Returns the block timestamp.
   *
   * @return the block timestamp
   */
  public long getTimestamp() {
    return timestamp;
  }

  /**
   * Returns the extra data field.
   *
   * @return the extra data
   */
  public String getExtraData() {
    return extraData;
  }

  /**
   * Returns the receipts root.
   *
   * @return the receipts root
   */
  public Hash getReceiptsRoot() {
    return receiptsRoot;
  }

  /**
   * Returns the logs bloom filter.
   *
   * @return the logs bloom
   */
  public LogsBloomFilter getLogsBloom() {
    return logsBloom;
  }

  /**
   * Returns the prevRandao value.
   *
   * @return the prevRandao
   */
  public Bytes32 getPrevRandao() {
    return prevRandao;
  }

  /**
   * Returns the encoded transactions.
   *
   * @return the transactions
   */
  public List<String> getTransactions() {
    return transactions;
  }
}
