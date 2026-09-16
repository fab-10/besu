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
package org.hyperledger.besu.ethereum.core.kzg;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.hyperledger.besu.datatypes.BlobType.KZG_PROOF;
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELL_PROOFS_PER_BLOB;

import org.hyperledger.besu.datatypes.BlobType;
import org.hyperledger.besu.datatypes.VersionedHash;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes;

/** A class to hold the blobs, commitments, proofs, and versioned hashes for a set of blobs. */
public class BlobsWithCommitments implements org.hyperledger.besu.datatypes.BlobsWithCommitments {
  private final BlobType blobType;
  private final List<BlobProofBundle> blobProofBundles;

  public BlobsWithCommitments(
      final BlobType blobType, final List<BlobProofBundle> blobProofBundles) {
    this.blobType = blobType;
    this.blobProofBundles = blobProofBundles;
  }

  /**
   * Constructs an instance from a list of {@link BlobProofBundle}.
   *
   * @param blobProofBundles the list of blob proof bundles to be attached to the transaction.
   */
  public BlobsWithCommitments(final List<BlobProofBundle> blobProofBundles) {
    checkArgument(!blobProofBundles.isEmpty(), "BlobProofBundles list cannot be empty");

    BlobType blobType = blobProofBundles.getFirst().getBlobType();
    checkArgument(
        blobProofBundles.stream()
            .skip(1)
            .map(BlobProofBundle::getBlobType)
            .allMatch(blobType::equals),
        "BlobProofBundles must have the same BlobType");

    final Optional<CellsWithMask> firstCellsWithMask =
        blobProofBundles.getFirst().getCellsWithMask();
    firstCellsWithMask.ifPresent(
        cellsWithMask -> {
          final CellMask firstCellMask = cellsWithMask.getCellMask();
          checkArgument(
              blobProofBundles.stream()
                  .skip(1)
                  .allMatch(
                      bundle ->
                          bundle
                              .getCellsWithMask()
                              .map(cwm -> cwm.getCellMask().equals(firstCellMask))
                              .orElse(Boolean.FALSE)),
              "BlobProofBundles must have the same cell mask");
        });

    this.blobProofBundles = blobProofBundles;
    this.blobType = blobType;
  }

  /**
   * Constructs an instance.
   *
   * @param blobType blobType for the sidecar.
   * @param kzgCommitments commitments for the blobs.
   * @param blobs list of blobs to be committed to.
   * @param kzgProofs proofs for the commitments.
   * @param versionedHashes hashes of the commitments.
   * @throws InvalidParameterException if the input parameters are invalid.
   */
  public static BlobsWithCommitments createFromBlobs(
      final BlobType blobType,
      final List<KZGCommitment> kzgCommitments,
      final List<Blob> blobs,
      final List<KZGProof> kzgProofs,
      final List<VersionedHash> versionedHashes) {
    checkArgument(blobs.stream().anyMatch(Objects::isNull), "all blobs must be non null");
    commonValidateInputParameters(
        blobType, kzgCommitments, kzgProofs, versionedHashes, blobs.size());

    return new BlobsWithCommitments(
        blobType,
        IntStream.range(0, blobs.size())
            .mapToObj(
                index -> {
                  List<KZGProof> kzgProofsForBlob =
                      extractProofsForBlob(blobType, kzgProofs, index);
                  return new BlobProofBundle(
                      blobType,
                      blobs.get(index),
                      kzgCommitments.get(index),
                      kzgProofsForBlob,
                      versionedHashes.get(index));
                })
            .toList());
  }

  public static BlobsWithCommitments createFromBlobCells(
      final BlobType blobType,
      final List<KZGCommitment> kzgCommitments,
      final List<CellsWithMask> cellsWithMaskList,
      final List<KZGProof> kzgProofs,
      final List<VersionedHash> versionedHashes) {
    commonValidateInputParameters(
        blobType, kzgCommitments, kzgProofs, versionedHashes, cellsWithMaskList.size());
    return new BlobsWithCommitments(
        blobType,
        IntStream.range(0, cellsWithMaskList.size())
            .mapToObj(
                index -> {
                  List<KZGProof> kzgProofsForBlob =
                      extractProofsForBlob(blobType, kzgProofs, index);
                  return new BlobProofBundle(
                      blobType,
                      cellsWithMaskList.get(index),
                      kzgCommitments.get(index),
                      kzgProofsForBlob,
                      versionedHashes.get(index));
                })
            .toList());
  }

  private static List<KZGProof> extractProofsForBlob(
      final BlobType blobType, final List<KZGProof> kzgProofs, final int index) {
    return switch (blobType) {
      case KZG_PROOF -> List.of(kzgProofs.get(index)); // Single proof per blob
      case KZG_CELL_PROOFS ->
          kzgProofs.subList(
              index * CELL_PROOFS_PER_BLOB,
              (index + 1) * CELL_PROOFS_PER_BLOB); // 128 cell proofs per blob
    };
  }

  private static void commonValidateInputParameters(
      final BlobType blobType,
      final List<KZGCommitment> kzgCommitments,
      final List<KZGProof> kzgProofs,
      final List<VersionedHash> versionedHashes,
      final int count) {
    checkNotNull(versionedHashes, "versionedHashes must be set before calling kzgBlobs()");
    checkArgument(
        count > 0,
        "There needs to be a minimum of one blob in a blob transaction with commitments");
    int expectedProofs = blobType == KZG_PROOF ? count : CELL_PROOFS_PER_BLOB * count;
    checkArgument(
        count == versionedHashes.size(),
        "Invalid number of versionedHashes, expected %s, got %s",
        count,
        versionedHashes.size());
    checkArgument(
        count == kzgCommitments.size(),
        "Invalid number of kzgCommitments, expected %s, got %s",
        count,
        kzgCommitments.size());
    checkArgument(
        kzgProofs.size() == expectedProofs,
        "Invalid number of proofs (%s), expected %s, got %s",
        blobType,
        expectedProofs,
        kzgProofs.size());
  }

  /**
   * Get the blobs.
   *
   * @return the blobs
   */
  @Override
  public List<Blob> getBlobs() {
    return blobProofBundles.stream()
        .map(BlobProofBundle::getBlob)
        .map(b -> b.orElse(null))
        .toList();
  }

  /**
   * Get the commitments.
   *
   * @return the commitments
   */
  @Override
  public List<KZGCommitment> getKzgCommitments() {
    return blobProofBundles.stream().map(BlobProofBundle::getKzgCommitment).toList();
  }

  /**
   * Get the proofs.
   *
   * @return the proofs
   */
  @Override
  public List<KZGProof> getKzgProofs() {
    return blobProofBundles.stream().flatMap(bundle -> bundle.getKzgProof().stream()).toList();
  }

  /**
   * Get the hashes.
   *
   * @return the hashes
   */
  @Override
  public List<VersionedHash> getVersionedHashes() {
    return blobProofBundles.stream().map(BlobProofBundle::getVersionedHash).toList();
  }

  /**
   * Get the list of BlobProofBundle.
   *
   * @return blob proof bundles
   */
  public List<BlobProofBundle> getBlobProofBundles() {
    return blobProofBundles;
  }

  /**
   * Get the BlobType
   *
   * @return the type of the blobs
   */
  @Override
  public BlobType getBlobType() {
    return blobType;
  }

  /**
   * Get the KZG proofs as a byte array. Passed to the CKZG4844JNI for proof verification.
   *
   * @return the KZG proofs as a byte array
   */
  byte[] getKzgProofsByteArray() {
    return Bytes.wrap(getKzgProofs().stream().map(kp -> (Bytes) kp.getData()).toList())
        .toArrayUnsafe();
  }

  /**
   * Get the blobs as a byte array. Passed to the CKZG4844JNI for proof verification.
   *
   * @return the blobs as a byte array
   */
  byte[] getBlobsByteArray() {
    return Bytes.wrap(getBlobs().stream().map(Blob::getData).toList()).toArrayUnsafe();
  }

  /**
   * Get the KZG commitments as a byte array. Passed to the CKZG4844JNI for proof verification.
   *
   * @return the KZG commitments as a byte array
   */
  byte[] getKzgCommitmentsByteArray() {
    List<KZGCommitment> commitments =
        (blobType == BlobType.KZG_CELL_PROOFS)
            ? extendCommitments(getKzgCommitments())
            : getKzgCommitments();
    return Bytes.wrap(commitments.stream().map(kc -> (Bytes) kc.getData()).toList())
        .toArrayUnsafe();
  }

  /**
   * Extends the KZG commitments to match the number of cell proofs per blob. This is necessary when
   * the blob type is KZG_CELL_PROOFS, and we want to verify the cell proofs
   *
   * @param commitments the original list of KZG commitments.
   * @return a new list of KZG commitments, extended to match the number of cell proofs per blob.
   */
  private List<KZGCommitment> extendCommitments(final List<KZGCommitment> commitments) {
    int newSize = commitments.size() * CELL_PROOFS_PER_BLOB;
    ArrayList<KZGCommitment> extendedCommitments = new ArrayList<>(newSize);
    for (KZGCommitment kzgCommitment : commitments) {
      for (int i = 0; i < CELL_PROOFS_PER_BLOB; i++) {
        extendedCommitments.add(new KZGCommitment(kzgCommitment.getData()));
      }
    }
    return extendedCommitments;
  }

  /**
   * Get the blob cells as a byte array. Passed to the CKZG4844JNI for proof verification.
   *
   * @return the blob cells as a byte array
   */
  byte[] getBlobCellsByteArray() {
    if (!getCellMask().isFull()) {
      throw new IllegalStateException("Not all cells are present");
    }
    return Bytes.wrap(
            blobProofBundles.stream().map(cell -> cell.getBlobCellsBytes().orElseThrow()).toList())
        .toArrayUnsafe();
  }

  /**
   * Get the cell indexes for the blobs. Passed to the CKZG4844JNI for proof verification.
   *
   * @return an array of cell indexes
   */
  long[] getCellIndexes() {
    long[] cellIndices = new long[CELL_PROOFS_PER_BLOB * blobProofBundles.size()];
    for (int blobIndex = 0; blobIndex < blobProofBundles.size(); blobIndex++) {
      for (int index = 0; index < CELL_PROOFS_PER_BLOB; index++) {
        cellIndices[blobIndex * CELL_PROOFS_PER_BLOB + index] = index;
      }
    }
    return cellIndices;
  }

  public CellMask getCellMask() {
    return blobProofBundles
        .getFirst()
        .getCellsWithMask()
        .map(CellsWithMask::getCellMask)
        .orElse(CellMask.FULL);
  }

  public boolean allCellsPresent() {
    return getCellMask().isFull();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    BlobsWithCommitments that = (BlobsWithCommitments) o;
    return blobType == that.blobType && Objects.equals(blobProofBundles, that.blobProofBundles);
  }

  @Override
  public int hashCode() {
    return Objects.hash(blobProofBundles, blobType);
  }

  public BlobsWithCommitments detachedCopy() {
    return new BlobsWithCommitments(
        blobType, blobProofBundles.stream().map(BlobProofBundle::detachedCopy).toList());
  }
}
