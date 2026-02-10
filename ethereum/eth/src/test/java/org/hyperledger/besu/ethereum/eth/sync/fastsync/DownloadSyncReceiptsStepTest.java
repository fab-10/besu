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
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator.BlockOptions;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.SyncBlock;
import org.hyperledger.besu.ethereum.core.SyncBlockBody;
import org.hyperledger.besu.ethereum.core.SyncBlockWithReceipts;
import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.SyncTransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.eth.core.Utils;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Response;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetSyncReceiptsFromPeerTask;
import org.hyperledger.besu.ethereum.mainnet.DefaultProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DownloadSyncReceiptsStepTest {

  @Mock private EthContext ethContext;
  @Mock private EthScheduler ethScheduler;
  @Mock private PeerTaskExecutor peerTaskExecutor;
  @Mock private SyncTransactionReceiptEncoder syncTransactionReceiptEncoder;
  private ProtocolSchedule protocolSchedule;
  private final BlockDataGenerator gen = new BlockDataGenerator();

  private DownloadSyncReceiptsStep downloadSyncReceiptsStep;

  @BeforeEach
  public void setUp() {
    protocolSchedule = new DefaultProtocolSchedule(Optional.of(BigInteger.ONE));
    when(ethContext.getScheduler()).thenReturn(ethScheduler);
    when(ethContext.getPeerTaskExecutor()).thenReturn(peerTaskExecutor);

    // Mock the scheduler to execute tasks immediately
    when(ethScheduler.scheduleServiceTask(
            ArgumentMatchers
                .<Supplier<CompletableFuture<Map<BlockHeader, List<SyncTransactionReceipt>>>>>
                    any()))
        .thenAnswer(
            invocation -> {
              Supplier<CompletableFuture<Map<BlockHeader, List<SyncTransactionReceipt>>>> supplier =
                  invocation.getArgument(0);
              return supplier.get();
            });

    downloadSyncReceiptsStep =
        new DownloadSyncReceiptsStep(protocolSchedule, ethContext, syncTransactionReceiptEncoder);
  }

  @Test
  public void shouldDownloadReceiptsForBlocksWithTransactions()
      throws ExecutionException, InterruptedException {
    final List<Block> blockWithTxs = gen.blockSequence(3).subList(1, 3);

    final var returnedReceiptsByBlock =
        blockWithTxs.stream().map(gen::receipts).map(Utils::receiptsToSyncReceipts).toList();
    final var syncBlocks = blocksToSyncBlocks(blockWithTxs);

    // Mock the peer task executor to return receipts
    final var taskResult = new Response<>(returnedReceiptsByBlock, false);
    final var executorResult =
        new PeerTaskExecutorResult<>(Optional.of(taskResult), SUCCESS, emptyList());
    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(executorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should return blocks with receipts
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
    final Block blockWithTxs = gen.blockSequence(gen.genesisBlock(), 1).getFirst();

    gen.setBlockOptionsSupplier(
        () -> BlockOptions.create().hasTransactions(false).setReceiptsRoot(Hash.EMPTY_TRIE_HASH));
    final Block blockWithoutTxs = gen.blockSequence(blockWithTxs, 1).getFirst();

    final var blocks = List.of(blockWithTxs, blockWithoutTxs);
    final var returnedReceiptsByBlock = List.of(receiptsToSyncReceipts(gen.receipts(blockWithTxs)));
    final var syncBlocks = blocksToSyncBlocks(blocks);

    // Mock the peer task executor (should not be called for empty receipts)
    final var taskResult = new Response<>(returnedReceiptsByBlock, false);
    final var executorResult =
        new PeerTaskExecutorResult<>(Optional.of(taskResult), SUCCESS, emptyList());
    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(executorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should return blocks with empty receipts
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(2);

    assertThat(blocksWithReceipts.get(0).getBlock()).isEqualTo(syncBlocks.get(0));
    assertThat(blocksWithReceipts.get(0).getReceipts()).isEqualTo(returnedReceiptsByBlock.get(0));

    assertThat(blocksWithReceipts.get(1).getBlock()).isEqualTo(syncBlocks.get(1));
    assertThat(blocksWithReceipts.get(1).getReceipts()).isEmpty();

    // Verify the task was executed once with empty request list
    verify(peerTaskExecutor, times(1)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void shouldHandlePartialReceiptsFromFirstBlock()
      throws ExecutionException, InterruptedException {
    // Given: blocks with transactions
    final List<Block> realBlocks = gen.blockSequence(2);
    final List<SyncBlock> syncBlocks = blockToSyncBlock(realBlocks);
    final List<List<SyncTransactionReceipt>> allBlockReceipts = new ArrayList<>();

    for (Block block : realBlocks) {
      allBlockReceipts.add(receiptsToSyncReceipts(gen.receipts(block)));
    }

    // First call returns partial receipts for first block
    final List<SyncTransactionReceipt> firstBlockReceipts = allBlockReceipts.getFirst();
    final List<SyncTransactionReceipt> partialReceipts =
        firstBlockReceipts.subList(0, firstBlockReceipts.size() / 2);
    final List<SyncTransactionReceipt> remainingReceipts =
        firstBlockReceipts.subList(firstBlockReceipts.size() / 2, firstBlockReceipts.size());

    final var firstResult = new Response<SyncTransactionReceipt>(List.of(partialReceipts), true);
    final var firstExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(firstResult), SUCCESS, emptyList());

    // Second call returns remaining receipts and second block
    final var secondResult =
        new Response<SyncTransactionReceipt>(
            List.of(remainingReceipts, allBlockReceipts.get(1)), false);
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
    assertThat(blocksWithReceipts.get(1).getReceipts()).isEqualTo(allBlockReceipts.get(1));

    // Verify the task was executed twice
    verify(peerTaskExecutor, times(2)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void shouldRetryUntilAllReceiptsDownloaded()
      throws ExecutionException, InterruptedException {
    // Given: 3 blocks with transactions
    final List<Block> realBlocks = gen.blockSequence(3);
    final List<SyncBlock> syncBlocks = blockToSyncBlock(realBlocks);
    final List<List<SyncTransactionReceipt>> allBlockReceipts = new ArrayList<>();

    for (Block block : realBlocks) {
      allBlockReceipts.add(receiptsToSyncReceipts(gen.receipts(block)));
    }

    // First call returns first block
    final var firstResult =
        new Response<SyncTransactionReceipt>(List.of(allBlockReceipts.get(0)), false);
    final var firstExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(firstResult), SUCCESS, emptyList());

    // Second call returns second block
    final var secondResult =
        new Response<SyncTransactionReceipt>(List.of(allBlockReceipts.get(1)), false);
    final var secondExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(secondResult), SUCCESS, emptyList());

    // Third call returns third block
    final var thirdResult =
        new Response<SyncTransactionReceipt>(List.of(allBlockReceipts.get(2)), false);
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
      assertThat(blocksWithReceipts.get(i).getReceipts()).isEqualTo(allBlockReceipts.get(i));
    }

    // Verify the task was executed three times
    verify(peerTaskExecutor, times(3)).execute(any(GetSyncReceiptsFromPeerTask.class));
  }

  @Test
  public void combineBlocksAndReceiptsShouldThrowWhenReceiptCountMismatch() {
    // Given: a block with transactions and fewer receipts than transactions
    final Block blockWithTxs = gen.block();
    final List<SyncBlock> syncBlocks = blockToSyncBlock(List.of(blockWithTxs));
    final Map<BlockHeader, List<SyncTransactionReceipt>> receiptsByHeader = new HashMap<>();

    // Add fewer receipts than transactions (only return first receipt if there are at least 2)
    final List<SyncTransactionReceipt> allReceipts =
        receiptsToSyncReceipts(gen.receipts(blockWithTxs));
    if (allReceipts.size() < 2) {
      // Skip test if not enough transactions
      return;
    }
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
    final Block blockWithoutTxs = gen.genesisBlock();
    final List<SyncBlock> syncBlocks = blockToSyncBlock(List.of(blockWithoutTxs));
    final Map<BlockHeader, List<SyncTransactionReceipt>> receiptsByHeader = Collections.emptyMap();

    // When: combining blocks and receipts
    final List<SyncBlockWithReceipts> result =
        downloadSyncReceiptsStep.combineBlocksAndReceipts(syncBlocks, receiptsByHeader);

    // Then: should return blocks with empty receipts
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getReceipts()).isEmpty();
  }

  @Test
  public void combineBlocksAndReceiptsShouldCombineCorrectly() {
    // Given: blocks and matching receipts
    final List<Block> realBlocks = gen.blockSequence(2);
    final List<SyncBlock> syncBlocks = blockToSyncBlock(realBlocks);
    final Map<BlockHeader, List<SyncTransactionReceipt>> receiptsByHeader = new HashMap<>();

    for (int i = 0; i < realBlocks.size(); i++) {
      final List<SyncTransactionReceipt> receipts =
          receiptsToSyncReceipts(gen.receipts(realBlocks.get(i)));
      receiptsByHeader.put(syncBlocks.get(i).getHeader(), receipts);
    }

    // When: combining blocks and receipts
    final List<SyncBlockWithReceipts> result =
        downloadSyncReceiptsStep.combineBlocksAndReceipts(syncBlocks, receiptsByHeader);

    // Then: should return correctly combined blocks
    assertThat(result).hasSize(2);
    for (int i = 0; i < result.size(); i++) {
      assertThat(result.get(i).getBlock()).isEqualTo(syncBlocks.get(i));
      assertThat(result.get(i).getReceipts())
          .isEqualTo(receiptsByHeader.get(syncBlocks.get(i).getHeader()));
    }
  }

  @Test
  public void shouldHandleMixOfEmptyAndNonEmptyReceiptRoots()
      throws ExecutionException, InterruptedException {
    // Given: mix of blocks with and without transactions
    final List<SyncBlock> syncBlocks = new ArrayList<>();

    // Add a block without transactions (genesis block has no transactions)
    final Block blockWithoutTxs = gen.genesisBlock();
    syncBlocks.add(blockToSyncBlock(List.of(blockWithoutTxs)).get(0));

    // Add a block with transactions
    final Block blockWithTxs = gen.block();
    final List<SyncTransactionReceipt> receiptsForBlockWithTxs =
        receiptsToSyncReceipts(gen.receipts(blockWithTxs));
    syncBlocks.add(blockToSyncBlock(List.of(blockWithTxs)).get(0));

    // Mock peer task executor
    final var taskResult =
        new Response<SyncTransactionReceipt>(List.of(receiptsForBlockWithTxs), false);
    final var executorResult =
        new PeerTaskExecutorResult<>(Optional.of(taskResult), SUCCESS, emptyList());
    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(executorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: should return correct receipts for each block
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(2);
    assertThat(blocksWithReceipts.get(0).getReceipts()).isEmpty();
    assertThat(blocksWithReceipts.get(1).getReceipts()).isEqualTo(receiptsForBlockWithTxs);
  }

  @Test
  public void shouldPassCorrectOffsetForPartialReceipts()
      throws ExecutionException, InterruptedException {
    // Given: blocks with partial receipts scenario
    final List<Block> realBlocks = gen.blockSequence(1);
    final List<SyncBlock> syncBlocks = blockToSyncBlock(realBlocks);
    final List<SyncTransactionReceipt> allReceipts =
        receiptsToSyncReceipts(gen.receipts(realBlocks.get(0)));

    // Ensure we have enough receipts to split
    if (allReceipts.size() < 2) {
      // Skip test if not enough receipts
      return;
    }

    final int splitPoint = allReceipts.size() / 2;
    final List<SyncTransactionReceipt> partialReceipts = allReceipts.subList(0, splitPoint);
    final List<SyncTransactionReceipt> remainingReceipts =
        allReceipts.subList(splitPoint, allReceipts.size());

    // First call returns partial receipts
    final var firstResult = new Response<SyncTransactionReceipt>(List.of(partialReceipts), true);
    final var firstExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(firstResult), SUCCESS, emptyList());

    // Second call returns remaining receipts
    final var secondResult =
        new Response<SyncTransactionReceipt>(List.of(remainingReceipts), false);
    final var secondExecutorResult =
        new PeerTaskExecutorResult<>(Optional.of(secondResult), SUCCESS, emptyList());

    when(peerTaskExecutor.execute(any(GetSyncReceiptsFromPeerTask.class)))
        .thenReturn(firstExecutorResult)
        .thenReturn(secondExecutorResult);

    // When: downloading receipts
    final CompletableFuture<List<SyncBlockWithReceipts>> result =
        downloadSyncReceiptsStep.apply(syncBlocks);

    // Then: verify task was executed twice (once for partial, once for remaining)
    verify(peerTaskExecutor, times(2)).execute(any(GetSyncReceiptsFromPeerTask.class));

    // And result should have all receipts
    final List<SyncBlockWithReceipts> blocksWithReceipts = result.get();
    assertThat(blocksWithReceipts).hasSize(1);
    assertThat(blocksWithReceipts.get(0).getReceipts()).isEqualTo(allReceipts);
  }

  private SyncBlockBody createSyncBlockBody(final BlockBody body) {
    BytesValueRLPOutput rlpOutput = new BytesValueRLPOutput();
    body.writeWrappedBodyTo(rlpOutput);
    final BytesValueRLPInput input = new BytesValueRLPInput(rlpOutput.encoded(), false);
    return SyncBlockBody.readWrappedBodyFrom(input, false, protocolSchedule);
  }

  private List<SyncBlock> blockToSyncBlock(final List<Block> blocks) {
    final ArrayList<SyncBlock> syncBlocks = new ArrayList<>(blocks.size());
    for (final Block block : blocks) {
      syncBlocks.add(new SyncBlock(block.getHeader(), createSyncBlockBody(block.getBody())));
    }
    return syncBlocks;
  }
}
