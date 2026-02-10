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
import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.Util;
import org.hyperledger.besu.ethereum.core.encoding.receipt.SyncTransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.eth.messages.ReceiptsMessage;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;

public class GetSyncReceiptsFromPeerTask
    extends AbstractGetReceiptsFromPeerTask<SyncTransactionReceipt> {

  private final SyncTransactionReceiptEncoder syncTransactionReceiptEncoder;

  public GetSyncReceiptsFromPeerTask(
      final Request request,
      final ProtocolSchedule protocolSchedule,
      final SyncTransactionReceiptEncoder syncTransactionReceiptEncoder) {
    super(request, protocolSchedule);
    this.syncTransactionReceiptEncoder = syncTransactionReceiptEncoder;
  }

  @Override
  protected List<List<SyncTransactionReceipt>> getMessageReceipts(
      final ReceiptsMessage receiptsMessage) {
    return receiptsMessage.syncReceipts();
  }

  @Override
  protected Response<SyncTransactionReceipt> newResponse(
      final List<List<SyncTransactionReceipt>> blocksReceipts, final boolean lastBlockIncomplete) {
    return new Response<>(blocksReceipts, lastBlockIncomplete);
  }

  @Override
  protected boolean receiptsRootMatches(
      final BlockHeader blockHeader, final List<SyncTransactionReceipt> receipts) {
    final var calculatedReceiptsRoot =
        Util.getRootFromListOfBytes(
            receipts.stream()
                .map(
                    (r) -> {
                      Bytes rlp =
                          r.isFormattedForRootCalculation()
                              ? r.getRlpBytes()
                              : syncTransactionReceiptEncoder.encodeForRootCalculation(r);
                      r.clearSubVariables();
                      return rlp;
                    })
                .toList());

    return calculatedReceiptsRoot.getBytes().equals(blockHeader.getReceiptsRoot().getBytes());
  }
}
