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
package org.hyperledger.besu.ethereum.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.datatypes.Wei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DefaultInclusionListSelectorTest {

  private static final Optional<Wei> BASE_FEE = Optional.of(Wei.of(1000));
  private static KeyPair senderKeys1;
  private static KeyPair senderKeys2;

  @BeforeAll
  static void setupKeys() {
    final var algo = SignatureAlgorithmFactory.getInstance();
    senderKeys1 = algo.generateKeyPair();
    senderKeys2 = algo.generateKeyPair();
  }

  private Transaction createFrontierTx(final KeyPair keys, final long nonce, final Wei gasPrice) {
    return new TransactionTestFixture()
        .type(TransactionType.FRONTIER)
        .nonce(nonce)
        .gasPrice(gasPrice)
        .createTransaction(keys);
  }

  private Transaction createBlobTx(final KeyPair keys, final long nonce) {
    return new TransactionTestFixture()
        .type(TransactionType.BLOB)
        .nonce(nonce)
        .versionedHashes(
            Optional.of(
                List.of(
                    new VersionedHash(
                        Bytes32.fromHexString(
                            "0x0100000000000000000000000000000000000000000000000000000000000000")))))
        .createTransaction(keys);
  }

  @Test
  void emptyMempoolReturnsEmptyList() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();
    assertThat(selector.selectTransactions(Hash.ZERO, Collections.emptyList(), 8192, BASE_FEE))
        .isEmpty();
  }

  @Test
  void nullMempoolReturnsEmptyList() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();
    assertThat(selector.selectTransactions(Hash.ZERO, null, 8192, BASE_FEE)).isEmpty();
  }

  @Test
  void selectsTransactionsByGasPriceHighestFirst() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    final Transaction lowPrice = createFrontierTx(senderKeys1, 0, Wei.of(2000));
    final Transaction highPrice = createFrontierTx(senderKeys2, 0, Wei.of(5000));

    final List<Bytes> result =
        selector.selectTransactions(Hash.ZERO, List.of(lowPrice, highPrice), 8192, BASE_FEE);

    assertThat(result).hasSize(2);
    // High price should be first
    assertThat(result.get(0)).isEqualTo(highPrice.encoded());
    assertThat(result.get(1)).isEqualTo(lowPrice.encoded());
  }

  @Test
  void excludesBlobTransactions() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    final Transaction normalTx = createFrontierTx(senderKeys1, 0, Wei.of(2000));
    final Transaction blobTx = createBlobTx(senderKeys2, 0);

    final List<Bytes> result =
        selector.selectTransactions(Hash.ZERO, List.of(normalTx, blobTx), 8192, BASE_FEE);

    // Blob transactions are excluded from inclusion lists per EIP-7805
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).isEqualTo(normalTx.encoded());
  }

  @Test
  void respectsByteLimit() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    final Transaction tx1 = createFrontierTx(senderKeys1, 0, Wei.of(3000));
    final int tx1Size = tx1.encoded().size();

    // Use a maxBytes that can fit only one transaction
    final List<Bytes> result =
        selector.selectTransactions(
            Hash.ZERO,
            List.of(tx1, createFrontierTx(senderKeys2, 0, Wei.of(2000))),
            tx1Size,
            BASE_FEE);

    assertThat(result).hasSize(1);
  }

  @Test
  void enforcesSequentialNoncesPerSender() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    // nonce 0 and nonce 2 (gap at nonce 1) from same sender
    final Transaction tx0 = createFrontierTx(senderKeys1, 0, Wei.of(5000));
    final Transaction tx2 = createFrontierTx(senderKeys1, 2, Wei.of(4000));

    final List<Bytes> result =
        selector.selectTransactions(Hash.ZERO, List.of(tx0, tx2), 8192, BASE_FEE);

    // Only nonce 0 should be selected; nonce 2 is skipped (gap)
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).isEqualTo(tx0.encoded());
  }

  @Test
  void selectsSequentialNoncesFromSameSender() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    // Both nonce 0 and 1 from same sender
    final Transaction tx0 = createFrontierTx(senderKeys1, 0, Wei.of(5000));
    final Transaction tx1 = createFrontierTx(senderKeys1, 1, Wei.of(4000));

    final List<Bytes> result =
        selector.selectTransactions(Hash.ZERO, List.of(tx0, tx1), 8192, BASE_FEE);

    assertThat(result).hasSize(2);
  }

  @Test
  void filtersTransactionsBelowBaseFee() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    // Gas price below base fee
    final Transaction lowTx = createFrontierTx(senderKeys1, 0, Wei.of(2000));
    // Gas price above base fee
    final Transaction highTx = createFrontierTx(senderKeys2, 0, Wei.of(5000));

    final List<Bytes> result =
        selector.selectTransactions(
            Hash.ZERO, List.of(lowTx, highTx), 8192, Optional.of(Wei.of(3000)));

    assertThat(result).hasSize(1);
    assertThat(result.get(0)).isEqualTo(highTx.encoded());
  }

  @Test
  void worksWithNoBaseFee() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    final Transaction tx = createFrontierTx(senderKeys1, 0, Wei.of(2000));

    final List<Bytes> result =
        selector.selectTransactions(Hash.ZERO, List.of(tx), 8192, Optional.empty());

    assertThat(result).hasSize(1);
  }

  @Test
  void stopsWhenByteLimitReached() {
    final DefaultInclusionListSelector selector = new DefaultInclusionListSelector();

    // Create many transactions to exceed the byte limit
    final List<Transaction> txs = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      txs.add(createFrontierTx(senderKeys1, i, Wei.of(2000)));
    }

    // Use a small byte limit
    final int smallLimit = 300;
    final List<Bytes> result = selector.selectTransactions(Hash.ZERO, txs, smallLimit, BASE_FEE);

    int totalBytes = result.stream().mapToInt(Bytes::size).sum();
    assertThat(totalBytes).isLessThanOrEqualTo(smallLimit);
    assertThat(result).isNotEmpty();
  }
}
