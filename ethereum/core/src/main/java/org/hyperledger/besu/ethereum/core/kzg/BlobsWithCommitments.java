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

  /**
   * Canonical constructor: every other construction path funnels through here, so this is where the
   * class invariants are enforced.
   *
   * <ul>
   *   <li>the bundle list is not empty
   *   <li>every bundle carries the declared {@link BlobType}
   *   <li>every bundle shares one cell availability mask, or none of them has one
   * </ul>
   *
   * @param blobType the blob type every bundle must declare
   * @param blobProofBundles the bundles, one per blob
   */
  public BlobsWithCommitments(
      final BlobType blobType, final List<BlobProofBundle> blobProofBundles) {
    checkArgument(!blobProofBundles.isEmpty(), "BlobProofBundles list cannot be empty");
    checkArgument(
        blobProofBundles.stream().map(BlobProofBundle::getBlobType).allMatch(blobType::equals),
        "BlobProofBundles must have the same BlobType");
    checkSharedCellMask(blobProofBundles);

    this.blobType = blobType;
    this.blobProofBundles = blobProofBundles;
  }

  /**
   * Constructs an instance from a list of {@link BlobProofBundle}, taking the blob type from the
   * first bundle.
   *
   * @param blobProofBundles the list of blob proof bundles to be attached to the transaction.
   */
  public BlobsWithCommitments(final List<BlobProofBundle> blobProofBundles) {
    this(firstBlobType(blobProofBundles), blobProofBundles);
  }

  private static BlobType firstBlobType(final List<BlobProofBundle> blobProofBundles) {
    checkArgument(!blobProofBundles.isEmpty(), "BlobProofBundles list cannot be empty");
    return blobProofBundles.getFirst().getBlobType();
  }

  /**
   * Enforces that all blobs of a transaction share one cell availability mask.
   *
   * <p>This is a property of the protocol, not an implementation convenience: an eth/72 cell index
   * is transaction level, referring to the corresponding cell of <em>every</em> blob in the
   * transaction, so per-blob divergence is not representable on the wire. Announcements, {@code
   * GetCells} requests and {@code Cells} responses all carry a single mask per transaction.
   *
   * <p>Enforcing it here lets {@link #getCellMask()} and {@link #allCellsPresent()} answer from the
   * first bundle alone, and lets consumers read cells for any index the mask reports without
   * re-checking each bundle.
   *
   * @param blobProofBundles the bundles to check
   */
  private static void checkSharedCellMask(final List<BlobProofBundle> blobProofBundles) {
    // Comparing Optionals covers both directions: all bundles hold an equal mask, or none holds
    // one at all. A mix of the two is just as invalid as two differing masks.
    final Optional<CellMask> firstCellMask =
        blobProofBundles.getFirst().getCellsWithMask().map(CellsWithMask::getCellMask);
    checkArgument(
        blobProofBundles.stream()
            .skip(1)
            .map(bundle -> bundle.getCellsWithMask().map(CellsWithMask::getCellMask))
            .allMatch(firstCellMask::equals),
        "BlobProofBundles must have the same cell mask");
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
    checkArgument(blobs.stream().noneMatch(Objects::isNull), "all blobs must be non null");
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
    final List<KZGProof> proofs =
        (blobType == BlobType.KZG_CELL_PROOFS) ? proofsForHeldCells() : getKzgProofs();
    return Bytes.wrap(proofs.stream().map(kp -> (Bytes) kp.getData()).toList()).toArrayUnsafe();
  }

  /**
   * The cell proofs matching the cells we hold, one per held cell per blob, in the same order as
   * {@link #getBlobCellsByteArray()}.
   *
   * <p>Proofs are never elided on the wire, so a bundle always carries all {@link
   * CKZG4844Helper#CELL_PROOFS_PER_BLOB} of them; only the subset covering the cells we actually
   * have can be verified.
   *
   * @return the proofs for the held cells
   */
  private List<KZGProof> proofsForHeldCells() {
    final int[] heldIndexes = getCellMask().indexes();
    final List<KZGProof> proofs = new ArrayList<>(heldIndexes.length * blobProofBundles.size());
    for (final BlobProofBundle bundle : blobProofBundles) {
      final List<KZGProof> blobProofs = bundle.getKzgProof();
      for (final int heldIndex : heldIndexes) {
        proofs.add(blobProofs.get(heldIndex));
      }
    }
    return proofs;
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
    // verifyCellKzgProofBatch takes four parallel arrays, one entry per cell being verified, so a
    // blob's commitment is repeated once per cell we actually hold, not once per possible cell.
    final int cellsPerBlob = getCellMask().cardinality();
    final ArrayList<KZGCommitment> extendedCommitments =
        new ArrayList<>(commitments.size() * cellsPerBlob);
    for (final KZGCommitment kzgCommitment : commitments) {
      for (int i = 0; i < cellsPerBlob; i++) {
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
    // The indexes we actually hold, repeated per blob, parallel to getBlobCellsByteArray().
    final int[] heldIndexes = getCellMask().indexes();
    final long[] cellIndices = new long[heldIndexes.length * blobProofBundles.size()];
    for (int blobIndex = 0; blobIndex < blobProofBundles.size(); blobIndex++) {
      for (int index = 0; index < heldIndexes.length; index++) {
        cellIndices[blobIndex * heldIndexes.length + index] = heldIndexes[index];
      }
    }
    return cellIndices;
  }

  /**
   * The cell availability mask shared by every blob of this transaction. Reading the first bundle
   * is sufficient because the constructor enforces that they all agree; see {@link
   * #checkSharedCellMask(List)}.
   *
   * @return the shared mask, or a full mask for blob types that do not carry cells
   */
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
