/*
 * Copyright Hyperledger Besu Contributors.
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
package org.hyperledger.besu.ethereum.eth.transactions.cache;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.maxBy;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.POSTPONED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.REJECTED_UNDERPRICED_REPLACEMENT;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PendingTransactionsCache {
  private static final Logger LOG = LoggerFactory.getLogger(PendingTransactionsCache.class);
  private static final Long MAX_READY_SIZE_BYTES = 100_000_000L;

  private final TransactionPoolConfiguration poolConfig;
  private final DummyPostponedTransactionsCache postponedCache;
  private final BiFunction<PendingTransaction, PendingTransaction, Boolean>
      transactionReplacementTester;
  private final Map<Address, NavigableMap<Long, PendingTransaction>> readyBySender =
      new ConcurrentHashMap<>();

  private final NavigableSet<Transaction> readyEvictionOrder =
      new TreeSet<>(Comparator.comparing(Transaction::getMaxGasFee));

  private final AtomicLong readyTotalSize = new AtomicLong();
  private final int maxPromotablePerSender;

  public PendingTransactionsCache(
      final TransactionPoolConfiguration poolConfig,
      final DummyPostponedTransactionsCache postponedCache,
      final BiFunction<PendingTransaction, PendingTransaction, Boolean>
          transactionReplacementTester) {
    this.poolConfig = poolConfig;
    this.postponedCache = postponedCache;
    this.maxPromotablePerSender = poolConfig.getTxPoolMaxFutureTransactionByAccount();
    this.transactionReplacementTester = transactionReplacementTester;
  }

  public TransactionAddedResult add(
      final PendingTransaction pendingTransaction, final long senderNonce) {

    // if transaction too much in the future postpone
    if (pendingTransaction.getNonce() - senderNonce
        >= poolConfig.getTxPoolMaxFutureTransactionByAccount()) {
      postponedCache.add(pendingTransaction);
      return POSTPONED;
    }

    // try to add to the ready set
    var addToReadyStatus =
        modifySenderReadyTxsWrapper(
            pendingTransaction.getSender(),
            senderTxs -> tryAddToReady(senderTxs, pendingTransaction, senderNonce));

    if (addToReadyStatus.equals(POSTPONED)) {
      postponedCache.add(pendingTransaction);
    }
    return addToReadyStatus;
  }

  public Optional<PendingTransaction> get(final Address sender, final long nonce) {
    var senderTxs = readyBySender.get(sender);
    if (senderTxs != null) {
      return Optional.ofNullable(senderTxs.get(nonce));
    }
    return Optional.empty();
  }

  public Stream<PendingTransaction> streamReadyTransactions(final Address sender) {
    return streamReadyTransactions(sender, -1);
  }

  public Stream<PendingTransaction> streamReadyTransactions(
      final Address sender, final long afterNonce) {
    var senderTxs = readyBySender.get(sender);
    if (senderTxs != null) {
      return senderTxs.tailMap(afterNonce, false).values().stream();
    }
    return Stream.empty();
  }

  private synchronized <R> R modifySenderReadyTxsWrapper(
      final Address sender,
      final Function<NavigableMap<Long, PendingTransaction>, R> modifySenderTxs) {

    var senderTxs = readyBySender.get(sender);
    final Optional<Transaction> prevFirstTx = getFirstReadyTransaction(senderTxs);
    final long prevLastNonce = getLastReadyNonce(senderTxs);

    final var result = modifySenderTxs.apply(senderTxs);

    if (senderTxs == null) {
      // it could have been created
      senderTxs = readyBySender.get(sender);
    }

    final Optional<Transaction> currFirstTx = getFirstReadyTransaction(senderTxs);
    final long currLastNonce = getLastReadyNonce(senderTxs);

    if (!prevFirstTx.equals(currFirstTx)) {
      prevFirstTx.ifPresent(readyEvictionOrder::remove);
      currFirstTx.ifPresent(readyEvictionOrder::add);
    }

    final var cacheFreeSpace = cacheFreeSpace();
    if (cacheFreeSpace < 0) {
      // free some space moving less valuable ready to postponed
      postponedCache.addAll((evictReadyTransactions(-cacheFreeSpace)));
    } else {
      if (prevLastNonce != currLastNonce) {
        final int maxPromotable = maxPromotablePerSender - senderTxs.size();
        if (maxPromotable > 0) {
          postponedToReady(sender, currLastNonce, maxPromotable);
        }
      }
    }

    if (senderTxs != null && senderTxs.isEmpty()) {
      readyBySender.remove(sender);
    }

    return result;
  }

  private List<PendingTransaction> evictReadyTransactions(final long evictSize) {
    final var lessReadySender = readyEvictionOrder.first().getSender();
    final var lessReadySenderTxs = readyBySender.get(lessReadySender);

    final List<PendingTransaction> toPostponed = new ArrayList<>();
    long postponedSize = 0;
    PendingTransaction lastTx = null;
    // lastTx must never be null, because the sender have at least the lessReadyTx
    while (postponedSize < evictSize && !lessReadySenderTxs.isEmpty()) {
      lastTx = lessReadySenderTxs.pollLastEntry().getValue();
      toPostponed.add(lastTx);
      decreaseTotalSize(lastTx);
      postponedSize += lastTx.getTransaction().getSize();
    }

    if (lessReadySenderTxs.isEmpty()) {
      readyBySender.remove(lessReadySender);
      // at this point lastTx was the first for the sender, then remove it from eviction order too
      readyEvictionOrder.remove(lastTx.getTransaction());
      // try next less valuable sender
      toPostponed.addAll(evictReadyTransactions(evictSize - postponedSize));
    }
    return toPostponed;
  }

  private long cacheFreeSpace() {
    return MAX_READY_SIZE_BYTES - readyTotalSize.get();
  }

  private void postponedToReady(
      final Address sender, final long currLastNonce, final int maxPromotable) {

    postponedCache
        .promoteForSender(sender, currLastNonce, maxPromotable)
        .thenAccept(
            toReadyTxs -> {
              modifySenderReadyTxsWrapper(
                  sender, senderTxs -> postponedToReady(senderTxs, toReadyTxs));
            })
        .exceptionally(
            throwable -> {
              LOG.debug(
                  "Error moving from postponed to ready for sender {}, last nonce {}, max promotable {}, cause {}",
                  sender,
                  currLastNonce,
                  maxPromotable,
                  throwable.getMessage());
              return null;
            });
  }

  private Void postponedToReady(
      final NavigableMap<Long, PendingTransaction> senderTxs,
      final List<PendingTransaction> toReadyTxs) {

    var expectedNonce = senderTxs.lastKey() + 1;

    for (var tx : toReadyTxs) {
      if (!fitsInCache(tx)) {
        // cache full, stop moving to ready
        break;
      }
      if (tx.getNonce() == expectedNonce++) {
        senderTxs.put(tx.getNonce(), tx);
        increaseTotalSize(tx);
      }
    }
    return null;
  }

  private Optional<Transaction> getFirstReadyTransaction(
      final NavigableMap<Long, PendingTransaction> senderTxs) {
    if (senderTxs == null || senderTxs.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(senderTxs.firstEntry().getValue().getTransaction());
  }

  private long getLastReadyNonce(final NavigableMap<Long, PendingTransaction> senderTxs) {
    if (senderTxs == null || senderTxs.isEmpty()) {
      return -1;
    }
    return senderTxs.lastEntry().getKey();
  }

  private TransactionAddedResult tryAddToReady(
      final NavigableMap<Long, PendingTransaction> senderTxs,
      final PendingTransaction pendingTransaction,
      final long senderNonce) {

    if (senderTxs == null) {
      // add to ready only if the tx is the next for the sender
      if (pendingTransaction.getNonce() == senderNonce) {
        var newSenderTxs = new TreeMap<Long, PendingTransaction>();
        newSenderTxs.put(pendingTransaction.getNonce(), pendingTransaction);
        readyBySender.put(pendingTransaction.getSender(), newSenderTxs);
        increaseTotalSize(pendingTransaction);
        return ADDED;
      }
      return POSTPONED;
    }

    // is replacing an existing one?
    var existingReadyTx = senderTxs.get(pendingTransaction.getNonce());
    if (existingReadyTx != null) {

      if (existingReadyTx.getHash().equals(pendingTransaction.getHash())) {
        return ALREADY_KNOWN;
      }

      if (!transactionReplacementTester.apply(existingReadyTx, pendingTransaction)) {
        return REJECTED_UNDERPRICED_REPLACEMENT;
      }
      senderTxs.put(pendingTransaction.getNonce(), pendingTransaction);
      decreaseTotalSize(existingReadyTx);
      increaseTotalSize(pendingTransaction);
      return TransactionAddedResult.createForReplacement(existingReadyTx);
    }

    // is the next one?
    if (pendingTransaction.getNonce() == senderTxs.lastKey() + 1) {
      senderTxs.put(pendingTransaction.getNonce(), pendingTransaction);
      increaseTotalSize(pendingTransaction);
      return ADDED;
    }
    return POSTPONED;
  }

  private void increaseTotalSize(final PendingTransaction pendingTransaction) {
    readyTotalSize.addAndGet(pendingTransaction.getTransaction().getSize());
  }

  private boolean fitsInCache(final PendingTransaction pendingTransaction) {
    return readyTotalSize.get() + pendingTransaction.getTransaction().getSize()
        <= MAX_READY_SIZE_BYTES;
  }

  private void decreaseTotalSize(final PendingTransaction pendingTransaction) {
    decreaseTotalSize(pendingTransaction.getTransaction());
  }

  private void decreaseTotalSize(final Transaction transaction) {
    readyTotalSize.addAndGet(-transaction.getSize());
  }

  public void remove(final Transaction transaction) {
    modifySenderReadyTxsWrapper(
        transaction.getSender(), senderTxs -> remove(senderTxs, transaction));
  }

  private Void remove(
      final NavigableMap<Long, PendingTransaction> senderTxs, final Transaction transaction) {
    if (senderTxs != null && senderTxs.remove(transaction.getNonce()) != null) {
      decreaseTotalSize(transaction);
    }
    // handle the possible async status of postponed in progress
    postponedCache.remove(transaction);
    return null;
  }

  public OptionalLong getNextReadyNonce(final Address sender) {
    var senderTxs = readyBySender.get(sender);
    if (senderTxs != null) {
      return OptionalLong.of(senderTxs.lastKey() + 1);
    }
    return OptionalLong.empty();
  }

  public List<PendingTransaction> getPromotableTransactions(
      final List<Transaction> confirmedTransactions,
      final int maxPromotable,
      final Predicate<PendingTransaction> promotionFilter) {

    List<PendingTransaction> promotableTxs = new ArrayList<>(maxPromotable);
    // get confirmed tx with max nonce by sender
    final Map<Address, Optional<Long>> confirmedBySender =
        maxConfirmedNonceBySender(confirmedTransactions);

    for (var senderMaxConfirmedNonce : confirmedBySender.entrySet()) {
      var maxConfirmedNonce = senderMaxConfirmedNonce.getValue().get();
      final int maxRemaining = maxPromotable - promotableTxs.size();
      final var sender = senderMaxConfirmedNonce.getKey();
      promotableTxs.addAll(
          modifySenderReadyTxsWrapper(
              sender,
              senderTxs ->
                  removeConfirmedAndPromoteReadyTxs(
                      sender, senderTxs, maxConfirmedNonce, maxRemaining, promotionFilter)));
    }

    // if there is still space pick other ready transactions
    final int maxRemaining = maxPromotable - promotableTxs.size();
    if (maxRemaining > 0) {
      promotableTxs.addAll(promoteReady(confirmedBySender.keySet(), maxRemaining, promotionFilter));
    }

    return promotableTxs;
  }

  private Collection<PendingTransaction> removeConfirmedAndPromoteReadyTxs(
      final Address sender,
      final NavigableMap<Long, PendingTransaction> senderTxs,
      final long maxConfirmedNonce,
      final int maxPromotable,
      final Predicate<PendingTransaction> promotionFilter) {

    var confirmedTxsToRemove = senderTxs.headMap(maxConfirmedNonce, true);
    confirmedTxsToRemove.clear();

    postponedCache.removeForSenderBelowNonce(sender, maxConfirmedNonce);

    // if there is still space to promote some txs
    if (maxPromotable > 0) {
      // add promotable according to promotion filter and remaining space
      return promoteReady(senderTxs, maxPromotable, promotionFilter);
    }
    return List.of();
  }

  private Map<Address, Optional<Long>> maxConfirmedNonceBySender(
      final List<Transaction> confirmedTransactions) {
    return confirmedTransactions.stream()
        .collect(
            groupingBy(
                Transaction::getSender, mapping(Transaction::getNonce, maxBy(Long::compare))));
  }

  private Collection<PendingTransaction> promoteReady(
      final Set<Address> skipSenders,
      final int maxRemaining,
      final Predicate<PendingTransaction> promotionFilter) {

    final List<PendingTransaction> promotedTxs = new ArrayList<>(maxRemaining);

    for (var senderEntry : readyBySender.entrySet()) {
      if (!skipSenders.contains(senderEntry.getKey())) {
        final int maxForThisSender = maxRemaining - promotedTxs.size();
        if (maxForThisSender <= 0) {
          break;
        }
        promotedTxs.addAll(
            modifySenderReadyTxsWrapper(
                senderEntry.getKey(),
                senderTxs -> promoteReady(senderTxs, maxForThisSender, promotionFilter)));
      }
    }
    return promotedTxs;
  }

  private Collection<PendingTransaction> promoteReady(
      final NavigableMap<Long, PendingTransaction> senderTxs,
      final int maxRemaining,
      final Predicate<PendingTransaction> promotionFilter) {

    final int maxForThisSender = Math.min(maxPromotablePerSender, maxRemaining);
    final List<PendingTransaction> promotableTxs = new ArrayList<>(maxForThisSender);

    while (!senderTxs.isEmpty() && promotableTxs.size() < maxForThisSender) {
      var promotableEntry = senderTxs.firstEntry();
      if (promotionFilter.test(promotableEntry.getValue())) {
        senderTxs.pollFirstEntry();
        promotableTxs.add(promotableEntry.getValue());
        decreaseTotalSize(promotableEntry.getValue());
      } else {
        break;
      }
    }

    return promotableTxs;
  }
}
