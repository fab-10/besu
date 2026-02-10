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

import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.SyncTransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.eth.core.Utils;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Request;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.rlp.SimpleNoCopyRlpEncoder;

public class GetSyncReceiptsFromPeerTaskTest
    extends AbstractGetReceiptsFromPeerTaskTest<
        SyncTransactionReceipt, GetSyncReceiptsFromPeerTask> {
  @Override
  protected GetSyncReceiptsFromPeerTask createTask(
      final Request request, final ProtocolSchedule protocolSchedule) {
    return new GetSyncReceiptsFromPeerTask(
        request, protocolSchedule, new SyncTransactionReceiptEncoder(new SimpleNoCopyRlpEncoder()));
  }

  @Override
  protected SyncTransactionReceipt toResponseReceipt(final TransactionReceipt receipt) {
    return Utils.receiptToSyncReceipt(receipt);
  }
}
