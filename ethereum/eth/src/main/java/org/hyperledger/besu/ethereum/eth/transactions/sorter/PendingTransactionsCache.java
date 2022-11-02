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

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.maxBy;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.POSTPONED;

import java.util.ArrayList;
import java.util.Collection;
import java.util.NavigableMap;
import java.util.Set;
import java.util.function.Predicate;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolReplacementHandler;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PendingTransactionsCache {

  private static final Long MAX_READY_SIZE_BYTES = 1_000_000_000L;

  private final Map<Hash, PendingTransaction> prioritized;
  private final Map<Address, NavigableMap<Long, PendingTransaction>> readyBySender =
      new ConcurrentHashMap<>();

  private final AtomicLong readyTotalSize = new AtomicLong();

  private final Map<Address, NavigableMap<Long, PendingTransaction>> postponedBySender =
      new ConcurrentHashMap<>();
  private final TransactionPoolReplacementHandler transactionReplacementHandler;
  private final Supplier<BlockHeader> chainHeadHeaderSupplier;

  private final int maxPromotablePerSender;

  public PendingTransactionsCache(
      final int maxPrioritized,
      final int maxPromotablePerSender,
      final TransactionPoolReplacementHandler transactionReplacementHandler,
      final Supplier<BlockHeader> chainHeadHeaderSupplier) {
    this.prioritized = new HashMap<>(maxPrioritized);
    this.maxPromotablePerSender = maxPromotablePerSender;
    this.transactionReplacementHandler = transactionReplacementHandler;
    this.chainHeadHeaderSupplier = chainHeadHeaderSupplier;
  }

  public TransactionAddedResult add(
      final PendingTransaction pendingTransaction, final long senderNonce) {
    var addToReadyStatus = addToReady(pendingTransaction, senderNonce);
    if (addToReadyStatus.equals(POSTPONED)) {
      postpone(pendingTransaction, senderNonce);
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
    var senderTxs = readyBySender.get(sender);
    if (senderTxs != null) {
      return senderTxs.values().stream();
    }
    return Stream.empty();
  }

  private TransactionAddedResult addToReady(
      final PendingTransaction pendingTransaction, final long senderNonce) {
    var senderTxs = readyBySender.get(pendingTransaction.getSender());
    if (senderTxs == null) {
      if (pendingTransaction.getNonce() == senderNonce) {
        var newSenderReadySet = new TreeMap<Long, PendingTransaction>();
        newSenderReadySet.put(pendingTransaction.getNonce(), pendingTransaction);
        readyBySender.put(pendingTransaction.getSender(), newSenderReadySet);
        updateTotalSize(payloadSize(pendingTransaction));
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
      senderTxs.put(pendingTransaction.getNonce(), pendingTransaction);
      var sizeDiff = payloadSize(pendingTransaction) - payloadSize(existingReadyTx);
      updateTotalSize(sizeDiff);
      return TransactionAddedResult.createForReplacement(existingReadyTx);
    }
    // is the next one?
    if (pendingTransaction.getNonce() == senderTxs.lastKey() + 1) {
      senderTxs.put(pendingTransaction.getNonce(), pendingTransaction);
      updateTotalSize(payloadSize(pendingTransaction));
      return ADDED;
    }
    return POSTPONED;
  }

  private void postpone(final PendingTransaction pendingTransaction, final Long senderNonce) {
    // add to long tail on disk cache, possibly asynchronously
  }

  private long payloadSize(final PendingTransaction pendingTransaction) {
    return pendingTransaction.getTransaction().getPayload().size();
  }

  private void updateTotalSize(final long sizeDiff) {
    if (readyTotalSize.addAndGet(sizeDiff) > MAX_READY_SIZE_BYTES) {
      // schedule move some ready to postpone
    }
  }

  public void remove(final Transaction transaction) {
    var senderTxs = readyBySender.get(transaction.getSender());
    if (senderTxs != null) {
      senderTxs.remove(transaction.getNonce());
    }
    // handle the possible async status of postponed in progress
    removePostponed(transaction);
  }

  private void removePostponed(Transaction transaction) {}

  public OptionalLong getNextReadyNonce(final Address sender) {
    var senderTxs = readyBySender.get(sender);
    if (senderTxs != null) {
      return OptionalLong.of(senderTxs.lastKey() + 1);
    }
    return OptionalLong.empty();
  }

  public List<PendingTransaction> promote(
      final List<Transaction> confirmedTransactions,
      final int maxPromotable,
      final Comparator<PendingTransaction> comparator) {

    List<PendingTransaction> promotableTxs = new ArrayList<>(maxPromotable);
    // get confirmed tx with max nonce by sender
    final Map<Address, Optional<Long>> confirmedBySender =
        maxConfirmedNonceBySender(confirmedTransactions);

    for (var senderMaxConfirmedNonce : confirmedBySender.entrySet()) {
      var sender = senderMaxConfirmedNonce.getKey();

      var senderTxs = readyBySender.get(sender);
      if (senderTxs != null) {
        // remove all tx <= max confirmed nonce for sender
        var maxNonce = senderMaxConfirmedNonce.getValue().get();
        senderTxs.headMap(maxNonce).clear();
        if (senderTxs.isEmpty()) {
          // check if some postponed are now ready
          postponedToReady(sender, senderTxs, maxNonce);
        }
        // if there is still space
        final int maxRemaining = maxPromotable - promotableTxs.size();
        if (maxRemaining > 0) {
          // add promotable according to promotion filter and remaining space
          promotableTxs.addAll(promoteReady(sender, senderTxs, maxRemaining, promotionFilter));
        }
      }
    }

    // if there is still space pick other ready senders
    final int maxRemaining = maxPromotable - promotableTxs.size();
    if (maxRemaining > 0) {
      promotableTxs.addAll(promoteReady(confirmedBySender.keySet(), maxRemaining, promotionFilter));
    }

    return promotableTxs;
  }

  private static Map<Address, Optional<Long>> maxConfirmedNonceBySender(
      List<Transaction> confirmedTransactions) {
    return confirmedTransactions.stream()
        .collect(
            groupingBy(
                Transaction::getSender, mapping(Transaction::getNonce, maxBy(Long::compare))));
  }

  private Collection<PendingTransaction> promoteReady(
      final Set<Address> skipSenders,
      final int maxRemaining,
      final Predicate<PendingTransaction> promotionFilter) {
    List<PendingTransaction> promotedTxs = new ArrayList<>(maxRemaining);
    for (var senderEntry : readyBySender.entrySet()) {
      if (!skipSenders.contains(senderEntry.getKey())) {
        final int maxForSender = maxRemaining - promotedTxs.size();
        promotedTxs.addAll(
            promoteReady(
                senderEntry.getKey(), senderEntry.getValue(), maxForSender, promotionFilter));
        if (maxForSender <= 0) {
          break;
        }
      }
    }
    return promotedTxs;
  }

  private Collection<PendingTransaction> promoteReady(
      final Address sender,
      final NavigableMap<Long, PendingTransaction> senderTxs,
      final int maxRemaining,
      final Predicate<PendingTransaction> promotionFilter) {

    final int maxForThisSender = Math.min(maxPromotablePerSender, maxRemaining);
    List<PendingTransaction> promotableTxs = new ArrayList<>(maxForThisSender);
    while (!senderTxs.isEmpty() && promotableTxs.size() < maxForThisSender) {
      var promotableEntry = senderTxs.firstEntry();
      if (promotionFilter.test(promotableEntry.getValue())) {
        senderTxs.pollFirstEntry();
        promotableTxs.add(promotableEntry.getValue());
      } else {
        break;
      }
    }

    if (senderTxs.isEmpty()) {
      readyBySender.remove(sender);
    }
    return promotableTxs;
  }

  private void postponedToReady(
      final Address sender,
      final NavigableMap<Long, PendingTransaction> senderTxs,
      final long maxNonce) {
    var postponedSenderTxs = postponedBySender.get(sender);
    if (postponedSenderTxs != null) {
      long expectedNonce = maxNonce + 1;
      while (!postponedSenderTxs.isEmpty() && postponedSenderTxs.firstKey() == expectedNonce) {
        var readyTx = postponedSenderTxs.pollFirstEntry();
        senderTxs.put(readyTx.getKey(), readyTx.getValue());
        expectedNonce++;
      }

      if (postponedSenderTxs.isEmpty()) {
        postponedBySender.remove(sender);
      }
    }
  }
}
