package org.hyperledger.besu.ethereum.eth.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.core.Util;
import org.hyperledger.besu.ethereum.eth.transactions.cache.ReadyTransactionsCache;
import org.hyperledger.besu.ethereum.eth.transactions.sorter.PendingTransactionsSorter;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.plugin.data.TransactionType;

import java.time.Instant;
import java.util.Optional;
import java.util.Random;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.apache.tuweni.bytes.Bytes;

public class BaseTransactionPoolTest {

  protected static final Supplier<SignatureAlgorithm> SIGNATURE_ALGORITHM =
      Suppliers.memoize(SignatureAlgorithmFactory::getInstance);
  protected static final KeyPair KEYS1 = SIGNATURE_ALGORITHM.get().generateKeyPair();
  protected static final KeyPair KEYS2 = SIGNATURE_ALGORITHM.get().generateKeyPair();
  protected static final Address SENDER1 = Util.publicKeyToAddress(KEYS1.getPublicKey());
  protected static final Address SENDER2 = Util.publicKeyToAddress(KEYS2.getPublicKey());

  private static final Random randomizeTxType = new Random();

  protected final Transaction transaction0 = createTransaction(0);
  protected final Transaction transaction1 = createTransaction(1);
  protected final Transaction transaction2 = createTransaction(2);

  protected Transaction createTransaction(final long nonce) {
    return createTransaction(nonce, Wei.of(5000L), KEYS1);
  }

  protected Transaction createTransaction(final long nonce, final KeyPair keys) {
    return createTransaction(nonce, Wei.of(5000L), keys);
  }

  protected Transaction createTransaction(final long nonce, final Wei maxGasPrice) {
    return createTransaction(nonce, maxGasPrice, KEYS1);
  }

  protected Transaction createTransaction(final long nonce, final int payloadSize) {
    return createTransaction(nonce, Wei.of(5000L), payloadSize, KEYS1);
  }

  protected Transaction createTransaction(
      final long nonce, final Wei maxGasPrice, final KeyPair keys) {
    return createTransaction(nonce, maxGasPrice, 0, keys);
  }

  protected Transaction createTransaction(
      final long nonce, final Wei maxGasPrice, final int payloadSize, final KeyPair keys) {

    return createTransaction(
        randomizeTxType.nextBoolean() ? TransactionType.EIP1559 : TransactionType.FRONTIER,
        nonce,
        maxGasPrice,
        payloadSize,
        keys);
  }

  protected Transaction createTransaction(
      final TransactionType type,
      final long nonce,
      final Wei maxGasPrice,
      final int payloadSize,
      final KeyPair keys) {

    var payloadBytes = Bytes.repeat((byte) 1, payloadSize);
    var tx =
        new TransactionTestFixture()
            .to(Optional.of(Address.fromHexString("0x634316eA0EE79c701c6F67C53A4C54cBAfd2316d")))
            .value(Wei.of(nonce))
            .nonce(nonce)
            .type(type)
            .payload(payloadBytes);
    if (type.supports1559FeeMarket()) {
      tx.maxFeePerGas(Optional.of(maxGasPrice))
          .maxPriorityFeePerGas(Optional.of(maxGasPrice.divide(10)));
    } else {
      tx.gasPrice(maxGasPrice);
    }
    return tx.createTransaction(keys);
  }

  protected Transaction createTransactionReplacement(
      final Transaction originalTransaction, final KeyPair keys) {
    return createTransaction(
        originalTransaction.getType(),
        originalTransaction.getNonce(),
        originalTransaction.getMaxGasFee().multiply(2),
        0,
        keys);
  }

  protected PendingTransaction createRemotePendingTransaction(final Transaction transaction) {
    return new PendingTransaction.Remote(transaction, Instant.now());
  }

  protected PendingTransaction createLocalPendingTransaction(final Transaction transaction) {
    return new PendingTransaction.Local(transaction, Instant.now());
  }

  protected void assertTransactionPendingAndReady(
      final ReadyTransactionsCache pendingTransactions, final Transaction transaction) {
    assertTransactionPending(pendingTransactions, transaction);
    assertThat(pendingTransactions.getReady(transaction.getSender(), transaction.getNonce()))
        .isPresent()
        .map(PendingTransaction::getHash)
        .hasValue(transaction.getHash());
  }

  protected void assertTransactionPendingAndNotReady(
      final ReadyTransactionsCache pendingTransactions, final Transaction transaction) {
    assertTransactionPending(pendingTransactions, transaction);
    final var maybeTransaction =
        pendingTransactions.getReady(transaction.getSender(), transaction.getNonce());
    if (!maybeTransaction.isEmpty()) {
      assertThat(maybeTransaction)
          .isPresent()
          .map(PendingTransaction::getHash)
          .isNotEqualTo(transaction.getHash());
    }
  }

  protected void assertTransactionPending(
      final PendingTransactionsSorter transactions, final Transaction t) {
    assertThat(transactions.getTransactionByHash(t.getHash())).contains(t);
  }

  protected void assertTransactionNotPending(
      final PendingTransactionsSorter transactions, final Transaction t) {
    assertThat(transactions.getTransactionByHash(t.getHash())).isEmpty();
  }

  protected void assertNoNextNonceForSender(
      final PendingTransactionsSorter pendingTransactions, final Address sender) {
    assertThat(pendingTransactions.getNextNonceForSender(sender)).isEmpty();
  }

  protected void assertNextNonceForSender(
      final PendingTransactionsSorter pendingTransactions, final Address sender1, final int i) {
    assertThat(pendingTransactions.getNextNonceForSender(sender1)).isPresent().hasValue(i);
  }

  protected void addLocalTransactions(
      final PendingTransactionsSorter sorter, final Account sender, final long... nonces) {
    for (final long nonce : nonces) {
      sorter.addLocalTransaction(createTransaction(nonce), Optional.of(sender));
    }
  }
}
