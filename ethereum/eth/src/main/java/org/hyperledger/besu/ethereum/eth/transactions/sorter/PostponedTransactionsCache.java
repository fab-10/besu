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

import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedStatus.POSTPONED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedStatus.REJECTED_UNDERPRICED_REPLACEMENT;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedStatus;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolReplacementHandler;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;

import java.util.function.Supplier;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PostponedTransactionsCache {
  //private static final Logger LOG = LoggerFactory.getLogger(PostponedTransactionsCache.class);

  private final TransactionPoolReplacementHandler transactionReplacementHandler;
  private final Supplier<BlockHeader> chainHeadHeaderSupplier;
  private final Table<Address, Long, TransactionInfo> postponedTransactions =
      HashBasedTable.create();
  final LabelledMetric<Counter> transactionReplacedCounter;

  public PostponedTransactionsCache(
      final TransactionPoolReplacementHandler transactionReplacementHandler,
      final Supplier<BlockHeader> chainHeadHeaderSupplier,
      final LabelledMetric<Counter> transactionReplacedCounter) {
    this.transactionReplacementHandler = transactionReplacementHandler;
    this.chainHeadHeaderSupplier = chainHeadHeaderSupplier;
    this.transactionReplacedCounter = transactionReplacedCounter;
  }

  TransactionAddedStatus add(final TransactionInfo transactionInfo) {
    final Address sender = transactionInfo.getSender();
    final long nonce = transactionInfo.getNonce();

    final var existingTxInfo = postponedTransactions.get(sender, nonce);
    if (existingTxInfo != null) {
      if (!transactionReplacementHandler.shouldReplace(
          existingTxInfo, transactionInfo, chainHeadHeaderSupplier.get())) {
        return REJECTED_UNDERPRICED_REPLACEMENT;
      }
      incrementTransactionReplacedCounter(transactionInfo.isReceivedFromLocalSource());
    }

    postponedTransactions.put(sender, nonce, transactionInfo);
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
