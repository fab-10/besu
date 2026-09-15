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
import org.hyperledger.besu.ethereum.core.kzg.BlobProofBundle;
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
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionsLimbo implements TransactionsAnnouncedListener {

  private static final Logger LOG = LoggerFactory.getLogger(TransactionsLimbo.class);

  //  private static final int MAX_BLOBS_PER_REQUEST = 64;
  private final Random random = new Random();
  private final EthContext ethContext;
  //  private final PeerTransactionTracker peerTransactionTracker;
  private final Supplier<CellMask> custodyColumnsSupplier;
  private final TransactionResubmitter transactionResubmitter;
  private final Map<Hash, TxMetadata> txMetadataByHash = new HashMap<>();
  //  private final Map<Hash, List<PeerAndCellMask>> unvalidated =
  //      new HashMap<>(); // ToDo: EIP-8070: make an LRU limited in size
  private final Map<Hash, List<PeerAndCellMask>> pcmByHash = new HashMap<>();
  private final Map<Hash, IncompleteBlob> incompleteBlobByHash = new HashMap<>();

  //  private final Map<CellMask, List<Hash>> fetchableBlobsByMask = new HashMap<>();

  public TransactionsLimbo(
      final EthContext ethContext,
      final PeerTransactionTracker peerTransactionTracker,
      final Supplier<CellMask> customColumnsSupplier,
      final TransactionResubmitter transactionResubmitter) {
    this.ethContext = ethContext;
    //    this.peerTransactionTracker = peerTransactionTracker;
    this.custodyColumnsSupplier = customColumnsSupplier;
    this.transactionResubmitter = transactionResubmitter;
  }

  public void addIncompleteBlob(
      final Transaction transaction,
      final boolean isLocal,
      final boolean hasPriority,
      final byte score) {
    final CellMask requestedCellMask = getCellMask();

    txMetadataByHash.put(transaction.getHash(), new TxMetadata(isLocal, hasPriority, score));
    incompleteBlobByHash.put(
        transaction.getHash(), new IncompleteBlob(transaction, CellsWithMask.EMPTY));

    if (hasEnoughAnnouncements(transaction.getHash(), requestedCellMask)) {
      processGetCells(new CellsRequest(transaction.getHash(), requestedCellMask));
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

  private void receivedBlobAnnouncement(
      final EthPeer peer, final TransactionAnnouncement blobAnnouncement) {
    final Hash txHash = blobAnnouncement.hash();

    List<PeerAndCellMask> wpcms = pcmByHash.computeIfAbsent(txHash, _ -> new ArrayList<>());
    wpcms.add(new PeerAndCellMask(peer, blobAnnouncement.cellMask()));
    final CellMask requestedCellMask = getCellMask();
    if (hasEnoughAnnouncements(wpcms, requestedCellMask)) {
      processGetCells(new CellsRequest(txHash, requestedCellMask));
    }
  }

  private boolean hasEnoughAnnouncements(final Hash txHash, final CellMask requestedCellMask) {
    final List<PeerAndCellMask> pcms = pcmByHash.getOrDefault(txHash, List.of());
    return hasEnoughAnnouncements(pcms, requestedCellMask);
  }

  private boolean hasEnoughAnnouncements(
      List<PeerAndCellMask> pcms, final CellMask requestedCellMask) {
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

  private Map<EthPeer, CellMask> getAnnouncingPeersFor(final Hash txHash, final CellMask cellMask) {
    final List<PeerAndCellMask> pcms = pcmByHash.get(txHash);
    if (pcms == null) {
      return Collections.emptyMap();
    }

    final Map<EthPeer, CellMask> selectedPeers = new HashMap<>();
    final Iterator<PeerAndCellMask> pcmIter = pcms.iterator();
    final CellMask remainingMask = cellMask.copy();
    while (pcmIter.hasNext() && !remainingMask.isEmpty()) {
      final PeerAndCellMask currPcm = pcmIter.next();
      final CellMask peerRequestMask = currPcm.cellMask().copy().intersect(cellMask);
      selectedPeers.put(currPcm.peer(), peerRequestMask);
      remainingMask.andNot(peerRequestMask);
    }

    if (remainingMask.isEmpty()) {
      pcms.subList(0, selectedPeers.size()).clear();
      return selectedPeers;
    }

    return Collections.emptyMap();
  }

  private void processGetCells(final CellsRequest request) {
    ethContext
        .getScheduler()
        .scheduleServiceTask(
            () -> {
              final IncompleteBlob incompleteBlob = incompleteBlobByHash.get(request.hash());
              final Transaction blobTx = incompleteBlob.tx;
              final Map<EthPeer, CellMask> selectedPeers =
                  getAnnouncingPeersFor(request.hash(), request.cellMask);

              for (final Map.Entry<EthPeer, CellMask> entry : selectedPeers.entrySet()) {
                ethContext
                    .getScheduler()
                    .scheduleServiceTaskDirect(
                        () -> {
                          final GetCellsFromPeerTask task =
                              new GetCellsFromPeerTask(blobTx, entry.getValue());
                          final PeerTaskExecutorResult<List<CellsWithMask>> response =
                              ethContext
                                  .getPeerTaskExecutor()
                                  .executeAgainstPeer(task, entry.getKey());

                          if (response.responseCode().equals(SUCCESS)
                              && response.result().isPresent()) {
                            // merge received masks
                            final List<CellsWithMask> result = response.result().get();

                            final CellMask receivedMask =
                                result.isEmpty() ? CellMask.EMPTY : result.getFirst().getCellMask();

                            if (receivedMask.equals(request.cellMask())) {
                              // complete the blob tx and resubmit
                              incompleteBlobByHash.remove(request.hash());
                              pcmByHash.remove(request.hash());

                              final Transaction completedTx = completeBlobs(blobTx, result);
                              final TxMetadata txMetadata = txMetadataByHash.get(request.hash());
                              transactionResubmitter.submit(
                                  completedTx,
                                  txMetadata.isLocal,
                                  txMetadata.hasPriority,
                                  txMetadata.score);
                              LOG.debug("Received all requested cells");
                            }

                          } else {
                            LOG.debug(
                                "Failed to get cells from peer {} for tx {}, reason {}",
                                entry.getKey(),
                                blobTx,
                                response.responseCode());
                          }
                        });
              }
            });
  }

  private Transaction completeBlobs(
      final Transaction incomplete, final List<CellsWithMask> result) {
    final BlobsWithCommitments incompleteBwc = incomplete.getBlobsWithCommitments().orElseThrow();
    final List<BlobProofBundle> incompleteBundles = incompleteBwc.getBlobProofBundles();

    final List<BlobProofBundle> completeBundles = new ArrayList<>(incompleteBundles.size());
    for (int i = 0; i < incompleteBundles.size(); i++) {
      final BlobProofBundle incompleteBundle = incompleteBundles.get(i);
      final BlobProofBundle completeBundle =
          new BlobProofBundle(
              incompleteBundle.getBlobType(),
              result.get(i),
              incompleteBundle.getKzgCommitment(),
              incompleteBundle.getKzgProof(),
              incompleteBundle.getVersionedHash());
      completeBundles.add(completeBundle);
    }

    return Transaction.builder()
        .copiedFrom(incomplete)
        .blobsWithCommitments(
            new BlobsWithCommitments(incompleteBwc.getBlobType(), completeBundles))
        .build();
  }

  private record CellsRequest(Hash hash, CellMask cellMask) {}

  private record PeerAndCellMask(EthPeer peer, CellMask cellMask) {}

  private record TxMetadata(boolean isLocal, boolean hasPriority, byte score) {}

  private record IncompleteBlob(Transaction tx, CellsWithMask retrievedCells) {}

  private record RetrievalOutcome() {}
}
