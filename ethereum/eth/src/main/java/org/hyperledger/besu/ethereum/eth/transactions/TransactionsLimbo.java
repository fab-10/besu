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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

public class TransactionsLimbo implements TransactionsAnnouncedListener, PendingTransactionAddedListener {
  private final Random random = new Random();
  private final EthContext ethContext;
  private final PeerTransactionTracker peerTransactionTracker;
  private final Supplier<CellMask> custodyColumnsSupplier;
  private final Map<Hash, Transaction> incompleteBlobs = new HashMap<>();
  private final EthScheduler.OrderedProcessor<CellsRequest> cellsFetcherProcessor;
  private final Map<Hash, List<PeerAndCellMask>> unvalidated = new HashMap<>(); // ToDo: EIP-8070: make an LRU limited in size
  private final Map<Hash, List<PeerAndCellMask>> validated = new HashMap<>();
  private final Map<CellMask, List<Hash>> fetchableBlobs = new HashMap<>();

  public TransactionsLimbo(
      final EthContext ethContext, final PeerTransactionTracker peerTransactionTracker, final Supplier<CellMask> customColumnsSupplier) {
    this.ethContext = ethContext;
    this.peerTransactionTracker = peerTransactionTracker;
    this.custodyColumnsSupplier = customColumnsSupplier;
    this.cellsFetcherProcessor =
        ethContext.getScheduler().createOrderedProcessor(this::processGetCells);
  }

  public void addIncompleteBlob(final Transaction transaction) {
    final CellMask requestedCellMask = getCellMask();

    if (hasEnoughAnnouncements(transaction.getHash(), requestedCellMask)) {
      addFetchable(transaction.getHash());
    } else {
      incompleteBlobs.put(transaction.getHash(), transaction);
    }
  }

  private CellMask getCellMask() {
    if (random.nextInt(100) < 15) {
      // fetch all cells
      return CellMask.FULL;
    }
    return custodyColumnsSupplier.get();
  }

  private void addFetchable(final Hash txHash) {
    fetchableBlobs.computeIfAbsent(fetchCellMask, _ -> new ArrayList<>()).add(txHash);
  }

  private void processGetCells(final CellsRequest request) {}

  @Override
  public void onTransactionsAnnounced(final EthPeer peer, final List<TransactionAnnouncement> announcements) {
    announcements.stream().filter(txAnnouncement -> txAnnouncement.type().supportsBlob())
        .forEach(txAnnouncement -> receivedAnnouncement(peer, txAnnouncement));
  }

  @Override
  public void onTransactionAdded(final Transaction transaction) {

  }

 private void receivedAnnouncement(
            final EthPeer peer, final TransactionAnnouncement txAnnouncement) {
      if (txAnnouncement.type().supportsBlob()) {
        final Hash txHash = txAnnouncement.hash();

        if(validated.containsKey(txHash)) {
          validated.get(txHash).add(new PeerAndCellMask(peer, txAnnouncement.cellMask()));
          return;
        }

        unvalidated.computeIfAbsent(txHash, _ -> new ArrayList<>()).add(new PeerAndCellMask(peer, txAnnouncement.cellMask()));
      }
    }

    private  List<PeerAndCellMask> getAnnouncingPeersFor(final Hash txHash) {
      final List<PeerAndCellMask> pcms = validated.get(txHash);
      return pcms == null ? List.of() : List.copyOf(pcms);
    }

    private boolean hasEnoughAnnouncements(final Hash txHash, final CellMask requestedCellMask) {
      final List<PeerAndCellMask> pcms = validated.getOrDefault(txHash, List.of());

      if (pcms.size() < 2) {
        return false;
      }

      CellMask unionMask = pcms.getFirst().cellMask.copy();
      for (int i = 1; i < pcms.size(); i++) {
        if (unionMask.containsAll(requestedCellMask)) {
          return true;
        }
        unionMask = unionMask.mergeInto(pcms.get(i).cellMask);
      }

      return false;
    }

  private record CellsRequest(CellMask cellMask, Hash txHash) {}


  private record PeerAndCellMask(EthPeer peer, CellMask cellMask) {}
}
