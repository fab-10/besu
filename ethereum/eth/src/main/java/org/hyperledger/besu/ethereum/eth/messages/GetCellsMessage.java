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
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.AbstractMessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

import java.util.Collection;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import org.apache.tuweni.bytes.Bytes;

public final class GetCellsMessage extends AbstractMessageData {

  private static final int MESSAGE_CODE = EthProtocolMessages.GET_CELLS;

  private final Supplier<MessageFields> fieldsSupplier;

  private GetCellsMessage(final Bytes rlp) {
    super(rlp);
    fieldsSupplier = Suppliers.memoize(this::parse);
  }

  private GetCellsMessage(final Bytes rlp, final MessageFields messageFields) {
    super(rlp);
    fieldsSupplier = Suppliers.memoize(() -> messageFields);
  }

  @Override
  public int getCode() {
    return MESSAGE_CODE;
  }

  public static GetCellsMessage create(
      final Collection<Hash> pooledTransactions, final CellMask cellMask) {
    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    out.writeList(pooledTransactions, (h, w) -> w.writeBytes(h.getBytes()));
    out.writeBytes(cellMask.bytes());
    return new GetCellsMessage(out.encoded(), new MessageFields(pooledTransactions, cellMask));
  }

  public static GetCellsMessage readFrom(final MessageData message) {
    if (message instanceof GetCellsMessage getCellsMessage) {
      return getCellsMessage;
    }
    final int code = message.getCode();
    if (code != MESSAGE_CODE) {
      throw new IllegalArgumentException(
          String.format("Message has code %d and thus is not a GetCellsMessage.", code));
    }

    return new GetCellsMessage(message.getData());
  }

  public Iterable<Hash> pooledTransactions() {
    return fieldsSupplier.get().pooledTransactions();
  }

  public CellMask cellMask() {
    return fieldsSupplier.get().cellMask();
  }

  private MessageFields parse() {
    final BytesValueRLPInput input = new BytesValueRLPInput(getData(), false);
    input.enterList();
    final Iterable<Hash> pooledTransactions = input.readList(rlp -> Hash.wrap(rlp.readBytes32()));
    final CellMask cellMask = CellMask.fromBytes(input.readBytes());
    input.leaveList();
    return new MessageFields(pooledTransactions, cellMask);
  }

  private record MessageFields(Iterable<Hash> pooledTransactions, CellMask cellMask) {}
}
