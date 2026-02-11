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
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hyperledger.besu.ethereum.eth.core.Utils.blocksToSyncBlocks;
import static org.hyperledger.besu.ethereum.eth.core.Utils.receiptsToSyncReceipts;
import static org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode.SUCCESS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator.BlockOptions;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.SyncBlock;
import org.hyperledger.besu.ethereum.core.SyncBlockWithReceipts;
import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.SyncTransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.eth.core.Utils;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Response;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetSyncReceiptsFromPeerTask;
import org.hyperledger.besu.ethereum.mainnet.DefaultProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.testutil.DeterministicEthScheduler;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DownloadSyncReceiptsStepTest {

  @Mock private EthContext ethContext;
  @Mock private PeerTaskExecutor peerTaskExecutor;
  @Mock private SyncTransactionReceiptEncoder syncTransactionReceiptEncoder;
  private ProtocolSchedule protocolSchedule;
  private final BlockDataGenerator gen = new BlockDataGenerator();

  private DownloadSyncReceiptsStep downloadSyncReceiptsStep;

  @BeforeEach
  public void setUp() {
    protocolSchedule = new DefaultProtocolSchedule(Optional.of(BigInteger.ONE));
    when(ethContext.getScheduler()).thenReturn(new DeterministicEthScheduler());
    when(ethContext.getPeerTaskExecutor()).thenReturn(peerTaskExecutor);

    downloadSyncReceiptsStep =
        new DownloadSyncReceiptsStep(protocolSchedule, ethContext, syncTransactionReceiptEncoder);
  }

  @Test
  public void shouldDownloadReceiptsForBlocksWithTransactions()
      throws ExecutionException, InterruptedException {

    // skip genesis block, since we do not need to retrieve receipt for it
    final List<Block> blockWithTxs = gen.blockSequence(3).subList(1, 3);

    final var returnedReceiptsByBlock =
        blockWithTxs.stream().map(gen::receipts).map(Utils::receiptsToSyncReceipts).toList();
    final var syncBlocks = blocksToSyncBlocks(blockWithTxs);

    // Mock the peer task executor to return receipts for both blocks
    final var taskResult = new Response<>(returnedReceiptsByBlock, false);
    final var executorResult =
        new PeerTaskExecutorResult<>(Optional.of(taskResult), SUCCESS, emptyList());
    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(executorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should return all blocks with receipts
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(2);
    for (int i = 0; i < blocksWithReceipts.size(); i++) {
      assertThat(blocksWithReceipts.get(i).getBlock()).isEqualTo(syncBlocks.get(i));
      assertThat(blocksWithReceipts.get(i).getReceipts()).isEqualTo(returnedReceiptsByBlock.get(i));
    }

    // Verify the task was executed once
    verify(peerTaskExecutor, times(1)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void shouldSkipDownloadForBlocksWithEmptyReceiptsRoot()
      throws ExecutionException, InterruptedException {

    final Block block1_withTxs = gen.blockSequence(gen.genesisBlock(), 1).getFirst();

    gen.setBlockOptionsSupplier(
        () -> BlockOptions.create().hasTransactions(false).setReceiptsRoot(Hash.EMPTY_TRIE_HASH));
    final Block block2_withoutTxs = gen.blockSequence(block1_withTxs, 1).getFirst();

    gen.setBlockOptionsSupplier(() -> BlockOptions.create().hasTransactions(true));
    final Block block3_withTxs = gen.blockSequence(block2_withoutTxs, 1).getFirst();

    final var blocks = List.of(block1_withTxs, block2_withoutTxs, block3_withTxs);

    // we must not request receipt for block2, so only return receipts for the 2 blocks with txs
    final var returnedReceiptsByBlock =
        List.of(
            receiptsToSyncReceipts(gen.receipts(block1_withTxs)),
            receiptsToSyncReceipts(gen.receipts(block3_withTxs)));

    final var syncBlocks = blocksToSyncBlocks(blocks);

    final var taskResult = new Response<>(returnedReceiptsByBlock, false);
    final var executorResult =
        new PeerTaskExecutorResult<>(Optional.of(taskResult), SUCCESS, emptyList());
    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(executorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should also return block with empty receipts
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(3);

    assertThat(blocksWithReceipts.get(0).getBlock()).isEqualTo(syncBlocks.get(0));
    assertThat(blocksWithReceipts.get(0).getReceipts()).isEqualTo(returnedReceiptsByBlock.get(0));

    assertThat(blocksWithReceipts.get(1).getBlock()).isEqualTo(syncBlocks.get(1));
    assertThat(blocksWithReceipts.get(1).getReceipts()).isEmpty();

    assertThat(blocksWithReceipts.get(2).getBlock()).isEqualTo(syncBlocks.get(2));
    assertThat(blocksWithReceipts.get(2).getReceipts()).isEqualTo(returnedReceiptsByBlock.get(1));

    // Verify the task was executed once with empty request list
    verify(peerTaskExecutor, times(1)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void shouldHandlePartialReceiptsFromFirstBlock()
      throws ExecutionException, InterruptedException {

    // Given: blocks with 3 transactions each, excluding genesis block
    gen.setBlockOptionsSupplier(
        () -> BlockOptions.create().hasTransactions(true).transactionCount(3));
    final List<Block> blocks = gen.blockSequence(3).subList(1, 3);

    final List<SyncBlock> syncBlocks = blocksToSyncBlocks(blocks);

    final var returnedReceiptsByBlock =
        blocks.stream().map(gen::receipts).map(Utils::receiptsToSyncReceipts).toList();

    // First call returns partial receipts for first block
    final List<SyncTransactionReceipt> firstBlockReceipts = returnedReceiptsByBlock.getFirst();
    final List<SyncTransactionReceipt> secondBlockReceipts = returnedReceiptsByBlock.get(1);
    final List<SyncTransactionReceipt> partialReceipts =
        firstBlockReceipts.subList(0, firstBlockReceipts.size() / 2);
    final List<SyncTransactionReceipt> remainingReceipts =
        firstBlockReceipts.subList(firstBlockReceipts.size() / 2, firstBlockReceipts.size());

    final var firstResult = new Response<>(List.of(partialReceipts), true);
    final var firstExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(firstResult), SUCCESS, emptyList());

    // Second call returns remaining receipts and second block
    final var secondResult = new Response<>(List.of(remainingReceipts, secondBlockReceipts), false);
    final var secondExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(secondResult), SUCCESS, emptyList());

    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(firstExecutorResult)
        .thenReturn(secondExecutorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should return blocks with complete receipts
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(2);
    assertThat(blocksWithReceipts.get(0).getReceipts()).isEqualTo(firstBlockReceipts);
    assertThat(blocksWithReceipts.get(1).getReceipts()).isEqualTo(secondBlockReceipts);

    // Verify the task was executed twice
    verify(peerTaskExecutor, times(2)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void shouldHandlePartialReceiptsFromBlockAdvanced()
      throws ExecutionException, InterruptedException {

    // Given: block with 3 transactions
    gen.setBlockOptionsSupplier(
        () -> BlockOptions.create().hasTransactions(true).transactionCount(3));
    final Block block = gen.block();

    final List<SyncBlock> syncBlocks = blocksToSyncBlocks(List.of(block));

    final var returnedReceipts = receiptsToSyncReceipts(gen.receipts(block));

    // Receipts for the block are split in three responses
    final List<SyncTransactionReceipt> firstCallReturnedReceipts = returnedReceipts.subList(0, 1);
    final List<SyncTransactionReceipt> secondCallReturnedReceipts = returnedReceipts.subList(1, 2);
    final List<SyncTransactionReceipt> thirdCallReturnedReceipts = returnedReceipts.subList(2, 3);

    final var firstResult = new Response<>(List.of(firstCallReturnedReceipts), true);
    final var firstExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(firstResult), SUCCESS, emptyList());
    final var secondResult = new Response<>(List.of(secondCallReturnedReceipts), true);
    final var secondExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(secondResult), SUCCESS, emptyList());
    final var thirdResult = new Response<>(List.of(thirdCallReturnedReceipts), false);
    final var thirdExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(thirdResult), SUCCESS, emptyList());

    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(firstExecutorResult)
        .thenReturn(secondExecutorResult)
        .thenReturn(thirdExecutorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should return block with complete receipts
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(1);
    assertThat(blocksWithReceipts.getFirst().getReceipts()).isEqualTo(returnedReceipts);

    // Verify the task was executed twice
    verify(peerTaskExecutor, times(3)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void shouldRetryUntilAllReceiptsDownloaded()
      throws ExecutionException, InterruptedException {
    // Given: 3 blocks with transactions
    final List<Block> blocks = gen.blockSequence(4).subList(1, 4);
    final List<SyncBlock> syncBlocks = blocksToSyncBlocks(blocks);

    final var returnedReceiptsByBlock =
        blocks.stream().map(gen::receipts).map(Utils::receiptsToSyncReceipts).toList();

    // First call returns first block
    final var firstResult = new Response<>(List.of(returnedReceiptsByBlock.get(0)), false);
    final var firstExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(firstResult), SUCCESS, emptyList());

    // Second call returns second block
    final var secondResult = new Response<>(List.of(returnedReceiptsByBlock.get(1)), false);
    final var secondExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(secondResult), SUCCESS, emptyList());

    // Third call returns third block
    final var thirdResult = new Response<>(List.of(returnedReceiptsByBlock.get(2)), false);
    final var thirdExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(thirdResult), SUCCESS, emptyList());

    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(firstExecutorResult)
        .thenReturn(secondExecutorResult)
        .thenReturn(thirdExecutorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should return all blocks with receipts
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(3);
    for (int i = 0; i < blocksWithReceipts.size(); i++) {
      assertThat(blocksWithReceipts.get(i).getReceipts()).isEqualTo(returnedReceiptsByBlock.get(i));
    }

    // Verify the task was executed three times
    verify(peerTaskExecutor, times(3)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void combineBlocksAndReceiptsShouldThrowWhenReceiptCountMismatch() {
    // Given: a block with transactions and fewer receipts than transactions
    gen.setBlockOptionsSupplier(
        () -> BlockOptions.create().hasTransactions(true).transactionCount(3));
    final Block block = gen.block();
    final List<SyncBlock> syncBlocks = blocksToSyncBlocks(List.of(block));

    final Map<BlockHeader, List<SyncTransactionReceipt>> receiptsByHeader = new HashMap<>();

    final List<SyncTransactionReceipt> allReceipts = receiptsToSyncReceipts(gen.receipts(block));

    // Add fewer receipts than transactions
    receiptsByHeader.put(
        syncBlocks.get(0).getHeader(), allReceipts.subList(0, allReceipts.size() - 1));

    // When/Then: should throw IllegalStateException
    assertThatThrownBy(
            () -> downloadSyncReceiptsStep.combineBlocksAndReceipts(syncBlocks, receiptsByHeader))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("incorrect number of receipts returned");
  }

  @Test
  public void combineBlocksAndReceiptsShouldReturnEmptyReceiptsWhenNotInMap() {
    // Given: blocks without transactions and without receipts in map
    gen.setBlockOptionsSupplier(() -> BlockOptions.create().hasTransactions(false));
    final Block blockWithoutTxs = gen.block();
    final List<SyncBlock> syncBlocks = blocksToSyncBlocks(List.of(blockWithoutTxs));

    final Map<BlockHeader, List<SyncTransactionReceipt>> receiptsByHeader = emptyMap();

    // When: combining blocks and receipts
    final List<SyncBlockWithReceipts> result =
        downloadSyncReceiptsStep.combineBlocksAndReceipts(syncBlocks, receiptsByHeader);

    // Then: should return blocks with empty receipts
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getReceipts()).isEmpty();
  }
}
