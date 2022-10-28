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

package org.hyperledger.besu.ethereum.eth.transactions.sorter;

import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PendingTransactionsForSender {
  private final NavigableMap<Long, PendingTransaction> pendingTransactions;

  public PendingTransactionsForSender() {
    this.pendingTransactions = new TreeMap<>();
  }

  public void addTransactionToTrack(final PendingTransaction pendingTransaction) {
    pendingTransactions.put(pendingTransaction.getNonce(), pendingTransaction);
  }

  public void removeTrackedPendingTransaction(final PendingTransaction txInfo) {
    pendingTransactions.remove(txInfo.getNonce());
  }

  public void replaceTrackedTransactionInfo(final PendingTransaction txInfo) {
    pendingTransactions.put(txInfo.getNonce(), txInfo);
  }

  public OptionalLong maybeNextNonce() {
    if (pendingTransactions.isEmpty()) {
      return OptionalLong.empty();
    } else {
      return OptionalLong.of(pendingTransactions.lastKey() + 1);
    }
  }

  public Optional<PendingTransaction> maybeLastTx() {
    return Optional.ofNullable(pendingTransactions.lastEntry()).map(Map.Entry::getValue);
  }

  public int transactionCount() {
    return pendingTransactions.size();
  }

  public List<PendingTransaction> getPendingTransactions(final long startingNonce) {
    return List.copyOf(pendingTransactions.tailMap(startingNonce).values());
  }

  public Stream<PendingTransaction> streamPendingTransactions() {
    return pendingTransactions.values().stream();
  }

  public Optional<PendingTransaction> getPendingTransactionForNonce(final long nonce) {
    return Optional.ofNullable(pendingTransactions.get(nonce));
  }

  public boolean shouldPostpone(final PendingTransaction pendingTransaction) {
    return !pendingTransactions.isEmpty()
        && pendingTransaction.getNonce() > pendingTransactions.lastKey() + 1;
  }

  public String toTraceLog() {
    return "transactions "
        + pendingTransactions.entrySet().stream()
            .map(e -> "(" + e.getKey() + ")" + e.getValue().toTraceLog())
            .collect(Collectors.joining("; "))
        + '}';
  }
}
