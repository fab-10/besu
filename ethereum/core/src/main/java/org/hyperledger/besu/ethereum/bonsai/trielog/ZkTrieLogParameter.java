package org.hyperledger.besu.ethereum.bonsai.trielog;

import org.hyperledger.besu.datatypes.Hash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ZkTrieLogParameter {

  private final Long blockNumber;

  private final Hash blockHash;

  private final Boolean isSyncing;

  private final String trieLogRlpBytes;

  public Long getBlockNumber() {
    return blockNumber;
  }

  public Hash getBlockHash() {
    return blockHash;
  }

  public boolean isSyncing() {
    return isSyncing;
  }

  public String gettrieLogRlpBytes() {
    return trieLogRlpBytes;
  }

  @JsonCreator
  public ZkTrieLogParameter(
      @JsonProperty("blockNumber") final Long blockNumber,
      @JsonProperty("blockHash") final Hash blockHash,
      @JsonProperty("isSyncing") final boolean isSyncing,
      @JsonProperty("trieLogRlpBytes") final String trieLogRlpBytes) {
    this.blockNumber = blockNumber;
    this.blockHash = blockHash;
    this.isSyncing = isSyncing;
    this.trieLogRlpBytes = trieLogRlpBytes;
  }

  @Override
  public String toString() {
    return "blockNumber="
        + blockNumber
        + ", blockHash="
        + blockHash
        + ", isSyncing="
        + isSyncing
        + ", trieLogRlpBytes="
        + trieLogRlpBytes;
  }
}
