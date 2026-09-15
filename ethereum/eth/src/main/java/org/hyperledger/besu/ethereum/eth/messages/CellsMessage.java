/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.eth.messages;

import static com.google.common.base.Preconditions.checkArgument;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.eth.transactions.CellMask;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.AbstractMessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLPException;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

public final class CellsMessage extends AbstractMessageData {

  private static final int MESSAGE_CODE = EthProtocolMessages.CELLS;

  private Response response;

  private CellsMessage(final Bytes rlp) {
    super(rlp);
  }

  @Override
  public int getCode() {
    return MESSAGE_CODE;
  }

  public static CellsMessage create(
      final List<Hash> hashes, final List<List<Bytes>> cells, final CellMask cellMask) {
    checkArgument(
        hashes.size() == cells.size(),
        "Cell response hashes and cells must have the same number of elements");

    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    out.startList();
    out.writeList(hashes, (hash, rlpOutput) -> rlpOutput.writeBytes(hash.getBytes()));
    out.writeList(
        cells,
        (transactionCells, rlpOutput) ->
            rlpOutput.writeList(
                transactionCells, (cell, cellOutput) -> cellOutput.writeBytes(cell)));
    out.writeBytes(cellMask.bytes());
    out.endList();
    return new CellsMessage(out.encoded());
  }

  public static CellsMessage readFrom(final MessageData message) {
    if (message instanceof CellsMessage) {
      return (CellsMessage) message;
    }
    final int code = message.getCode();
    if (code != MESSAGE_CODE) {
      throw new IllegalArgumentException(
          String.format("Message has code %d and thus is not a CellsMessage.", code));
    }

    return new CellsMessage(message.getData());
  }

  public Response response() {
    if (response == null) {
      final BytesValueRLPInput input = new BytesValueRLPInput(getData(), false);
      input.enterList();
      final List<Hash> hashes =
          input.readList(rlpInput -> Hash.wrap(rlpInput.readBytes32().copy()));
      final List<List<Bytes>> cells =
          input.readList(
              transactionCellsInput ->
                  transactionCellsInput.readList(cellInput -> cellInput.readBytes().copy()));
      final Bytes maskBytes = input.readBytes();
      if (maskBytes.size() != CellMask.BYTE_LENGTH) {
        throw new RLPException(
            "Invalid eth/72 cell mask length %s, expected %s"
                .formatted(maskBytes.size(), CellMask.BYTE_LENGTH));
      }
      input.leaveList();
      response = new Response(hashes, cells, CellMask.fromBytes(maskBytes));
    }
    return response;
  }

  public record Response(List<Hash> hashes, List<List<Bytes>> cells, CellMask cellMask) {}
}
