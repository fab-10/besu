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

import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.SUCCESS;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetCellsFromPeerTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionsLimbo
    implements TransactionsAnnouncedListener, PendingTransactionAddedListener {

  private static final Logger LOG = LoggerFactory.getLogger(TransactionsLimbo.class);

  private static final int MAX_BLOBS_PER_REQUEST = 64;
  private final Random random = new Random();
  private final EthContext ethContext;
  private final PeerTransactionTracker peerTransactionTracker;
  private final Supplier<CellMask> custodyColumnsSupplier;
  private final Map<Hash, List<PeerAndCellMask>> unvalidated =
      new HashMap<>(); // ToDo: EIP-8070: make an LRU limited in size
  private final Map<Hash, List<PeerAndCellMask>> validated = new HashMap<>();
  private final Map<Hash, Transaction> incompleteBlobs = new HashMap<>();
  private final Map<CellMask, List<Hash>> fetchableBlobsByMask = new HashMap<>();

  public TransactionsLimbo(
      final EthContext ethContext,
      final PeerTransactionTracker peerTransactionTracker,
      final Supplier<CellMask> customColumnsSupplier) {
    this.ethContext = ethContext;
    this.peerTransactionTracker = peerTransactionTracker;
    this.custodyColumnsSupplier = customColumnsSupplier;
  }

  public void addIncompleteBlob(final Transaction transaction) {
    final CellMask requestedCellMask = getCellMask();

    if (hasEnoughAnnouncements(transaction.getHash(), requestedCellMask)) {
      processGetCells(new CellsRequest(transaction.getHash(), requestedCellMask));
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

  @Override
  public void onTransactionsAnnounced(
      final EthPeer peer, final List<TransactionAnnouncement> announcements) {
    announcements.stream()
        .filter(txAnnouncement -> txAnnouncement.type().supportsBlob())
        .forEach(txAnnouncement -> receivedBlobAnnouncement(peer, txAnnouncement));
  }

  @Override
  public void onTransactionAdded(final Transaction transaction) {
    if (transaction.getType().supportsBlob()) {
      // now tx is valid, then move it to the validated map
      List<PeerAndCellMask> pcms = unvalidated.remove(transaction.getHash());
      validated.put(transaction.getHash(), Objects.requireNonNullElse(pcms, new ArrayList<>()));
      final CellMask requestedCellMask = getCellMask();
      if (hasEnoughAnnouncements(transaction.getHash(), requestedCellMask)) {
        processGetCells(new CellsRequest(transaction.getHash(), requestedCellMask));
      }
    }
  }

  private void receivedBlobAnnouncement(
      final EthPeer peer, final TransactionAnnouncement blobAnnouncement) {
    final Hash txHash = blobAnnouncement.hash();

    List<PeerAndCellMask> vpcms = validated.get(txHash);
    if (vpcms != null) {
      vpcms.add(new PeerAndCellMask(peer, blobAnnouncement.cellMask()));
      final CellMask requestedCellMask = getCellMask();
      if (hasEnoughAnnouncements(txHash, requestedCellMask)) {
        processGetCells(new CellsRequest(txHash, requestedCellMask));
      }
      return;
    }

    unvalidated
        .computeIfAbsent(txHash, _ -> new ArrayList<>())
        .add(new PeerAndCellMask(peer, blobAnnouncement.cellMask()));
  }

  private boolean hasEnoughAnnouncements(final Hash txHash, final CellMask requestedCellMask) {
    final List<PeerAndCellMask> pcms = validated.getOrDefault(txHash, List.of());

    if (pcms.size() < 2) {
      return false;
    }

    // verify if requested cells are covered by the union of all the peers' cell masks
    CellMask unionMask = pcms.getFirst().cellMask.copy();
    for (int i = 1; i < pcms.size(); i++) {
      if (unionMask.containsAll(requestedCellMask)) {
        return true;
      }
      unionMask = unionMask.merge(pcms.get(i).cellMask);
    }

    return false;
  }

  private List<PeerAndCellMask> getAnnouncingPeersFor(final Hash txHash) {
    final List<PeerAndCellMask> pcms = validated.get(txHash);
    return pcms == null ? List.of() : List.copyOf(pcms);
  }

  private void processGetCells(final CellsRequest request) {
    ethContext
        .getScheduler()
        .scheduleServiceTask(
            () -> {
              final Transaction tx = incompleteBlobs.get(request.hash());
              final List<PeerAndCellMask> allPeers = getAnnouncingPeersFor(request.hash());
              // final List<PeerAndCellMask> triedPeers = new ArrayList<>();

              if (!allPeers.isEmpty()) {
                // get a list of peers that together have the requested cells
                final Map<EthPeer, CellMask> selectedPeers = new HashMap<>();
                final Iterator<PeerAndCellMask> pcmIter = allPeers.iterator();
                CellMask currUnion = CellMask.EMPTY.copy();
                while (pcmIter.hasNext() && !currUnion.containsAll(request.cellMask())) {
                  final PeerAndCellMask currPcm = pcmIter.next();
                  final CellMask peerRequestMask =
                      currPcm.cellMask().copy().intersect(request.cellMask());
                  selectedPeers.put(currPcm.peer(), peerRequestMask);
                  currUnion = currUnion.merge(currPcm.cellMask());
                  if (currUnion.containsAll(request.cellMask())) {
                    break;
                  }
                }

                for (final Map.Entry<EthPeer, CellMask> entry : selectedPeers.entrySet()) {
                  ethContext
                      .getScheduler()
                      .scheduleServiceTask(
                          () -> {
                            final GetCellsFromPeerTask task =
                                new GetCellsFromPeerTask(List.of(tx), entry.getValue());
                            final PeerTaskExecutorResult<Map<Hash, List<CellsWithMask>>> response =
                                ethContext
                                    .getPeerTaskExecutor()
                                    .executeAgainstPeer(task, entry.getKey());

                            if (response.responseCode().equals(SUCCESS)
                                && response.result().isPresent()) {
                              // merge received masks
                              final Map<Hash, List<CellsWithMask>> result = response.result().get();

                              final CellMask mergedReceivedMask =
                                  result.values().stream()
                                      .map(CellsWithMask::getCellMask)
                                      .reduce(CellMask::merge)
                                      .orElse(CellMask.EMPTY);

                            } else {
                              LOG.debug(
                                  "Failed to get cells from peer {} for tx {}, reason {}",
                                  entry.getKey(),
                                  tx,
                                  response.responseCode());
                            }
                          });
                }
              }
            });
  }

  private record CellsRequest(Hash hash, CellMask cellMask) {}

  private record PeerAndCellMask(EthPeer peer, CellMask cellMask) {}
}
