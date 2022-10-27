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

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TransactionsForSenderInfo {
  private final NavigableMap<Long, TransactionInfo> transactionsInfos;

  public TransactionsForSenderInfo() {
    this.transactionsInfos = new TreeMap<>();
  }

  public void addTransactionToTrack(final TransactionInfo transactionInfo) {
    transactionsInfos.put(transactionInfo.getNonce(), transactionInfo);
  }

  public void removeTrackedTransactionInfo(
      final TransactionInfo txInfo, final boolean addedToBlock) {
    if (addedToBlock) {
      transactionsInfos.remove(txInfo.getNonce());
    } else {
      // check the value when removing, because it could have been replaced
      transactionsInfos.remove(txInfo.getNonce(), txInfo);
    }
  }

  public void replaceTrackedTransactionInfo(final TransactionInfo txInfo) {
    transactionsInfos.put(txInfo.getNonce(), txInfo);
  }

  public OptionalLong maybeNextNonce() {
    if (transactionsInfos.isEmpty()) {
      return OptionalLong.empty();
    } else {
      return OptionalLong.of(transactionsInfos.lastKey() + 1);
    }
  }

  public Optional<TransactionInfo> maybeLastTx() {
    return Optional.ofNullable(transactionsInfos.lastEntry()).map(Map.Entry::getValue);
  }

  public int transactionCount() {
    return transactionsInfos.size();
  }

  public List<TransactionInfo> getTransactionInfos(final long startingNonce) {
    return List.copyOf(transactionsInfos.tailMap(startingNonce).values());
  }

  public Stream<TransactionInfo> streamTransactionInfos() {
    return transactionsInfos.values().stream();
  }

  public Optional<TransactionInfo> getTransactionInfoForNonce(final long nonce) {
    return Optional.ofNullable(transactionsInfos.get(nonce));
  }

  public boolean shouldPostpone(final TransactionInfo transactionInfo) {
    return !transactionsInfos.isEmpty()
        && transactionInfo.getNonce() > transactionsInfos.lastKey() + 1;
  }

  public String toTraceLog() {
    return "transactions "
        + transactionsInfos.entrySet().stream()
            .map(e -> "(" + e.getKey() + ")" + e.getValue().toTraceLog())
            .collect(Collectors.joining("; "))
        + '}';
  }
}
