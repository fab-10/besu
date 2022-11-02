/*
 * Copyright Besu contributors.
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

import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.POSTPONED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.REJECTED_UNDERPRICED_REPLACEMENT;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolReplacementHandler;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

class PostponedTransactionsCache {
  // private static final Logger LOG = LoggerFactory.getLogger(PostponedTransactionsCache.class);

  private final TransactionPoolReplacementHandler transactionReplacementHandler;
  private final Supplier<BlockHeader> chainHeadHeaderSupplier;
  private final Table<Address, Long, PendingTransaction> postponedTransactions =
      HashBasedTable.create();
  private final Map<Address, Long> nonceGap = new HashMap<>();
  final LabelledMetric<Counter> transactionReplacedCounter;

  public PostponedTransactionsCache(
      final TransactionPoolReplacementHandler transactionReplacementHandler,
      final Supplier<BlockHeader> chainHeadHeaderSupplier,
      final LabelledMetric<Counter> transactionReplacedCounter) {
    this.transactionReplacementHandler = transactionReplacementHandler;
    this.chainHeadHeaderSupplier = chainHeadHeaderSupplier;
    this.transactionReplacedCounter = transactionReplacedCounter;
  }

  TransactionAddedResult add(
      final PendingTransaction pendingTransaction, final long nonceDistance) {
    final Address sender = pendingTransaction.getSender();
    final long nonce = pendingTransaction.getNonce();

    final var existingTxInfo = postponedTransactions.get(sender, nonce);
    if (existingTxInfo != null) {
      if (!transactionReplacementHandler.shouldReplace(
          existingTxInfo, pendingTransaction, chainHeadHeaderSupplier.get())) {
        return REJECTED_UNDERPRICED_REPLACEMENT;
      }
      incrementTransactionReplacedCounter(pendingTransaction.isReceivedFromLocalSource());
    }

    postponedTransactions.put(sender, nonce, pendingTransaction);
    nonceGap.merge(
        sender, nonceDistance, (prevDistance, newDistance) -> Math.min(prevDistance, newDistance));
    return POSTPONED;
  }

  public int size() {
    return postponedTransactions.size();
  }

  private void incrementTransactionReplacedCounter(final boolean receivedFromLocalSource) {
    final String location = receivedFromLocalSource ? "local" : "remote";
    transactionReplacedCounter.labels(location, "postponed").inc();
  }
}
