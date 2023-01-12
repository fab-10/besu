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
package org.hyperledger.besu.ethereum.eth.transactions.sorter;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionDroppedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionListener;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.evm.account.Account;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public interface PendingTransactionsSorter {
  void evictOldTransactions();

  List<Transaction> getLocalTransactions();

  TransactionAddedResult addRemoteTransaction(
      Transaction transaction, Optional<Account> maybeSenderAccount);

  TransactionAddedResult addLocalTransaction(
      Transaction transaction, Optional<Account> maybeSenderAccount);

  boolean isLocalSender(Address sender);

  // There's a small edge case here we could encounter.
  // When we pass an upgrade block that has a new transaction type, we start allowing transactions
  // of that new type into our pool.
  // If we then reorg to a block lower than the upgrade block height _and_ we create a block, that
  // block could end up with transactions of the new type.
  // This seems like it would be very rare but worth it to document that we don't handle that case
  // right now.
  void selectTransactions(TransactionSelector selector);

  long maxSize();

  int size();

  boolean containsTransaction(Transaction transaction);

  Optional<Transaction> getTransactionByHash(Hash transactionHash);

  Set<PendingTransaction> getPrioritizedPendingTransactions();

  long subscribePendingTransactions(PendingTransactionListener listener);

  void unsubscribePendingTransactions(long id);

  long subscribeDroppedTransactions(PendingTransactionDroppedListener listener);

  void unsubscribeDroppedTransactions(long id);

  OptionalLong getNextNonceForSender(Address sender);

  void manageBlockAdded(
      BlockHeader blockHeader, List<Transaction> confirmedTransactions, FeeMarket feeMarket);

  String toTraceLog();

  String logStats();

  void reset();

  default void signalInvalidTransaction(final Transaction transaction) {
    // ToDo: remove when the legacy tx pool is removed
  }

  public enum TransactionSelectionResult {
    DELETE_TRANSACTION_AND_CONTINUE,
    CONTINUE,
    COMPLETE_OPERATION
  }

  @FunctionalInterface
  public interface TransactionSelector {
    TransactionSelectionResult evaluateTransaction(final Transaction transaction);
  }
}
