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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetReceiptsFromPeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetReceiptsFromPeerTask.BlockHeaderAndReceiptCount;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetReceiptsFromPeerTask.Request;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetReceiptsFromPeerTask.Response;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.tasks.GetReceiptsForHeadersTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDownloadReceiptsStep<B, BWR>
    implements Function<List<B>, CompletableFuture<List<BWR>>> {
  // B is the type of block being processed (Block, SyncBlock), BWR is the type of block with
  // blocksReceipts (BlockWithReceipts, SyncBlockWithReceipts)

  private static final Logger LOG = LoggerFactory.getLogger(AbstractDownloadReceiptsStep.class);

  private final ProtocolSchedule protocolSchedule;
  private final EthContext ethContext;
  private final SynchronizerConfiguration synchronizerConfiguration;
  private final MetricsSystem metricsSystem;

  public AbstractDownloadReceiptsStep(
      final ProtocolSchedule protocolSchedule,
      final EthContext ethContext,
      final SynchronizerConfiguration synchronizerConfiguration,
      final MetricsSystem metricsSystem) {
    this.protocolSchedule = protocolSchedule;
    this.ethContext = ethContext;
    this.synchronizerConfiguration = synchronizerConfiguration;
    this.metricsSystem = metricsSystem;
  }

  @Override
  public CompletableFuture<List<BWR>> apply(final List<B> blocks) {
    final List<BlockHeader> headers = blocks.stream().map(this::getBlockHeader).toList();
    if (synchronizerConfiguration.isPeerTaskSystemEnabled()) {
      return ethContext
          .getScheduler()
          .scheduleServiceTask(() -> getReceiptsWithPeerTaskSystem(blocks))
          .thenApply((receipts) -> combineBlocksAndReceipts(blocks, receipts));
    } else {
      return GetReceiptsForHeadersTask.forHeaders(ethContext, headers, metricsSystem)
          .run()
          .thenApply((receipts) -> combineBlocksAndReceipts(blocks, receipts));
    }
  }

  abstract BlockHeader getBlockHeader(final B b);

  abstract int getTransactionCount(final B b);

  abstract List<BWR> combineBlocksAndReceipts(
      final List<B> blocks, final Map<BlockHeader, List<TransactionReceipt>> receiptsByHeader);

  private CompletableFuture<Map<BlockHeader, List<TransactionReceipt>>>
      getReceiptsWithPeerTaskSystem(final List<B> blocks) {
    final var receiptRequests =
        blocks.stream()
            .map(b -> new BlockHeaderAndReceiptCount(getBlockHeader(b), getTransactionCount(b)))
            .collect(toCollection(ArrayList::new));

    final Map<BlockHeader, List<TransactionReceipt>> getReceipts =
        HashMap.newHashMap(receiptRequests.size());

    // pre-filter blocks with empty blocksReceipts root, for which we do not need to request
    // anything
    final var itEmptyBlocks = receiptRequests.iterator();
    while (itEmptyBlocks.hasNext()) {
      final var blockHeader = itEmptyBlocks.next().blockHeader();
      if (blockHeader.getReceiptsRoot().equals(Hash.EMPTY_TRIE_HASH)) {
        getReceipts.put(blockHeader, emptyList());
        itEmptyBlocks.remove();
      }
    }

    final var firstBlockPartialReceipts = new ArrayList<TransactionReceipt>();

    do {
      final var task =
          new GetReceiptsFromPeerTask(
              new Request(receiptRequests, firstBlockPartialReceipts), protocolSchedule);
      final PeerTaskExecutorResult<Response> getReceiptsResult =
          ethContext.getPeerTaskExecutor().execute(task);

      if (getReceiptsResult.responseCode() == PeerTaskExecutorResponseCode.SUCCESS
          && getReceiptsResult.result().isPresent()) {
        final Response taskResult = getReceiptsResult.result().get();

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
        final List<TransactionReceipt> transactionReceipts = getReceipts.get(getBlockHeader(block));
        LOG.trace(
            "{} blocksReceipts received for header {}",
            transactionReceipts == null ? 0 : transactionReceipts.size(),
            getBlockHeader(block).getBlockHash());
      }
    }
    return CompletableFuture.completedFuture(getReceipts);
  }
}
