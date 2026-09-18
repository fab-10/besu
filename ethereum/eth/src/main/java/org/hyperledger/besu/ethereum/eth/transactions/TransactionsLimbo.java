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
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetCellsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool.TransactionResubmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionsLimbo implements TransactionsAnnouncedListener {

  private static final Logger LOG = LoggerFactory.getLogger(TransactionsLimbo.class);

  private final Random random = new Random();
  private final EthContext ethContext;
  private final Supplier<CellMask> custodyColumnsSupplier;
  private final TransactionResubmitter transactionResubmitter;
  private final Map<Hash, List<PeerAndCellMask>> peersByHash = new ConcurrentHashMap<>();
  private final Map<Hash, IncompleteBlob> incompleteBlobByHash = new ConcurrentHashMap<>();

  TransactionsLimbo(
      final EthContext ethContext,
      final Supplier<CellMask> customColumnsSupplier,
      final TransactionResubmitter transactionResubmitter) {
    this.ethContext = ethContext;
    this.custodyColumnsSupplier = customColumnsSupplier;
    this.transactionResubmitter = transactionResubmitter;
  }

  void addIncompleteBlob(
      final Transaction transaction,
      final boolean isLocal,
      final boolean hasPriority,
      final byte score) {

    final CellMask requestMask = getCellMask();
    final TxMetadata txMetadata = new TxMetadata(isLocal, hasPriority, score);
    final IncompleteBlob incompleteBlob =
        new IncompleteBlob(transaction, txMetadata, requestMask, CellsWithMask.empty());

    synchronized (this) {
      if (hasEnoughAnnouncements(transaction.getHash(), requestMask)) {
        // get cells directly
        processGetCells(incompleteBlob);
      } else {
        // wait for more announcements
        incompleteBlobByHash.put(transaction.getHash(), incompleteBlob);
      }
    }
  }

  @Override
  public void onTransactionsAnnounced(
      final EthPeer peer, final List<TransactionAnnouncement> announcements) {
    announcements.stream()
        .filter(txAnnouncement -> txAnnouncement.type().supportsBlob())
        .forEach(txAnnouncement -> receivedBlobAnnouncement(peer, txAnnouncement));
  }

  private void receivedBlobAnnouncement(
      final EthPeer peer, final TransactionAnnouncement blobAnnouncement) {
    final Hash txHash = blobAnnouncement.hash();

    synchronized (this) {
      List<PeerAndCellMask> wpcms = peersByHash.computeIfAbsent(txHash, _ -> new ArrayList<>());
      wpcms.add(new PeerAndCellMask(peer, blobAnnouncement.cellMask()));
      final IncompleteBlob incompleteBlob = incompleteBlobByHash.remove(txHash);
      if (incompleteBlob != null && hasEnoughAnnouncements(wpcms, incompleteBlob.requestMask)) {
        processGetCells(incompleteBlob);
      }
    }
  }

  private CellMask getCellMask() {
    if (random.nextInt(100) < 15) {
      // fetch all cells
      return CellMask.FULL;
    }
    return custodyColumnsSupplier.get();
  }

  private boolean hasEnoughAnnouncements(final Hash txHash, final CellMask requestedCellMask) {
    final List<PeerAndCellMask> pcms = peersByHash.getOrDefault(txHash, List.of());
    return hasEnoughAnnouncements(pcms, requestedCellMask);
  }

  private boolean hasEnoughAnnouncements(
      final List<PeerAndCellMask> pcms, final CellMask requestedCellMask) {
    if (pcms.size() < 2) {
      return false;
    }

    // verify if requested cells are covered by the union of all the peers' cell masks
    CellMask unionMask = pcms.getFirst().cellMask.copy();
    for (int i = 1; i < pcms.size(); i++) {
      if (unionMask.containsAll(requestedCellMask)) {
        return true;
      }
      unionMask.merge(pcms.get(i).cellMask);
    }

    return false;
  }

  @SuppressWarnings("MixedMutabilityReturnType")
  private Map<EthPeer, CellMask> getAnnouncingPeersFor(final Hash txHash, final CellMask cellMask) {

    final List<PeerAndCellMask> pcms;
    pcms = peersByHash.get(txHash);

    if (pcms == null) {
      return Collections.emptyMap();
    }

    final Map<EthPeer, CellMask> selectedPeers = new HashMap<>();
    final Iterator<PeerAndCellMask> pcmIter = pcms.iterator();
    final CellMask remainingMask = cellMask.copy();
    while (pcmIter.hasNext() && !remainingMask.isEmpty()) {
      final PeerAndCellMask currPcm = pcmIter.next();
      final CellMask peerRequestMask = currPcm.cellMask().copy();
      peerRequestMask.intersect(cellMask);
      selectedPeers.put(currPcm.peer(), peerRequestMask);
      remainingMask.andNot(peerRequestMask);
    }

    if (remainingMask.isEmpty()) {
      pcms.subList(0, selectedPeers.size()).clear();
      return selectedPeers;
    }

    return Collections.emptyMap();
  }

  private void processGetCells(final IncompleteBlob incompleteBlob) {
    ethContext
        .getScheduler()
        .scheduleServiceTask(
            () -> {
              final Transaction blobTx = incompleteBlob.tx;
              // One accumulator per blob, each owned by this transaction: they are merged into
              // below, so they must not be shared with any other transaction.
              final List<CellsWithMask> mergedReceivedCells =
                  Stream.generate(CellsWithMask::empty).limit(blobTx.getBlobCount()).toList();

              do {
                final Map<EthPeer, CellMask> selectedPeers =
                    getAnnouncingPeersFor(blobTx.getHash(), incompleteBlob.requestMask);
                if (selectedPeers.isEmpty()) {
                  break;
                }

                final List<CompletableFuture<List<CellsWithMask>>> futures =
                    new ArrayList<>(selectedPeers.size());
                for (final Map.Entry<EthPeer, CellMask> entry : selectedPeers.entrySet()) {
                  futures.add(
                      ethContext
                          .getScheduler()
                          .scheduleServiceTaskDirect(
                              () ->
                                  retrieveCellsFromPeer(entry.getKey(), blobTx, entry.getValue())));
                }

                for (final CompletableFuture<List<CellsWithMask>> future : futures) {
                  final List<CellsWithMask> receivedCells = future.join();
                  for (int i = 0; i < receivedCells.size(); i++) {
                    mergedReceivedCells.get(i).merge(receivedCells.get(i));
                  }
                }

              } while (!mergedReceivedCells
                  .getFirst()
                  .getCellMask()
                  .equals(incompleteBlob.requestMask));

              if (mergedReceivedCells.getFirst().getCellMask().equals(incompleteBlob.requestMask)) {
                // complete the blob tx and resubmit to pool
                final Transaction completedTx = completeBlobs(blobTx, mergedReceivedCells);
                final TxMetadata txMetadata = incompleteBlob.txMetadata;
                transactionResubmitter.submit(
                    completedTx, txMetadata.isLocal, txMetadata.hasPriority, txMetadata.score);
                LOG.debug("Received all requested cells for tx {}", blobTx.getHash());
              } else {
                LOG.debug("Unable to retrieve cells for tx {}", blobTx.getHash());
              }
              peersByHash.remove(blobTx.getHash());
            });
  }

  private List<CellsWithMask> retrieveCellsFromPeer(
      final EthPeer peer, final Transaction blobTx, final CellMask mask) {
    final GetCellsFromPeerTask task = new GetCellsFromPeerTask(blobTx, mask);
    final PeerTaskExecutorResult<List<CellsWithMask>> response =
        ethContext.getPeerTaskExecutor().executeAgainstPeer(task, peer);

    if (response.responseCode().equals(SUCCESS) && response.result().isPresent()) {
      return response.result().get();
    }

    LOG.debug(
        "Failed to get cells from peer {} for tx {} mask {}, reason {}",
        peer,
        blobTx,
        mask,
        response.responseCode());
    return List.of();
  }

  private Transaction completeBlobs(
      final Transaction incomplete, final List<CellsWithMask> receivedCells) {
    final BlobsWithCommitments incompleteBwc = incomplete.getBlobsWithCommitments().orElseThrow();

    return Transaction.builder()
        .copiedFrom(incomplete)
        .blobsWithCommitments(
            BlobsWithCommitments.createFromBlobCells(
                incompleteBwc.getKzgCommitments(),
                receivedCells,
                incompleteBwc.getKzgProofs(),
                incompleteBwc.getVersionedHashes()))
        .build();
  }

  private record PeerAndCellMask(EthPeer peer, CellMask cellMask) {}

  private record TxMetadata(boolean isLocal, boolean hasPriority, byte score) {}

  private record IncompleteBlob(
      Transaction tx, TxMetadata txMetadata, CellMask requestMask, CellsWithMask retrievedCells) {}
}
