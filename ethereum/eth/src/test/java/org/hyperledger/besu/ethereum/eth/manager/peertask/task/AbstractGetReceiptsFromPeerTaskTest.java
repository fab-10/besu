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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.TransactionReceiptEncodingConfiguration;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.manager.ChainState;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.EthPeerImmutableAttributes;
import org.hyperledger.besu.ethereum.eth.manager.PeerReputation;
import org.hyperledger.besu.ethereum.eth.manager.peertask.InvalidPeerTaskResponseException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.MalformedRlpFromPeerException;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskValidationResponse;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.BlockHeaderAndReceiptCount;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Request;
import org.hyperledger.besu.ethereum.eth.manager.peertask.task.AbstractGetReceiptsFromPeerTask.Response;
import org.hyperledger.besu.ethereum.eth.messages.EthProtocolMessages;
import org.hyperledger.besu.ethereum.eth.messages.GetReceiptsMessage;
import org.hyperledger.besu.ethereum.eth.messages.ReceiptsMessage;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.p2p.rlpx.connections.PeerConnection;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

public abstract class AbstractGetReceiptsFromPeerTaskTest<
    TR, TASK extends AbstractGetReceiptsFromPeerTask<TR>> {
  private static final Set<Capability> AGREED_CAPABILITIES_ETH69 = Set.of(EthProtocol.ETH69);
  private static final Set<Capability> AGREED_CAPABILITIES_LATEST = Set.of(EthProtocol.LATEST);
  private static ProtocolSchedule protocolSchedule;

  @BeforeAll
  public static void setup() {
    protocolSchedule = mock(ProtocolSchedule.class);
    final ProtocolSpec protocolSpec = mock(ProtocolSpec.class);
    when(protocolSpec.isPoS()).thenReturn(false);
    when(protocolSchedule.getByBlockHeader(Mockito.any())).thenReturn(protocolSpec);
    when(protocolSchedule.anyMatch(Mockito.any())).thenReturn(false);
  }

  protected abstract TASK createTask(
      final Request request, final ProtocolSchedule protocolSchedule);

  protected abstract TR toResponseReceipt(final TransactionReceipt receipt);

  @Test
  public void testGetSubProtocol() {
    final var task = createTask(new Request(List.of(), 0), protocolSchedule);
    Assertions.assertEquals(EthProtocol.get(), task.getSubProtocol());
  }

  @ParameterizedTest
  @MethodSource("testGetRequestMessage")
  public void testGetRequestMessage(
      final List<BlockHeaderAndReceiptCount> headerAndReceiptCounts,
      final Set<Capability> capabilities,
      final Optional<List<TR>> maybeFirstBlockPartialReceipts) {
    final var task =
        createTask(
            new Request(
                headerAndReceiptCounts, maybeFirstBlockPartialReceipts.map(List::size).orElse(0)),
            protocolSchedule);

    MessageData messageData = task.getRequestMessage(capabilities);
    GetReceiptsMessage getReceiptsMessage = GetReceiptsMessage.readFrom(messageData);

    Assertions.assertEquals(EthProtocolMessages.GET_RECEIPTS, getReceiptsMessage.getCode());

    List<Hash> hashesInMessage = getReceiptsMessage.blockHashes();
    List<Hash> expectedHashes =
        headerAndReceiptCounts.stream()
            .map(BlockHeaderAndReceiptCount::blockHeader)
            .map(BlockHeader::getHash)
            .toList();

    assertThat(expectedHashes).containsExactlyElementsOf(hashesInMessage);

    assertThat(getReceiptsMessage.firstBlockReceiptIndex())
        .isEqualTo(maybeFirstBlockPartialReceipts.map(List::size).orElse(-1));
  }

  private static Stream<Arguments> testGetRequestMessage() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receiptForBlock1a =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    TransactionReceipt receiptForBlock1b =
        new TransactionReceipt(1, 321, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock1a, receiptForBlock1b)));

    BlockHeader blockHeader2 = mockBlockHeader(2);
    TransactionReceipt receiptForBlock2 =
        new TransactionReceipt(1, 456, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader2.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock2)));

    BlockHeader blockHeader3 = mockBlockHeader(3);
    TransactionReceipt receiptForBlock3 =
        new TransactionReceipt(1, 789, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader3.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock3)));

    final var headers =
        List.of(
            new BlockHeaderAndReceiptCount(blockHeader1, 2),
            new BlockHeaderAndReceiptCount(blockHeader2, 1),
            new BlockHeaderAndReceiptCount(blockHeader3, 1));

    return Stream.of(
        Arguments.of(headers, AGREED_CAPABILITIES_ETH69, Optional.empty()),
        Arguments.of(headers, AGREED_CAPABILITIES_LATEST, Optional.of(List.of(receiptForBlock1a))));
  }

  @Test
  public void testParseResponseWithNullResponseMessage() {
    final var task = createTask(new Request(List.of(), 0), protocolSchedule);
    Assertions.assertThrows(
        InvalidPeerTaskResponseException.class, () -> task.processResponse(null));
  }

  @Test
  public void testParseResponse()
      throws InvalidPeerTaskResponseException, MalformedRlpFromPeerException {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receiptForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock1)));

    BlockHeader blockHeader2 = mockBlockHeader(2);
    TransactionReceipt receiptForBlock2 =
        new TransactionReceipt(1, 456, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader2.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock2)));

    BlockHeader blockHeader3 = mockBlockHeader(3);
    TransactionReceipt receiptForBlock3 =
        new TransactionReceipt(1, 789, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader3.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock3)));

    BlockHeader blockHeader4 = mockBlockHeader(4);
    Mockito.when(blockHeader4.getReceiptsRoot()).thenReturn(Hash.EMPTY_TRIE_HASH);

    final var task =
        createTask(
            new Request(
                List.of(
                    new BlockHeaderAndReceiptCount(blockHeader1, 1),
                    new BlockHeaderAndReceiptCount(blockHeader2, 1),
                    new BlockHeaderAndReceiptCount(blockHeader3, 1),
                    new BlockHeaderAndReceiptCount(blockHeader4, 0)),
                0),
            protocolSchedule);

    ReceiptsMessage receiptsMessage =
        ReceiptsMessage.create(
            List.of(
                List.of(receiptForBlock1),
                List.of(receiptForBlock2),
                List.of(receiptForBlock3),
                List.of()),
            TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);

    final var response = task.processResponse(receiptsMessage);

    assertThat(response.blocksReceipts())
        .containsExactly(
            List.of(toResponseReceipt(receiptForBlock1)),
            List.of(toResponseReceipt(receiptForBlock2)),
            List.of(toResponseReceipt(receiptForBlock3)),
            Collections.emptyList());
  }

  @Test
  @Disabled("ToDo moved to DownloadSyncReceiptsStep")
  public void testParseResponseForOnlyPrefilledEmptyTrieReceiptsRoots()
      throws InvalidPeerTaskResponseException, MalformedRlpFromPeerException {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    Mockito.when(blockHeader1.getReceiptsRoot()).thenReturn(Hash.EMPTY_TRIE_HASH);

    GetReceiptsFromPeerTask task =
        new GetReceiptsFromPeerTask(
            new Request(List.of(new BlockHeaderAndReceiptCount(blockHeader1, 0)), 0),
            protocolSchedule);

    ReceiptsMessage receiptsMessage =
        ReceiptsMessage.create(
            Collections.emptyList(),
            TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);

    var resultMap = task.processResponse(receiptsMessage);

    Assertions.assertEquals(1, resultMap.size());
    Assertions.assertEquals(Collections.emptyList(), resultMap.blocksReceipts().getFirst());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true})
  public void testGetPeerRequirementFilter(final boolean isPoS) {
    reset(protocolSchedule);
    when(protocolSchedule.anyMatch(any())).thenReturn(isPoS);

    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receiptForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock1)));

    BlockHeader blockHeader2 = mockBlockHeader(2);
    TransactionReceipt receiptForBlock2 =
        new TransactionReceipt(1, 456, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader2.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock2)));

    final var task =
        createTask(
            new Request(
                List.of(
                    new BlockHeaderAndReceiptCount(blockHeader1, 1),
                    new BlockHeaderAndReceiptCount(blockHeader2, 1)),
                0),
            protocolSchedule);

    EthPeer failForShortChainHeight = mockPeer(1);
    EthPeer successfulCandidate = mockPeer(5);

    assertThat(
            task.getPeerRequirementFilter()
                .test(EthPeerImmutableAttributes.from(failForShortChainHeight)))
        .isEqualTo(isPoS);
    Assertions.assertTrue(
        task.getPeerRequirementFilter().test(EthPeerImmutableAttributes.from(successfulCandidate)));
  }

  static List<Arguments> validateResultFailsWhenNoResultAreReturned() {
    return List.of(
        Arguments.of(0, false),
        Arguments.of(0, true),
        Arguments.of(1, false),
        Arguments.of(1, true));
  }

  @ParameterizedTest
  @MethodSource("validateResultFailsWhenNoResultAreReturned")
  public void validateResultFailsWhenNoResultAreReturned(
      final int firstBlockReceiptIndex, final boolean lastBlockIncomplete) {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    final var task =
        createTask(
            new Request(
                List.of(new BlockHeaderAndReceiptCount(blockHeader1, 1)), firstBlockReceiptIndex),
            protocolSchedule);

    Assertions.assertEquals(
        PeerTaskValidationResponse.NO_RESULTS_RETURNED,
        task.validateResult(new Response<>(List.of(), lastBlockIncomplete)));
  }

  @Test
  public void testValidateResultForFullSuccess() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receiptForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock1)));

    final var task =
        createTask(
            new Request(List.of(new BlockHeaderAndReceiptCount(blockHeader1, 1)), 0),
            protocolSchedule);

    Assertions.assertEquals(
        PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD,
        task.validateResult(
            new Response<>(List.of(List.of(toResponseReceipt(receiptForBlock1))), false)));
  }

  @Test
  public void testParseResponseForInvalidResponse() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receiptForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock1)));

    BlockHeader blockHeader2 = mockBlockHeader(2);
    TransactionReceipt receiptForBlock2 =
        new TransactionReceipt(1, 456, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader2.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock2)));

    BlockHeader blockHeader3 = mockBlockHeader(3);
    TransactionReceipt receiptForBlock3 =
        new TransactionReceipt(1, 789, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader3.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock3)));
    final var task =
        createTask(
            new Request(
                List.of(
                    new BlockHeaderAndReceiptCount(blockHeader1, 1),
                    new BlockHeaderAndReceiptCount(blockHeader2, 1),
                    new BlockHeaderAndReceiptCount(blockHeader3, 1)),
                0),
            protocolSchedule);
    var response =
        new Response<>(
            List.of(
                List.of(toResponseReceipt(receiptForBlock1)),
                List.of(toResponseReceipt(receiptForBlock2)),
                List.of(toResponseReceipt(receiptForBlock3)),
                List.of(
                    toResponseReceipt(
                        new TransactionReceipt(
                            1, 101112, Collections.emptyList(), Optional.empty())))),
            false);

    Assertions.assertEquals(
        PeerTaskValidationResponse.TOO_MANY_RESULTS_RETURNED, task.validateResult(response));
  }

  @Test
  public void validateResultFailsWhenTooManyBlocksReturned() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receiptForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock1)));

    TransactionReceipt extraReceiptForBlock2 =
        new TransactionReceipt(1, 321, Collections.emptyList(), Optional.empty());

    final var task =
        createTask(
            new Request(List.of(new BlockHeaderAndReceiptCount(blockHeader1, 1)), 0),
            protocolSchedule);

    Assertions.assertEquals(
        PeerTaskValidationResponse.TOO_MANY_RESULTS_RETURNED,
        task.validateResult(
            new Response<>(
                List.of(
                    List.of(toResponseReceipt(receiptForBlock1)),
                    List.of(toResponseReceipt(extraReceiptForBlock2))),
                false)));
  }

  @Test
  public void validateResultFailsReceiptRootDoesNotMatch() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receiptForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receiptForBlock1)));

    TransactionReceipt returnedReceiptForBlock1 =
        new TransactionReceipt(1, 321, Collections.emptyList(), Optional.empty());

    final var task =
        createTask(
            new Request(List.of(new BlockHeaderAndReceiptCount(blockHeader1, 1)), 0),
            protocolSchedule);

    Assertions.assertEquals(
        PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY,
        task.validateResult(
            new Response<>(List.of(List.of(toResponseReceipt(returnedReceiptForBlock1))), false)));
  }

  @Test
  public void validateResultSuccessIfLastBlockIncomplete() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receipt1ForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    TransactionReceipt receipt2ForBlock1 =
        new TransactionReceipt(1, 456, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receipt1ForBlock1, receipt2ForBlock1)));

    final var task =
        createTask(
            new Request(List.of(new BlockHeaderAndReceiptCount(blockHeader1, 2)), 0),
            protocolSchedule);

    Assertions.assertEquals(
        PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD,
        task.validateResult(
            new Response<>(List.of(List.of(toResponseReceipt(receipt1ForBlock1))), true)));
  }

  @Test
  public void validateResultSuccessWhenPartialBlockIsComplete() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receipt1ForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    TransactionReceipt receipt2ForBlock1 =
        new TransactionReceipt(1, 456, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(BodyValidation.receiptsRoot(List.of(receipt1ForBlock1, receipt2ForBlock1)));

    final var task =
        createTask(
            new Request(List.of(new BlockHeaderAndReceiptCount(blockHeader1, 2)), 1),
            protocolSchedule);

    Assertions.assertEquals(
        PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD,
        task.validateResult(
            new Response<>(
                List.of(
                    List.of(
                        toResponseReceipt(receipt1ForBlock1),
                        toResponseReceipt(receipt2ForBlock1))),
                false)));
  }

  @Test
  public void validateResultSuccessWhenPartialBlockIsIncomplete() {
    BlockHeader blockHeader1 = mockBlockHeader(1);
    TransactionReceipt receipt1ForBlock1 =
        new TransactionReceipt(1, 123, Collections.emptyList(), Optional.empty());
    TransactionReceipt receipt2ForBlock1 =
        new TransactionReceipt(1, 456, Collections.emptyList(), Optional.empty());
    TransactionReceipt receipt3ForBlock1 =
        new TransactionReceipt(1, 789, Collections.emptyList(), Optional.empty());
    Mockito.when(blockHeader1.getReceiptsRoot())
        .thenReturn(
            BodyValidation.receiptsRoot(
                List.of(receipt1ForBlock1, receipt2ForBlock1, receipt3ForBlock1)));

    final var task =
        createTask(
            new Request(List.of(new BlockHeaderAndReceiptCount(blockHeader1, 3)), 1),
            protocolSchedule);

    Assertions.assertEquals(
        PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD,
        task.validateResult(
            new Response<>(
                List.of(
                    List.of(
                        toResponseReceipt(receipt1ForBlock1),
                        toResponseReceipt(receipt2ForBlock1))),
                true)));
  }

  private static BlockHeader mockBlockHeader(final long blockNumber) {
    BlockHeader blockHeader = Mockito.mock(BlockHeader.class);
    Mockito.when(blockHeader.getNumber()).thenReturn(blockNumber);
    // second to last hex digit indicates the blockNumber, last hex digit indicates the usage of the
    // hash
    Mockito.when(blockHeader.getHash())
        .thenReturn(Hash.fromHexString(StringUtils.repeat("00", 31) + blockNumber + "1"));

    return blockHeader;
  }

  private EthPeer mockPeer(final long chainHeight) {
    EthPeer ethPeer = Mockito.mock(EthPeer.class);
    ChainState chainState = Mockito.mock(ChainState.class);

    Mockito.when(ethPeer.chainState()).thenReturn(chainState);
    Mockito.when(chainState.getEstimatedHeight()).thenReturn(chainHeight);
    Mockito.when(chainState.getEstimatedTotalDifficulty()).thenReturn(Difficulty.of(0));
    Mockito.when(ethPeer.getReputation()).thenReturn(new PeerReputation());
    PeerConnection connection = mock(PeerConnection.class);
    Mockito.when(ethPeer.getConnection()).thenReturn(connection);
    return ethPeer;
  }
}
