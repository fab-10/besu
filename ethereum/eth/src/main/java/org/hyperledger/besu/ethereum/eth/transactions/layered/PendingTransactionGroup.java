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

import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;

import java.util.Collection;
import java.util.Iterator;
import java.util.stream.Stream;

/** A group of related pending transactions */
public class PendingTransactionGroup implements Iterable<PendingTransaction> {
  private final Collection<PendingTransaction> pendingTxs;
  private final byte score;

  public PendingTransactionGroup(final Collection<PendingTransaction> pendingTxs) {
    this.pendingTxs = pendingTxs;
    this.score = pendingTxs.stream().map(PendingTransaction::getScore).min(Byte::compareTo).get();
  }

  public byte getScore() {
    return score;
  }

  public boolean isAtomic() {
    return false;
  }

  public int size() {
    return pendingTxs.size();
  }

  public Stream<PendingTransaction> stream() {
    return pendingTxs.stream();
  }

  @Override
  public Iterator<PendingTransaction> iterator() {
    return pendingTxs.iterator();
  }
}
