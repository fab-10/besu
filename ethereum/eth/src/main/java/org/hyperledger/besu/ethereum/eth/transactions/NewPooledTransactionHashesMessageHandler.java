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

import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthMessage;
import org.hyperledger.besu.ethereum.eth.manager.EthMessages;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler.ExpirableTask;
import org.hyperledger.besu.ethereum.eth.messages.NewPooledTransactionHashesMessage;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

class NewPooledTransactionHashesMessageHandler implements EthMessages.MessageCallback {
  private static final String METRIC_LABEL = "new_pooled_transaction_hashes";

  private final NewPooledTransactionHashesMessageProcessor transactionsMessageProcessor;
  private final TransactionPoolMetrics metrics;
  private final EthScheduler scheduler;
  private final Duration txMsgKeepAlive;
  private final AtomicBoolean isEnabled = new AtomicBoolean(false);

  public NewPooledTransactionHashesMessageHandler(
      final EthScheduler scheduler,
      final NewPooledTransactionHashesMessageProcessor transactionsMessageProcessor,
      final TransactionPoolMetrics metrics,
      final int txMsgKeepAliveSeconds) {
    this.scheduler = scheduler;
    this.transactionsMessageProcessor = transactionsMessageProcessor;
    this.metrics = metrics;
    this.txMsgKeepAlive = Duration.ofSeconds(txMsgKeepAliveSeconds);
    metrics.initExpiredMessagesCounter(METRIC_LABEL, true);
  }

  @Override
  public void exec(final EthMessage message) {
    if (isEnabled.get()) {
      final Capability capability = message.getPeer().getConnection().capability(EthProtocol.NAME);
      final NewPooledTransactionHashesMessage transactionsMessage =
          NewPooledTransactionHashesMessage.readFrom(message.getData(), capability);
      scheduler
          .scheduleTxWorkerExpirableTask(
              new ExpirableTask<Void>(
                  txMsgKeepAlive,
                  () -> {
                    transactionsMessageProcessor.processNewPooledTransactionHashesMessage(
                        message.getPeer(), transactionsMessage);
                    return null;
                  }) {

                @Override
                public String toLogString() {
                  return "message=transactions, peer="
                      + message.getPeer()
                      + ", hashes="
                      + transactionsMessage.pendingTransactionHashes();
                }
              })
          .whenComplete(
              (r, t) -> {
                if (t instanceof EthScheduler.ExpiredException) {
                  metrics.incrementExpiredMessages(METRIC_LABEL, true);
                }
              });
    }
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
