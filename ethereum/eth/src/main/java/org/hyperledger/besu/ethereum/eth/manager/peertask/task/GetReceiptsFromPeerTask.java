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
  //
  //  private final Request request;
  //  private final ProtocolSchedule protocolSchedule;
  //  private final long requiredBlockchainHeight;
  //
  //  public record BlockHeaderAndReceiptCount(BlockHeader blockHeader, int receiptCount) {}
  //
  //  public record Request(
  //      List<BlockHeaderAndReceiptCount> blockHeaderAndReceiptCounts,
  //      List<TransactionReceipt> firstBlockPartialReceipts) {
  //    public boolean isEmpty() {
  //      return blockHeaderAndReceiptCounts.isEmpty();
  //    }
  //
  //    public int size() {
  //      return blockHeaderAndReceiptCounts.size();
  //    }
  //
  //    public int firstBlockReceiptIndex() {
  //      return firstBlockPartialReceipts.size();
  //    }
  //  }
  //
  //  public record Response(
  //      List<List<TransactionReceipt>> blocksReceipts, boolean lastBlockIncomplete) {
  //    public boolean isEmpty() {
  //      return blocksReceipts.isEmpty();
  //    }
  //
  //    public int size() {
  //      return blocksReceipts.size();
  //    }
  //  }

  public GetReceiptsFromPeerTask(
      final Request<TransactionReceipt> request, final ProtocolSchedule protocolSchedule) {
    super(request, protocolSchedule);
    //    this.request = request;
    //    this.protocolSchedule = protocolSchedule;
    //
    //    requiredBlockchainHeight =
    //        this.request.blockHeaderAndReceiptCounts.stream()
    //            .map(BlockHeaderAndReceiptCount::blockHeader)
    //            .mapToLong(BlockHeader::getNumber)
    //            .max()
    //            .orElse(BlockHeader.GENESIS_BLOCK_NUMBER);
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

  //
  //  @Override
  //  public Predicate<EthPeerImmutableAttributes> getPeerRequirementFilter() {
  //    return (ethPeer) ->
  //        (protocolSchedule.anyMatch((ps) -> ps.spec().isPoS())
  //            || ethPeer.estimatedChainHeight() >= requiredBlockchainHeight);
  //  }

}
