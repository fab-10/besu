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
import org.hyperledger.besu.ethereum.core.CellsOnlyBlobTransactionFixture;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.Cell;
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
import org.hyperledger.besu.testutil.DeterministicEthScheduler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class TransactionsLimboTest {

  private static final byte SCORE = 127;

  private final PeerTaskExecutor peerTaskExecutor = mock(PeerTaskExecutor.class);
  private final EthContext ethContext = mock(EthContext.class);
  private final TransactionPool.TransactionResubmitter resubmitter =
      mock(TransactionPool.TransactionResubmitter.class);

  /** The mask each peer is willing to serve, or absent when it answers nothing. */
  private final Map<EthPeer, Optional<CellMask>> servedByPeer = new HashMap<>();

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
            // the sampling policy asks for every cell, so the request mask is the same whichever
            // branch of TransactionsLimbo#getCellMask is taken
            CellMask.FULL::copy,
            resubmitter,
            _ -> false);

    // As decoded from an eth/72 PooledTransactions response: commitments and proofs, no cells.
    blobTx = new CellsOnlyBlobTransactionFixture().create(1, CellMask.EMPTY);
  }

  @AfterEach
  void roundsEndCleanly() {
    // A round that throws would leave no other trace: its future is discarded and the failure only
    // reaches a log line, so every test asserts the absence of one.
    assertThat(rounds).allMatch(round -> !round.isCompletedExceptionally());
  }

  @Test
  void retriesOnlyTheCellsStillMissing() {
    final EthPeer lowerHalf = announcingPeer(range(0, 64), range(0, 64));
    final EthPeer upperHalfThatAnswersNothing = announcingPeer(range(64, 128), null);
    announce(lowerHalf, upperHalfThatAnswersNothing);

    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    // Half the cells arrived, so the transaction is not poolable yet and is kept for later.
    // The peers of a round are held in a HashMap, so which is asked first is not defined.
    assertThat(requests)
        .containsExactlyInAnyOrder(
            Map.entry(lowerHalf, range(0, 64)),
            Map.entry(upperHalfThatAnswersNothing, range(64, 128)));
    verify(resubmitter, never()).submit(any(), anyBoolean(), anyBoolean(), anyByte());

    requests.clear();
    final EthPeer laterPeer = announcingPeer(CellMask.FULL, CellMask.FULL);
    final EthPeer anotherLaterPeer = announcingPeer(CellMask.FULL, CellMask.FULL);
    announce(laterPeer, anotherLaterPeer);

    // Only the half that is still missing is asked for, even though the peer announced all of it.
    assertThat(requests).containsExactly(Map.entry(laterPeer, range(64, 128)));

    // And the cells of the first round were kept, so the transaction is now complete.
    assertThat(resubmittedCellMask()).isEqualTo(CellMask.FULL);
  }

  @Test
  void keepsTheWholeRequestWhenNothingArrives() {
    final EthPeer silent = announcingPeer(range(0, 64), null);
    final EthPeer alsoSilent = announcingPeer(range(64, 128), null);
    announce(silent, alsoSilent);

    limbo.addIncompleteBlob(blobTx, false, false, SCORE);

    verify(resubmitter, never()).submit(any(), anyBoolean(), anyBoolean(), anyByte());

    requests.clear();
    final EthPeer generous = announcingPeer(CellMask.FULL, CellMask.FULL);
    final EthPeer alsoGenerous = announcingPeer(CellMask.FULL, CellMask.FULL);
    announce(generous, alsoGenerous);

    // Nothing was retrieved, so nothing was struck off the request: the retry asks for all of it.
    assertThat(requests).containsExactly(Map.entry(generous, CellMask.FULL));
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

  /** The cell mask of the transaction handed back to the pool. */
  private CellMask resubmittedCellMask() {
    final ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(resubmitter).submit(captor.capture(), anyBoolean(), anyBoolean(), anyByte());
    return captor.getValue().getBlobsWithCommitments().orElseThrow().getCellMask();
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

  private static CellsWithMask cellsFor(final CellMask mask) {
    return new CellsWithMask(
        mask.streamIndexes()
            .mapToObj(index -> new Cell(Bytes.repeat((byte) index, Cell.SIZE)))
            .toList(),
        mask);
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
