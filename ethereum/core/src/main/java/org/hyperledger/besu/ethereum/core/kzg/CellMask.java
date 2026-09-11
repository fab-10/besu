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

import org.apache.tuweni.bytes.Bytes;

/** Fixed-width eth/72 cell availability mask. */
public record CellMask(Bytes bytes) {
  public static final int BYTE_LENGTH = 16;

  public static final CellMask EMPTY = new CellMask(Bytes.wrap(new byte[BYTE_LENGTH]));
  public static final CellMask FULL = new CellMask(fullMaskBytes());

  public CellMask(final Bytes bytes) {
    checkNotNull(bytes, "cell mask bytes must not be null");
    checkArgument(
        bytes.size() == BYTE_LENGTH,
        "cell mask must be %s bytes, got %s",
        BYTE_LENGTH,
        bytes.size());
    this.bytes = bytes;
  }

  public static CellMask fromBytes(final Bytes bytes) {
    return new CellMask(bytes.copy());
  }

  @Override
  public Bytes bytes() {
    return bytes;
  }

  public boolean isEmpty() {
    return bytes.equals(EMPTY.bytes);
  }

  public boolean isFull() {
    return bytes.equals(FULL.bytes);
  }

  public int cardinality() {
    int count = 0;
    for (int i = 0; i < bytes.size(); i++) {
      count += Integer.bitCount(Byte.toUnsignedInt(bytes.get(i)));
    }
    return count;
  }

  public int[] indexes() {
    final int[] indexes = new int[cardinality()];
    int arrayIdx = 0;
    for (int index = 0; index < CELLS_PER_EXT_BLOB; index++) {
      if (isSet(index)) {
        indexes[arrayIdx++] = index;
      }
    }
    return indexes;
  }

  public boolean isSet(final int index) {
    checkArgument(index >= 0 && index < CELLS_PER_EXT_BLOB, "cell index out of range: %s", index);
    final int byteIndex = index / Byte.SIZE;
    final int bitIndex = index % Byte.SIZE;
    return (Byte.toUnsignedInt(bytes.get(byteIndex)) & (1 << bitIndex)) != 0;
  }

  public boolean containsAll(final CellMask other) {
    for (int i = 0; i < BYTE_LENGTH; i++) {
      final int mine = Byte.toUnsignedInt(bytes.get(i));
      final int theirs = Byte.toUnsignedInt(other.bytes.get(i));
      if ((mine & theirs) != theirs) {
        return false;
      }
    }
    return true;
  }

  public CellMask intersection(final CellMask other) {
    final byte[] intersectionBytes = new byte[BYTE_LENGTH];
    for (int i = 0; i < BYTE_LENGTH; i++) {
      intersectionBytes[i] =
          (byte) (Byte.toUnsignedInt(this.bytes.get(i)) & Byte.toUnsignedInt(other.bytes.get(i)));
    }
    return new CellMask(Bytes.wrap(intersectionBytes));
  }

  private static Bytes fullMaskBytes() {
    final byte[] bytes = new byte[BYTE_LENGTH];
    Arrays.fill(bytes, (byte) 0xFF);
    return Bytes.wrap(bytes);
  }
}
