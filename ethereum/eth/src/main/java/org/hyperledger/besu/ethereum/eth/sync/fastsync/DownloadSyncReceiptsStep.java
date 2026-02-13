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
import static java.util.stream.Collectors.toCollection;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.NO_PEER_AVAILABLE;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.SUCCESS;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.TIMEOUT;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.SyncBlock;
import org.hyperledger.besu.ethereum.core.SyncBlockWithReceipts;
import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.SyncTransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.BlockHeaderAndReceiptCount;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Request;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetSyncReceiptsFromPeerTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

import java.util.ArrayList;
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
  private static final long DEFAULT_BASE_WAIT_MILLIS = 100;

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

  @Override
  public CompletableFuture<List<SyncBlockWithReceipts>> apply(final List<SyncBlock> blocks) {
    return ethScheduler
        .scheduleServiceTask(() -> getReceipts(blocks))
        .thenApply((receipts) -> combineBlocksAndReceipts(blocks, receipts));
  }

  private CompletableFuture<Map<BlockHeader, List<SyncTransactionReceipt>>> getReceipts(
      final List<SyncBlock> blocks) {
    final var receiptRequests =
        blocks.stream()
            .map(
                b ->
                    new BlockHeaderAndReceiptCount(
                        b.getHeader(), b.getBody().getTransactionCount()))
            .collect(toCollection(ArrayList::new));

    final Map<BlockHeader, List<SyncTransactionReceipt>> getReceipts =
        HashMap.newHashMap(receiptRequests.size());

    // pre-filter blocks with empty blocksReceipts root, for which we do not need to request
    // anything
    final var itEmptyBlocks = receiptRequests.iterator();
    while (itEmptyBlocks.hasNext()) {
      final var blockHeader = itEmptyBlocks.next().blockHeader();
      if (blockHeader.getReceiptsRoot().getBytes().equals(Hash.EMPTY_TRIE_HASH.getBytes())) {
        LOG.trace("Skipping receipts retrieval for empty block {}", blockHeader.getNumber());
        itEmptyBlocks.remove();
      }
    }

    final var firstBlockPartialReceipts = new ArrayList<SyncTransactionReceipt>();

    int iteration = 0;
    // repeat until all headers have blocksReceipts
    while (!receiptRequests.isEmpty()) {
      ++iteration;
      final String logDetails =
          LOG.isTraceEnabled()
              ? receiptRequests.stream()
                  .map(br -> br.blockHeader().getNumber() + "(" + br.receiptCount() + ")")
                  .collect(Collectors.joining(","))
              : "";

      LOG.trace(
          "[{}] Requesting receipts for {} blocks: {}; partial receipts fetched for first block {}",
          iteration,
          receiptRequests.size(),
          logDetails,
          firstBlockPartialReceipts.size());

      final var task =
          new GetSyncReceiptsFromPeerTask(
              new Request<>(receiptRequests, firstBlockPartialReceipts),
              protocolSchedule,
              syncTransactionReceiptEncoder);
      final var getReceiptsResult = peerTaskExecutor.execute(task);

      final var responseCode = getReceiptsResult.responseCode();

      if (responseCode == SUCCESS && getReceiptsResult.result().isPresent()) {
        final var taskResult = getReceiptsResult.result().get();

        final var blocksReceipts = taskResult.blocksReceipts();

        final int completedBlockSize;
        firstBlockPartialReceipts.clear();
        if (taskResult.lastBlockIncomplete()) {
          completedBlockSize = blocksReceipts.size() - 1;
          firstBlockPartialReceipts.addAll(blocksReceipts.getLast());
        } else {
          completedBlockSize = blocksReceipts.size();
        }

        final var resolvedRequests = receiptRequests.subList(0, completedBlockSize);

        LOG.trace(
            "[{}] Received response for {} blocks, last block is incomplete? {}, complete blocks {}",
            iteration,
            taskResult.blocksReceipts().size(),
            taskResult.lastBlockIncomplete(),
            resolvedRequests.size());

        for (int i = 0; i < resolvedRequests.size(); i++) {
          final var request = receiptRequests.get(i);
          final List<SyncTransactionReceipt> blockReceipts = blocksReceipts.get(i);
          LOG.trace(
              "[{}] Request {}, received receipts {}", iteration, request, blockReceipts.size());
          getReceipts.put(request.blockHeader(), blockReceipts);
        }

        resolvedRequests.clear();
      } else {
        LOG.trace(
            "[{}] Failed with {} to retrieve receipts for {} blocks: {}; partial receipts fetched for first block {}",
            iteration,
            responseCode,
            receiptRequests.size(),
            logDetails,
            firstBlockPartialReceipts.size());
        if (responseCode == NO_PEER_AVAILABLE || responseCode == TIMEOUT) {
          // wait a bit more every iteration before retrying
          try {
            final long incrementalWaitTime = DEFAULT_BASE_WAIT_MILLIS * iteration;
            LOG.trace("[{}] Waiting for {}ms before retrying", iteration, incrementalWaitTime);
            Thread.sleep(incrementalWaitTime);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }

    if (LOG.isTraceEnabled()) {
      for (final var block : blocks) {
        final var transactionReceipts = getReceipts.get(block.getHeader());
        if (transactionReceipts != null) {
          LOG.trace(
              "{} blocksReceipts received for block {}",
              transactionReceipts.size(),
              block.getHeader().getNumber());
        }
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
                    .addArgument(headerRlpOutput::encoded)
                    .log();
                LOG.atTrace()
                    .setMessage("Body: {}")
                    .addArgument(() -> block.getBody().getRlp())
                    .log();
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
}
