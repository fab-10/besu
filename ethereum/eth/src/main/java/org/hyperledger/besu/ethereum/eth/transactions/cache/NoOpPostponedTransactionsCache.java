/*
 * Copyright Hyperledger Besu Contributors.
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
package org.hyperledger.besu.ethereum.eth.transactions.cache;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NoOpPostponedTransactionsCache implements PostponedTransactionsCache {

  @Override
  public CompletableFuture<List<PendingTransaction>> promoteForSender(
      final Address sender, final long lastReadyNonce, final long maxSize) {
    return CompletableFuture.completedFuture(List.of());
  }

  @Override
  public CompletableFuture<List<PendingTransaction>> promote(
      final int maxPromotable, final long maxSize) {
    return CompletableFuture.completedFuture(List.of());
  }

  @Override
  public void add(final PendingTransaction pendingTransaction) {}

  @Override
  public void addAll(final List<PendingTransaction> evictedTransactions) {}

  @Override
  public void remove(final Transaction transaction) {}

  @Override
  public void removeForSenderBelowNonce(final Address sender, final long maxConfirmedNonce) {}
}
