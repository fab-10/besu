/*
 * Copyright Hyperledger Besu Contributors.
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

import static java.util.concurrent.CompletableFuture.completedFuture;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.tasks.RetryingGetHeaderFromPeerByHashTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PoSFastSyncTargetManager extends AbstractFastSyncTargetManager {
  private static final Logger LOG = LoggerFactory.getLogger(PoSFastSyncTargetManager.class);

  public PoSFastSyncTargetManager(
      final SynchronizerConfiguration config,
      final WorldStateStorage worldStateStorage,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final EthContext ethContext,
      final MetricsSystem metricsSystem,
      final FastSyncState fastSyncState) {
    super(
        config,
        worldStateStorage,
        protocolSchedule,
        protocolContext,
        ethContext,
        metricsSystem,
        fastSyncState);
  }

  @Override
  protected CompletableFuture<Optional<EthPeer>> selectBestAvailableSyncTarget() {
    final BlockHeader pivotBlockHeader = fastSyncState.getPivotBlockHeader().get();
    final Optional<EthPeer> maybeBestPeer = findPeerWithPivot(pivotBlockHeader);
    if (maybeBestPeer.isEmpty()) {
      //      throttledLog(
      //          LOG::info,
      //          String.format(
      //              "Unable to find sync target. Currently checking %d peers for usefulness. Pivot
      // block: %s",
      //              ethContext.getEthPeers().peerCount(), pivotBlockHeader.toLogString()),
      //          logDebug,
      //          logDebugRepeatDelay);
      LOG.atDebug()
          .setMessage(
              "Unable to find sync target. Currently checking {} peers for usefulness. Pivot block: {}")
          .addArgument(ethContext.getEthPeers().peerCount())
          .addArgument(pivotBlockHeader::toLogString)
          .log();
      return completedFuture(Optional.empty());
    } else {
      LOG.info("Using peer {} as sync target", maybeBestPeer.get());
      return completedFuture(maybeBestPeer);
    }
  }

  private Optional<EthPeer> findPeerWithPivot(final BlockHeader pivotBlockHeader) {
    final var task =
        RetryingGetHeaderFromPeerByHashTask.byHash(
            protocolSchedule, ethContext, pivotBlockHeader.getHash(), 0, metricsSystem);
    task.run();
    return task.getAssignedPeer();
  }
}
