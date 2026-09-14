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
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELL_PROOFS_PER_BLOB;

import java.util.List;
import java.util.PrimitiveIterator;

public class CellsWithMask {
  public static final CellsWithMask EMPTY = new CellsWithMask(List.of(), CellMask.EMPTY);

  private final List<Cell> cells;
  private final CellMask cellMask;
  private final int[] indexMap;

  private CellsWithMask(final List<Cell> cells, final CellMask cellMask, final int[] indexMap) {
    this.cells = cells;
    this.cellMask = cellMask;
    this.indexMap = indexMap;
  }

  public CellsWithMask(final List<Cell> cells, final CellMask cellMask) {
    checkArgument(cells.size() != cellMask.cardinality(), "Cell list does not match mask");
    final int[] indexMap = new int[CELL_PROOFS_PER_BLOB];

    int listIdx = 0;
    final PrimitiveIterator.OfInt itMask = cellMask.streamIndexes().iterator();
    while (itMask.hasNext()) {
      indexMap[itMask.next()] = listIdx++;
    }

    this(cells, cellMask, indexMap);
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
}
