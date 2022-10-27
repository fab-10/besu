package org.hyperledger.besu.ethereum.eth.transactions.sorter;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Tracks the additional metadata associated with transactions to enable prioritization for mining
 * and deciding which transactions to drop when the transaction pool reaches its size limit.
 */
public class TransactionInfo {

  private static final AtomicLong TRANSACTIONS_ADDED = new AtomicLong();
  private final Transaction transaction;
  private final boolean receivedFromLocalSource;
  private final Instant addedToPoolAt;
  private final long sequence; // Allows prioritization based on order transactions are added

  public TransactionInfo(
      final Transaction transaction,
      final boolean receivedFromLocalSource,
      final Instant addedToPoolAt) {
    this.transaction = transaction;
    this.receivedFromLocalSource = receivedFromLocalSource;
    this.addedToPoolAt = addedToPoolAt;
    this.sequence = TRANSACTIONS_ADDED.getAndIncrement();
  }

  public Transaction getTransaction() {
    return transaction;
  }

  public Wei getGasPrice() {
    return transaction.getGasPrice().orElse(Wei.ZERO);
  }

  public long getSequence() {
    return sequence;
  }

  public long getNonce() {
    return transaction.getNonce();
  }

  public Address getSender() {
    return transaction.getSender();
  }

  public boolean isReceivedFromLocalSource() {
    return receivedFromLocalSource;
  }

  public Hash getHash() {
    return transaction.getHash();
  }

  public Instant getAddedToPoolAt() {
    return addedToPoolAt;
  }

  public static List<Transaction> toTransactionList(
      final Collection<TransactionInfo> transactionsInfo) {
    return transactionsInfo.stream()
        .map(TransactionInfo::getTransaction)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    TransactionInfo that = (TransactionInfo) o;

    return sequence == that.sequence;
  }

  @Override
  public int hashCode() {
    return 31 * (int) (sequence ^ (sequence >>> 32));
  }

  public String toTraceLog() {
    return "{sequence: "
        + sequence
        + ", addedAt: "
        + addedToPoolAt
        + ", "
        + transaction.toTraceLog()
        + "}";
  }
}
