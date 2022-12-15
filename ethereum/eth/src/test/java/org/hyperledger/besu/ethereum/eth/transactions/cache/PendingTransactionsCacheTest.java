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
package org.hyperledger.besu.ethereum.eth.transactions.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.POSTPONED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.REJECTED_UNDERPRICED_REPLACEMENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.eth.transactions.ImmutableTransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolReplacementHandler;
import org.hyperledger.besu.plugin.data.TransactionType;

import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiFunction;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PendingTransactionsCacheTest {
  protected static final int MAX_TRANSACTIONS = 5;
  protected static final Supplier<SignatureAlgorithm> SIGNATURE_ALGORITHM =
      Suppliers.memoize(SignatureAlgorithmFactory::getInstance);
  protected static final KeyPair KEYS1 = SIGNATURE_ALGORITHM.get().generateKeyPair();

  private static final Random randomizeTxType = new Random();

  private final TransactionPoolConfiguration poolConf =
      ImmutableTransactionPoolConfiguration.builder()
          .txPoolMaxSize(MAX_TRANSACTIONS)
          .txPoolLimitByAccountPercentage(1.0f)
          .build();

  private PendingTransactionsCache pendingTransactionsCache;

  private static BlockHeader mockBlockHeader() {
    final BlockHeader blockHeader = mock(BlockHeader.class);
    when(blockHeader.getBaseFee()).thenReturn(Optional.of(Wei.of(100)));
    return blockHeader;
  }

  @BeforeEach
  public void setup() {
    final BiFunction<PendingTransaction, PendingTransaction, Boolean> transactionReplacementTester =
        (t1, t2) ->
            new TransactionPoolReplacementHandler(poolConf.getPriceBump())
                .shouldReplace(t1, t2, mockBlockHeader());
    pendingTransactionsCache =
        new PendingTransactionsCache(
            poolConf, new DummyPostponedTransactionsCache(), transactionReplacementTester);
  }

  @Test
  public void shouldAddFirstTransaction() {
    final var firstTransaction = createTransaction(0);
    assertThat(pendingTransactionsCache.add(createPendingTransaction(firstTransaction), 0))
        .isEqualTo(ADDED);
    assertTransactionPresent(firstTransaction);
  }

  @Test
  public void shouldPostponeTransactionWithNonceGap() {
    final var firstTransaction = createTransaction(1);
    assertThat(pendingTransactionsCache.add(createPendingTransaction(firstTransaction), 0))
        .isEqualTo(POSTPONED);
    assertTransactionNotPresent(firstTransaction);
  }

  @Test
  public void shouldReplaceTransaction() {
    final var lowValueTransaction = createTransaction(0, KEYS1);
    final var highValueTransaction = createTransactionReplacement(lowValueTransaction, KEYS1);
    assertThat(pendingTransactionsCache.add(createPendingTransaction(lowValueTransaction), 0))
        .isEqualTo(ADDED);
    assertTransactionPresent(lowValueTransaction);
    final var txAddResult =
        pendingTransactionsCache.add(createPendingTransaction(highValueTransaction), 0);
    assertThat(txAddResult.isReplacement()).isTrue();
    assertThat(txAddResult.maybeReplacedTransaction())
        .isPresent()
        .map(PendingTransaction::getHash)
        .hasValue(lowValueTransaction.getHash());
    assertTransactionPresent(highValueTransaction);
    assertTransactionNotPresent(lowValueTransaction);
  }

  @Test
  public void shouldNotReplaceTransaction() {
    final var highValueTransaction = createTransaction(0, Wei.of(101));
    final var lowValueTransaction = createTransaction(0, Wei.of(100));

    assertThat(pendingTransactionsCache.add(createPendingTransaction(highValueTransaction), 0))
        .isEqualTo(ADDED);
    assertTransactionPresent(highValueTransaction);
    assertThat(pendingTransactionsCache.add(createPendingTransaction(lowValueTransaction), 0))
        .isEqualTo(REJECTED_UNDERPRICED_REPLACEMENT);
    assertTransactionNotPresent(lowValueTransaction);
    assertTransactionPresent(highValueTransaction);
  }

  @Test
  public void shouldDoNothingIfTransactionAlreadyPresent() {
    final var transaction = createTransaction(0);

    assertThat(pendingTransactionsCache.add(createPendingTransaction(transaction), 0))
        .isEqualTo(ADDED);
    assertTransactionPresent(transaction);
    assertThat(pendingTransactionsCache.add(createPendingTransaction(transaction), 0))
        .isEqualTo(ALREADY_KNOWN);
    assertTransactionPresent(transaction);
  }

  @Test
  void get() {}

  @Test
  void remove() {}

  @Test
  void getNextReadyNonce() {}

  @Test
  void getPromotableTransactions() {}

  @Test
  void streamReadyTransactions() {}

  @Test
  void testStreamReadyTransactions() {}

  private PendingTransaction createPendingTransaction(final Transaction transaction) {
    return new PendingTransaction(transaction, false, Instant.now());
  }

  private Transaction createTransaction(final long transactionNumber) {
    return createTransaction(transactionNumber, Wei.of(5000L), KEYS1);
  }

  private Transaction createTransaction(final long transactionNumber, final KeyPair keys) {
    return createTransaction(transactionNumber, Wei.of(5000L), keys);
  }

  private Transaction createTransaction(final long transactionNumber, final Wei maxGasPrice) {
    return createTransaction(transactionNumber, maxGasPrice, KEYS1);
  }

  private Transaction createTransaction(
      final long nonce, final Wei maxGasPrice, final KeyPair keys) {

    return createTransaction(
        randomizeTxType.nextBoolean() ? TransactionType.EIP1559 : TransactionType.FRONTIER,
        nonce,
        maxGasPrice,
        keys);
  }

  private Transaction createTransaction(
      final TransactionType type, final long nonce, final Wei maxGasPrice, final KeyPair keys) {

    var tx = new TransactionTestFixture().value(Wei.of(nonce)).nonce(nonce).type(type);
    if (type.supports1559FeeMarket()) {
      tx.maxFeePerGas(Optional.of(maxGasPrice))
          .maxPriorityFeePerGas(Optional.of(maxGasPrice.divide(10)));
    } else {
      tx.gasPrice(maxGasPrice);
    }
    return tx.createTransaction(keys);
  }

  private Transaction createTransactionReplacement(
      final Transaction originalTransaction, final KeyPair keys) {
    return createTransaction(
        originalTransaction.getType(),
        originalTransaction.getNonce(),
        originalTransaction.getMaxGasFee().multiply(2),
        keys);
  }

  private void assertTransactionPresent(final Transaction transaction) {
    assertThat(pendingTransactionsCache.get(transaction.getSender(), transaction.getNonce()))
        .isPresent()
        .map(PendingTransaction::getHash)
        .hasValue(transaction.getHash());
  }

  private void assertTransactionNotPresent(final Transaction transaction) {
    final var maybeTransaction =
        pendingTransactionsCache.get(transaction.getSender(), transaction.getNonce());
    if (!maybeTransaction.isEmpty()) {
      assertThat(maybeTransaction)
          .isPresent()
          .map(PendingTransaction::getHash)
          .isNotEqualTo(transaction.getHash());
    }
  }
}
