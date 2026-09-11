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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class TransactionLimbo {
  private final Random random = new Random();
  private final EthContext ethContext;
  private final PeerTransactionTracker peerTransactionTracker;
  private final Map<Hash, Transaction> incompleteBlobs = new HashMap<>();
  private final Map<CellMask, List<Hash>> fetchableBlobs = new HashMap<>();
  private final EthScheduler.OrderedProcessor<CellsRequest> cellsFetcherProcessor;

  public TransactionLimbo(final EthContext ethContext, final PeerTransactionTracker peerTransactionTracker) {
    this.ethContext = ethContext;
    this.peerTransactionTracker = peerTransactionTracker;
    this.cellsFetcherProcessor = ethContext.getScheduler().createOrderedProcessor(this::processGetCells);
  }

  public void addIncompleteBlob(final Transaction transaction) {
    final CellMask requestedCellMask = getCellMask();

    if (peerTransactionTracker.hasEnoughAnnouncements(transaction.getHash(), requestedCellMask)) {
      addFetchable(transaction.getHash());
    } else {
      incompleteBlobs.put(transaction.getHash(), transaction);
    }
  }

  private CellMask getCellMask() {
    if (random.nextInt(100) < 15) {
      // fetch all cells
      return CellMask.FULL;
    }
      return peerTransactionTracker.getBlobCustodyColumns();
  }

  private void addFetchable(final Hash txHash) {
    final CellMask fetchCellMask;
    if (random.nextInt(100) < 15) {
      // fetch all cells
      fetchCellMask = CellMask.FULL;
    } else {
      fetchCellMask = peerTransactionTracker.getBlobCustodyColumns();
    }

    fetchableBlobs.computeIfAbsent(fetchCellMask, _ -> new ArrayList<>()).add(txHash);
  }

  private void processGetCells(final CellsRequest request) {

  }

  record CellsRequest(CellMask cellMask, Hash txHash) {

  }
}
