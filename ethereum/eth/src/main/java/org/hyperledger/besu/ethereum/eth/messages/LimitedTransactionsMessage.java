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
package org.hyperledger.besu.ethereum.eth.messages;

import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;

import org.apache.tuweni.bytes.Bytes;

public final class LimitedTransactionsMessage {
  //  private static final Logger LOG = LoggerFactory.getLogger(LimitedTransactionsMessage.class);
  //
  //  private final TransactionsMessage transactionsMessage;
  //  private final Set<Transaction> includedTransactions;
  //
  //  public LimitedTransactionsMessages(
  //      final TransactionsMessage transactionsMessage, final Set<Transaction>
  // includedTransactions) {
  //    this.transactionsMessage = transactionsMessage;
  //    this.includedTransactions = includedTransactions;
  //  }
  private final int maxTransactionsMessageSize;
  private final BytesValueRLPOutput message = new BytesValueRLPOutput();
  private int estimatedMsgSize = RLP.MAX_PREFIX_SIZE;

  public LimitedTransactionsMessage(final int maxTransactionsMessageSize) {
    this.maxTransactionsMessageSize = maxTransactionsMessageSize;
    message.startList();
  }

  public boolean add(final Transaction transaction) {
    final BytesValueRLPOutput encodedTransaction = new BytesValueRLPOutput();
    transaction.writeTo(encodedTransaction);
    final Bytes encodedBytes = encodedTransaction.encoded();
    if (estimatedMsgSize + encodedBytes.size() > maxTransactionsMessageSize) {
      return false;
    }
    message.writeRaw(encodedBytes);
    estimatedMsgSize += encodedBytes.size();
    return true;
  }

  //    message.startList();
  //
  //    final var itTransactions = transactions.iterator();
  //    while (itTransactions.hasNext()) {
  //      final var transaction = itTransactions.next();
  //      if (!transactionTracker.hasPeerSeenTransaction(peer, transaction)) {
  //        final BytesValueRLPOutput encodedTransaction = new BytesValueRLPOutput();
  //        transaction.writeTo(encodedTransaction);
  //        final Bytes encodedBytes = encodedTransaction.encoded();
  //        if (estimatedMsgSize + encodedBytes.size() > maxTransactionsMessageSize) {
  //          break;
  //        }
  //        message.writeRaw(encodedBytes);
  //        includedTransactions.add(transaction);
  //        // Check if last transaction to add to the message
  //        estimatedMsgSize += encodedBytes.size();
  //      }
  //      itTransactions.remove();
  //    }
  //    message.endList();
  //    LOG.atTrace()
  //        .setMessage(
  //            "Transactions message created with {} txs included out of {} txs available, message
  // size {}")
  //        .addArgument(includedTransactions::size)
  //        .addArgument(transactions::size)
  //        .addArgument(message::encodedSize)
  //        .log();
  //    return new LimitedTransactionsMessage(
  //        new TransactionsMessage(message.encoded()), includedTransactions);
  //  }

  public int getEstimatedMessageSize() {
    return estimatedMsgSize;
  }

  public TransactionsMessage getTransactionsMessage() {
    message.endList();
    return new TransactionsMessage(message.encoded());
  }
  //
  //  public Set<Transaction> getIncludedTransactions() {
  //    return includedTransactions;
  //  }
}
