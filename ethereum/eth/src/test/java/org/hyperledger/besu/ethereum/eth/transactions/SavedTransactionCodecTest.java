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
package org.hyperledger.besu.ethereum.eth.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELLS_PER_EXT_BLOB;
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELL_PROOFS_PER_BLOB;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.BlobType;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlobTestFixture;
import org.hyperledger.besu.ethereum.core.CellsOnlyBlobTransactionFixture;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.encoding.TransactionEncoder;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.Cell;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.core.kzg.KZGCommitment;
import org.hyperledger.besu.ethereum.core.kzg.KZGProof;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.util.TrustedSetupClassLoaderExtension;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes48;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The save file payload of one transaction, in both its forms: the pooled encoding on its own, and
 * the elided encoding followed by the cells of a sparsely sampled blob transaction.
 */
class SavedTransactionCodecTest extends TrustedSetupClassLoaderExtension {

  private static final KeyPair KEYS = SignatureAlgorithmFactory.getInstance().generateKeyPair();
  private final CellsOnlyBlobTransactionFixture cellsOnlyFixture =
      new CellsOnlyBlobTransactionFixture();

  @Test
  void aTransactionWithoutBlobsIsSavedAsOneField() {
    final Transaction transaction = new TransactionTestFixture().nonce(1).createTransaction(KEYS);

    final String payload = SavedTransactionCodec.encode(transaction);

    assertThat(payload).doesNotContain(".");
    assertThat(SavedTransactionCodec.decode(payload)).isEqualTo(transaction);
  }

  @ParameterizedTest
  @EnumSource(BlobType.class)
  void aTransactionHoldingItsBlobsIsSavedWithoutItsCells(final BlobType blobType) {
    final Transaction transaction = blobTransaction(sidecarWithBlobs(blobType));

    final String payload = SavedTransactionCodec.encode(transaction);

    // The cells of a held blob are computed from it when it is read back, so saving them would
    // double the file to save nothing.
    assertThat(payload).doesNotContain(".");

    final Transaction decoded = SavedTransactionCodec.decode(payload);
    assertThat(decoded).isEqualTo(transaction);
    assertThat(decoded.getBlobsWithCommitments()).isEqualTo(transaction.getBlobsWithCommitments());
    assertThat(decoded.getBlobsWithCommitments().orElseThrow().hasBlobData()).isTrue();
  }

  @Test
  void aPayloadWrittenBeforeCellsWereSavedStillDecodes() {
    // Every line of a file written by an earlier version is one field, so the separator being
    // absent is what tells the two apart.
    final Transaction transaction = blobTransaction(sidecarWithBlobs(BlobType.KZG_PROOF));
    final BytesValueRLPOutput rlp = new BytesValueRLPOutput();
    TransactionEncoder.encodeRLP(transaction, rlp, EncodingContext.POOLED_TRANSACTION);

    final Transaction decoded = SavedTransactionCodec.decode(rlp.encoded().toBase64String());

    assertThat(decoded).isEqualTo(transaction);
    assertThat(decoded.getBlobsWithCommitments()).isEqualTo(transaction.getBlobsWithCommitments());
  }

  @Test
  void aSampledTransactionKeepsTheCellsItHolds() {
    final CellMask mask = maskOf(0, 7, 70, CELLS_PER_EXT_BLOB - 1);
    final Transaction transaction = cellsOnlyTransaction(3, mask);

    final String payload = SavedTransactionCodec.encode(transaction);
    assertThat(payload).contains(".");

    final Transaction decoded = SavedTransactionCodec.decode(payload);

    assertThat(decoded).isEqualTo(transaction);
    // Each blob of the fixture holds cells of its own, so this also fails if the flat cell list is
    // read back cell major, pairing the cells of one blob with another.
    assertThat(decoded.getBlobsWithCommitments()).isEqualTo(transaction.getBlobsWithCommitments());

    final BlobsWithCommitments decodedSidecar = decoded.getBlobsWithCommitments().orElseThrow();
    assertThat(decodedSidecar.hasBlobData()).isFalse();
    assertThat(decodedSidecar.getBlobType()).isEqualTo(BlobType.KZG_CELL_PROOFS);
    assertThat(decodedSidecar.getCellMask()).isEqualTo(mask);
    // What it is announced as, and so what a peer expects GetPooledTransactions to return, has to
    // survive a restart as well: the elided encoding is the same on both sides of it.
    assertThat(decoded.getSizeForEth72Announcement())
        .isEqualTo(transaction.getSizeForEth72Announcement());
  }

  @Test
  void aSampledTransactionHoldingOneCellKeepsIt() {
    final Transaction transaction = cellsOnlyTransaction(1, maskOf(13));

    final Transaction decoded =
        SavedTransactionCodec.decode(SavedTransactionCodec.encode(transaction));

    final CellsWithMask cells =
        decoded.getBlobsWithCommitments().orElseThrow().getBlobProofBundles().stream()
            .map(bundle -> bundle.getCellsWithMask().orElseThrow())
            .findFirst()
            .orElseThrow();
    assertThat(cells.getCellMask()).isEqualTo(maskOf(13));
    assertThat(cells.getCell(13)).isEqualTo(cellOf(0, 13));
    assertThat(cells.hasCell(12)).isFalse();
  }

  @Test
  void aTransactionHoldingEveryCellOfEveryBlobRoundTrips() {
    // What a transaction looks like once sampling has fetched the lot, which is still cells only
    // until its blobs are recovered.
    final Transaction transaction = cellsOnlyTransaction(2, CellMask.FULL);

    final Transaction decoded =
        SavedTransactionCodec.decode(SavedTransactionCodec.encode(transaction));

    assertThat(decoded.getBlobsWithCommitments()).isEqualTo(transaction.getBlobsWithCommitments());
    assertThat(decoded.getBlobsWithCommitments().orElseThrow().allCellsPresent()).isTrue();
  }

  @Test
  void refusesCellsThatDoNotCoverEveryBlob() {
    final CellMask mask = maskOf(2, 9);
    final String payload = SavedTransactionCodec.encode(cellsOnlyTransaction(2, mask));
    final String transactionField = payload.substring(0, payload.indexOf('.'));

    // the cells of one blob where two are expected, as a file truncated mid-write would hold
    final BytesValueRLPOutput cellsOut = new BytesValueRLPOutput();
    cellsOut.startList();
    cellsOut.writeBytes(mask.toBytes());
    cellsOut.writeList(List.of(cellOf(0, 2), cellOf(0, 9)), Cell::writeTo);
    cellsOut.endList();
    final String truncated = transactionField + "." + cellsOut.encoded().toBase64String();

    assertThatThrownBy(() -> SavedTransactionCodec.decode(truncated))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Saved 2 cells for 2 blobs holding 2 cells each");
  }

  private BlobsWithCommitments sidecarWithBlobs(final BlobType blobType) {
    final BlobTestFixture fixture = new BlobTestFixture();
    return switch (blobType) {
      case KZG_PROOF -> fixture.createBlobsWithCommitments(2);
      // One blob: a cell proof sidecar costs a KZG extension and 128 proofs per blob to build.
      case KZG_CELL_PROOFS ->
          BlobsWithCommitments.createFromBundles(
              List.of(fixture.createBlobProofBundle(BlobType.KZG_CELL_PROOFS)));
    };
  }

  private Transaction blobTransaction(final BlobsWithCommitments sidecar) {
    return new TransactionTestFixture()
        .nonce(2)
        .type(TransactionType.BLOB)
        .maxFeePerGas(Optional.of(Wei.of(5000)))
        .maxPriorityFeePerGas(Optional.of(Wei.of(1000)))
        .maxFeePerBlobGas(Optional.of(Wei.of(5000)))
        .blobsWithCommitments(Optional.of(sidecar))
        .createTransaction(KEYS);
  }

  /**
   * A transaction holding only the cells of {@code mask}, each cell distinguishable by the blob it
   * belongs to as well as its index, which {@link CellsOnlyBlobTransactionFixture} does not do.
   */
  private Transaction cellsOnlyTransaction(final int blobCount, final CellMask mask) {
    final List<KZGCommitment> commitments = new ArrayList<>(blobCount);
    final List<CellsWithMask> cellsPerBlob = new ArrayList<>(blobCount);
    final List<KZGProof> proofs = new ArrayList<>(blobCount * CELL_PROOFS_PER_BLOB);
    final List<VersionedHash> versionedHashes = new ArrayList<>(blobCount);

    for (int blob = 0; blob < blobCount; blob++) {
      final int blobIndex = blob;
      cellsPerBlob.add(
          new CellsWithMask(
              mask.streamIndexes().mapToObj(index -> cellOf(blobIndex, index)).toList(), mask));
      final KZGCommitment commitment =
          new KZGCommitment(
              Bytes48.wrap(Bytes.concatenate(Bytes.of((byte) blob), Bytes.repeat((byte) 7, 47))));
      commitments.add(commitment);
      // never elided on the wire, so a blob carries its whole set however few cells are held
      proofs.addAll(
          Collections.nCopies(
              CELL_PROOFS_PER_BLOB, new KZGProof(Bytes48.wrap(Bytes.repeat((byte) 2, 48)))));
      versionedHashes.add(
          new VersionedHash(
              VersionedHash.SHA256_VERSION_ID,
              Hash.wrap(org.hyperledger.besu.crypto.Hash.sha256(commitment.getData()))));
    }

    return cellsOnlyFixture.create(
        BlobsWithCommitments.createFromBlobCells(
            commitments, cellsPerBlob, proofs, versionedHashes));
  }

  private static Cell cellOf(final int blobIndex, final int cellIndex) {
    return new Cell(
        Bytes.concatenate(
            Bytes.of((byte) blobIndex, (byte) cellIndex),
            Bytes.repeat((byte) 0xEE, Cell.SIZE - 2)));
  }

  private static CellMask maskOf(final int... indexes) {
    final BitSet bits = new BitSet(CELLS_PER_EXT_BLOB);
    for (final int index : indexes) {
      bits.set(index);
    }
    final byte[] bytes = new byte[CellMask.BYTE_LENGTH];
    final byte[] set = bits.toByteArray();
    System.arraycopy(set, 0, bytes, 0, set.length);
    return CellMask.fromBytes(Bytes.wrap(bytes));
  }
}
