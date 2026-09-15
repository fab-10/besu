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
package org.hyperledger.besu.ethereum.eth.manager.peertask.task;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthPeerImmutableAttributes;
import org.hyperledger.besu.ethereum.eth.manager.peertask.InvalidPeerTaskResponseException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskValidationResponse;
import org.hyperledger.besu.ethereum.eth.messages.CellsMessage;
import org.hyperledger.besu.ethereum.eth.messages.GetCellsMessage;
import org.hyperledger.besu.ethereum.eth.transactions.CellMask;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.SubProtocol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.tuweni.bytes.Bytes;

public class GetCellsFromPeerTask implements PeerTask<CellsMessage.Response> {

  private final SequencedSet<Hash> hashes;
  private final CellMask cellMask;

  public GetCellsFromPeerTask(final List<Hash> hashes, final CellMask cellMask) {
    this.hashes = new LinkedHashSet<>(hashes);
    this.cellMask = cellMask;
  }

  @Override
  public SubProtocol getSubProtocol() {
    return EthProtocol.get();
  }

  @Override
  public MessageData getRequestMessage(final Set<Capability> agreedCapabilities) {
    return GetCellsMessage.create(hashes, cellMask);
  }

  @Override
  public CellsMessage.Response processResponse(
      final MessageData messageData, final Set<Capability> agreedCapabilities)
      throws InvalidPeerTaskResponseException {
    final CellsMessage cellsMessage = CellsMessage.readFrom(messageData);
    final CellsMessage.Response response = cellsMessage.response();
    if (response.hashes().size() != response.cells().size()) {
      throw new InvalidPeerTaskResponseException(
          "Response cell hashes and cells do not have matching lengths");
    }
    return response;
  }

  @Override
  public Predicate<EthPeerImmutableAttributes> getPeerRequirementFilter() {
    return peer ->
        peer.ethPeer().getAgreedCapabilities().stream().anyMatch(EthProtocol::isEth72Compatible);
  }

  @Override
  public PeerTaskValidationResponse validateResult(final CellsMessage.Response result) {
    if (!cellMask.equals(result.cellMask())) {
      return PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY;
    }
    if (result.hashes().size() > hashes.size()) {
      return PeerTaskValidationResponse.TOO_MANY_RESULTS_RETURNED;
    }

    final int expectedCellsPerBlob = cellMask.cardinality();
    for (int i = 0; i < result.hashes().size(); i++) {
      if (!hashes.contains(result.hashes().get(i))) {
        return PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY;
      }
      final List<Bytes> transactionCells = result.cells().get(i);
      if (expectedCellsPerBlob == 0) {
        if (!transactionCells.isEmpty()) {
          return PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY;
        }
        continue;
      }
      if (transactionCells.isEmpty()) {
        return PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY;
      }
      if (transactionCells.size() % expectedCellsPerBlob != 0) {
        return PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY;
      }
      if (transactionCells.stream().anyMatch(cell -> cell.size() != CellMask.CELL_SIZE)) {
        return PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY;
      }
    }
    return PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD;
  }
}
