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
package org.hyperledger.besu.ethereum.eth.manager.peertask.task;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.kzg.Cell;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthPeerImmutableAttributes;
import org.hyperledger.besu.ethereum.eth.manager.peertask.InvalidPeerTaskResponseException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.MalformedRlpFromPeerException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskValidationResponse;
import org.hyperledger.besu.ethereum.eth.messages.CellsMessage;
import org.hyperledger.besu.ethereum.eth.messages.GetCellsMessage;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.SubProtocol;
import org.hyperledger.besu.ethereum.rlp.RLPException;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.Predicate;

public class GetCellsFromPeerTask implements PeerTask<Map<Hash, CellsWithMask>> {

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
  public Map<Hash, CellsWithMask> processResponse(
      final MessageData messageData, final Set<Capability> agreedCapabilities)
      throws InvalidPeerTaskResponseException, MalformedRlpFromPeerException {
    final CellsMessage cellsMessage = CellsMessage.readFrom(messageData);
    final Map<Hash, List<Cell>> resCellByHash;
    final CellMask resCellMask;
    try {
      resCellByHash = cellsMessage.cellsByTxHash();
      resCellMask = cellsMessage.cellMask();
    } catch (RLPException e) {
      throw new MalformedRlpFromPeerException(e, messageData.getData());
    }
    if (resCellByHash.size() > hashes.size()) {
      throw new InvalidPeerTaskResponseException(
          "Received %d results, more than requested %d"
              .formatted(resCellByHash.size(), hashes.size()));
    }

    if (!cellMask.containsAll(resCellMask)) {
      throw new InvalidPeerTaskResponseException(
          "Received cell mask %s is not contained in requested cell mask %s"
              .formatted(resCellMask.bytes().toHexString(), cellMask.bytes().toHexString()));
    }

    final Map<Hash, CellsWithMask> result = HashMap.newHashMap(resCellByHash.size());

    for (final var entry : resCellByHash.entrySet()) {
      final Hash txHash = entry.getKey();

      if (!hashes.contains(txHash)) {
        throw new InvalidPeerTaskResponseException(
            "Received not requested cells for tx hash %s".formatted(txHash));
      }

      result.put(txHash, new CellsWithMask(entry.getValue(), resCellMask));
    }
    return result;
  }

  @Override
  public Predicate<EthPeerImmutableAttributes> getPeerRequirementFilter() {
    return _ -> true;
  }

  @Override
  public PeerTaskValidationResponse validateResult(final Map<Hash, CellsWithMask> result) {
    return PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD;
  }
}
