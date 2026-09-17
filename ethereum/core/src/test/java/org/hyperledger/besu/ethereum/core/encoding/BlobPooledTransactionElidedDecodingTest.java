/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.ethereum.core.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.BlobType;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlobTestFixture;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.BlobProofBundle;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.util.TrustedSetupClassLoaderExtension;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

/**
 * Covers the eth/72 wire form in which a {@code PooledTransactions} response elides blob payloads,
 * encoding the {@code blobs} field as the empty list.
 */
class BlobPooledTransactionElidedDecodingTest extends TrustedSetupClassLoaderExtension {

  private static final KeyPair KEYS = SignatureAlgorithmFactory.getInstance().generateKeyPair();

  private static final int BLOB_COUNT = 3;

  private static Transaction blobTransaction() {
    final BlobsWithCommitments withBlobs =
        CKZG4844Helper.convertToVersion1(
            new BlobTestFixture().createBlobsWithCommitments(BLOB_COUNT));
    return Transaction.builder()
        .type(TransactionType.BLOB)
        .chainId(java.math.BigInteger.ONE)
        .nonce(1)
        .maxPriorityFeePerGas(Wei.of(1))
        .maxFeePerGas(Wei.of(10))
        .maxFeePerBlobGas(Wei.of(10))
        .gasLimit(100_000)
        .to(org.hyperledger.besu.datatypes.Address.ZERO)
        .value(Wei.ZERO)
        .payload(Bytes.EMPTY)
        .versionedHashes(withBlobs.getVersionedHashes())
        .blobsWithCommitments(withBlobs)
        .signAndBuild(KEYS);
  }

  private static Transaction roundTripElided(final Transaction tx) {
    final Bytes encoded =
        TransactionEncoder.encodeOpaqueBytes(tx, EncodingContext.POOLED_TRANSACTION_ETH_72);
    return TransactionDecoder.decodeOpaqueBytes(encoded, EncodingContext.POOLED_TRANSACTION_ETH_72);
  }

  @Test
  void decodesTransactionWhoseBlobsWereElided() {
    final Transaction tx = blobTransaction();
    final Transaction decoded = roundTripElided(tx);

    // Eliding the payloads must not change the transaction's identity.
    assertThat(decoded.getHash()).isEqualTo(tx.getHash());

    final BlobsWithCommitments bwc = decoded.getBlobsWithCommitments().orElseThrow();
    assertThat(bwc.getBlobType()).isEqualTo(BlobType.KZG_CELL_PROOFS);
    // One bundle per blob, i.e. per commitment. Sizing this from the proof count would give
    // 128x too many.
    assertThat(bwc.getBlobProofBundles()).hasSize(BLOB_COUNT);
    assertThat(bwc.getKzgCommitments())
        .isEqualTo(tx.getBlobsWithCommitments().get().getKzgCommitments());
  }

  @Test
  void elidedTransactionStartsWithNoCellsAndOneCellSetPerBlob() {
    final Transaction decoded = roundTripElided(blobTransaction());

    final List<BlobProofBundle> bundles =
        decoded.getBlobsWithCommitments().orElseThrow().getBlobProofBundles();

    for (final BlobProofBundle bundle : bundles) {
      assertThat(bundle.getBlob()).isEmpty();
      final CellsWithMask cells = bundle.getCellsWithMask().orElseThrow();
      assertThat(cells.getCellMask().isEmpty()).isTrue();
      assertThat(cells.getCells()).isEmpty();
    }

    // Each blob must own its cell set. Sharing one instance would make a merge into any blob
    // visible on all of them, and across every transaction decoded this way.
    for (int i = 1; i < bundles.size(); i++) {
      assertThat(bundles.get(i).getCellsWithMask().orElseThrow())
          .isNotSameAs(bundles.getFirst().getCellsWithMask().orElseThrow());
    }
  }
}
