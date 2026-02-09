/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.eth.sync.fastsync;

import static java.util.Collections.emptyList;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.SyncBlock;
import org.hyperledger.besu.ethereum.core.SyncBlockWithReceipts;
import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.SyncTransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Request;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetSyncReceiptsFromPeerTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DownloadSyncReceiptsStep
    implements Function<List<SyncBlock>, CompletableFuture<List<SyncBlockWithReceipts>>> {

  private static final Logger LOG = LoggerFactory.getLogger(DownloadSyncReceiptsStep.class);

  private final EthScheduler ethScheduler;
  private final ProtocolSchedule protocolSchedule;
  private final PeerTaskExecutor peerTaskExecutor;
  private final SyncTransactionReceiptEncoder syncTransactionReceiptEncoder;

  public DownloadSyncReceiptsStep(
      final ProtocolSchedule protocolSchedule,
      final EthContext ethContext,
      final SyncTransactionReceiptEncoder syncTransactionReceiptEncoder) {
    this.protocolSchedule = protocolSchedule;
    this.ethScheduler = ethContext.getScheduler();
    this.peerTaskExecutor = ethContext.getPeerTaskExecutor();
    this.syncTransactionReceiptEncoder = syncTransactionReceiptEncoder;
  }

  //  public CompletableFuture<List<BWR>> apply2(final List<B> blocks) {
  //    final List<BlockHeader> headers =
  // blocks.stream().map(this::getBlockHeader).collect(toList());
  //    final List<BlockHeader> originalBlockHeaders =
  //        LOG.isTraceEnabled() ? List.copyOf(headers) : null;
  //    return ethScheduler
  //        .scheduleServiceTask(
  //            () -> {
  //              Map<BlockHeader, List<TR>> receiptsByBlockHeader = new HashMap<>();
  //              while (!headers.isEmpty()) {
  //                Map<BlockHeader, List<TR>> receipts = getReceipts(headers);
  //                headers.removeAll(receipts.keySet());
  //                for (BlockHeader blockHeader : receipts.keySet()) {
  //                  receiptsByBlockHeader.put(blockHeader, receipts.get(blockHeader));
  //                }
  //              }
  //              if (LOG.isTraceEnabled()) {
  //                for (BlockHeader blockHeader : originalBlockHeaders) {
  //                  final List<TR> transactionReceipts = receiptsByBlockHeader.get(blockHeader);
  //                  LOG.atTrace()
  //                      .setMessage("{} receipts received for header {}")
  //                      .addArgument(transactionReceipts == null ? 0 : transactionReceipts.size())
  //                      .addArgument(blockHeader.getBlockHash())
  //                      .log();
  //                }
  //              }
  //              return CompletableFuture.completedFuture(receiptsByBlockHeader);
  //            })
  //        .thenApply((receipts) -> combineBlocksAndReceipts(blocks, receipts));
  //  }

  @Override
  public CompletableFuture<List<SyncBlockWithReceipts>> apply(final List<SyncBlock> blocks) {
    return ethScheduler
        .scheduleServiceTask(() -> getReceiptsWithPeerTaskSystem(blocks))
        .thenApply((receipts) -> combineBlocksAndReceipts(blocks, receipts));
  }

  private CompletableFuture<Map<BlockHeader, List<SyncTransactionReceipt>>>
      getReceiptsWithPeerTaskSystem(final List<SyncBlock> blocks) {
    final var receiptRequests =
        blocks.stream()
            .map(
                b ->
                    new AbstractGetReceiptsFromPeerTask.BlockHeaderAndReceiptCount(
                        b.getHeader(), b.getBody().getTransactionCount()))
            .collect(Collectors.toCollection(ArrayList::new));

    final Map<BlockHeader, List<SyncTransactionReceipt>> getReceipts =
        HashMap.newHashMap(receiptRequests.size());

    // pre-filter blocks with empty blocksReceipts root, for which we do not need to request
    // anything
    final var itEmptyBlocks = receiptRequests.iterator();
    while (itEmptyBlocks.hasNext()) {
      final var blockHeader = itEmptyBlocks.next().blockHeader();
      if (blockHeader.getReceiptsRoot().getBytes().equals(Hash.EMPTY_TRIE_HASH.getBytes())) {
        getReceipts.put(blockHeader, Collections.emptyList());
        itEmptyBlocks.remove();
      }
    }

    final var firstBlockPartialReceipts = new ArrayList<SyncTransactionReceipt>();

    do {
      final var task =
          new GetSyncReceiptsFromPeerTask(
              new Request<>(receiptRequests, firstBlockPartialReceipts),
              protocolSchedule,
              syncTransactionReceiptEncoder);
      final var getReceiptsResult = peerTaskExecutor.execute(task);

      if (getReceiptsResult.responseCode() == PeerTaskExecutorResponseCode.SUCCESS
          && getReceiptsResult.result().isPresent()) {
        final var taskResult = getReceiptsResult.result().get();

        final var blocksReceipts = taskResult.blocksReceipts();

        firstBlockPartialReceipts.clear();
        final int completeBlockSize;
        if (taskResult.lastBlockIncomplete()) {
          completeBlockSize = blocksReceipts.size() - 1;
          firstBlockPartialReceipts.addAll(blocksReceipts.getLast());
        } else {
          completeBlockSize = blocksReceipts.size();
        }

        final var resolvedRequests = receiptRequests.subList(0, completeBlockSize);

        for (int i = 0; i < resolvedRequests.size(); i++) {
          final var requestBlockHeader = receiptRequests.get(i).blockHeader();
          final var blockReceipts = blocksReceipts.get(i);
          getReceipts.put(requestBlockHeader, blockReceipts);
        }

        resolvedRequests.clear();
      }
      // repeat until all headers have blocksReceipts
    } while (!receiptRequests.isEmpty());
    if (LOG.isTraceEnabled()) {
      for (final var block : blocks) {
        final var transactionReceipts = getReceipts.get(block.getHeader());
        LOG.trace(
            "{} blocksReceipts received for header {}",
            transactionReceipts == null ? 0 : transactionReceipts.size(),
            block.getHash());
      }
    }
    return CompletableFuture.completedFuture(getReceipts);
  }

  List<SyncBlockWithReceipts> combineBlocksAndReceipts(
      final List<SyncBlock> blocks,
      final Map<BlockHeader, List<SyncTransactionReceipt>> receiptsByHeader) {
    return blocks.stream()
        .map(
            block -> {
              final List<SyncTransactionReceipt> receipts =
                  receiptsByHeader.getOrDefault(block.getHeader(), emptyList());
              if (block.getBody().getTransactionCount() != receipts.size()) {
                final BytesValueRLPOutput headerRlpOutput = new BytesValueRLPOutput();
                block.getHeader().writeTo(headerRlpOutput);
                LOG.atTrace()
                    .setMessage("Header RLP: {}")
                    .addArgument(headerRlpOutput.encoded())
                    .log();
                LOG.atTrace().setMessage("Body: {}").addArgument(block.getBody().getRlp()).log();
                throw new IllegalStateException(
                    "PeerTask response code was success, but incorrect number of receipts returned. Block hash: "
                        + block.getHeader().getHash()
                        + ", transactions: "
                        + block.getBody().getTransactionCount()
                        + ", receipts: "
                        + receipts.size());
              }
              return new SyncBlockWithReceipts(block, receipts);
            })
        .toList();
  }
  //
  //  abstract BlockHeader getBlockHeader(final B b);
  //
  //  abstract int getTransactionCount(final B b);
  //
  //  abstract List<BWR> combineBlocksAndReceipts(
  //      final List<B> blocks, final Map<BlockHeader, List<TR>> receiptsByHeader);
  //
  //  /**
  //   * Retrieves transaction receipts for as many of the supplied headers as possible. Repeat
  // calls
  //   * may be made after removing headers no longer needing transaction receipts.
  //   *
  //   * @param headers A list of headers to retrieve transaction receipts for
  //   * @return transaction receipts for as many of the supplied headers as possible
  //   */
  //  Map<BlockHeader, List<SyncTransactionReceipt>> getReceipts(final List<BlockHeader> headers) {
  //    GetSyncReceiptsFromPeerTask task =
  //        new GetSyncReceiptsFromPeerTask(headers, protocolSchedule,
  // syncTransactionReceiptEncoder);
  //    PeerTaskExecutorResult<Map<BlockHeader, List<SyncTransactionReceipt>>> getReceiptsResult =
  //        peerTaskExecutor.execute(task);
  //    if (getReceiptsResult.responseCode() == PeerTaskExecutorResponseCode.SUCCESS
  //        && getReceiptsResult.result().isPresent()) {
  //      return getReceiptsResult.result().get();
  //    }
  //    return Collections.emptyMap();
  //  }
}
