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

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELLS_TO_RECOVER_BLOB;
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELL_PROOFS_PER_BLOB;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.SUCCESS;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.chain.BlockAddedEvent;
import org.hyperledger.besu.ethereum.chain.BlockAddedObserver;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper;
import org.hyperledger.besu.ethereum.core.kzg.Cell;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetCellsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool.TransactionResubmitter;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;

import java.time.Duration;
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
import java.util.stream.IntStream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionsLimbo implements TransactionsAnnouncedListener, BlockAddedObserver {

  private static final Logger LOG = LoggerFactory.getLogger(TransactionsLimbo.class);

  /**
   * How much of what the limbo holds is kept, in KiB, before the least useful entries are dropped.
   *
   * <p>Weight rather than a count of entries: an entry holds nothing until a sampling round ends
   * short, and up to every cell of every blob of its transaction afterwards, so the two differ by
   * three orders of magnitude and a count would describe a ceiling anywhere between a few MiB and
   * more than a GiB.
   */
  private static final int MAX_INCOMPLETE_BLOBS_KIB = 32 * 1024;

  /** How many transactions may be tracked as announced but not yet received. */
  private static final int MAX_ANNOUNCED_BLOBS = 1_000;

  /**
   * How long a transaction is sampled for before it is given up on. The entry is rewritten by every
   * round that retrieves something, so this bounds the time without progress, not the total.
   */
  private static final Duration INCOMPLETE_BLOB_TTL = Duration.ofMinutes(5);

  /**
   * How long announcements are kept for a transaction that never arrives. Refreshed by every
   * announcement, so this bounds the silence, not the age.
   */
  private static final Duration ANNOUNCEMENT_TTL = Duration.ofMinutes(5);

  /** A cell is {@link Cell#SIZE} bytes, and the weigher counts in KiB. */
  private static final int KIB_PER_CELL = Cell.SIZE / 1024;

  /**
   * A blob costs this much in KiB whatever has been retrieved for it: {@link
   * CKZG4844Helper#CELL_PROOFS_PER_BLOB} proofs of 48 bytes each, plus its commitment and versioned
   * hash. The transaction body around them is noise by comparison.
   */
  private static final int KIB_PER_BLOB_SIDECAR = (CELL_PROOFS_PER_BLOB * 48 + 48 + 32) / 1024 + 1;

  private final Random random = new Random();
  private final EthContext ethContext;
  private final Supplier<CellMask> custodyColumnsSupplier;
  private final TransactionResubmitter transactionResubmitter;
  private final Predicate<Hash> isTransactionAlreadyPooled;
  private final Cache<Hash, Queue<PeerAndCellMask>> peersByHash;
  private final Cache<Hash, IncompleteBlob> incompleteBlobByHash;
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
    // Announcements are small and uniform, so a count is a fair bound for them; expiry is what
    // forgets the ones never followed by the transaction itself.
    this.peersByHash =
        Caffeine.newBuilder()
            .maximumSize(MAX_ANNOUNCED_BLOBS)
            .expireAfterAccess(ANNOUNCEMENT_TTL)
            .build();
    this.incompleteBlobByHash =
        Caffeine.newBuilder()
            .weigher(
                (Hash _, IncompleteBlob blob) ->
                    weighInKiB(blob.tx.getBlobCount(), heldCellsOf(blob)))
            .maximumWeight(MAX_INCOMPLETE_BLOBS_KIB)
            .expireAfterWrite(INCOMPLETE_BLOB_TTL)
            .build();
  }

  boolean isIncompleteBlob(final Transaction transaction) {
    if (transaction.getType().supportsBlob()) {
      final BlobsWithCommitments bwc = transaction.getBlobsWithCommitments().orElseThrow();
      if (bwc.hasBlobData()) {
        // case of blob retrieved in full by PooledTransaction version < eth/72
        // no need to fetch cells for it
        LOG.trace("Complete blob {} received from peer pre eth/72", transaction.getHash());
        removeTrackingFor(transaction.getHash());
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

    final Hash txHash = transaction.getHash();

    if (incompleteBlobByHash.getIfPresent(txHash) != null) {
      LOG.trace("Ignoring already known incomplete blob {}", txHash);
      return;
    }

    final CellMask requestMask = getCellMask();
    final TxMetadata txMetadata = new TxMetadata(isLocal, hasPriority, score);
    final IncompleteBlob incompleteBlob =
        new IncompleteBlob(transaction, txMetadata, requestMask, emptyList());

    synchronized (this) {
      incompleteBlobByHash.put(txHash, incompleteBlob);
      if (hasEnoughAnnouncements(txHash, requestMask)) {
        // get cells directly
        LOG.trace("Processing added incomplete blob directly {}", incompleteBlob);
        processGetCells(txHash);
      } else {
        // wait for more announcements
        LOG.trace("Added incomplete blob {} waiting for more announcements", incompleteBlob);
      }
    }
  }

  private synchronized void removeTrackingFor(final Hash hash) {
    peersByHash.invalidate(hash);
    incompleteBlobByHash.invalidate(hash);
    LOG.trace(
        "Removed tracking for hash {}, peersByHash size {}, incompleteBlobByHash size {}, inProgressGetCellsTaskByHash {}",
        hash,
        peersByHash.estimatedSize(),
        incompleteBlobByHash.estimatedSize(),
        inProgressGetCellsTaskByHash);
  }

  @Override
  public void onBlockAdded(final BlockAddedEvent event) {
    event.getAddedTransactions().stream()
        .map(Transaction::getHash)
        .forEach(this::removeTrackingFor);
  }

  @Override
  public void onTransactionsAnnounced(
      final EthPeer peer, final List<TransactionAnnouncement> announcements) {
    announcements.stream()
        .filter(txAnnouncement -> txAnnouncement.type().supportsBlob())
        .filter(_ -> supportsEth72(peer))
        .forEach(txAnnouncement -> receivedSparseBlobAnnouncement(peer, txAnnouncement));
  }

  private boolean supportsEth72(final EthPeer peer) {
    if (peer.getAgreedCapabilities().stream().anyMatch(EthProtocol::isEth72Compatible)) {
      return true;
    }
    LOG.atTrace()
        .setMessage("Ignoring announcement from peer with capability not supporting eth/72: {}")
        .addArgument(() -> logPeer(peer))
        .log();
    return false;
  }

  private void receivedSparseBlobAnnouncement(
      final EthPeer peer, final TransactionAnnouncement blobAnnouncement) {
    final Hash txHash = blobAnnouncement.hash();

    if (isTransactionAlreadyPooled.test(txHash)) {
      removeTrackingFor(txHash);
      LOG.atTrace()
          .setMessage("Ignoring announcement for already pooled tx {} from peer {}")
          .addArgument(txHash)
          .addArgument(() -> logPeer(peer))
          .log();
    } else {
      synchronized (this) {
        Queue<PeerAndCellMask> wpcms = peersByHash.get(txHash, _ -> new ConcurrentLinkedQueue<>());
        wpcms.add(new PeerAndCellMask(peer, blobAnnouncement.cellMask()));
        final IncompleteBlob incompleteBlob = incompleteBlobByHash.getIfPresent(txHash);
        if (incompleteBlob != null) {
          if (hasEnoughAnnouncements(wpcms, incompleteBlob.requestMask)) {
            processGetCells(txHash);
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
    final Queue<PeerAndCellMask> pcms = peersByHash.getIfPresent(txHash);
    return pcms != null && hasEnoughAnnouncements(pcms, requestedCellMask);
  }

  private boolean hasEnoughAnnouncements(
      final Queue<PeerAndCellMask> pcms, final CellMask requestedCellMask) {
    if (pcms.size() < 2) {
      return false;
    }

    final Iterator<PeerAndCellMask> it = pcms.iterator();
    final CellMask unionMask = it.next().cellMask.copy();
    while (!unionMask.containsAll(requestedCellMask)) {
      if (!it.hasNext()) {
        return false;
      }
      unionMask.merge(it.next().cellMask);
    }

    return true;
  }

  @SuppressWarnings("MixedMutabilityReturnType")
  private Map<EthPeer, CellMask> getAnnouncingPeersFor(final Hash txHash, final CellMask cellMask) {
    final Queue<PeerAndCellMask> pcms = peersByHash.getIfPresent(txHash);

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

  private void processGetCells(final Hash txHash) {
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

              // need to re-check since this is executed async
              if (alreadyInProgress.get()) {
                LOG.trace(
                    "Skipping this get cells task since an existing one is already in progress task for blob {}, existing task {}",
                    txHash,
                    getCellsTasks);
                return;
              }

              try {
                // Not the blob captured when this task was scheduled: another round may have run
                // in between, narrowing the request and retrieving cells, or completing the
                // transaction altogether. Reading it here is what keeps progress moving forward.
                final IncompleteBlob trackedBlob = incompleteBlobByHash.getIfPresent(txHash);
                if (trackedBlob == null) {
                  LOG.trace("Blob {} is no longer tracked, nothing to sample", txHash);
                  return;
                }

                final Transaction blobTx = trackedBlob.tx;
                // What this round still has to fetch. It narrows as cells arrive, and if the round
                // ends short it becomes the request mask of the IncompleteBlob put back in the
                // cache, so a later announcement retries only the gap.
                final CellMask missingMask = trackedBlob.requestMask.copy();
                // One accumulator per blob, each owned by this transaction: they are merged into
                // below, so they must not be shared with any other transaction.
                final List<CellsWithMask> mergedReceivedCells = accumulatorsFor(trackedBlob);

                do {

                  final Map<EthPeer, CellMask> selectedPeers =
                      getAnnouncingPeersFor(blobTx.getHash(), missingMask);
                  if (selectedPeers.isEmpty()) {
                    LOG.trace("No more peers available for blob {}", trackedBlob);
                    break;
                  }

                  for (final Map.Entry<EthPeer, CellMask> entry : selectedPeers.entrySet()) {
                    LOG.atTrace()
                        .setMessage("Get cells for tx {} from peer {} with request mask {}")
                        .addArgument(trackedBlob)
                        .addArgument(() -> logPeer(entry.getKey()))
                        .addArgument(entry::getValue)
                        .log();

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
                                        LOG.atTrace()
                                            .setMessage(
                                                "Task for {} {}, completed with result {} and throwable")
                                            .addArgument(() -> logPeer(entry.getKey()))
                                            .addArgument(entry::getValue)
                                            .addArgument(cellsWithMasks)
                                            .setCause(throwable)
                                            .log())));
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
                          trackedBlob,
                          inProgressTask,
                          e);
                    }
                  }

                  // every cell now held stops being missing, including any this round was not
                  // asking for, so the loop ends as soon as nothing is left to fetch
                  missingMask.andNot(mergedReceivedCells.getFirst().getCellMask());

                } while (!missingMask.isEmpty());

                if (missingMask.isEmpty()) {
                  // blob tx has all the requested cells, resubmit it to the pool
                  final Transaction cellsAddedTx =
                      recoverBlobsIfEnoughCells(addCellsToBlobs(blobTx, mergedReceivedCells));
                  final TxMetadata txMetadata = trackedBlob.txMetadata;
                  transactionResubmitter.submit(
                      cellsAddedTx, txMetadata.isLocal, txMetadata.hasPriority, txMetadata.score);
                  removeTrackingFor(blobTx.getHash());
                  LOG.debug("Received all requested cells for tx {}", trackedBlob);
                } else {
                  // keep what did arrive, so a later announcement only has to cover the gap
                  final IncompleteBlob updatedIncompleteBlob =
                      trackedBlob.withPartialCells(missingMask, mergedReceivedCells);
                  incompleteBlobByHash.put(blobTx.getHash(), updatedIncompleteBlob);
                  LOG.debug(
                      "Retrieved only part of the cells for tx {}, waiting for more announcements",
                      updatedIncompleteBlob);
                }
              } finally {
                inProgressGetCellsTaskByHash.remove(txHash);
              }
            })
        .whenComplete(
            (cellsWithMasks, throwable) ->
                LOG.trace(
                    "Get cells for blob {}, completed with result {} and throwable",
                    txHash,
                    cellsWithMasks,
                    throwable));
  }

  /**
   * What an entry costs, in KiB, which is what {@link #MAX_INCOMPLETE_BLOBS_KIB} bounds.
   *
   * @param blobCount how many blobs the transaction carries
   * @param heldCells how many cells have been retrieved, counted across all of its blobs
   * @return its size in KiB, never zero, so that every entry remains evictable
   */
  static int weighInKiB(final int blobCount, final int heldCells) {
    return blobCount * KIB_PER_BLOB_SIDECAR + heldCells * KIB_PER_CELL;
  }

  private static int heldCellsOf(final IncompleteBlob incompleteBlob) {
    return incompleteBlob.retrievedCells.stream().mapToInt(cells -> cells.getCells().size()).sum();
  }

  /**
   * Fresh accumulators, one per blob, preloaded with the cells earlier rounds already retrieved so
   * that this round only has to fetch the rest.
   *
   * <p>They are new instances merged from the stored ones rather than the stored ones themselves:
   * {@link CellsWithMask} is mutable, and a round that timed out can still be running when the next
   * one starts, so the copy held in the cache must not be written to from here.
   *
   * @param incompleteBlob the blob being sampled
   * @return one accumulator per blob of the transaction
   */
  private List<CellsWithMask> accumulatorsFor(final IncompleteBlob incompleteBlob) {
    final List<CellsWithMask> retrievedCells = incompleteBlob.retrievedCells;
    return IntStream.range(0, incompleteBlob.tx.getBlobCount())
        .mapToObj(
            blobIndex -> {
              final CellsWithMask accumulator = CellsWithMask.empty();
              if (blobIndex < retrievedCells.size()) {
                accumulator.merge(retrievedCells.get(blobIndex));
              }
              return accumulator;
            })
        .toList();
  }

  private List<CellsWithMask> retrieveCellsFromPeer(
      final EthPeer peer, final Transaction blobTx, final CellMask mask) {
    if (isTransactionAlreadyPooled.test(blobTx.getHash())) {
      removeTrackingFor(blobTx.getHash());
      LOG.atTrace()
          .setMessage("Skip get cells for already pooled tx {} from peer {}")
          .addArgument(blobTx::getHash)
          .addArgument(() -> logPeer(peer))
          .log();
      return List.of();
    }

    final GetCellsFromPeerTask task = new GetCellsFromPeerTask(blobTx, mask);
    final PeerTaskExecutorResult<List<CellsWithMask>> response =
        ethContext.getPeerTaskExecutor().executeAgainstPeer(task, peer);

    // GetCellsFromPeerTask verifies that the cells open their commitments, so a peer answering
    // with cells of its own invention has already been disconnected by the time we get here.
    if (response.responseCode().equals(SUCCESS) && response.result().isPresent()) {
      return response.result().get();
    }

    LOG.atDebug()
        .setMessage("Failed to get cells from peer {} for tx {} mask {}, reason {}")
        .addArgument(() -> logPeer(peer))
        .addArgument(blobTx::getHash)
        .addArgument(mask)
        .addArgument(response::responseCode)
        .log();
    return List.of();
  }

  private Transaction addCellsToBlobs(
      final Transaction blobTx, final List<CellsWithMask> receivedCells) {
    final BlobsWithCommitments incompleteBwc = blobTx.getBlobsWithCommitments().orElseThrow();

    return Transaction.builder()
        .copiedFrom(blobTx)
        .blobsWithCommitments(
            BlobsWithCommitments.createFromBlobCells(
                incompleteBwc.getKzgCommitments(),
                receivedCells,
                incompleteBwc.getKzgProofs(),
                incompleteBwc.getVersionedHashes()))
        .build();
  }

  /**
   * Rebuilds the blobs themselves when enough cells were sampled to do so.
   *
   * <p>Cells alone make a transaction poolable and servable, but not buildable: a block carries the
   * blobs, so until they exist this node has to keep the transaction out of the blocks it builds.
   * Recovery is what ends that, and it also yields the cells that were never sampled, so the node
   * goes on to serve every one of them.
   *
   * <p>A custody mask narrower than half the cells cannot be recovered from, which is inherent to
   * sampling rather than a gap: a node that wants to build with a blob transaction has to fetch at
   * least {@link CKZG4844Helper#CELLS_TO_RECOVER_BLOB} of its cells.
   *
   * @param sampledTx a transaction holding the cells a round retrieved
   * @return the same transaction with its blobs recovered, or unchanged if too few cells are held
   */
  private Transaction recoverBlobsIfEnoughCells(final Transaction sampledTx) {
    final BlobsWithCommitments sampledBwc = sampledTx.getBlobsWithCommitments().orElseThrow();
    final int heldCells = sampledBwc.getCellMask().cardinality();
    if (heldCells < CELLS_TO_RECOVER_BLOB) {
      LOG.trace(
          "Holding {} cells of tx {}, too few to recover its blobs",
          heldCells,
          sampledTx.getHash());
      return sampledTx;
    }

    return Transaction.builder()
        .copiedFrom(sampledTx)
        .blobsWithCommitments(CKZG4844Helper.recoverBlobs(sampledBwc))
        .build();
  }

  private static String logPeer(final EthPeer peer) {
    return peer.getLoggableId()
        + " "
        + peer.getConnection().getPeerInfo().getClientId()
        + " "
        + peer.getAgreedCapabilities().stream()
            .map(Capability::toString)
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
  }

  private record PeerAndCellMask(EthPeer peer, CellMask cellMask) {
    @Override
    public @NonNull String toString() {
      return logPeer(peer) + " " + cellMask;
    }
  }

  private record TxMetadata(boolean isLocal, boolean hasPriority, byte score) {}

  private record InProgressGetCellsTask(
      EthPeer peer, CellMask cellMask, CompletableFuture<List<CellsWithMask>> future) {}

  /**
   * A blob transaction waiting for its cells.
   *
   * @param id order of arrival, for tracing
   * @param tx the transaction, whose sidecar holds no cells of its own
   * @param txMetadata what the pool needs to re-add it once it is complete
   * @param requestMask the cells still to fetch, not the ones originally wanted: it narrows as
   *     rounds retrieve cells, so a later announcement only retries the gap
   * @param retrievedCells what earlier rounds did retrieve, one entry per blob, empty until the
   *     first round ends short
   */
  private record IncompleteBlob(
      long id,
      Transaction tx,
      TxMetadata txMetadata,
      CellMask requestMask,
      List<CellsWithMask> retrievedCells) {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    public IncompleteBlob(
        final Transaction tx,
        final TxMetadata txMetadata,
        final CellMask requestMask,
        final List<CellsWithMask> retrievedCells) {
      this(SEQUENCE.getAndIncrement(), tx, txMetadata, requestMask, retrievedCells);
    }

    private IncompleteBlob withPartialCells(
        final CellMask missingMask, final List<CellsWithMask> retrievedCells) {
      return new IncompleteBlob(id, tx, txMetadata, missingMask, retrievedCells);
    }

    @Override
    public @NonNull String toString() {
      return "["
          + id
          + "] tx="
          + tx.getHash()
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
