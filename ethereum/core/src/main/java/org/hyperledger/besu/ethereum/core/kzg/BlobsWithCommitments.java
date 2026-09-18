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
import static org.hyperledger.besu.datatypes.BlobType.KZG_CELL_PROOFS;
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
   * Private constructor: instances are built through the static factories, each of which validates
   * what its own inputs can get wrong. Whichever way it was built, an instance satisfies:
   *
   * <ul>
   *   <li>the bundle list is not empty
   *   <li>every bundle carries the declared {@link BlobType}
   *   <li>every bundle shares one cell availability mask, or none of them has cells
   *   <li>every bundle carries its blob payload, or none of them does
   * </ul>
   *
   * <p>The last two are what let {@link #getCellMask()} and {@link #hasBlobData()} answer from the
   * first bundle alone. For the factories that build the bundles themselves they hold by
   * construction; {@link #createFromBundles} checks them.
   *
   * @param blobType the blob type every bundle must declare
   * @param blobProofBundles the bundles, one per blob
   */
  private BlobsWithCommitments(
      final BlobType blobType, final List<BlobProofBundle> blobProofBundles) {
    this.blobType = blobType;
    this.blobProofBundles = blobProofBundles;
  }

  /**
   * Assembles an instance from ready-made bundles, whose origin this class cannot see, so every
   * invariant has to be checked here rather than following from how the bundles were built.
   *
   * @param bundles the bundles, one per blob
   * @return the assembled instance
   */
  public static BlobsWithCommitments createFromBundles(final List<BlobProofBundle> bundles) {
    checkArgument(!bundles.isEmpty(), "at least one bundle should be present");
    checkArgument(bundles.stream().noneMatch(Objects::isNull), "all bundles must be non null");
    final BlobType firstBlobType = bundles.getFirst().getBlobType();
    checkArgument(
        bundles.stream().map(BlobProofBundle::getBlobType).allMatch(firstBlobType::equals),
        "all bundles must be of the same type");
    // Both of these follow from how the other factories build their bundles, but here the bundles
    // arrive already built, so they have to be checked. getCellMask() and hasBlobData() read the
    // first bundle only, and rely on them.
    checkCells(
        bundles.stream().map(BlobProofBundle::getCellsWithMask).flatMap(Optional::stream).toList());
    checkArgument(
        bundles.stream().map(bundle -> bundle.getCellsWithMask().isPresent()).distinct().count()
            == 1,
        "all bundles must either carry cells or none of them");
    checkArgument(
        bundles.stream().map(bundle -> bundle.getBlob().isPresent()).distinct().count() == 1,
        "all bundles must either carry their blob payload or none of them");
    return new BlobsWithCommitments(firstBlobType, bundles);
  }

  /**
   * Constructs an instance.
   *
   * @param kzgCommitments commitments for the blobs.
   * @param blobs list of blobs to be committed to.
   * @param kzgProofs proofs for the commitments.
   * @param versionedHashes hashes of the commitments.
   * @throws InvalidParameterException if the input parameters are invalid.
   */
  public static BlobsWithCommitments createFromBlobsType0(
      final List<KZGCommitment> kzgCommitments,
      final List<Blob> blobs,
      final List<KZGProof> kzgProofs,
      final List<VersionedHash> versionedHashes) {
    final int blobCount = blobs.size();
    commonValidateInputParameters(kzgCommitments, versionedHashes, blobCount);
    checkArgument(
        kzgProofs.size() == blobCount,
        "Invalid number of proofs (type KZG_PROOF), expected %s, got %s",
        blobCount,
        kzgProofs.size());
    checkArgument(blobs.stream().noneMatch(Objects::isNull), "all blobs must be non null");

    return new BlobsWithCommitments(
        KZG_PROOF,
        IntStream.range(0, blobs.size())
            .mapToObj(
                index ->
                    new BlobProofBundle(
                        KZG_PROOF,
                        blobs.get(index),
                        kzgCommitments.get(index),
                        List.of(kzgProofs.get(index)),
                        versionedHashes.get(index)))
            .toList());
  }

  public static BlobsWithCommitments createFromBlobsType1(
      final List<KZGCommitment> kzgCommitments,
      final List<Blob> blobs,
      final List<List<KZGProof>> kzgProofs,
      final List<VersionedHash> versionedHashes) {
    final int blobCount = blobs.size();
    commonValidateInputParameters(kzgCommitments, versionedHashes, blobCount);
    checkArgument(
        kzgProofs.size() == blobCount,
        "Invalid number of proof groups (type KZG_CELL_PROOFS), expected %s, got %s",
        blobCount,
        kzgProofs.size());
    // One group per blob is not enough: each group must hold that blob's full set of cell proofs.
    kzgProofs.forEach(
        proofsForBlob ->
            checkArgument(
                proofsForBlob.size() == CELL_PROOFS_PER_BLOB,
                "Invalid number of proofs (type KZG_CELL_PROOFS), expected %s per blob, got %s",
                CELL_PROOFS_PER_BLOB,
                proofsForBlob.size()));
    checkArgument(blobs.stream().noneMatch(Objects::isNull), "all blobs must be non null");

    return new BlobsWithCommitments(
        KZG_CELL_PROOFS,
        IntStream.range(0, blobs.size())
            .mapToObj(
                index ->
                    new BlobProofBundle(
                        KZG_CELL_PROOFS,
                        blobs.get(index),
                        kzgCommitments.get(index),
                        kzgProofs.get(index),
                        versionedHashes.get(index)))
            .toList());
  }

  public static BlobsWithCommitments createFromBlobCells(
      final List<KZGCommitment> kzgCommitments,
      final List<CellsWithMask> cellsWithMaskList,
      final List<KZGProof> kzgProofs,
      final List<VersionedHash> versionedHashes) {
    final int blobCount = cellsWithMaskList.size();
    commonValidateInputParameters(kzgCommitments, versionedHashes, blobCount);
    final int expectedProofs = CELL_PROOFS_PER_BLOB * blobCount;
    checkArgument(
        kzgProofs.size() == expectedProofs,
        "Invalid number of proofs (type KZG_CELL_PROOFS), expected %s, got %s",
        expectedProofs,
        kzgProofs.size());
    checkArgument(
        cellsWithMaskList.stream().noneMatch(Objects::isNull), "all cells must be non null");
    checkCells(cellsWithMaskList);

    return new BlobsWithCommitments(
        KZG_CELL_PROOFS,
        IntStream.range(0, cellsWithMaskList.size())
            .mapToObj(
                index -> {
                  List<KZGProof> kzgProofsForBlob =
                      kzgProofs.subList(
                          index * CELL_PROOFS_PER_BLOB, (index + 1) * CELL_PROOFS_PER_BLOB);
                  return new BlobProofBundle(
                      KZG_CELL_PROOFS,
                      cellsWithMaskList.get(index),
                      kzgCommitments.get(index),
                      kzgProofsForBlob,
                      versionedHashes.get(index));
                })
            .toList());
  }

  private static void checkCells(final List<CellsWithMask> cellsWithMasks) {
    if (!cellsWithMasks.isEmpty()) {
      final CellMask firstCellMask = cellsWithMasks.getFirst().getCellMask();
      checkArgument(
          cellsWithMasks.stream()
              .skip(1)
              .map(CellsWithMask::getCellMask)
              .allMatch(firstCellMask::equals),
          "Cells must have the same cell mask");
    }
  }

  private static void commonValidateInputParameters(
      final List<KZGCommitment> kzgCommitments,
      final List<VersionedHash> versionedHashes,
      final int count) {
    checkNotNull(versionedHashes, "versionedHashes must be set before calling kzgBlobs()");
    checkArgument(
        count > 0,
        "There needs to be a minimum of one blob in a blob transaction with commitments");
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
  }

  /*
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
        (blobType == KZG_CELL_PROOFS) ? proofsForHeldCells() : getKzgProofs();
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
        (blobType == KZG_CELL_PROOFS)
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
   * is sufficient because the constructor enforces that they all agree.
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

  /**
   * Whether the actual blob payloads are held, as opposed to cells.
   *
   * <p>Distinct from {@link #allCellsPresent()}, which asks about the cell mask. A transaction
   * reassembled from a complete set of cells reports every cell present while holding no {@link
   * Blob} at all, so only this predicate answers whether the pre-eth/72 wire form — which carries
   * the payloads themselves — can be produced.
   *
   * @return true if every blob of this transaction is held in full
   */
  public boolean hasBlobData() {
    // The canonical constructor rejects a mix, so the first bundle answers for all of them.
    return blobProofBundles.getFirst().getBlob().isPresent();
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
