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
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
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
import org.hyperledger.besu.ethereum.eth.transactions.cache.PostponedTransactionsCache;
import org.hyperledger.besu.ethereum.eth.transactions.cache.ReadyTransactionsCache;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
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
  private static final Logger LOG =
      LoggerFactory.getLogger(AbstractPendingTransactionsSorter.class);

  protected final Clock clock;
  protected final TransactionPoolConfiguration poolConfig;

  protected final Object lock = new Object();
  protected final Map<Hash, PendingTransaction> prioritizedPendingTransactions;

  protected volatile TreeSet<PendingTransaction> orderByFee;

  protected final ReadyTransactionsCache readyTransactionsCache;

  protected final Subscribers<PendingTransactionListener> pendingTransactionSubscribers =
      Subscribers.create();

  protected final Subscribers<PendingTransactionDroppedListener> transactionDroppedListeners =
      Subscribers.create();

  protected final LabelledMetric<Counter> transactionAddedCounter;
  protected final LabelledMetric<Counter> transactionRemovedCounter;
  protected final LabelledMetric<Counter> transactionReplacedCounter;

  protected final TransactionPoolReplacementHandler transactionReplacementHandler;
  protected final Supplier<BlockHeader> chainHeadHeaderSupplier;

  private final Set<Address> localSenders = ConcurrentHashMap.newKeySet();

  public AbstractPendingTransactionsSorter(
      final TransactionPoolConfiguration poolConfig,
      final Clock clock,
      final MetricsSystem metricsSystem,
      final Supplier<BlockHeader> chainHeadHeaderSupplier,
      final PostponedTransactionsCache postponedTransactionsCache) {
    this.poolConfig = poolConfig;
    this.prioritizedPendingTransactions = new ConcurrentHashMap<>(poolConfig.getTxPoolMaxSize());
    this.clock = clock;
    this.chainHeadHeaderSupplier = chainHeadHeaderSupplier;
    this.transactionReplacementHandler =
        new TransactionPoolReplacementHandler(poolConfig.getPriceBump());
    this.transactionAddedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_added_total",
            "Count of transactions added to the transaction pool",
            "source");

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

    final BiFunction<PendingTransaction, PendingTransaction, Boolean> transactionReplacementTester =
        (t1, t2) ->
            transactionReplacementHandler.shouldReplace(t1, t2, chainHeadHeaderSupplier.get());

    this.readyTransactionsCache =
        new ReadyTransactionsCache(
            poolConfig, postponedTransactionsCache, transactionReplacementTester);
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

    final PendingTransaction pendingTransaction =
        new PendingTransaction.Remote(transaction, clock.instant());
    final TransactionAddedResult transactionAddedResult =
        addTransaction(pendingTransaction, maybeSenderAccount);
    return transactionAddedResult;
  }

  public TransactionAddedResult addLocalTransaction(
      final Transaction transaction, final Optional<Account> maybeSenderAccount) {
    final TransactionAddedResult transactionAdded =
        addTransaction(
            new PendingTransaction.Local(transaction, clock.instant()), maybeSenderAccount);
    if (transactionAdded.equals(ADDED)) {
      localSenders.add(transaction.getSender());
    }
    return transactionAdded;
  }

  void removeTransaction(final Transaction transaction) {
    removeTransaction(transaction, false);
    notifyTransactionDropped(transaction);
  }

  protected void incrementTransactionAddedCounter(final boolean receivedFromLocalSource) {
    final String location = receivedFromLocalSource ? "local" : "remote";
    transactionAddedCounter.labels(location).inc();
  }

  protected void incrementTransactionRemovedCounter(
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
        readyTransactionsCache
            .streamReadyTransactions(address)
            .map(PendingTransaction::getTransaction));
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
    return readyTransactionsCache.getNextReadyNonce(sender);
  }

  public void manageBlockAdded(
      final Block block, final List<Transaction> confirmedTransactions, final FeeMarket feeMarket) {
    synchronized (lock) {
      transactionsAddedToBlock(confirmedTransactions);
      manageBlockAdded(block, feeMarket);
      promoteFromReady();
    }
  }

  protected void transactionsAddedToBlock(final List<Transaction> confirmedTransactions) {
    confirmedTransactions.stream()
        .map(Transaction::getHash)
        .map(prioritizedPendingTransactions::remove)
        .filter(Objects::nonNull)
        .forEach(tx -> removeFromOrderedTransactions(tx, true));

    readyTransactionsCache.removeConfirmedTransactions(confirmedTransactions);
  }

  protected abstract void manageBlockAdded(final Block block, final FeeMarket feeMarket);

  protected void promoteFromReady() {
    final int maxPromotable = poolConfig.getTxPoolMaxSize() - prioritizedPendingTransactions.size();

    if (maxPromotable > 0) {
      final Predicate<PendingTransaction> notAlreadyPrioritized =
          pt -> prioritizedPendingTransactions.containsKey(pt.getHash());

      final List<PendingTransaction> promoteTransactions =
          readyTransactionsCache.getPromotableTransactions(
              maxPromotable, notAlreadyPrioritized.and(getPromotionFilter()));

      promoteTransactions.forEach(this::addPrioritizedTransaction);
    }
  }

  public abstract int compareByValue(PendingTransaction pt1, PendingTransaction pt2);

  private void removeTransaction(final Transaction transaction, final boolean addedToBlock) {
    final PendingTransaction removedPendingTx =
        prioritizedPendingTransactions.remove(transaction.getHash());
    if (removedPendingTx != null) {
      removeFromOrderedTransactions(removedPendingTx, addedToBlock);
    }
    readyTransactionsCache.remove(transaction);
  }

  private void removeReplacedPrioritizedTransaction(final PendingTransaction pendingTransaction) {
    final PendingTransaction removedPendingTransaction =
        prioritizedPendingTransactions.remove(pendingTransaction.getHash());
    if (removedPendingTransaction != null) {
      removeFromOrderedTransactions(removedPendingTransaction, false);
    }
    incrementTransactionReplacedCounter(pendingTransaction.isReceivedFromLocalSource());
  }

  protected abstract void removeFromOrderedTransactions(
      final PendingTransaction removedPendingTx, final boolean addedToBlock);

  private Iterator<PendingTransaction> prioritizedTransactions() {
    return orderByFee.descendingIterator();
  }

  private TransactionAddedResult addTransaction(
      final PendingTransaction pendingTransaction, final Optional<Account> maybeSenderAccount) {
    synchronized (lock) {
      final long senderNonce = maybeSenderAccount.map(AccountState::getNonce).orElse(0L);

      final long nonceDistance = pendingTransaction.getNonce() - senderNonce;

      if (nonceDistance < 0) {
        traceLambda(
            LOG,
            "Drop already confirmed transaction {}, since current sender nonce is {}",
            pendingTransaction::toTraceLog,
            () -> senderNonce);
        return ALREADY_KNOWN;
      } else if (nonceDistance > poolConfig.getTxPoolMaxFutureTransactionByAccount()) {
        traceLambda(
            LOG,
            "Drop too much in the future transaction {}, since current sender nonce is {}",
            pendingTransaction::toTraceLog,
            () -> senderNonce);
        return NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
      }

      var addResult = readyTransactionsCache.add(pendingTransaction, senderNonce);

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
                  removeReplacedPrioritizedTransaction(replacedTx);
                });

        if (prioritizedPendingTransactions.size() >= poolConfig.getTxPoolMaxSize()) {
          LOG.trace("Max number of prioritized transactions reached");

          var currentLeastPriorityTx = orderByFee.first();
          if (compareByValue(pendingTransaction, currentLeastPriorityTx) <= 0) {
            traceLambda(
                LOG,
                "Not adding incoming transaction {} to the prioritized list, since it is less valuable than the current least priority transactions {}",
                pendingTransaction::toTraceLog,
                currentLeastPriorityTx::toTraceLog);
            return addResult;
          } else if (currentLeastPriorityTx.getSender().equals(pendingTransaction.getSender())) {
            traceLambda(
                LOG,
                "Not adding incoming transaction {} to the prioritized list, since it is from the same sender as the least valuable one {}",
                pendingTransaction::toTraceLog,
                currentLeastPriorityTx::toTraceLog);
            return addResult;
          }

          traceLambda(
              LOG,
              "Demote transactions for the sender of the current least priority transaction {}, to make space for the incoming transaction {}",
              currentLeastPriorityTx::toTraceLog,
              pendingTransaction::toTraceLog);
          demoteTransactionForSenderAfter(currentLeastPriorityTx);
        }

        addPrioritizedTransaction(pendingTransaction);
        notifyTransactionAdded(pendingTransaction.getTransaction());
      }

      return addResult;
    }
  }

  private void demoteTransactionForSenderAfter(final PendingTransaction firstDemotedTx) {
    final var demotableSenderTxs =
        readyTransactionsCache
            .streamReadyTransactions(firstDemotedTx.getSender(), firstDemotedTx.getNonce())
            .iterator();

    var lastPrioritizedForSender = firstDemotedTx;
    while (demotableSenderTxs.hasNext()) {
      final var maybeNewLast = demotableSenderTxs.next();
      if (!prioritizedPendingTransactions.containsKey(maybeNewLast.getHash())) {
        break;
      }
      lastPrioritizedForSender = maybeNewLast;
    }

    prioritizedPendingTransactions.remove(lastPrioritizedForSender.getHash());
    removeFromOrderedTransactions(lastPrioritizedForSender, false);
    traceLambda(
        LOG,
        "Demoted transaction {}, to make space for the incoming transaction",
        lastPrioritizedForSender::toTraceLog);
  }

  protected void addPrioritizedTransaction(final PendingTransaction prioritizedTx) {
    prioritizedPendingTransactions.put(prioritizedTx.getHash(), prioritizedTx);
    orderByFee.add(prioritizedTx);
    incrementTransactionAddedCounter(prioritizedTx.isReceivedFromLocalSource());
  }

  protected abstract Predicate<PendingTransaction> getPromotionFilter();

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

  public String logStats() {
    return "Ready " + prioritizedPendingTransactions.size();
  }

  public boolean isLocalSender(final Address sender) {
    return localSenders.contains(sender);
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
