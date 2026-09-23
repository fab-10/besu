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

import static java.util.Collections.emptyMap;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.SUCCESS;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetCellsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool.TransactionResubmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionsLimbo implements TransactionsAnnouncedListener {

  private static final Logger LOG = LoggerFactory.getLogger(TransactionsLimbo.class);
  private final Random random = new Random();
  private final AtomicLong sequence = new AtomicLong(0);
  private final EthContext ethContext;
  private final Supplier<CellMask> custodyColumnsSupplier;
  private final TransactionResubmitter transactionResubmitter;
  private final Predicate<Hash> isTransactionAlreadyPooled;
  private final Map<Hash, Queue<PeerAndCellMask>> peersByHash = new ConcurrentHashMap<>();
  private final Map<Hash, IncompleteBlob> incompleteBlobByHash = new ConcurrentHashMap<>();
  private final Map<Hash, List<InProgressGetCellsTask>> inProgressGetCellsTaskByHash =
      new ConcurrentHashMap<>();

  TransactionsLimbo(
      final EthContext ethContext,
      final Supplier<CellMask> customColumnsSupplier,
      final TransactionResubmitter transactionResubmitter,
      final Predicate<Hash> isTransactionAlreadyPooled) {
    this.ethContext = ethContext;
    this.custodyColumnsSupplier = customColumnsSupplier;
    this.transactionResubmitter = transactionResubmitter;
    this.isTransactionAlreadyPooled = isTransactionAlreadyPooled;
  }

  boolean isIncompleteBlob(final Transaction transaction) {
    if (transaction.getType().supportsBlob()) {
      final BlobsWithCommitments bwc = transaction.getBlobsWithCommitments().orElseThrow();
      if (bwc.hasBlobData()) {
        // case of blob retrieved in full by PooledTransaction version < eth/72
        // no need to fetch cells for it
        removeTrackingFor(transaction.getHash());
        LOG.trace("Complete blob {} received from peer pre eth/72", transaction.getHash());
        return false;
      }
      return bwc.getCellMask().isEmpty();
    }
    return false;
  }

  void addIncompleteBlob(
      final Transaction transaction,
      final boolean isLocal,
      final boolean hasPriority,
      final byte score) {

    if (incompleteBlobByHash.containsKey(transaction.getHash())) {
      LOG.trace("Ignoring already known incomplete blob {}", transaction.getHash());
      return;
    }

    final CellMask requestMask = getCellMask();
    final TxMetadata txMetadata = new TxMetadata(isLocal, hasPriority, score);
    final IncompleteBlob incompleteBlob =
        new IncompleteBlob(
            sequence.getAndIncrement(),
            transaction,
            txMetadata,
            requestMask,
            CellsWithMask.empty());

    synchronized (this) {
      incompleteBlobByHash.put(transaction.getHash(), incompleteBlob);
      if (hasEnoughAnnouncements(transaction.getHash(), requestMask)) {
        // get cells directly
        LOG.trace("Processing added incomplete blob directly {}", incompleteBlob);
        processGetCells(incompleteBlob);
      } else {
        // wait for more announcements
        LOG.trace("Added incomplete blob {} waiting for more announcements", incompleteBlob);
      }
    }
  }

  private synchronized void removeTrackingFor(final Hash hash) {
    peersByHash.remove(hash);
    incompleteBlobByHash.remove(hash);
    LOG.trace(
        "Removed tracking for hash {}, peersByHash size {}, incompleteBlobByHash size {}, inProgressGetCellsTaskByHash {}",
        hash,
        peersByHash,
        incompleteBlobByHash,
        inProgressGetCellsTaskByHash);
  }

  @Override
  public void onTransactionsAnnounced(
      final EthPeer peer, final List<TransactionAnnouncement> announcements) {
    announcements.stream()
        .filter(txAnnouncement -> txAnnouncement.type().supportsBlob())
        .filter(_ -> supportsEth72(peer))
        .forEach(txAnnouncement -> receivedSparseBlobAnnouncement(peer, txAnnouncement));
  }

  private static boolean supportsEth72(final EthPeer peer) {
    if (peer.getAgreedCapabilities().stream().anyMatch(EthProtocol::isEth72Compatible)) {
      return true;
    }
    LOG.debug("Ignoring announcement from peer with capability not supporting eth/72: {}", peer);
    return false;
  }

  private void receivedSparseBlobAnnouncement(
      final EthPeer peer, final TransactionAnnouncement blobAnnouncement) {
    final Hash txHash = blobAnnouncement.hash();

    if (isTransactionAlreadyPooled.test(txHash)) {
      removeTrackingFor(txHash);
      LOG.trace("Ignoring announcement for already pooled tx {} from peer {}", txHash, peer);
    } else {
      synchronized (this) {
        Queue<PeerAndCellMask> wpcms =
            peersByHash.computeIfAbsent(txHash, _ -> new ConcurrentLinkedQueue<>());
        wpcms.add(new PeerAndCellMask(peer, blobAnnouncement.cellMask()));
        final IncompleteBlob incompleteBlob = incompleteBlobByHash.remove(txHash);
        if (incompleteBlob != null) {
          if (hasEnoughAnnouncements(wpcms, incompleteBlob.requestMask)) {
            processGetCells(incompleteBlob);
          } else {
            LOG.trace(
                "New blob announcements {} for tx {} with incomplete blob {} has not enough announcements {}",
                wpcms.size(),
                txHash,
                incompleteBlob,
                wpcms);
          }
        } else {
          LOG.trace(
              "New blob announcements {} for tx {} w/o incomplete blob; announcements {}",
              wpcms.size(),
              txHash,
              wpcms);
        }
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
    final Queue<PeerAndCellMask> pcms = peersByHash.get(txHash);
    return pcms != null && hasEnoughAnnouncements(pcms, requestedCellMask);
  }

  private boolean hasEnoughAnnouncements(
      final Queue<PeerAndCellMask> pcms, final CellMask requestedCellMask) {
    if (pcms.size() < 2) {
      return false;
    }

    final Iterator<PeerAndCellMask> it = pcms.iterator();
    // verify if requested cells are covered by the union of all the peers' cell masks
    CellMask unionMask = it.next().cellMask.copy();
    while (it.hasNext()) {
      if (unionMask.containsAll(requestedCellMask)) {
        return true;
      }
      unionMask.merge(it.next().cellMask);
    }

    return false;
  }

  @SuppressWarnings("MixedMutabilityReturnType")
  private Map<EthPeer, CellMask> getAnnouncingPeersFor(final Hash txHash, final CellMask cellMask) {
    final Queue<PeerAndCellMask> pcms = peersByHash.get(txHash);

    if (pcms == null) {
      return emptyMap();
    }

    final Map<EthPeer, CellMask> selectedPeers = new HashMap<>();
    final CellMask remainingMask = cellMask.copy();

    while (!pcms.isEmpty() && !remainingMask.isEmpty()) {
      final PeerAndCellMask currPcm = pcms.poll();
      final CellMask peerRequestMask = currPcm.cellMask().copy();
      peerRequestMask.intersect(remainingMask);
      if (peerRequestMask.isEmpty()) {
        continue;
      }
      selectedPeers.put(currPcm.peer(), peerRequestMask);
      remainingMask.andNot(peerRequestMask);
    }

    if (remainingMask.isEmpty()) {
      return selectedPeers;
    }

    return emptyMap();
  }

  private void processGetCells(final IncompleteBlob incompleteBlob) {
    final Hash txHash = incompleteBlob.tx.getHash();
    final List<InProgressGetCellsTask> inProgressTasks = inProgressGetCellsTaskByHash.get(txHash);
    if (inProgressTasks != null) {
      LOG.trace(
          "Skipping this get cells task since an existing one is already in progress task for blob {}, existing task {}",
          incompleteBlob,
          inProgressTasks);
      return;
    }

    ethContext
        .getScheduler()
        .scheduleServiceTask(
            () -> {
              final AtomicBoolean alreadyInProgress = new AtomicBoolean(true);

              final List<InProgressGetCellsTask> getCellsTasks =
                  inProgressGetCellsTaskByHash.computeIfAbsent(
                      txHash,
                      _ -> {
                        alreadyInProgress.set(false);
                        return new ArrayList<>();
                      });

              try {
                // need to re-check since this is executed async
                if (alreadyInProgress.get()) {
                  LOG.trace(
                      "Skipping this get cells task since an existing one is already in progress task for blob {}, existing task {}",
                      incompleteBlob,
                      getCellsTasks);
                  return;
                }

                final Transaction blobTx = incompleteBlob.tx;
                // One accumulator per blob, each owned by this transaction: they are merged into
                // below, so they must not be shared with any other transaction.
                final List<CellsWithMask> mergedReceivedCells =
                    Stream.generate(CellsWithMask::empty).limit(blobTx.getBlobCount()).toList();

                do {

                  final Map<EthPeer, CellMask> selectedPeers =
                      getAnnouncingPeersFor(blobTx.getHash(), incompleteBlob.requestMask);
                  if (selectedPeers.isEmpty()) {
                    LOG.trace("No more peers available for blob {}", incompleteBlob);
                    break;
                  }

                  for (final Map.Entry<EthPeer, CellMask> entry : selectedPeers.entrySet()) {
                    LOG.trace(
                        "Get cells for tx {} from peer {} with request mask {}",
                        incompleteBlob,
                        entry.getKey(),
                        entry.getValue());
                    getCellsTasks.add(
                        new InProgressGetCellsTask(
                            entry.getKey(),
                            entry.getValue(),
                            ethContext
                                .getScheduler()
                                .scheduleTxWorkerTask(
                                    () ->
                                        retrieveCellsFromPeer(
                                            entry.getKey(), blobTx, entry.getValue()))
                                .whenComplete(
                                    (cellsWithMasks, throwable) ->
                                        LOG.trace(
                                            "Task for {} {}, completed with result {} and throwable",
                                            entry.getKey(),
                                            entry.getValue(),
                                            cellsWithMasks,
                                            throwable))));
                  }

                  for (final InProgressGetCellsTask inProgressTask : getCellsTasks) {
                    try {
                      final List<CellsWithMask> receivedCells =
                          inProgressTask.future.get(10, TimeUnit.SECONDS);
                      for (int i = 0; i < receivedCells.size(); i++) {
                        mergedReceivedCells.get(i).merge(receivedCells.get(i));
                      }
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                      LOG.debug(
                          "Failed to retrieve cells for blob {} for task {}",
                          incompleteBlob,
                          inProgressTask,
                          e);
                    }
                  }

                } while (!mergedReceivedCells
                    .getFirst()
                    .getCellMask()
                    .equals(incompleteBlob.requestMask));

                if (mergedReceivedCells
                    .getFirst()
                    .getCellMask()
                    .equals(incompleteBlob.requestMask)) {
                  // complete the blob tx and resubmit to pool
                  final Transaction completedTx = completeBlobs(blobTx, mergedReceivedCells);
                  final TxMetadata txMetadata = incompleteBlob.txMetadata;
                  transactionResubmitter.submit(
                      completedTx, txMetadata.isLocal, txMetadata.hasPriority, txMetadata.score);
                  removeTrackingFor(blobTx.getHash());
                  LOG.debug("Received all requested cells for tx {}", incompleteBlob);
                } else {
                  LOG.debug("Unable to retrieve cells for tx {}", incompleteBlob);
                }
              } finally {
                inProgressGetCellsTaskByHash.remove(txHash);
              }
            })
        .whenComplete(
            (cellsWithMasks, throwable) ->
                LOG.trace(
                    "Get cells for blob {}, completed with result {} and throwable",
                    incompleteBlob,
                    cellsWithMasks,
                    throwable));
  }

  private List<CellsWithMask> retrieveCellsFromPeer(
      final EthPeer peer, final Transaction blobTx, final CellMask mask) {
    if (isTransactionAlreadyPooled.test(blobTx.getHash())) {
      removeTrackingFor(blobTx.getHash());
      LOG.trace("Skip get cells for already pooled tx {} from peer {}", blobTx.getHash(), peer);
      return List.of();
    }

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

  private record InProgressGetCellsTask(
      EthPeer peer, CellMask cellMask, CompletableFuture<List<CellsWithMask>> future) {}

  private record IncompleteBlob(
      long sequence,
      Transaction tx,
      TxMetadata txMetadata,
      CellMask requestMask,
      CellsWithMask retrievedCells) {

    @Override
    public String toString() {
      return "["
          + sequence
          + "] tx="
          + tx.toTraceLog()
          + ", txMetadata="
          + txMetadata
          + ", requestMask="
          + requestMask
          + ", retrievedCells="
          + retrievedCells
          + '}';
    }
  }
}
