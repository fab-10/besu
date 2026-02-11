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

import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Request;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import java.util.List;

public class GetReceiptsFromPeerTaskTest
    extends AbstractGetReceiptsFromPeerTaskTest<TransactionReceipt, GetReceiptsFromPeerTask> {
  @Override
  protected GetReceiptsFromPeerTask createTask(
      final Request<TransactionReceipt> request, final ProtocolSchedule protocolSchedule) {
    return new GetReceiptsFromPeerTask(request, protocolSchedule);
  }

  @Override
  protected TransactionReceipt toResponseReceipt(final TransactionReceipt receipt) {
    return receipt;
  }

  @Override
  protected int receiptsComparator(
      final List<TransactionReceipt> receipts1, final List<TransactionReceipt> receipts2) {
    if (receipts1.size() != receipts2.size()) {
      return receipts1.size() - receipts2.size();
    }
    for (int i = 0; i < receipts1.size(); i++) {
      if (!receipts1.get(i).equals(receipts2.get(i))) {
        // quick tiebreak since we are not interested in the order here
        return receipts1.hashCode() - receipts2.hashCode();
      }
    }
    return 0;
  }
}
