/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.eth.transactions.sorter;

import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.util.Slf4jLambdaHelper.traceLambda;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.AccountTransactionOrder;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionDroppedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionListener;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolReplacementHandler;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.AccountState;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.util.Subscribers;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the current set of pending transactions with the ability to iterate them based on priority
 * for mining or look-up by hash.
 *
 * <p>This class is safe for use across multiple threads.
 */
public abstract class AbstractPendingTransactionsSorter {
  //  private static final int DEFAULT_LOWEST_INVALID_KNOWN_NONCE_CACHE = 10_000;
  private static final Logger LOG =
      LoggerFactory.getLogger(AbstractPendingTransactionsSorter.class);

  protected final Clock clock;
  protected final TransactionPoolConfiguration poolConfig;

  protected final Object lock = new Object();
  protected final Map<Hash, PendingTransaction> prioritizedPendingTransactions;

  protected final PendingTransactionsCache pendingTransactionsCache;

  //  protected final LowestInvalidNonceCache lowestInvalidKnownNonceCache =
  //      new LowestInvalidNonceCache(DEFAULT_LOWEST_INVALID_KNOWN_NONCE_CACHE);
  protected final Subscribers<PendingTransactionListener> pendingTransactionSubscribers =
      Subscribers.create();

  protected final Subscribers<PendingTransactionDroppedListener> transactionDroppedListeners =
      Subscribers.create();

  protected final LabelledMetric<Counter> transactionRemovedCounter;
  protected final LabelledMetric<Counter> transactionReplacedCounter;
  protected final Counter localTransactionAddedCounter;
  protected final Counter remoteTransactionAddedCounter;

  protected final TransactionPoolReplacementHandler transactionReplacementHandler;
  protected final Supplier<BlockHeader> chainHeadHeaderSupplier;

  public AbstractPendingTransactionsSorter(
      final TransactionPoolConfiguration poolConfig,
      final Clock clock,
      final MetricsSystem metricsSystem,
      final Supplier<BlockHeader> chainHeadHeaderSupplier) {
    this.poolConfig = poolConfig;
    this.prioritizedPendingTransactions = new ConcurrentHashMap<>(poolConfig.getTxPoolMaxSize());
    this.clock = clock;
    this.chainHeadHeaderSupplier = chainHeadHeaderSupplier;
    this.transactionReplacementHandler =
        new TransactionPoolReplacementHandler(poolConfig.getPriceBump());

    final LabelledMetric<Counter> transactionAddedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_added_total",
            "Count of transactions added to the transaction pool",
            "source");
    localTransactionAddedCounter = transactionAddedCounter.labels("local");
    remoteTransactionAddedCounter = transactionAddedCounter.labels("remote");

    transactionRemovedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_removed_total",
            "Count of transactions removed from the transaction pool",
            "source",
            "operation");
    transactionReplacedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_replaced_total",
            "Count of transactions replaced in the transaction pool",
            "source",
            "list");
    metricsSystem.createIntegerGauge(
        BesuMetricCategory.TRANSACTION_POOL,
        "transactions",
        "Current size of the transaction pool",
        prioritizedPendingTransactions::size);

    this.pendingTransactionsCache =
        new PendingTransactionsCache(
            poolConfig.getTxPoolMaxSize(), transactionReplacementHandler, chainHeadHeaderSupplier);
  }

  public void evictOldTransactions() {
    final Instant removeTransactionsBefore =
        clock.instant().minus(poolConfig.getPendingTxRetentionPeriod(), ChronoUnit.HOURS);

    prioritizedPendingTransactions.values().stream()
        .filter(transaction -> transaction.getAddedToPoolAt().isBefore(removeTransactionsBefore))
        .forEach(
            transactionInfo -> {
              traceLambda(LOG, "Evicted {} due to age", transactionInfo::toTraceLog);
              removeTransaction(transactionInfo.getTransaction());
            });
  }

  public List<Transaction> getLocalTransactions() {
    return prioritizedPendingTransactions.values().stream()
        .filter(PendingTransaction::isReceivedFromLocalSource)
        .map(PendingTransaction::getTransaction)
        .collect(Collectors.toList());
  }

  public TransactionAddedResult addRemoteTransaction(
      final Transaction transaction, final Optional<Account> maybeSenderAccount) {

    //    if (lowestInvalidKnownNonceCache.hasInvalidLowerNonce(transaction)) {
    //      debugLambda(
    //          LOG,
    //          "Dropping transaction {} since the sender has an invalid transaction with lower
    // nonce",
    //          transaction::toTraceLog);
    //      return LOWER_NONCE_INVALID_TRANSACTION_KNOWN;
    //    }

    final PendingTransaction pendingTransaction =
        new PendingTransaction(transaction, false, clock.instant());
    final TransactionAddedResult transactionAddedResult =
        addTransaction(pendingTransaction, maybeSenderAccount);
    if (transactionAddedResult.equals(ADDED)) {
      //      lowestInvalidKnownNonceCache.registerValidTransaction(transaction);
      remoteTransactionAddedCounter.inc();
    }
    return transactionAddedResult;
  }

  public TransactionAddedResult addLocalTransaction(
      final Transaction transaction, final Optional<Account> maybeSenderAccount) {
    final TransactionAddedResult transactionAdded =
        addTransaction(
            new PendingTransaction(transaction, true, clock.instant()), maybeSenderAccount);
    if (transactionAdded.equals(ADDED)) {
      localTransactionAddedCounter.inc();
    }
    return transactionAdded;
  }

  public void removeTransaction(final Transaction transaction) {
    removeTransaction(transaction, false);
    notifyTransactionDropped(transaction);
  }

  public void transactionAddedToBlock(final Transaction transaction) {
    removeTransaction(transaction, true);
    //    lowestInvalidKnownNonceCache.registerValidTransaction(transaction);
  }

  public void transactionsAddedToBlock(final List<Transaction> confirmedTransactions) {
    synchronized (lock) {
      final long prioritizedRemovedCount =
          confirmedTransactions.stream()
              .map(prioritizedPendingTransactions::remove)
              .filter(Objects::nonNull)
              .peek(
                  (PendingTransaction tx) -> {
                    removePrioritizedTransaction(tx);
                    incrementTransactionRemovedCounter(tx.isReceivedFromLocalSource(), true);
                  })
              .count();

      if (prioritizedRemovedCount > 0) {
        List<PendingTransaction> promoteTransactions =
            pendingTransactionsCache.promote(confirmedTransactions);
      }
    }
  }

  private void incrementTransactionRemovedCounter(
      final boolean receivedFromLocalSource, final boolean addedToBlock) {
    final String location = receivedFromLocalSource ? "local" : "remote";
    final String operation = addedToBlock ? "addedToBlock" : "dropped";
    transactionRemovedCounter.labels(location, operation).inc();
  }

  protected void incrementTransactionReplacedCounter(final boolean receivedFromLocalSource) {
    final String location = receivedFromLocalSource ? "local" : "remote";
    transactionReplacedCounter.labels(location, "priority").inc();
  }

  // There's a small edge case here we could encounter.
  // When we pass an upgrade block that has a new transaction type, we start allowing transactions
  // of that new type into our pool.
  // If we then reorg to a block lower than the upgrade block height _and_ we create a block, that
  // block could end up with transactions of the new type.
  // This seems like it would be very rare but worth it to document that we don't handle that case
  // right now.
  public void selectTransactions(final TransactionSelector selector) {
    synchronized (lock) {
      final Set<Transaction> transactionsToRemove = new HashSet<>();
      final Map<Address, AccountTransactionOrder> accountTransactions = new HashMap<>();
      final Iterator<PendingTransaction> prioritizedTransactions = prioritizedTransactions();
      while (prioritizedTransactions.hasNext()) {
        final PendingTransaction highestPriorityPendingTransaction = prioritizedTransactions.next();
        final AccountTransactionOrder accountTransactionOrder =
            accountTransactions.computeIfAbsent(
                highestPriorityPendingTransaction.getSender(), this::createSenderTransactionOrder);

        for (final Transaction transactionToProcess :
            accountTransactionOrder.transactionsToProcess(
                highestPriorityPendingTransaction.getTransaction())) {
          final TransactionSelectionResult result =
              selector.evaluateTransaction(transactionToProcess);
          switch (result) {
            case DELETE_TRANSACTION_AND_CONTINUE:
              transactionsToRemove.add(transactionToProcess);
              break;
            case CONTINUE:
              break;
            case COMPLETE_OPERATION:
              transactionsToRemove.forEach(this::removeTransaction);
              return;
            default:
              throw new RuntimeException("Illegal value for TransactionSelectionResult.");
          }
        }
      }
      transactionsToRemove.forEach(this::removeTransaction);
    }
  }

  private AccountTransactionOrder createSenderTransactionOrder(final Address address) {
    return new AccountTransactionOrder(
        pendingTransactionsCache
            .streamReadyTransactions(address)
            .map(PendingTransaction::getTransaction));
  }

  private TransactionAddedResult addToCache(
      final PendingTransaction pendingTransaction, final long senderNonce) {

    var addResult = pendingTransactionsCache.add(pendingTransaction, senderNonce);

    if (addResult.isReplacement()) {
      final var existingPendingTx = addResult.maybeReplacedTransaction().get();
      traceLambda(
          LOG,
          "Replace existing transaction {}, with new transaction {}",
          existingPendingTx::toTraceLog,
          pendingTransaction::toTraceLog);
      removeReplacedTransaction(existingPendingTx);
    }

    return addResult;
  }

  private void notifyTransactionAdded(final Transaction transaction) {
    pendingTransactionSubscribers.forEach(listener -> listener.onTransactionAdded(transaction));
  }

  private void notifyTransactionDropped(final Transaction transaction) {
    transactionDroppedListeners.forEach(listener -> listener.onTransactionDropped(transaction));
  }

  public long maxSize() {
    return poolConfig.getTxPoolMaxSize();
  }

  public int size() {
    return prioritizedPendingTransactions.size();
  }

  public boolean containsTransaction(final Hash transactionHash) {
    return prioritizedPendingTransactions.containsKey(transactionHash);
  }

  public Optional<Transaction> getTransactionByHash(final Hash transactionHash) {
    return Optional.ofNullable(prioritizedPendingTransactions.get(transactionHash))
        .map(PendingTransaction::getTransaction);
  }

  public Set<PendingTransaction> getPrioritizedPendingTransactions() {
    return new HashSet<>(prioritizedPendingTransactions.values());
  }

  public long subscribePendingTransactions(final PendingTransactionListener listener) {
    return pendingTransactionSubscribers.subscribe(listener);
  }

  public void unsubscribePendingTransactions(final long id) {
    pendingTransactionSubscribers.unsubscribe(id);
  }

  public long subscribeDroppedTransactions(final PendingTransactionDroppedListener listener) {
    return transactionDroppedListeners.subscribe(listener);
  }

  public void unsubscribeDroppedTransactions(final long id) {
    transactionDroppedListeners.unsubscribe(id);
  }

  public OptionalLong getNextNonceForSender(final Address sender) {
    return pendingTransactionsCache.getNextReadyNonce(sender);
  }

  public abstract void manageBlockAdded(final Block block);

  private void removeTransaction(final Transaction transaction, final boolean addedToBlock) {
    final PendingTransaction removedPendingTx =
        prioritizedPendingTransactions.remove(transaction.getHash());
    if (removedPendingTx != null) {
      removePrioritizedTransaction(removedPendingTx);
      incrementTransactionRemovedCounter(
          removedPendingTx.isReceivedFromLocalSource(), addedToBlock);
    }
    pendingTransactionsCache.remove(transaction);
  }

  private void removeReplacedTransaction(final PendingTransaction pendingTransaction) {
    final PendingTransaction removedPendingTransaction =
        prioritizedPendingTransactions.remove(pendingTransaction.getHash());
    if (removedPendingTransaction != null) {
      removePrioritizedTransaction(removedPendingTransaction);
    }
    incrementTransactionReplacedCounter(pendingTransaction.isReceivedFromLocalSource());
  }

  protected abstract void removePrioritizedTransaction(PendingTransaction removedPendingTx);

  protected abstract Iterator<PendingTransaction> prioritizedTransactions();

  protected abstract void prioritizeTransaction(final PendingTransaction pendingTransaction);

  private TransactionAddedResult addTransaction(
      final PendingTransaction pendingTransaction, final Optional<Account> maybeSenderAccount) {
    synchronized (lock) {
      final long senderNonce = maybeSenderAccount.map(AccountState::getNonce).orElse(0L);

      var addResult = pendingTransactionsCache.add(pendingTransaction, senderNonce);

      if (addResult.equals(ADDED) || addResult.isReplacement()) {
        addResult
            .maybeReplacedTransaction()
            .ifPresent(
                replacedTx -> {
                  traceLambda(
                      LOG,
                      "Replace existing transaction {}, with new transaction {}",
                      replacedTx::toTraceLog,
                      pendingTransaction::toTraceLog);
                  removeReplacedTransaction(replacedTx);
                });

        var nonceDistance = pendingTransaction.getNonce() - senderNonce;

        if (nonceDistance >= poolConfig.getTxPoolMaxFutureTransactionByAccount()) {
          traceLambda(
              LOG,
              "Transaction {} not prioritized now because nonce too far in the future for sender {}",
              pendingTransaction.getTransaction()::toTraceLog,
              maybeSenderAccount::toString);
        } else {
          prioritizedPendingTransactions.put(pendingTransaction.getHash(), pendingTransaction);
          prioritizeTransaction(pendingTransaction);
          notifyTransactionAdded(pendingTransaction.getTransaction());
          traceLambda(LOG, "Transaction prioritized {}", pendingTransaction::toTraceLog);
        }
      }

      return addResult;
    }
  }

  protected abstract PendingTransaction getLeastPriorityTransaction();

  //  private void evictLessPriorityTransactions() {
  //    final PendingTransaction leastPriorityTx = getLeastPriorityTransaction();
  //    // evict all txs for the sender with nonce >= the least priority one to avoid gaps
  //    final var pendingTxsForSender = transactionsBySender.get(leastPriorityTx.getSender());
  //    final var txsToEvict =
  // pendingTxsForSender.getPendingTransactions(leastPriorityTx.getNonce());
  //
  //    // remove backward to avoid gaps
  //    for (int i = txsToEvict.size() - 1; i >= 0; i--) {
  //      removeTransaction(txsToEvict.get(i).getTransaction());
  //    }
  //  }

  public String toTraceLog() {
    synchronized (lock) {
      StringBuilder sb =
          new StringBuilder(
              "Prioritized transactions { "
                  + StreamSupport.stream(
                          Spliterators.spliteratorUnknownSize(
                              prioritizedTransactions(), Spliterator.ORDERED),
                          false)
                      .map(PendingTransaction::toTraceLog)
                      .collect(Collectors.joining("; "))
                  + " }");

      return sb.toString();
    }
  }
  //
  //  public List<Transaction> signalInvalidAndGetDependentTransactions(final Transaction
  // transaction) {
  //    final long invalidNonce =
  // lowestInvalidKnownNonceCache.registerInvalidTransaction(transaction);
  //
  //    PendingTransactionsCache txsForSender =
  // pendingTransactionsCache.remove(transaction.getSender());
  //    if (txsForSender != null) {
  //      return txsForSender
  //          .streamPendingTransactions()
  //          .filter(pendingTx -> pendingTx.getTransaction().getNonce() > invalidNonce)
  //          .peek(
  //              pendingTx ->
  //                  traceLambda(
  //                      LOG,
  //                      "Transaction {} piked since there is a lowest invalid nonce {} for the
  // sender",
  //                      pendingTx::toTraceLog,
  //                      () -> invalidNonce))
  //          .map(PendingTransaction::getTransaction)
  //          .collect(Collectors.toList());
  //    }
  //    return List.of();
  //  }

  public String logStats() {
    return "Ready " + prioritizedPendingTransactions.size();
  }

  public enum TransactionSelectionResult {
    DELETE_TRANSACTION_AND_CONTINUE,
    CONTINUE,
    COMPLETE_OPERATION
  }

  @FunctionalInterface
  public interface TransactionSelector {
    TransactionSelectionResult evaluateTransaction(final Transaction transaction);
  }
}
