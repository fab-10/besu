/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.eth.transactions.layered;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;

import java.util.SequencedCollection;
import java.util.stream.Stream;

/** A group of related pending transactions */
public class AbstractPendingTransactionGroup implements PendingTransactionGroup {
  private final SequencedCollection<PendingTransaction> pendingTxs;
  private final boolean hasPriority;
  private final Wei avgFee;
  private final byte score;

  public AbstractPendingTransactionGroup(final SequencedCollection<PendingTransaction> pendingTxs) {
    this(pendingTxs, Wei.ZERO);
  }

  public AbstractPendingTransactionGroup(
      final SequencedCollection<PendingTransaction> pendingTxs, final Wei avgFee) {
    this.pendingTxs = pendingTxs;
    this.avgFee = avgFee;
    this.score = pendingTxs.stream().map(PendingTransaction::getScore).min(Byte::compareTo).get();
    this.hasPriority = pendingTxs.stream().anyMatch(PendingTransaction::hasPriority);
  }

  @Override
  public boolean hasPriority() {
    return hasPriority;
  }

  @Override
  public Wei getAverageFee() {
    return avgFee;
  }

  @Override
  public byte getScore() {
    return score;
  }

  @Override
  public int size() {
    return pendingTxs.size();
  }

  @Override
  public Stream<PendingTransaction> stream() {
    return pendingTxs.stream();
  }
}
