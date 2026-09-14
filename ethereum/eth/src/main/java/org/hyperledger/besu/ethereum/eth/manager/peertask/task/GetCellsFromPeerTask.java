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
import org.hyperledger.besu.ethereum.core.Transaction;
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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetCellsFromPeerTask implements PeerTask<Map<Hash, List<CellsWithMask>>> {
  private static final Logger LOG = LoggerFactory.getLogger(GetCellsFromPeerTask.class);

  private final SequencedSet<Transaction> txs;
  private final CellMask cellMask;

  public GetCellsFromPeerTask(final Collection<Transaction> txs, final CellMask cellMask) {
    this.txs = new LinkedHashSet<>(txs);
    this.cellMask = cellMask;
  }

  @Override
  public SubProtocol getSubProtocol() {
    return EthProtocol.get();
  }

  @Override
  public MessageData getRequestMessage(final Set<Capability> agreedCapabilities) {
    return GetCellsMessage.create(txs, cellMask);
  }

  @Override
  public Map<Hash, List<CellsWithMask>> processResponse(
      final MessageData messageData, final Set<Capability> agreedCapabilities)
      throws InvalidPeerTaskResponseException, MalformedRlpFromPeerException {
    final CellsMessage cellsMessage = CellsMessage.readFrom(messageData);
    final CellsMessage.MessageFields messageFields;
    final CellMask resCellMask;
    try {
      messageFields = cellsMessage.getFields();
      resCellMask = cellsMessage.cellMask();
    } catch (RLPException e) {
      throw new MalformedRlpFromPeerException(e, messageData.getData());
    }

    if (messageFields.txHashes().size() > txs.size()) {
      throw new InvalidPeerTaskResponseException(
          "Received results for %d txs, more than requested %d"
              .formatted(messageFields.txHashes().size(), txs.size()));
    }

    if (!cellMask.containsAll(resCellMask)) {
      throw new InvalidPeerTaskResponseException(
          "Received cell mask %s is not contained in requested cell mask %s"
              .formatted(resCellMask.toString(), cellMask.toString()));
    }

    final Map<Hash, List<CellsWithMask>> result =
        HashMap.newHashMap(messageFields.txHashes().size());

    int consumedCells = 0;

    final Set<Hash> receivedHashes = new HashSet<>(messageFields.txHashes());

    for (final Transaction requestedTx : txs) {
      if (!receivedHashes.remove(requestedTx.getHash())) {
        LOG.debug("Not received cells for tx hash {}", requestedTx.getHash());
      }

      final int txBlobCount = requestedTx.getBlobCount();
      if (messageFields.cells().size() < consumedCells + txBlobCount) {
        throw new InvalidPeerTaskResponseException(
            "Received cells count %d is less than requested %d"
                .formatted(messageFields.cells().size(), txBlobCount + consumedCells));
      }

      final List<CellsWithMask> cellsWithMask =
          messageFields.cells().subList(consumedCells, consumedCells + txBlobCount).stream()
              .map(cells -> new CellsWithMask(cells, resCellMask))
              .toList();

      result.put(requestedTx.getHash(), cellsWithMask);
      consumedCells += txBlobCount;
    }

    if (!receivedHashes.isEmpty()) {
      throw new InvalidPeerTaskResponseException(
          "Received cells for not requested tx hashes: " + receivedHashes);
    }

    return result;
  }

  @Override
  public Predicate<EthPeerImmutableAttributes> getPeerRequirementFilter() {
    return _ -> true;
  }

  @Override
  public PeerTaskValidationResponse validateResult(final Map<Hash, List<CellsWithMask>> result) {
    return PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD;
  }

  @Override
  public int getRetriesWithOtherPeer() {
    return 0;
  }

  @Override
  public int getRetriesWithSamePeer() {
    return 0;
  }
}
