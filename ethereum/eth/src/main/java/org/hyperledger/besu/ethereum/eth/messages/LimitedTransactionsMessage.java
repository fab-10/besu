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
package org.hyperledger.besu.ethereum.eth.messages;

import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;

import org.apache.tuweni.bytes.Bytes;

public final class LimitedTransactionsMessage {
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

  public int getEstimatedMessageSize() {
    return estimatedMsgSize;
  }

  public TransactionsMessage getTransactionsMessage() {
    message.endList();
    return new TransactionsMessage(message.encoded());
  }
}
