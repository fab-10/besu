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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.EthPeerImmutableAttributes;
import org.hyperledger.besu.ethereum.eth.manager.peertask.InvalidPeerTaskResponseException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.MalformedRlpFromPeerException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTask;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskValidationResponse;
import org.hyperledger.besu.ethereum.eth.messages.GetReceiptsMessage;
import org.hyperledger.besu.ethereum.eth.messages.ReceiptsMessage;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.SubProtocol;
import org.hyperledger.besu.ethereum.rlp.RLPException;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.annotations.VisibleForTesting;

public abstract class AbstractGetReceiptsFromPeerTask<TR>
    implements PeerTask<AbstractGetReceiptsFromPeerTask.Response<TR>> {
  protected final Request request;
  protected final ProtocolSchedule protocolSchedule;
  private final long requiredBlockchainHeight;
  private final boolean isPoS;

  protected AbstractGetReceiptsFromPeerTask(
      final Request request, final ProtocolSchedule protocolSchedule) {
    this.request = request;
    this.protocolSchedule = protocolSchedule;

    requiredBlockchainHeight =
        this.request.blockHeaderAndReceiptCounts.stream()
            .map(BlockHeaderAndReceiptCount::blockHeader)
            .mapToLong(BlockHeader::getNumber)
            .max()
            .orElse(BlockHeader.GENESIS_BLOCK_NUMBER);

    isPoS = protocolSchedule.anyMatch(ps -> ps.spec().isPoS());
  }

  @Override
  public MessageData getRequestMessage(final Set<Capability> agreedCapabilities) {
    final List<Hash> blockHashes =
        request.blockHeaderAndReceiptCounts.stream()
            .map(BlockHeaderAndReceiptCount::blockHeader)
            .map(BlockHeader::getHash)
            .toList();
    return agreedCapabilities.stream().anyMatch(EthProtocol::isEth70Compatible)
        ? GetReceiptsMessage.create(blockHashes, request.firstBlockReceiptIndex())
        : GetReceiptsMessage.create(blockHashes);
  }

  @Override
  public SubProtocol getSubProtocol() {
    return EthProtocol.get();
  }

  @Override
  public Predicate<EthPeerImmutableAttributes> getPeerRequirementFilter() {
    return (ethPeer) -> isPoS || ethPeer.estimatedChainHeight() >= requiredBlockchainHeight;
  }

  @Override
  public Response<TR> processResponse(final MessageData messageData)
      throws InvalidPeerTaskResponseException, MalformedRlpFromPeerException {
    if (messageData == null) {
      throw new InvalidPeerTaskResponseException();
    }
    final ReceiptsMessage receiptsMessage = ReceiptsMessage.readFrom(messageData);
    try {
      //      final var returnReceipts = getMessageReceipts(receiptsMessage);
      //      // complete the first block if needed
      //      if (request.firstBlockReceiptIndex() > 0) {
      //        // at least few receipts for the first block must be returned
      //        if (returnReceipts.isEmpty()) {
      //          throw new InvalidPeerTaskResponseException("No receipts returned");
      //        }
      //        // prepend the already fetched partial list of receipts to the first block result
      //        returnReceipts.getFirst().addAll(0, request.firstBlockPartialReceipts());
      //      }
      return newResponse(
          getMessageReceipts(receiptsMessage), receiptsMessage.lastBlockIncomplete());
    } catch (RLPException e) {
      // indicates a malformed or unexpected RLP result from the peer
      throw new MalformedRlpFromPeerException(e, messageData.getData());
    }
  }

  protected abstract List<List<TR>> getMessageReceipts(final ReceiptsMessage receiptsMessage);

  protected abstract Response<TR> newResponse(
      final List<List<TR>> blocksReceipts, final boolean lastBlockIncomplete);

  @Override
  public PeerTaskValidationResponse validateResult(final Response<TR> result) {
    if (!request.isEmpty()) {
      if (result.isEmpty()) {
        return PeerTaskValidationResponse.NO_RESULTS_RETURNED;
      }

      if (result.size() > request.size()) {
        return PeerTaskValidationResponse.TOO_MANY_RESULTS_RETURNED;
      }

      for (int i = 0; i < result.size(); i++) {
        final var requestedReceipts = request.blockHeaderAndReceiptCounts().get(i);
        final var receivedReceiptsForBlock = result.blocksReceipts().get(i);

        // verify that the receipts count is within bounds for every received block
        if (receivedReceiptsForBlock.size() > requestedReceipts.receiptCount()) {
          return PeerTaskValidationResponse.TOO_MANY_RESULTS_RETURNED;
        }

        // do not verify receipts root if last block is incomplete
        if (i < result.size() - 1 || !result.lastBlockIncomplete()) {
          // ensure the calculated receipts root matches the one in the requested block header
          if (!receiptsRootMatches(requestedReceipts.blockHeader(), receivedReceiptsForBlock)) {
            return PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY;
          }
        }
      }
    }

    return PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD;
  }

  protected abstract boolean receiptsRootMatches(
      final BlockHeader blockHeader, final List<TR> receipts);

  @VisibleForTesting
  public Collection<BlockHeader> getBlockHeaders() {
    return request.blockHeaderAndReceiptCounts().stream()
        .map(BlockHeaderAndReceiptCount::blockHeader)
        .toList();
  }

  public record BlockHeaderAndReceiptCount(BlockHeader blockHeader, int receiptCount) {}

  public record Request(
      List<BlockHeaderAndReceiptCount> blockHeaderAndReceiptCounts, int firstBlockReceiptIndex) {
    public boolean isEmpty() {
      return blockHeaderAndReceiptCounts.isEmpty();
    }

    public int size() {
      return blockHeaderAndReceiptCounts.size();
    }
  }

  public record Response<TR>(List<List<TR>> blocksReceipts, boolean lastBlockIncomplete) {
    public boolean isEmpty() {
      return blocksReceipts.isEmpty();
    }

    public int size() {
      return blocksReceipts.size();
    }
  }
}
