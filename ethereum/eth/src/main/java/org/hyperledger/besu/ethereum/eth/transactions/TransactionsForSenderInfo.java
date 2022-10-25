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

import java.util.Collection;
import org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter.TransactionInfo;
import org.hyperledger.besu.evm.account.Account;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hyperledger.besu.evm.account.AccountState;

public class TransactionsForSenderInfo {
  private final NavigableMap<Long, TransactionInfo> transactionsInfos;
  private OptionalLong firstNonceGap = OptionalLong.empty();

  private volatile long senderAccountNonce;

  private volatile long minNonceDistance = Long.MAX_VALUE;

  public TransactionsForSenderInfo(final Optional<Account> senderAccountNonce) {
    this.transactionsInfos = new TreeMap<>();
    this.senderAccountNonce = senderAccountNonce.map(AccountState::getNonce).orElse(0L);
  }

  public void addTransactionToTrack(
      final TransactionInfo transactionInfo, final Optional<Account> maybeSenderAccount) {
    final long nonce = transactionInfo.getNonce();
    synchronized (transactionsInfos) {
      if (!transactionsInfos.isEmpty()) {
        final long expectedNext = transactionsInfos.lastKey() + 1;
        if (nonce > expectedNext && firstNonceGap.isEmpty()) {
          firstNonceGap = OptionalLong.of(expectedNext);
        }
      }
      transactionsInfos.put(nonce, transactionInfo);
      if (nonce == firstNonceGap.orElse(-1)) {
        findGap();
      }
      maybeSenderAccount.ifPresent(senderAccount -> updateSenderNonce(senderAccount.getNonce()));
    }
  }

  public void removeTrackedTransactionInfo(
      final TransactionInfo txInfo, final boolean addedToBlock) {

    synchronized (transactionsInfos) {
      final boolean removed;
      if (addedToBlock) {
        removed = transactionsInfos.remove(txInfo.getNonce()) != null;
        updateSenderNonce(txInfo.getNonce());
      } else {
        // check the value when removing, because it could have been replaced
        removed = transactionsInfos.remove(txInfo.getNonce(), txInfo);
      }

      if (removed && !transactionsInfos.isEmpty()) {
        findGap();
      }
    }
  }

  private void updateSenderNonce(final long nonce) {
    if (nonce > senderAccountNonce) {
      senderAccountNonce = nonce;
      updateMinNonceDistance();
    }
  }

  private void updateMinNonceDistance() {
    var firstEntry = transactionsInfos.firstEntry();
    if (firstEntry != null) {
      minNonceDistance = firstEntry.getKey() - senderAccountNonce;
    } else {
      minNonceDistance = Long.MAX_VALUE;
    }
  }

  public long getSenderAccountNonce() {
    return senderAccountNonce;
  }

  public long getMinNonceDistance() {
    return minNonceDistance;
  }

  private void findGap() {
    // find first gap
    long expectedValue = transactionsInfos.firstKey();
    for (final Long nonce : transactionsInfos.keySet()) {
      if (expectedValue == nonce) {
        // no gap, keep moving
        expectedValue++;
      } else {
        firstNonceGap = OptionalLong.of(expectedValue);
        return;
      }
    }
    firstNonceGap = OptionalLong.empty();
  }

  public OptionalLong maybeNextNonce() {
    if (transactionsInfos.isEmpty()) {
      return OptionalLong.empty();
    } else {
      return firstNonceGap.isEmpty()
          ? OptionalLong.of(transactionsInfos.lastKey() + 1)
          : firstNonceGap;
    }
  }

  public Optional<TransactionInfo> maybeLastTx() {
    return Optional.ofNullable(transactionsInfos.lastEntry()).map(Map.Entry::getValue);
  }

  public int transactionCount() {
    return transactionsInfos.size();
  }

  public Collection<TransactionInfo> getConsecutiveTransactionInfos(final long startingNonce) {
    if (firstNonceGap.isEmpty()) {
      return transactionsInfos.tailMap(startingNonce).values();
    }
    return transactionsInfos.subMap(startingNonce, firstNonceGap.getAsLong()).values();
  }

  public Stream<TransactionInfo> streamTransactionInfos() {
    return transactionsInfos.values().stream();
  }

  public Optional<TransactionInfo> getTransactionInfoForNonce(final long nonce) {
    return Optional.ofNullable(transactionsInfos.get(nonce));
  }

  public String toTraceLog() {
    return "{"
        + "senderAccountNonce "
        + senderAccountNonce
        + "minNonceDistance "
        + minNonceDistance
        + ", transactions "
        + transactionsInfos.entrySet().stream()
            .map(e -> "(" + e.getKey() + ")" + e.getValue().toTraceLog())
            .collect(Collectors.joining("; "))
        + ", nextGap "
        + firstNonceGap
        + '}';
  }
}
