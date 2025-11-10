/*
 * Copyright contributors to Besu.
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

import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.encoding.TransactionEncoder;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler.ExpirableTask;
import org.hyperledger.besu.ethereum.eth.messages.GetPooledTransactionsMessage;
import org.hyperledger.besu.ethereum.eth.messages.PooledTransactionsMessage;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetPooledTransactionsMessageHandler {
  private static final Logger LOG =
      LoggerFactory.getLogger(GetPooledTransactionsMessageHandler.class);
  private static final String METRIC_LABEL = "get_pooled_transactions";
  private static final PooledTransactionsMessage EMPTY_RESPONSE =
      PooledTransactionsMessage.create(List.of());

  private final TransactionPoolMetrics metrics;
  private final TransactionPool transactionPool;
  private final EthScheduler scheduler;
  private final Duration txMsgKeepAlive;
  private final int p2pMsgMaxSize;
  private final int maxReturnedTxs;
  private final AtomicBoolean isEnabled = new AtomicBoolean(false);

  public GetPooledTransactionsMessageHandler(
      final EthScheduler scheduler,
      final TransactionPool transactionPool,
      final TransactionPoolMetrics metrics,
      final int txMsgKeepAliveSeconds,
      final int p2pMsgMaxSize,
      final int maxReturnedTxs) {
    this.scheduler = scheduler;
    this.transactionPool = transactionPool;
    this.metrics = metrics;
    this.txMsgKeepAlive = Duration.ofSeconds(txMsgKeepAliveSeconds);
    this.p2pMsgMaxSize = p2pMsgMaxSize;
    this.maxReturnedTxs = maxReturnedTxs;
    metrics.initExpiredMessagesCounter(METRIC_LABEL, true);
  }

  MessageData processGetPooledTransactionsMessage(final MessageData messageData) {
    if (isEnabled.get()) {
      final GetPooledTransactionsMessage getPooledTransactionsMessage =
          GetPooledTransactionsMessage.readFrom(messageData);
      final var result =
          scheduler.scheduleTxWorkerExpirableTask(
              new ExpirableTask<>(
                  txMsgKeepAlive, () -> getPooledTransactions(getPooledTransactionsMessage)) {

                @Override
                public String toLogString() {
                  return "message=get_pooled_transactions, peer="
                      //                      + message.getPeer()
                      + ", hashes="
                      + getPooledTransactionsMessage.pooledTransactions();
                }
              });

      try {
        return result.get(txMsgKeepAlive.toSeconds(), TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        LOG.debug("Interrupted while processing get pooled transactions", e);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof EthScheduler.ExpiredException) {
          metrics.incrementExpiredMessages(METRIC_LABEL, true);
        }
      } catch (TimeoutException e) {
        metrics.incrementExpiredMessages(METRIC_LABEL, true);
      }
    }
    return EMPTY_RESPONSE;
  }

  MessageData getPooledTransactions(
      final GetPooledTransactionsMessage getPooledTransactionsMessage) {
    final var requestedHashes = getPooledTransactionsMessage.pooledTransactions();

    if (requestedHashes.size() > maxReturnedTxs) {
      LOG.atTrace()
          .setMessage(
              "Requested {} pooled transactions are more that the max allowed of {}, trimming requested list, peer {}")
          .addArgument(requestedHashes::size)
          .addArgument(maxReturnedTxs)
          //          .addArgument(peer)
          .log();
      requestedHashes.subList(maxReturnedTxs, requestedHashes.size()).clear();
    }

    int responseSizeEstimate = RLP.MAX_PREFIX_SIZE;
    final BytesValueRLPOutput rlp = new BytesValueRLPOutput();
    rlp.startList();
    int count = 0;
    for (final var hash : requestedHashes) {

      final Optional<Transaction> maybeTx = transactionPool.getTransactionByHash(hash);
      if (maybeTx.isPresent()) {

        final BytesValueRLPOutput txRlp = new BytesValueRLPOutput();
        TransactionEncoder.encodeRLP(maybeTx.get(), txRlp, EncodingContext.POOLED_TRANSACTION);
        final int encodedSize = txRlp.encodedSize();
        if (responseSizeEstimate + encodedSize > p2pMsgMaxSize) {
          break;
        }

        responseSizeEstimate += encodedSize;
        rlp.writeRaw(txRlp.encoded());
        count++;
      }
    }
    rlp.endList();

    final var finalCount = count;
    LOG.atTrace()
        .setMessage("Sending pooled transactions  to peer {}, transaction hashes count {}, list {}")
        //        .addArgument(peer)
        .addArgument(finalCount)
        .addArgument(() -> requestedHashes.subList(0, finalCount))
        .log();

    return PooledTransactionsMessage.createUnsafe(rlp.encoded());
  }

  public void setEnabled() {
    isEnabled.set(true);
  }

  public void setDisabled() {
    isEnabled.set(false);
  }

  public boolean isEnabled() {
    return isEnabled.get();
  }
}
