package org.hyperledger.besu.ethereum.eth.transactions.cache;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PostponedTransactionsCache {

  CompletableFuture<List<PendingTransaction>> promoteForSender(
      Address sender, long lastReadyNonce, int maxPromotable);

  void add(PendingTransaction pendingTransaction);

  void addAll(List<PendingTransaction> evictedTransactions);

  void remove(Transaction transaction);

  void removeForSenderBelowNonce(Address sender, long maxConfirmedNonce);
}
