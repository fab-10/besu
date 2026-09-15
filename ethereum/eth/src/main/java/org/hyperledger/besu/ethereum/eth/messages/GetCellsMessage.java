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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.eth.transactions.CellMask;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.AbstractMessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLPException;

import java.util.Collection;
import java.util.List;

import org.apache.tuweni.bytes.Bytes;

public final class GetCellsMessage extends AbstractMessageData {

  private static final int MESSAGE_CODE = EthProtocolMessages.GET_CELLS;

  private List<Hash> hashes;
  private CellMask cellMask;

  private GetCellsMessage(final Bytes rlp) {
    super(rlp);
  }

  @Override
  public int getCode() {
    return MESSAGE_CODE;
  }

  public static GetCellsMessage create(final Collection<Hash> hashes, final CellMask cellMask) {
    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    out.startList();
    out.writeList(hashes, (hash, rlpOutput) -> rlpOutput.writeBytes(hash.getBytes()));
    out.writeBytes(cellMask.bytes());
    out.endList();
    return new GetCellsMessage(out.encoded());
  }

  public static GetCellsMessage readFrom(final MessageData message) {
    if (message instanceof GetCellsMessage) {
      return (GetCellsMessage) message;
    }
    final int code = message.getCode();
    if (code != MESSAGE_CODE) {
      throw new IllegalArgumentException(
          String.format("Message has code %d and thus is not a GetCellsMessage.", code));
    }

    return new GetCellsMessage(message.getData());
  }

  public List<Hash> hashes() {
    decodeIfNeeded();
    return hashes;
  }

  public CellMask cellMask() {
    decodeIfNeeded();
    return cellMask;
  }

  private void decodeIfNeeded() {
    if (hashes != null) {
      return;
    }
    final BytesValueRLPInput input = new BytesValueRLPInput(getData(), false);
    input.enterList();
    hashes = input.readList(rlpInput -> Hash.wrap(rlpInput.readBytes32().copy()));
    final Bytes maskBytes = input.readBytes();
    if (maskBytes.size() != CellMask.BYTE_LENGTH) {
      throw new RLPException(
          "Invalid eth/72 cell mask length %s, expected %s"
              .formatted(maskBytes.size(), CellMask.BYTE_LENGTH));
    }
    cellMask = CellMask.fromBytes(maskBytes);
    input.leaveList();
  }
}
