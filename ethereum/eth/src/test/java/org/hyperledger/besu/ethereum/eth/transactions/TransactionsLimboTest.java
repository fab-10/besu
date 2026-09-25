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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper.CELLS_TO_RECOVER_BLOB;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.INVALID_RESPONSE;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.SUCCESS;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.TIMEOUT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.chain.BlockAddedEvent;
import org.hyperledger.besu.ethereum.core.BlobTestFixture;
import org.hyperledger.besu.ethereum.core.CellsOnlyBlobTransactionFixture;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.core.kzg.CKZG4844Helper;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.core.kzg.CellsWithMask;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetCellsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.messages.GetCellsMessage;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;
import org.hyperledger.besu.ethereum.util.TrustedSetupClassLoaderExtension;
import org.hyperledger.besu.testutil.DeterministicEthScheduler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Sampling of a blob transaction received over eth/72, which arrives carrying no cells and has to
 * collect them from the peers that announced it.
 */
class TransactionsLimboTest extends TrustedSetupClassLoaderExtension {

  private static final byte SCORE = 127;

  private final PeerTaskExecutor peerTaskExecutor = mock(PeerTaskExecutor.class);
  private final EthContext ethContext = mock(EthContext.class);
  private final TransactionPool.TransactionResubmitter resubmitter =
      mock(TransactionPool.TransactionResubmitter.class);

  /** One fixture for the whole test: each call to it yields a different blob. */
  private final BlobTestFixture blobTestFixture = new BlobTestFixture();

  /** The complete sidecar the peers are answering out of. */
  private BlobsWithCommitments fullSidecar;

  /** Cells of a different blob, which a lying peer answers with. */
  private CellsWithMask otherBlobCells;

  /** Its cells, which is what a peer serves a slice of. */
  private CellsWithMask realCells;

  /** The mask each peer is willing to serve, or absent when it answers nothing. */
  private final Map<EthPeer, Optional<CellMask>> servedByPeer = new HashMap<>();

  /** Peers that answer with cells belonging to a different blob. */
  private final Set<EthPeer> liars = new HashSet<>();

  /** The mask each peer announced. */
  private final Map<EthPeer, CellMask> announcedByPeer = new HashMap<>();

  /** Every (peer, mask) actually requested, in order. */
  private final List<Map.Entry<EthPeer, CellMask>> requests = new ArrayList<>();

  /** Every sampling round scheduled, so that none is allowed to end in an exception. */
  private final List<CompletableFuture<Void>> rounds = new ArrayList<>();

  private final DeterministicEthScheduler scheduler =
      new DeterministicEthScheduler() {
        @Override
        public CompletableFuture<Void> scheduleServiceTask(final Runnable task) {
          final CompletableFuture<Void> round = super.scheduleServiceTask(task);
          rounds.add(round);
          return round;
        }
      };

  private TransactionsLimbo limbo;
  private Transaction blobTx;

  @BeforeEach
  void setUp() {
    when(ethContext.getScheduler()).thenReturn(scheduler);
    when(ethContext.getPeerTaskExecutor()).thenReturn(peerTaskExecutor);
    when(peerTaskExecutor.executeAgainstPeer(any(), any())).thenAnswer(this::serveCells);

    limbo =
        new TransactionsLimbo(
            ethContext,
            // every cell is wanted, so the request is the same whichever branch of
            // TransactionsLimbo#getCellMask is taken - and it is then capped to half of them
            CellMask.FULL::copy,
            resubmitter,
            _ -> false,
            // seeded, so which half is asked for is at least the same from run to run
            new Random(1));

    // Genuine KZG material, because a completed sampling round recovers the blobs from the cells
    // it gathered and that only works on real ones.
    fullSidecar = CKZG4844Helper.convertToVersion1(blobTestFixture.createBlobsWithCommitments(1));
    realCells = fullSidecar.getBlobProofBundles().getFirst().getCellsWithMask().orElseThrow();

    // As decoded from an eth/72 PooledTransactions response: commitments and proofs, no cells.
    blobTx =
        new CellsOnlyBlobTransactionFixture()
            .create(
                BlobsWithCommitments.createFromBlobCells(
                    fullSidecar.getKzgCommitments(),
                    List.of(CellsWithMask.empty()),
                    fullSidecar.getKzgProofs(),
                    fullSidecar.getVersionedHashes()));
  }

  @AfterEach
  void roundsEndCleanly() {
    // A round that throws would leave no other trace: its future is discarded and the failure only
    // reaches a log line, so every test asserts the absence of one.
    assertThat(rounds).allMatch(round -> !round.isCompletedExceptionally());
  }

  @Test
  void penalisesAPeerRepeatingAnAnnouncementItAlreadyMade() {
    final EthPeer repeats = announcingPeer(range(0, 64), range(0, 64));
    announce(repeats);
    announce(repeats);

    verify(repeats).recordUselessResponse(any());

    // And the repeat adds nothing: a second peer is still needed before anything is sampled.
    assertThat(requests).isEmpty();
  }

  @Test
  void takesAnUpdatedAnnouncementFromAPeerWithoutPenalisingIt() {
    final EthPeer widens = announcingPeer(range(0, 32), range(0, 64));
    announce(widens);
    announcedByPeer.put(widens, range(32, 64));
    announce(widens);

    verify(widens, never()).recordUselessResponse(any());

    // The two masks were taken together: one peer now covers the whole lower half, so adding a
    // second announcing the upper half is enough to sample the whole request.
    announce(announcingPeer(range(64, 128), range(64, 128)));
    limbo.addIncompleteBlob(blobTx, false, false, SCORE);
    assertThat(unionOfRequests().cardinality()).isEqualTo(CELLS_TO_RECOVER_BLOB);
  }

  @Test
  void doesNotWidenWhatAPeerHoldsForItsOtherAnnouncements() {
    // Every announcement decoded from one message shares a single CellMask instance, so merging an
    // update must not reach the masks recorded for the transactions announced alongside.
    final EthPeer peer = announcingPeer(range(0, 32), range(0, 32));
    final CellMask sharedByTheWholeMessage = announcedByPeer.get(peer);
    announce(peer);

    limbo.onTransactionsAnnounced(
        peer,
        List.of(
            new TransactionAnnouncement(
                blobTx.getHash(),
                blobTx.getType(),
                (long) blobTx.getSizeForEth72Announcement(),
                range(32, 64))));

    assertThat(sharedByTheWholeMessage).isEqualTo(range(0, 32));
  }

  @Test
  void asksForHalfTheCellsEvenWhenItWantsThemAll() {
    // Custody is every cell here, so without the cap the request would be all 128 of them.
    announce(
        announcingPeer(CellMask.FULL, CellMask.FULL), announcingPeer(CellMask.FULL, CellMask.FULL));

    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    assertThat(requests).hasSize(1);
    final CellMask requested = requests.getFirst().getValue();
    assertThat(requested.cardinality()).isEqualTo(CELLS_TO_RECOVER_BLOB);
    assertThat(requested.isFull()).isFalse();

    // Half the cells off the wire, and the node still ends up holding every one of them, because
    // the rest are recovered rather than fetched.
    final BlobsWithCommitments resubmitted = resubmittedSidecar();
    assertThat(resubmitted.getCellMask()).isEqualTo(CellMask.FULL);
    assertThat(resubmitted.hasBlobData()).isTrue();
  }

  @Test
  void retriesOnlyTheCellsStillMissing() {
    final EthPeer answers = announcingPeer(range(0, 64), range(0, 64));
    final EthPeer staysSilent = announcingPeer(range(64, 128), null);
    announce(answers, staysSilent);

    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    // The request was divided between the two, neither asked for what the other was covering.
    assertThat(requests).hasSize(2);
    final CellMask askedOfAnswerer = requestedOf(answers);
    final CellMask askedOfSilent = requestedOf(staysSilent);
    assertThat(intersectionOf(askedOfAnswerer, askedOfSilent).isEmpty()).isTrue();

    // Only half of it arrived, so the transaction is kept for later rather than handed back.
    verify(resubmitter, never()).submit(any(), anyBoolean(), anyBoolean(), anyByte());

    requests.clear();
    announce(
        announcingPeer(CellMask.FULL, CellMask.FULL), announcingPeer(CellMask.FULL, CellMask.FULL));

    // Exactly what the silent peer never delivered is asked for again, and nothing else, even
    // though the peer answering now announced every cell.
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().getValue()).isEqualTo(askedOfSilent);

    // The cells of the first round were kept, so between them the transaction is complete.
    assertThat(resubmittedCellMask()).isEqualTo(CellMask.FULL);
  }

  @Test
  void keepsTheWholeRequestWhenNothingArrives() {
    final EthPeer silent = announcingPeer(range(0, 64), null);
    final EthPeer alsoSilent = announcingPeer(range(64, 128), null);
    announce(silent, alsoSilent);

    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    final CellMask everythingAskedFor = unionOfRequests();
    verify(resubmitter, never()).submit(any(), anyBoolean(), anyBoolean(), anyByte());

    requests.clear();
    announce(
        announcingPeer(CellMask.FULL, CellMask.FULL), announcingPeer(CellMask.FULL, CellMask.FULL));

    // Nothing was retrieved, so nothing was struck off the request: the retry asks for all of it.
    assertThat(unionOfRequests()).isEqualTo(everythingAskedFor);
    assertThat(resubmittedCellMask()).isEqualTo(CellMask.FULL);
  }

  @Test
  void doesNotSampleABlobThatStoppedBeingTrackedWhileTheRoundWasQueued() {
    // A round reads the blob it is about to sample when it runs, not when it was scheduled, so a
    // transaction mined in between is not sampled — and, more to the point, is not put back into
    // the limbo by the round that was already on its way.
    scheduler.mockServiceExecutor().setAutoRun(false);
    announce(
        announcingPeer(CellMask.FULL, CellMask.FULL), announcingPeer(CellMask.FULL, CellMask.FULL));
    limbo.addIncompleteBlob(blobTx, false, false, SCORE);
    assertThat(requests).isEmpty();

    final BlockAddedEvent blockWithTheTransaction = mock(BlockAddedEvent.class);
    when(blockWithTheTransaction.getAddedTransactions()).thenReturn(List.of(blobTx));
    limbo.onBlockAdded(blockWithTheTransaction);

    scheduler.runPendingFutures();

    assertThat(requests).isEmpty();
    verify(resubmitter, never()).submit(any(), anyBoolean(), anyBoolean(), anyByte());
  }

  @Test
  void rebuildsTheBlobsOnceEnoughCellsHaveBeenSampled() {
    // Cells alone leave a transaction poolable but not buildable. Sampling enough of them and
    // recovering is what lets this node put it in a block.
    // Half the cells each, so neither peer alone could have completed it.
    announce(
        announcingPeer(range(0, 64), range(0, 64)), announcingPeer(range(64, 128), range(64, 128)));
    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(resubmitter).submit(captor.capture(), anyBoolean(), anyBoolean(), anyByte());
    final BlobsWithCommitments resubmitted =
        captor.getValue().getBlobsWithCommitments().orElseThrow();

    assertThat(resubmitted.hasBlobData()).isTrue();
    assertThat(resubmitted.getBlobs()).isEqualTo(fullSidecar.getBlobs());
    assertThat(CKZG4844Helper.verify4844Kzg(resubmitted)).isTrue();
  }

  @Test
  void ignoresAnAnswerTheTaskRejected() {
    // GetCellsFromPeerTask verifies that cells open their commitments and the executor disconnects
    // the peer, leaving an INVALID_RESPONSE here. Those cells must not be gathered: recovering
    // from them would produce a blob that is merely wrong.
    final EthPeer liar = announcingPeer(range(0, 64), range(0, 64));
    liars.add(liar);
    announce(liar, announcingPeer(range(64, 128), range(64, 128)));

    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    // Half the cells are still missing, so the transaction is not handed back to the pool.
    verify(resubmitter, never()).submit(any(), anyBoolean(), anyBoolean(), anyByte());
  }

  @Test
  void weighsAnEntryByWhatItActuallyHolds() {
    // The cache is bounded by weight because an entry's size varies by three orders of magnitude:
    // nothing until a round ends short, up to every cell of every blob afterwards. The unit is
    // KiB, matching the bound it is compared against.
    final int twoBlobsNothingRetrieved = TransactionsLimbo.weighInKiB(2, 0);
    assertThat(twoBlobsNothingRetrieved).isEqualTo(14); // proofs and commitments only

    // 64 cells of 2 KiB for each of the two blobs, on top of that
    assertThat(TransactionsLimbo.weighInKiB(2, 2 * 64)).isEqualTo(twoBlobsNothingRetrieved + 256);

    // and a blob held in full is a quarter of a MiB
    assertThat(TransactionsLimbo.weighInKiB(1, 128) - TransactionsLimbo.weighInKiB(1, 0))
        .isEqualTo(256);
  }

  @Test
  void doesNotSampleUntilTheAnnouncementsCoverTheRequest() {
    // One peer, holding half the cells: nothing it can answer would complete the transaction.
    announce(announcingPeer(range(0, 64), range(0, 64)));

    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    assertThat(requests).isEmpty();
    verify(resubmitter, never()).submit(any(), anyBoolean(), anyBoolean(), anyByte());
  }

  private CellMask requestedOf(final EthPeer peer) {
    return requests.stream()
        .filter(request -> request.getKey().equals(peer))
        .map(Map.Entry::getValue)
        .reduce(this::unionOf)
        .orElseThrow(() -> new AssertionError("Nothing was requested of " + peer));
  }

  private CellMask unionOfRequests() {
    return requests.stream()
        .map(Map.Entry::getValue)
        .reduce(this::unionOf)
        .orElse(CellMask.EMPTY.copy());
  }

  private CellMask unionOf(final CellMask one, final CellMask other) {
    final CellMask union = one.copy();
    union.merge(other);
    return union;
  }

  private CellMask intersectionOf(final CellMask one, final CellMask other) {
    final CellMask intersection = one.copy();
    intersection.intersect(other);
    return intersection;
  }

  /** The sidecar of the transaction handed back to the pool. */
  private BlobsWithCommitments resubmittedSidecar() {
    final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(resubmitter).submit(captor.capture(), anyBoolean(), anyBoolean(), anyByte());
    return captor.getValue().getBlobsWithCommitments().orElseThrow();
  }

  private CellMask resubmittedCellMask() {
    return resubmittedSidecar().getCellMask();
  }

  private PeerTaskExecutorResult<List<CellsWithMask>> serveCells(
      final org.mockito.invocation.InvocationOnMock invocation) {
    final GetCellsFromPeerTask task = invocation.getArgument(0);
    final EthPeer peer = invocation.getArgument(1);
    final CellMask requested =
        GetCellsMessage.readFrom(task.getRequestMessage(Set.of(EthProtocol.ETH72))).cellMask();
    requests.add(Map.entry(peer, requested));

    return servedByPeer
        .get(peer)
        .map(
            served -> {
              final CellMask answered = served.copy();
              answered.intersect(requested);
              if (liars.contains(peer)) {
                return new PeerTaskExecutorResult<>(
                    Optional.of(List.of(cellsOfAnotherBlob(answered))),
                    INVALID_RESPONSE,
                    List.of(peer));
              }
              return new PeerTaskExecutorResult<>(
                  Optional.of(List.of(cellsFor(answered))), SUCCESS, List.of(peer));
            })
        .orElseGet(
            () ->
                new PeerTaskExecutorResult<List<CellsWithMask>>(
                    Optional.empty(), TIMEOUT, List.of(peer)));
  }

  /**
   * A peer that announces {@code announcedMask} and, when asked, answers with its share of {@code
   * servedMask} — or with nothing when that is null.
   */
  private EthPeer announcingPeer(final CellMask announcedMask, final CellMask servedMask) {
    final EthPeer peer = mock(EthPeer.class);
    when(peer.getAgreedCapabilities()).thenReturn(Set.<Capability>of(EthProtocol.ETH72));
    servedByPeer.put(peer, Optional.ofNullable(servedMask));
    announcedByPeer.put(peer, announcedMask);
    return peer;
  }

  private void announce(final EthPeer... peers) {
    for (final EthPeer peer : peers) {
      limbo.onTransactionsAnnounced(
          peer,
          List.of(
              new TransactionAnnouncement(
                  blobTx.getHash(),
                  blobTx.getType(),
                  (long) blobTx.getSizeForEth72Announcement(),
                  announcedByPeer.get(peer))));
    }
  }

  private CellsWithMask cellsFor(final CellMask mask) {
    return new CellsWithMask(mask.streamIndexes().mapToObj(realCells::getCell).toList(), mask);
  }

  /** Genuine cells of another blob, so each one is well formed but opens nothing. */
  private CellsWithMask cellsOfAnotherBlob(final CellMask mask) {
    if (otherBlobCells == null) {
      // From the same fixture, so that it is a genuinely different blob: a fresh one would start
      // its raw material over and hand back the very blob the peers are meant to be serving.
      otherBlobCells =
          CKZG4844Helper.convertToVersion1(blobTestFixture.createBlobsWithCommitments(1))
              .getBlobProofBundles()
              .getFirst()
              .getCellsWithMask()
              .orElseThrow();
      assertThat(otherBlobCells.getCells()).isNotEqualTo(realCells.getCells());
    }
    return new CellsWithMask(mask.streamIndexes().mapToObj(otherBlobCells::getCell).toList(), mask);
  }

  private static CellMask range(final int fromInclusive, final int toExclusive) {
    final BitSet bits = new BitSet(CellMask.BYTE_LENGTH * 8);
    bits.set(fromInclusive, toExclusive);
    final byte[] bytes = new byte[CellMask.BYTE_LENGTH];
    final byte[] set = bits.toByteArray();
    System.arraycopy(set, 0, bytes, 0, set.length);
    return CellMask.fromBytes(Bytes.wrap(bytes));
  }
}
