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
package org.hyperledger.besu.ethereum.eth.manager.task;

import static org.hyperledger.besu.ethereum.core.Transaction.toHashList;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetPooledTransactionsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.transactions.PeerTransactionTracker;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BufferedGetPooledTransactionsFromPeerFetcher {
  private static final Logger LOG =
      LoggerFactory.getLogger(BufferedGetPooledTransactionsFromPeerFetcher.class);
  @VisibleForTesting static final int MAX_HASHES = 256;

  private final TransactionPool transactionPool;
  private final PeerTransactionTracker transactionTracker;
  private final EthContext ethContext;
  private final EthPeer peer;

  public BufferedGetPooledTransactionsFromPeerFetcher(
      final EthContext ethContext,
      final EthPeer peer,
      final TransactionPool transactionPool,
      final PeerTransactionTracker transactionTracker) {
    this.ethContext = ethContext;
    this.peer = peer;
    this.transactionPool = transactionPool;
    this.transactionTracker = transactionTracker;
  }

  public void requestTransactions() {
    List<Hash> txHashesToRequest;
    while (!(txHashesToRequest =
            transactionTracker.claimTransactionAnnouncementsToRequestFromPeer(peer, MAX_HASHES))
        .isEmpty()) {
      LOG.atTrace()
          .setMessage("Transaction hashes to request from peer={}, requesting hashes={}")
          .addArgument(peer)
          .addArgument(txHashesToRequest)
          .log();

      final GetPooledTransactionsFromPeerTask task =
          new GetPooledTransactionsFromPeerTask(txHashesToRequest);

      try {
        PeerTaskExecutorResult<List<Transaction>> taskResult =
            ethContext.getPeerTaskExecutor().executeAgainstPeer(task, peer);

        if (taskResult.responseCode() != PeerTaskExecutorResponseCode.SUCCESS
            || taskResult.result().isEmpty()) {
          LOG.atTrace()
              .setMessage(
                  "Failed to retrieve transactions by hash from peer={}, requested hashes={}")
              .addArgument(peer)
              .addArgument(txHashesToRequest)
              .log();
        } else {
          final var retrievedTransactions = taskResult.result().get();
          final var retrievedHashes = toHashList(retrievedTransactions);
          transactionTracker.markTransactionsAsSeen(peer, retrievedHashes);

          final var missedHashes =
              txHashesToRequest.stream().filter(h -> !retrievedHashes.contains(h)).toList();
          transactionTracker.missedTransactionAnnouncements(peer, missedHashes);

          LOG.atTrace()
              .setMessage(
                  "Got transactions requested by hash from peer={}, "
                      + "requested hashes={}, retrieved hashes={}, missed hashes={}")
              .addArgument(peer)
              .addArgument(txHashesToRequest)
              .addArgument(retrievedHashes)
              .addArgument(missedHashes)
              .log();

          transactionPool.addRemoteTransactions(retrievedTransactions);
        }
      } catch (final Throwable t) {
        LOG.atTrace()
            .setMessage("Failed to retrieve transactions by hash from peer={}, requested hashes={}")
            .addArgument(peer)
            .addArgument(txHashesToRequest)
            .setCause(t)
            .log();
      } finally {
        transactionTracker.consumedTransactionAnnouncements(txHashesToRequest);
      }
    }
  }
}
