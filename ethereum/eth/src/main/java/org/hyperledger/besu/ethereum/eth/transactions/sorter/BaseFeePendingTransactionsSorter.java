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

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toUnmodifiableList;
import static org.hyperledger.besu.util.Slf4jLambdaHelper.traceLambda;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.time.Clock;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the current set of pending transactions with the ability to iterate them based on priority
 * for mining or look-up by hash.
 *
 * <p>This class is safe for use across multiple threads.
 */
public class BaseFeePendingTransactionsSorter extends AbstractPendingTransactionsSorter {

  private static final Logger LOG = LoggerFactory.getLogger(BaseFeePendingTransactionsSorter.class);

  private Optional<Wei> baseFee;

  public BaseFeePendingTransactionsSorter(
      final TransactionPoolConfiguration poolConfig,
      final Clock clock,
      final MetricsSystem metricsSystem,
      final Supplier<BlockHeader> chainHeadHeaderSupplier) {
    super(poolConfig, clock, metricsSystem, chainHeadHeaderSupplier);
    this.baseFee = chainHeadHeaderSupplier.get().getBaseFee();
  }

  /**
   * See this post for an explainer about these data structures:
   * https://hackmd.io/@adietrichs/1559-transaction-sorting
   */
  private final NavigableSet<TransactionInfo> prioritizedTransactionsStaticRange =
      new TreeSet<>(
          comparing(TransactionInfo::isReceivedFromLocalSource)
              .thenComparing(
                  transactionInfo ->
                      transactionInfo
                          .getTransaction()
                          .getMaxPriorityFeePerGas()
                          // just in case we attempt to compare non-1559 transaction
                          .orElse(Wei.ZERO)
                          .getAsBigInteger()
                          .longValue())
              .thenComparing(TransactionInfo::getAddedToPoolAt)
              .thenComparing(TransactionInfo::getSequence)
              .reversed());

  private final NavigableSet<TransactionInfo> prioritizedTransactionsDynamicRange =
      new TreeSet<>(
          comparing(TransactionInfo::isReceivedFromLocalSource)
              .thenComparing(
                  transactionInfo ->
                      transactionInfo
                          .getTransaction()
                          .getMaxFeePerGas()
                          .map(maxFeePerGas -> maxFeePerGas.getAsBigInteger().longValue())
                          .orElse(transactionInfo.getGasPrice().toLong()))
              .thenComparing(TransactionInfo::getAddedToPoolAt)
              .thenComparing(TransactionInfo::getSequence)
              .reversed());

  @Override
  public void manageBlockAdded(final Block block) {
    block.getHeader().getBaseFee().ifPresent(this::updateBaseFee);
  }

  //  @Override
  //  protected void doRemoveTransaction(final Transaction transaction, final boolean addedToBlock)
  // {
  //    synchronized (lock) {
  //      final TransactionInfo removedTransactionInfo =
  //          pendingTransactions.remove(transaction.getHash());
  //      if (removedTransactionInfo != null) {
  //        if (prioritizedTransactionsDynamicRange.remove(removedTransactionInfo)) {
  //          traceLambda(
  //              LOG, "Removed dynamic range transaction {}", removedTransactionInfo::toTraceLog);
  //        } else {
  //          removedTransactionInfo
  //              .getTransaction()
  //              .getMaxPriorityFeePerGas()
  //              .ifPresent(
  //                  __ -> {
  //                    if (prioritizedTransactionsStaticRange.remove(removedTransactionInfo)) {
  //                      traceLambda(
  //                          LOG,
  //                          "Removed static range transaction {}",
  //                          removedTransactionInfo::toTraceLog);
  //                    }
  //                  });
  //        }
  //        removeTransactionInfoTrackedBySenderAndNonce(removedTransactionInfo, addedToBlock);
  //        incrementTransactionRemovedCounter(
  //            removedTransactionInfo.isReceivedFromLocalSource(), addedToBlock);
  //      }
  //    }
  //  }
  //
  //  @Override
  //  protected TransactionAddedStatus customAddTransaction(
  //      final TransactionInfo transactionInfo, final Optional<Account> maybeSenderAccount) {
  //    final Transaction transaction = transactionInfo.getTransaction();
  //    synchronized (lock) {
  //      if (pendingTransactions.containsKey(transactionInfo.getHash())) {
  //        traceLambda(LOG, "Already known transaction {}", transactionInfo::toTraceLog);
  //        return ALREADY_KNOWN;
  //      }
  //
  //      if (transaction.getNonce() - maybeSenderAccount.map(AccountState::getNonce).orElse(0L)
  //          >= poolConfig.getTxPoolMaxFutureTransactionByAccount()) {
  //        traceLambda(
  //            LOG,
  //            "Transaction {} not added because nonce too far in the future for sender {}",
  //            transaction::toTraceLog,
  //            maybeSenderAccount::toString);
  //        return NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
  //      }
  //
  //      final TransactionAddedStatus transactionAddedStatus =
  //          addTransactionForSenderAndNonce(transactionInfo);
  //
  //      if (transactionAddedStatus.equals(ADDED)) {
  //        transactionPrioritization(transactionInfo);
  //        notifyTransactionAdded(transaction);
  //        traceLambda(LOG, "Transaction prioritized {}", transactionInfo::toTraceLog);
  //      } else if (transactionAddedStatus.equals(POSTPONED)) {
  //        traceLambda(LOG, "Transaction postponed {}", transactionInfo::toTraceLog);
  //      } else {
  //        traceLambda(
  //            LOG,
  //            "Transaction {}, not added with status {}",
  //            transactionInfo::toTraceLog,
  //            transactionAddedStatus::name);
  //      }
  //      return transactionAddedStatus;
  //    }
  //  }

  @Override
  protected void addPriorityTransaction(final TransactionInfo transactionInfo) {
    // check if it's in static or dynamic range
    final String kind;
    if (isInStaticRange(transactionInfo.getTransaction(), baseFee)) {
      kind = "static";
      prioritizedTransactionsStaticRange.add(transactionInfo);
    } else {
      kind = "dynamic";
      prioritizedTransactionsDynamicRange.add(transactionInfo);
    }
    traceLambda(
        LOG,
        "Added {} to pending transactions, range type {}",
        transactionInfo::toTraceLog,
        kind::toString);
  }

  @Override
  protected void removePriorityTransaction(final TransactionInfo removedTransactionInfo) {
    if (prioritizedTransactionsDynamicRange.remove(removedTransactionInfo)) {
      traceLambda(LOG, "Removed dynamic range transaction {}", removedTransactionInfo::toTraceLog);
    } else if (prioritizedTransactionsStaticRange.remove(removedTransactionInfo)) {
      traceLambda(LOG, "Removed static range transaction {}", removedTransactionInfo::toTraceLog);
    }
  }

  @Override
  protected TransactionInfo getLeastPriorityTransaction() {
    final var lastStatic = prioritizedTransactionsStaticRange.last();
    final var lastDynamic = prioritizedTransactionsDynamicRange.last();

    if (lastDynamic == null) {
      return lastStatic;
    }
    if (lastStatic == null) {
      return lastDynamic;
    }

    final Comparator<TransactionInfo> compareByValue =
        Comparator.comparing(
            txInfo ->
                txInfo.getTransaction().getEffectivePriorityFeePerGas(baseFee).getAsBigInteger());

    return compareByValue.compare(lastStatic, lastDynamic) < 0 ? lastStatic : lastDynamic;
  }

//  private void prioritizeTransaction(TransactionInfo txInfo) {
//    // check if it's in static or dynamic range
//    final String kind;
//    if (isInStaticRange(txInfo.getTransaction(), baseFee)) {
//      kind = "static";
//      prioritizedTransactionsStaticRange.add(txInfo);
//    } else {
//      kind = "dynamic";
//      prioritizedTransactionsDynamicRange.add(txInfo);
//    }
//    pendingTransactions.put(txInfo.getHash(), txInfo);
//    traceLambda(
//        LOG, "Added {} to pending transactions, range type {}", txInfo::toTraceLog, kind::toString);
//  }

  //  private void dePrioritizeTransaction(final TransactionInfo transactionInfo) {
  //    final TransactionsForSenderInfo transactionsForSenderInfo =
  //        transactionsBySender.get(transactionInfo.getSender());
  //
  //    // de-prioritized the tx and all the following ones for that sender
  //    final var dePrioritizedTxs =
  //        transactionsForSenderInfo.getConsecutiveTransactionInfos(transactionInfo.getNonce());
  //    prioritizedTransactionsDynamicRange.removeAll(dePrioritizedTxs);
  //    prioritizedTransactionsStaticRange.removeAll(dePrioritizedTxs);
  //    pendingTransactions
  //        .entrySet()
  //        .removeAll(
  //
  // dePrioritizedTxs.stream().map(TransactionInfo::getHash).collect(toUnmodifiableList()));
  //    traceLambda(LOG, "De-prioritized transactions {}", dePrioritizedTxs::toString);
  //  }

  //  private TransactionInfo compareWithLowestValueTransactionInfo(
  //      final TransactionInfo incomingTxInfo) {
  //    final Stream.Builder<TransactionInfo> removalCandidates = Stream.builder();
  //    removalCandidates.add(incomingTxInfo);
  //    if (!prioritizedTransactionsDynamicRange.isEmpty()) {
  //      removalCandidates.add(prioritizedTransactionsDynamicRange.last());
  //    }
  //    if (!prioritizedTransactionsStaticRange.isEmpty()) {
  //      removalCandidates.add(prioritizedTransactionsStaticRange.last());
  //    }
  //
  //    return removalCandidates
  //        .build()
  //        .min(
  //            Comparator.comparing(
  //                txInfo ->
  //                    txInfo
  //                        .getTransaction()
  //                        .getEffectivePriorityFeePerGas(baseFee)
  //                        .getAsBigInteger()))
  //        .get();
  //  }

  private boolean isInStaticRange(final Transaction transaction, final Optional<Wei> baseFee) {
    return transaction
        .getMaxPriorityFeePerGas()
        .map(
            maxPriorityFeePerGas ->
                transaction.getEffectivePriorityFeePerGas(baseFee).compareTo(maxPriorityFeePerGas)
                    >= 0)
        .orElse(
            // non-eip-1559 txs can't be in static range
            false);
  }

  public void updateBaseFee(final Wei newBaseFee) {
    traceLambda(
        LOG,
        "Updating base fee from {} to {}",
        this.baseFee::toString,
        newBaseFee::toShortHexString);
    if (this.baseFee.orElse(Wei.ZERO).equals(newBaseFee)) {
      return;
    }
    synchronized (lock) {
      final boolean baseFeeIncreased = newBaseFee.compareTo(this.baseFee.orElse(Wei.ZERO)) > 0;
      this.baseFee = Optional.of(newBaseFee);
      if (baseFeeIncreased) {
        // base fee increases can only cause transactions to go from static to dynamic range
        prioritizedTransactionsStaticRange.stream()
            .filter(
                // these are the transactions whose effective priority fee have now dropped
                // below their max priority fee
                transactionInfo1 -> !isInStaticRange(transactionInfo1.getTransaction(), baseFee))
            .collect(toUnmodifiableList())
            .forEach(
                transactionInfo -> {
                  traceLambda(
                      LOG,
                      "Moving {} from static to dynamic gas fee paradigm",
                      transactionInfo::toTraceLog);
                  prioritizedTransactionsStaticRange.remove(transactionInfo);
                  prioritizedTransactionsDynamicRange.add(transactionInfo);
                });
      } else {
        // base fee decreases can only cause transactions to go from dynamic to static range
        prioritizedTransactionsDynamicRange.stream()
            .filter(
                // these are the transactions whose effective priority fee are now above their
                // max priority fee
                transactionInfo1 -> isInStaticRange(transactionInfo1.getTransaction(), baseFee))
            .collect(toUnmodifiableList())
            .forEach(
                transactionInfo -> {
                  traceLambda(
                      LOG,
                      "Moving {} from dynamic to static gas fee paradigm",
                      transactionInfo::toTraceLog);
                  prioritizedTransactionsDynamicRange.remove(transactionInfo);
                  prioritizedTransactionsStaticRange.add(transactionInfo);
                });
      }
    }
  }

  @Override
  protected Iterator<TransactionInfo> prioritizedTransactions() {
    return new Iterator<>() {
      final Iterator<TransactionInfo> staticRangeIterable =
          prioritizedTransactionsStaticRange.iterator();
      final Iterator<TransactionInfo> dynamicRangeIterable =
          prioritizedTransactionsDynamicRange.iterator();

      Optional<TransactionInfo> currentStaticRangeTransaction =
          getNextOptional(staticRangeIterable);
      Optional<TransactionInfo> currentDynamicRangeTransaction =
          getNextOptional(dynamicRangeIterable);

      @Override
      public boolean hasNext() {
        return currentStaticRangeTransaction.isPresent()
            || currentDynamicRangeTransaction.isPresent();
      }

      @Override
      public TransactionInfo next() {
        if (currentStaticRangeTransaction.isEmpty() && currentDynamicRangeTransaction.isEmpty()) {
          throw new NoSuchElementException("Tried to iterate past end of iterator.");
        } else if (currentStaticRangeTransaction.isEmpty()) {
          // only dynamic range txs left
          final TransactionInfo best = currentDynamicRangeTransaction.get();
          currentDynamicRangeTransaction = getNextOptional(dynamicRangeIterable);
          return best;
        } else if (currentDynamicRangeTransaction.isEmpty()) {
          // only static range txs left
          final TransactionInfo best = currentStaticRangeTransaction.get();
          currentStaticRangeTransaction = getNextOptional(staticRangeIterable);
          return best;
        } else {
          // there are both static and dynamic txs remaining, so we need to compare them by their
          // effective priority fees
          final Wei dynamicRangeEffectivePriorityFee =
              currentDynamicRangeTransaction
                  .get()
                  .getTransaction()
                  .getEffectivePriorityFeePerGas(baseFee);
          final Wei staticRangeEffectivePriorityFee =
              currentStaticRangeTransaction
                  .get()
                  .getTransaction()
                  .getEffectivePriorityFeePerGas(baseFee);
          final TransactionInfo best;
          if (dynamicRangeEffectivePriorityFee.compareTo(staticRangeEffectivePriorityFee) > 0) {
            best = currentDynamicRangeTransaction.get();
            currentDynamicRangeTransaction = getNextOptional(dynamicRangeIterable);
          } else {
            best = currentStaticRangeTransaction.get();
            currentStaticRangeTransaction = getNextOptional(staticRangeIterable);
          }
          return best;
        }
      }

      private Optional<TransactionInfo> getNextOptional(
          final Iterator<TransactionInfo> transactionInfoIterator) {
        return transactionInfoIterator.hasNext()
            ? Optional.of(transactionInfoIterator.next())
            : Optional.empty();
      }
    };
  }
}
