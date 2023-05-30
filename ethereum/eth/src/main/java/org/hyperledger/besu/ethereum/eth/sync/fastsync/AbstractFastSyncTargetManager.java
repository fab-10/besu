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

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.sync.SyncTargetManager;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;
import org.hyperledger.besu.plugin.services.MetricsSystem;

public abstract class AbstractFastSyncTargetManager extends SyncTargetManager {
  protected final WorldStateStorage worldStateStorage;
  protected final FastSyncState fastSyncState;

  public AbstractFastSyncTargetManager(
      final SynchronizerConfiguration config,
      final WorldStateStorage worldStateStorage,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final EthContext ethContext,
      final MetricsSystem metricsSystem,
      final FastSyncState fastSyncState) {
    super(config, protocolSchedule, protocolContext, ethContext, metricsSystem);
    this.worldStateStorage = worldStateStorage;
    this.fastSyncState = fastSyncState;
  }

  @Override
  public boolean shouldContinueDownloading() {
    final BlockHeader pivotBlockHeader = fastSyncState.getPivotBlockHeader().get();
    boolean isValidChainHead =
        protocolContext.getBlockchain().getChainHeadHash().equals(pivotBlockHeader.getHash());
    if (!isValidChainHead) {
      if (protocolContext.getBlockchain().contains(pivotBlockHeader.getHash())) {
        protocolContext.getBlockchain().rewindToBlock(pivotBlockHeader.getHash());
      } else {
        return true;
      }
    }
    return !worldStateStorage.isWorldStateAvailable(
        pivotBlockHeader.getStateRoot(), pivotBlockHeader.getBlockHash());
  }
}
