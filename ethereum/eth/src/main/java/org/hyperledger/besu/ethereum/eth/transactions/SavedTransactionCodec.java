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

import static com.google.common.base.Preconditions.checkArgument;

import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.kzg.BlobProofBundle;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.Cell;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

import java.util.List;
import java.util.stream.IntStream;

import org.apache.tuweni.bytes.Bytes;

/**
 * The save file payload of one pending transaction: its pooled encoding, followed by the cells of a
 * sparsely sampled blob transaction, which no wire form carries.
 *
 * <p>A transaction that holds its blobs is saved as the ordinary pooled form, as it always was. Its
 * cells are computed from the blobs when it is read back, so saving them would double the file to
 * save nothing. A transaction that holds only cells has no ordinary pooled form at all: eliding the
 * payloads is what eth/72 sends, and that sends no cells either. It is saved as the elided form
 * followed by its mask and its cells, so that a restart does not throw away the sampling that
 * fetching them cost.
 *
 * <p>The two fields are base64, separated by a character outside that alphabet, so the separator
 * being present is what says which form the transaction field is in. A file written before this
 * node spoke eth/72 has no separator on any line and reads back exactly as before.
 */
final class SavedTransactionCodec {

  /** Outside the base64 alphabet, so it cannot occur within either field. */
  private static final char CELLS_SEPARATOR = '.';

  private SavedTransactionCodec() {}

  /**
   * Encodes a transaction for the save file.
   *
   * @param transaction the transaction to save
   * @return its payload, one line's worth, with no trailing newline
   */
  static String encode(final Transaction transaction) {
    final var maybeSidecar = transaction.getBlobsWithCommitments();
    final boolean cellsOnly = maybeSidecar.isPresent() && !maybeSidecar.get().hasBlobData();

    final BytesValueRLPOutput txOut = new BytesValueRLPOutput();
    transaction.writeTo(
        txOut,
        cellsOnly ? EncodingContext.POOLED_TRANSACTION_ETH_72 : EncodingContext.POOLED_TRANSACTION);
    if (!cellsOnly) {
      return txOut.encoded().toBase64String();
    }

    final BlobsWithCommitments sidecar = maybeSidecar.get();
    // One mask for the whole transaction, as on the wire: an eth/72 cell index refers to the
    // corresponding cell of every blob, and BlobsWithCommitments enforces that they all agree.
    final CellMask cellMask = sidecar.getCellMask();
    final BytesValueRLPOutput cellsOut = new BytesValueRLPOutput();
    cellsOut.startList();
    cellsOut.writeBytes(cellMask.toBytes());
    cellsOut.startList();
    for (final BlobProofBundle bundle : sidecar.getBlobProofBundles()) {
      final CellsWithMask cells = bundle.getCellsWithMask().orElseThrow();
      // Read by cell index rather than in list order, as EthServer does: the two only coincide for
      // a mask that starts at zero with no gaps.
      cellMask.streamIndexes().forEach(index -> cells.getCell(index).writeTo(cellsOut));
    }
    cellsOut.endList();
    cellsOut.endList();

    return txOut.encoded().toBase64String() + CELLS_SEPARATOR + cellsOut.encoded().toBase64String();
  }

  /**
   * Decodes a transaction from a save file payload, in either form.
   *
   * @param payload the payload written by {@link #encode}, or by any earlier version of it
   * @return the transaction, holding whatever cells were saved with it
   */
  static Transaction decode(final String payload) {
    final int separator = payload.indexOf(CELLS_SEPARATOR);
    if (separator < 0) {
      return Transaction.readFrom(
          RLP.input(Bytes.fromBase64String(payload)), EncodingContext.POOLED_TRANSACTION);
    }

    final Transaction elidedTx =
        Transaction.readFrom(
            RLP.input(Bytes.fromBase64String(payload.substring(0, separator))),
            EncodingContext.POOLED_TRANSACTION_ETH_72);
    final RLPInput cellsIn = RLP.input(Bytes.fromBase64String(payload.substring(separator + 1)));
    cellsIn.enterList();
    final CellMask cellMask = CellMask.fromBytes(cellsIn.readBytes());
    final List<Cell> cells = cellsIn.readList(Cell::readFrom);
    cellsIn.leaveList();

    final BlobsWithCommitments elidedSidecar = elidedTx.getBlobsWithCommitments().orElseThrow();
    final int blobCount = elidedSidecar.getBlobProofBundles().size();
    final int cellsPerBlob = cellMask.cardinality();
    checkArgument(
        cells.size() == blobCount * cellsPerBlob,
        "Saved %s cells for %s blobs holding %s cells each",
        cells.size(),
        blobCount,
        cellsPerBlob);

    // Blob major, as the Cells message lays them out: each blob's held cells in ascending index
    // order, one blob after another.
    final List<CellsWithMask> cellsPerBlobList =
        IntStream.range(0, blobCount)
            .mapToObj(
                index ->
                    new CellsWithMask(
                        cells.subList(index * cellsPerBlob, (index + 1) * cellsPerBlob), cellMask))
            .toList();

    return Transaction.builder()
        .copiedFrom(elidedTx)
        .blobsWithCommitments(
            BlobsWithCommitments.createFromBlobCells(
                elidedSidecar.getKzgCommitments(),
                cellsPerBlobList,
                elidedSidecar.getKzgProofs(),
                elidedSidecar.getVersionedHashes()))
        .build();
  }
}
