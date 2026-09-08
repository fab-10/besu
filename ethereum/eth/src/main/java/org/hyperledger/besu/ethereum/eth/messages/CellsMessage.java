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
package org.hyperledger.besu.ethereum.eth.messages;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.kzg.Cell;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.AbstractMessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import org.apache.tuweni.bytes.Bytes;

public final class CellsMessage extends AbstractMessageData {

  private static final int MESSAGE_CODE = EthProtocolMessages.CELLS;
  private final Supplier<MessageFields> fieldsSupplier;

  private CellsMessage(final Bytes rlp) {
    super(rlp);
    fieldsSupplier = Suppliers.memoize(this::parse);
  }

  @Override
  public int getCode() {
    return MESSAGE_CODE;
  }

  public static CellsMessage createUnsafe(final Bytes data) {
    return new CellsMessage(data);
  }

  public static CellsMessage readFrom(final MessageData message) {
    if (message instanceof CellsMessage cellsMessage) {
      return cellsMessage;
    }
    final int code = message.getCode();
    if (code != MESSAGE_CODE) {
      throw new IllegalArgumentException(
          String.format("Message has code %d and thus is not a CellsMessage.", code));
    }

    return new CellsMessage(message.getData());
  }

  public Map<Hash, List<Cell>> cellsByTxHash() {
    return fieldsSupplier.get().cellsByTxHash;
  }

  public CellMask cellMask() {
    return fieldsSupplier.get().cellMask;
  }

  private MessageFields parse() {
    final BytesValueRLPInput input = new BytesValueRLPInput(getData(), false);
    input.enterList();
    final List<Hash> txHashes = input.readList(rlp -> Hash.wrap(rlp.readBytes32()));
    final Map<Hash, List<Cell>> cellsByTxHash = HashMap.newHashMap(txHashes.size());
    final AtomicInteger idxHash = new AtomicInteger(0);
    // ToDo EIP-8070: verify cell list have the right length according to cell mask
    input.readList(
        rlp ->
            cellsByTxHash.put(
                txHashes.get(idxHash.getAndIncrement()), rlp.readList(Cell::readFrom)));
    final CellMask cellMask = CellMask.fromBytes(input.readBytes());
    input.leaveList();
    return new MessageFields(cellsByTxHash, cellMask);
  }

  private record MessageFields(Map<Hash, List<Cell>> cellsByTxHash, CellMask cellMask) {}
}
