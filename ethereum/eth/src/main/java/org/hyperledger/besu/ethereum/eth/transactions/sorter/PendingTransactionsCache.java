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
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.POSTPONED;

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
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PendingTransactionsCache {

  private static final Long MAX_READY_SIZE_BYTES = 1_000_000_000L;

  private final Map<Hash, PendingTransaction> readyAndPrioritized;
  private final Map<Address, SortedMap<Long, PendingTransaction>> readyBySender =
      new ConcurrentHashMap<>();

  private final AtomicLong readyTotalSize = new AtomicLong();

  private final Map<Address, SortedMap<Long, PendingTransaction>> postponedBySender =
      new ConcurrentHashMap<>();
  private final TransactionPoolReplacementHandler transactionReplacementHandler;
  private final Supplier<BlockHeader> chainHeadHeaderSupplier;

  public PendingTransactionsCache(
      final int maxPrioritized,
      final TransactionPoolReplacementHandler transactionReplacementHandler,
      final Supplier<BlockHeader> chainHeadHeaderSupplier) {
    this.readyAndPrioritized = new HashMap<>(maxPrioritized);
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

  public List<PendingTransaction> promote(final List<Transaction> confirmedTransactions) {
    final Map<Address, Optional<Transaction>> confirmedBySender =
        confirmedTransactions.stream()
            .collect(
                Collectors.groupingBy(
                    Transaction::getSender,
                    Collectors.maxBy(Comparator.comparingLong(Transaction::getNonce))));

    for (var entry : confirmedBySender.entrySet()) {
      var sender = entry.getKey();

      var senderTxs = readyBySender.get(sender);
      if (senderTxs != null) {
        var maxNonce = entry.getValue().get();
      }
    }

    for (Transaction confirmedTx : confirmedTransactions) {
      var senderTxs = readyBySender.get(confirmedTx.getSender());
      if (senderTxs != null) {
        // first remove confirmed transaction for this sender
        senderTxs.headMap(confirmedTx.getNonce()).clear();
        if (!senderTxs.isEmpty()) {
          // if the sender has ready transactions
        }
      }
    }
  }
}
