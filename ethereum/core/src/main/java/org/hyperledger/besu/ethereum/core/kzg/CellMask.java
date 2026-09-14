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
import static ethereum.ckzg4844.CKZG4844JNI.CELLS_PER_EXT_BLOB;

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
    final int[] indexes = new int[CELLS_PER_EXT_BLOB];
    int arrayIdx = 0;
    for (int index = 0; index < CELLS_PER_EXT_BLOB; index++) {
      if (mask.get(index)) {
        indexes[arrayIdx++] = index;
      }
    }
    return indexes;
  }

  public boolean containsAll(final CellMask other) {
    return mask.intersects(other.mask);
  }

  public Bytes toBytes() {
    return Bytes.wrap(mask.toByteArray());
  }

  /**
   * Merges the current CellMask into the specified CellMask by performing a
   * logical OR operation on their respective BitSet representations.
   *
   * @param cellMask the target CellMask into which the current CellMask will be merged.
   * @return the updated target CellMask after the merge operation.
   */
  public CellMask mergeInto(final CellMask cellMask) {
    cellMask.mask.or(mask);
    return cellMask;
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

}
