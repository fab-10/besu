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
package org.hyperledger.besu.ethereum.eth.manager.peertask.task;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.eth.messages.ReceiptsMessage;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import java.util.List;

public class GetReceiptsFromPeerTask extends AbstractGetReceiptsFromPeerTask<TransactionReceipt> {

  public GetReceiptsFromPeerTask(final Request request, final ProtocolSchedule protocolSchedule) {
    super(request, protocolSchedule);
  }

  @Override
  protected List<List<TransactionReceipt>> getMessageReceipts(
      final ReceiptsMessage receiptsMessage) {
    return receiptsMessage.receipts();
  }

  @Override
  protected Response<TransactionReceipt> newResponse(
      final List<List<TransactionReceipt>> blocksReceipts, final boolean lastBlockIncomplete) {
    return new Response<>(blocksReceipts, lastBlockIncomplete);
  }

  @Override
  protected boolean receiptsRootMatches(
      final BlockHeader blockHeader, final List<TransactionReceipt> receipts) {
    return blockHeader
        .getReceiptsRoot()
        .getBytes()
        .equals(BodyValidation.receiptsRoot(receipts).getBytes());
  }
}
