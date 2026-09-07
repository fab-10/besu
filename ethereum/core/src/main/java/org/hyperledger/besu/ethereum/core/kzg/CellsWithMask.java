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

import java.util.List;

public class CellsWithMask {
  public static final CellsWithMask EMPTY = new CellsWithMask(List.of(), CellMask.EMPTY);

  private final Cell[] cells;
  private final CellMask cellMask;

  public CellsWithMask(final List<Cell> cells, final CellMask cellMask) {
    this.cells = new Cell[CKZG4844Helper.CELL_PROOFS_PER_BLOB];
    this.cellMask = cellMask;

    int listTIdx = 0;

    for (int i = 0; i < CKZG4844Helper.CELL_PROOFS_PER_BLOB; i++) {
      final int byteIndex = i / Byte.SIZE;
      final int bitIndex = i % Byte.SIZE;
      if ((Byte.toUnsignedInt(cellMask.bytes().get(byteIndex)) & (1 << bitIndex)) != 0) {
        if (listTIdx >= cells.size()) {
          throw new IllegalArgumentException("Not enough cells provided");
        }
        this.cells[i] = cells.get(listTIdx++);
      }
    }

    if (listTIdx != cells.size()) {
      throw new IllegalArgumentException("Too many cells provided");
    }
  }

  public CellMask getCellMask() {
    return cellMask;
  }

  public Cell getCell(final int index) {
    return cells[index];
  }

  public Cell[] getCells() {
    return cells;
  }
}
