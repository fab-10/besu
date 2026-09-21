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
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELLS_PER_EXT_BLOB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.stream.IntStream;

public class CellsWithMask {

  /**
   * A new, empty instance.
   *
   * <p>Deliberately a factory rather than a constant: {@link CellsWithMask} is mutable through
   * {@link #merge(CellsWithMask)}, so a single shared empty instance would be corrupted process
   * wide by the first merge into it, and every holder of it would silently see another
   * transaction's cells.
   *
   * @return a new empty instance, owned by the caller
   */
  public static CellsWithMask empty() {
    return new CellsWithMask(List.of(), CellMask.EMPTY.copy());
  }

  private final List<Cell> cells;
  private final CellMask cellMask;
  private final int[] indexMap;

  private CellsWithMask(final List<Cell> cells, final CellMask cellMask, final int[] indexMap) {
    this.cells = cells;
    this.cellMask = cellMask;
    this.indexMap = indexMap;
  }

  public CellsWithMask(final List<Cell> cells, final CellMask cellMask) {
    checkArgument(cells.size() == cellMask.cardinality(), "Cell list does not match mask");
    // maps a cell index to its position in the cells list, or -1 when not held
    final int[] indexMap = new int[CELLS_PER_EXT_BLOB];
    Arrays.fill(indexMap, -1);

    int listIdx = 0;
    final PrimitiveIterator.OfInt itMask = cellMask.streamIndexes().iterator();
    while (itMask.hasNext()) {
      indexMap[itMask.next()] = listIdx++;
    }

    this(new ArrayList<>(cells), cellMask.copy(), indexMap);
  }

  public CellMask getCellMask() {
    return cellMask;
  }

  public Cell getCell(final int index) {
    return cells.get(indexMap[index]);
  }

  public List<Cell> getCells() {
    return cells;
  }

  public CellsWithMask detachedCopy() {
    final List<Cell> detachedCells =
        cells.stream().map(cell -> cell.getData().copy()).map(Cell::new).toList();
    return new CellsWithMask(detachedCells, cellMask);
  }

  public void merge(final CellsWithMask other) {

    int mergedCellIdx = 0;
    int otherCellIdx = 0;
    final PrimitiveIterator.OfInt itMergedMasks =
        IntStream.concat(cellMask.streamIndexes(), other.cellMask.streamIndexes()).iterator();
    while (itMergedMasks.hasNext()) {
      final int index = itMergedMasks.next();
      if (indexMap[index] == -1) {
        // cell is from the other object
        cells.add(mergedCellIdx, other.getCell(otherCellIdx++));
      }
      indexMap[index] = mergedCellIdx++;
    }

    cellMask.merge(other.cellMask);
  }

  @Override
  public String toString() {
    return "cell count=" + cells.size() + ", cellMask=" + cellMask;
  }
}
