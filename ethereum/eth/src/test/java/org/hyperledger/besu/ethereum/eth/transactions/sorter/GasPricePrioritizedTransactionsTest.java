/*
 * Copyright Besu contributors.
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.cache.ReadyTransactionsCache;
import org.hyperledger.besu.testutil.TestClock;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Test;

public class GasPricePrioritizedTransactionsTest extends AbstractPrioritizedTransactionsTestBase {

  @Override
  AbstractPrioritizedTransactions getSorter(
      final TransactionPoolConfiguration poolConfig,
      final Optional<Clock> clock,
      final BiFunction<PendingTransaction, PendingTransaction, Boolean>
          transactionReplacementTester) {

    this.readyTransactionsCache =
        spy(new ReadyTransactionsCache(poolConfig, transactionReplacementTester));

    return new GasPricePrioritizedTransactions(
        poolConfig,
        clock.orElse(TestClock.system(ZoneId.systemDefault())),
        metricsSystem,
        transactionReplacementTester,
        readyTransactionsCache);
  }

  @Override
  protected BlockHeader mockBlockHeader() {
    final BlockHeader blockHeader = mock(BlockHeader.class);
    when(blockHeader.getBaseFee()).thenReturn(Optional.empty());
    return blockHeader;
  }

  @Override
  protected Transaction createTransaction(
      final long transactionNumber, final Wei maxGasPrice, final KeyPair keys) {
    return new TransactionTestFixture()
        .value(Wei.of(transactionNumber))
        .nonce(transactionNumber)
        .gasPrice(maxGasPrice)
        .createTransaction(keys);
  }

  @Override
  protected Transaction createTransactionReplacement(
      final Transaction originalTransaction, final KeyPair keys) {
    return createTransaction(
        originalTransaction.getNonce(), originalTransaction.getMaxGasFee().multiply(2), keys);
  }

  @Test
  public void shouldPrioritizeGasPriceThenTimeAddedToPool() {
    final Transaction highGasPriceTransaction = createTransaction(0, Wei.of(100), KEYS1);

    final var lowValueTxs =
        IntStream.range(0, MAX_TRANSACTIONS)
            .mapToObj(
                i -> createTransaction(0, Wei.of(10), SIGNATURE_ALGORITHM.get().generateKeyPair()))
            .collect(Collectors.toUnmodifiableList());

    shouldPrioritizeValueThenTimeAddedToPool(
        lowValueTxs.iterator(), highGasPriceTransaction, lowValueTxs.get(0));
  }
}
