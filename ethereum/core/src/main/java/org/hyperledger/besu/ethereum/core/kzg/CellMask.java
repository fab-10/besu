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
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELLS_PER_EXT_BLOB;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes;

/** Fixed-width eth/72 cell availability mask. */
public final class CellMask {
  private final BitSet mask;

  public static final int BYTE_LENGTH = 16;

  public static final CellMask EMPTY = new CellMask(new BitSet(128));
  public static final CellMask FULL = new CellMask(BitSet.valueOf(fullMaskBytes()));

  public CellMask(final Bytes bytes) {
    checkNotNull(bytes, "cell mask bytes must not be null");
    checkArgument(
        bytes.size() == BYTE_LENGTH,
        "cell mask must be %s bytes, got %s",
        BYTE_LENGTH,
        bytes.size());

    this.mask = BitSet.valueOf(bytes.toArray());
  }

  private CellMask(final BitSet mask) {
    this.mask = mask;
  }

  public CellMask copy() {
    return new CellMask((BitSet) mask.clone());
  }

  public static CellMask fromBytes(final Bytes bytes) {
    return new CellMask(bytes);
  }

  public boolean isEmpty() {
    return mask.isEmpty();
  }

  public boolean isFull() {
    return mask.cardinality() == CELLS_PER_EXT_BLOB;
  }

  public int cardinality() {
    return mask.cardinality();
  }

  public IntStream streamIndexes() {
    return mask.stream();
  }

  public int[] indexes() {
    return mask.stream().toArray();
  }

  /**
   * Tests whether every index set in {@code other} is also set in this mask, i.e. whether {@code
   * other} is a subset of this mask.
   *
   * @param other the mask that must be covered by this one
   * @return true if this mask contains all the indexes of the other mask
   */
  public boolean containsAll(final CellMask other) {
    final BitSet notCovered = (BitSet) other.mask.clone();
    notCovered.andNot(mask);
    return notCovered.isEmpty();
  }

  /**
   * Serializes this mask to its fixed width wire representation. {@link BitSet#toByteArray()} trims
   * trailing zero bytes, so the result is right padded to {@link #BYTE_LENGTH}, otherwise a mask
   * with no high indexes set would not round trip through {@link #fromBytes(Bytes)}.
   *
   * @return exactly {@link #BYTE_LENGTH} bytes
   */
  public Bytes toBytes() {
    final byte[] bytes = new byte[BYTE_LENGTH];
    final byte[] setBytes = mask.toByteArray();
    System.arraycopy(setBytes, 0, bytes, 0, setBytes.length);
    return Bytes.wrap(bytes);
  }

  /**
   * Changes this CellMask, merging the other CellMask into it by performing a logical OR operation
   * on their respective BitSet representations.
   *
   * @param other the CellMask to merge into this CellMask.
   */
  public void merge(final CellMask other) {
    mask.or(other.mask);
  }

  /**
   * Changes this CellMask, intersecting the other CellMask into it by performing a logical AND
   * operation on their respective BitSet representations.
   *
   * @param other the CellMask to intersect into this CellMask.
   */
  public void intersect(final CellMask other) {
    mask.and(other.mask);
  }

  @Override
  public String toString() {
    return mask.toString();
  }

  @Override
  public boolean equals(final Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    final CellMask cellMask = (CellMask) o;
    return Objects.equals(mask, cellMask.mask);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(mask);
  }

  private static byte[] fullMaskBytes() {
    final byte[] bytes = new byte[BYTE_LENGTH];
    Arrays.fill(bytes, (byte) 0xFF);
    return bytes;
  }

  public void andNot(final CellMask peerRequestMask) {
    mask.andNot(peerRequestMask.mask);
  }
}
