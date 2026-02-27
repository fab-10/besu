/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.eth.sync;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.SyncBlock;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResponseCode;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutorResult;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.GetSyncBlockBodiesFromPeerTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DownloadSyncBodiesStep
    implements Function<List<BlockHeader>, CompletableFuture<List<SyncBlock>>> {

  private static final Logger LOG = LoggerFactory.getLogger(DownloadSyncBodiesStep.class);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(1);
  private static final AtomicInteger taskSequence = new AtomicInteger(0);

  private final ProtocolSchedule protocolSchedule;
  private final EthScheduler ethScheduler;
  private final PeerTaskExecutor peerTaskExecutor;
  private final Duration timeoutDuration;

  public DownloadSyncBodiesStep(
      final ProtocolSchedule protocolSchedule,
      final EthContext ethContext,
      final Duration timeoutDuration) {
    this.protocolSchedule = protocolSchedule;
    this.ethScheduler = ethContext.getScheduler();
    this.peerTaskExecutor = ethContext.getPeerTaskExecutor();
    this.timeoutDuration = timeoutDuration;
  }

  @Override
  public CompletableFuture<List<SyncBlock>> apply(final List<BlockHeader> blockHeaders) {
    final int currTaskId = taskSequence.incrementAndGet();
    final List<SyncBlock> syncBlocks = new ArrayList<>(blockHeaders.size());
    return ethScheduler
        .scheduleServiceTask(() -> downloadAllBodies(currTaskId, 0, blockHeaders, syncBlocks))
        .orTimeout(timeoutDuration.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete(
            (unused, throwable) -> {
              if (throwable instanceof TimeoutException) {
                LOG.trace(
                    "[{}] Timed out after {} ms while downloading bodies for {} blocks",
                    currTaskId,
                    timeoutDuration.toMillis(),
                    blockHeaders.size());
              }
            });
  }

  private CompletableFuture<List<SyncBlock>> downloadAllBodies(
      final int currTaskId,
      final int prevIterations,
      final List<BlockHeader> headers,
      final List<SyncBlock> syncBlocks) {

    final int totalBodies = headers.size();
    int iteration = prevIterations;
    while (syncBlocks.size() < totalBodies) {
      ++iteration;

      final List<BlockHeader> remaining = headers.subList(syncBlocks.size(), totalBodies);

      LOG.atTrace()
          .setMessage("[{}:{}] Requesting {} block bodies (total {})")
          .addArgument(currTaskId)
          .addArgument(iteration)
          .addArgument(remaining::size)
          .addArgument(totalBodies)
          .log();

      final GetSyncBlockBodiesFromPeerTask task =
          new GetSyncBlockBodiesFromPeerTask(remaining, protocolSchedule);
      final PeerTaskExecutorResult<List<SyncBlock>> result = peerTaskExecutor.execute(task);
      final PeerTaskExecutorResponseCode responseCode = result.responseCode();

      if (responseCode == PeerTaskExecutorResponseCode.SUCCESS) {
        final List<SyncBlock> received = result.result().orElseGet(List::of);
        syncBlocks.addAll(received);
        LOG.atTrace()
            .setMessage("[{}:{}] Received {} block bodies ({} of {} total)")
            .addArgument(currTaskId)
            .addArgument(iteration)
            .addArgument(received::size)
            .addArgument(syncBlocks::size)
            .addArgument(totalBodies)
            .log();
      } else {
        LOG.atTrace()
            .setMessage("[{}:{}] Failed with {} to retrieve {} block bodies ({} of {} received)")
            .addArgument(currTaskId)
            .addArgument(iteration)
            .addArgument(responseCode)
            .addArgument(remaining::size)
            .addArgument(syncBlocks::size)
            .addArgument(totalBodies)
            .log();

        if (responseCode == PeerTaskExecutorResponseCode.INTERNAL_SERVER_ERROR) {
          return CompletableFuture.failedFuture(
              new RuntimeException(
                  "Failed to download bodies for " + totalBodies + " blocks"));
        }

        LOG.trace("[{}:{}] Waiting for {} before retrying", currTaskId, iteration, RETRY_DELAY);
        final int passIterations = iteration;
        return ethScheduler.scheduleFutureTask(
            () ->
                ethScheduler.scheduleServiceTask(
                    () -> downloadAllBodies(currTaskId, passIterations, headers, syncBlocks)),
            RETRY_DELAY);
      }
    }

    LOG.atTrace()
        .setMessage("[{}:{}] Downloaded {} block bodies")
        .addArgument(currTaskId)
        .addArgument(iteration)
        .addArgument(syncBlocks::size)
        .log();

    return CompletableFuture.completedFuture(syncBlocks);
  }
}
