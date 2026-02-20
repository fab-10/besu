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
package org.hyperledger.besu.ethereum.eth.manager.peertask.task;

import static org.hyperledger.besu.ethereum.eth.core.Utils.receiptToSyncReceipt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.SyncBlock;
import org.hyperledger.besu.ethereum.core.SyncBlockBody;
import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.SyncTransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.core.encoding.receipt.TransactionReceiptEncodingConfiguration;
import org.hyperledger.besu.ethereum.eth.core.Utils;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Request;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.rlp.SimpleNoCopyRlpEncoder;

import java.util.List;

public class GetSyncReceiptsFromPeerTaskTest
    extends AbstractGetReceiptsFromPeerTaskTest<
        SyncBlock, SyncTransactionReceipt, GetSyncReceiptsFromPeerTask> {
  @Override
  protected GetSyncReceiptsFromPeerTask createTask(
      final Request<SyncBlock, SyncTransactionReceipt> request,
      final ProtocolSchedule protocolSchedule) {
    return new GetSyncReceiptsFromPeerTask(
        request, protocolSchedule, new SyncTransactionReceiptEncoder(new SimpleNoCopyRlpEncoder()));
  }

  @Override
  protected SyncTransactionReceipt toResponseReceipt(final TransactionReceipt receipt) {
    return receiptToSyncReceipt(receipt, TransactionReceiptEncodingConfiguration.DEFAULT);
  }

  @Override
  protected int receiptsComparator(
      final List<SyncTransactionReceipt> receipts1, final List<SyncTransactionReceipt> receipts2) {
    if (receipts1.size() != receipts2.size()) {
      return receipts1.size() - receipts2.size();
    }
    for (int i = 0; i < receipts1.size(); i++) {
      if (Utils.compareSyncReceipts(receipts1.get(i), receipts2.get(i)) != 0) {
        // quick tiebreak since we are not interested in the order here
        return receipts1.hashCode() - receipts2.hashCode();
      }
    }
    return 0;
  }

  @Override
  protected BlockHeader getHeader(final SyncBlock block) {
    return block.getHeader();
  }

  @Override
  protected MockedBlock<SyncBlock> mockBlock(final long number, final int txCount) {
    final SyncBlockBody body = mock(SyncBlockBody.class);
    when(body.getTransactionCount()).thenReturn(txCount);
    final List<TransactionReceipt> receipts = mockTransactionReceipts(number, txCount);
    return new MockedBlock<>(new SyncBlock(mockBlockHeader(number, receipts), body), receipts);
  }
}
