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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.eth.transactions.CellMask;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.RawMessage;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

public class CellsMessageTest {

  @Test
  public void roundTripCellsMessage() {
    final List<Hash> hashes = List.of(Hash.ZERO);
    final List<List<Bytes>> cells = List.of(List.of(Bytes.repeat((byte) 0x01, CellMask.CELL_SIZE)));
    final CellsMessage message = CellsMessage.create(hashes, cells, CellMask.FULL);

    final CellsMessage.Response response = message.response();
    assertThat(message.getCode()).isEqualTo(EthProtocolMessages.CELLS);
    assertThat(response.hashes()).containsExactlyElementsOf(hashes);
    assertThat(response.cells()).isEqualTo(cells);
    assertThat(response.cellMask()).isEqualTo(CellMask.FULL);
  }

  @Test
  public void readFromMessageWithWrongCodeThrows() {
    final RawMessage rawMsg = new RawMessage(EthProtocolMessages.BLOCK_HEADERS, Bytes.of(0));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> CellsMessage.readFrom(rawMsg));
  }
}
