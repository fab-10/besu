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
package org.hyperledger.besu.ethereum.eth.transactions;

import static org.hyperledger.besu.ethereum.core.Transaction.toHashList;

import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.messages.LimitedTransactionsMessage;
import org.hyperledger.besu.ethereum.p2p.rlpx.connections.PeerConnection.PeerNotConnected;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TransactionsMessageSender {
  private static final Logger LOG = LoggerFactory.getLogger(TransactionsMessageSender.class);

  private final PeerTransactionTracker transactionTracker;
  private final int maxTransactionsMessageSize;

  public TransactionsMessageSender(
      final PeerTransactionTracker transactionTracker, final int maxTransactionsMessageSize) {
    this.transactionTracker = transactionTracker;
    this.maxTransactionsMessageSize = maxTransactionsMessageSize;
  }

  void sendTransactionsToPeer(final EthPeer peer) {
    final List<Transaction> transactionsBatch = new ArrayList<>();
    Transaction txToSend = transactionTracker.claimTransactionToSendToPeer(peer);
    LimitedTransactionsMessage limitedTransactionsMessage =
        new LimitedTransactionsMessage(maxTransactionsMessageSize);

    while (txToSend != null) {

      if (!transactionTracker.hasSeenTransactionOrAnnouncement(peer, txToSend.getHash())) {

        final var added = limitedTransactionsMessage.add(txToSend);

        if (added) {
          transactionTracker.markTransactionAsSeen(peer, txToSend);
        } else {
          // message is full, then send it and prepare the next batch
          send(peer, transactionsBatch, limitedTransactionsMessage);

          // prepare the next batch
          transactionsBatch.clear();
          limitedTransactionsMessage = new LimitedTransactionsMessage(maxTransactionsMessageSize);
          limitedTransactionsMessage.add(txToSend);
        }

        transactionsBatch.add(txToSend);
      }

      txToSend = transactionTracker.claimTransactionToSendToPeer(peer);
    }

    if (!transactionsBatch.isEmpty()) {
      send(peer, transactionsBatch, limitedTransactionsMessage);
    }
  }

  private static void send(
      final EthPeer peer,
      final List<Transaction> includedTransactions,
      final LimitedTransactionsMessage limitedTransactionsMessage) {
    if (!includedTransactions.isEmpty()) {
      try {
        LOG.atTrace()
            .setMessage(
                "Sent transactions to peer={}, this message txs count={},"
                    + " this message hashes={}, estimated size {}")
            .addArgument(peer)
            .addArgument(includedTransactions::size)
            .addArgument(() -> toHashList(includedTransactions))
            .addArgument(limitedTransactionsMessage::getEstimatedMessageSize)
            .log();

        peer.send(limitedTransactionsMessage.getTransactionsMessage());
      } catch (final PeerNotConnected e) {
        LOG.atTrace()
            .setMessage(
                "Peer no more connected while sending transactions: peer={}, message hashes={}")
            .addArgument(peer)
            .addArgument(() -> toHashList(includedTransactions))
            .log();
      } catch (final Exception e) {
        LOG.debug("Failed to send transactions to peer {}", peer, e);
      }
    }
  }
}
