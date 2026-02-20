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

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

public abstract class AbstractGetReceiptsFromPeerTaskTest<
    B, TR, TASK extends AbstractGetReceiptsFromPeerTask<B, TR>> {
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
      final Request<B, TR> request, final ProtocolSchedule protocolSchedule);

  protected abstract TR toResponseReceipt(final TransactionReceipt receipt);

  protected List<TR> toResponseReceipts(final List<TransactionReceipt> receipts) {
    return receipts.stream().map(this::toResponseReceipt).toList();
  }

  protected abstract int receiptsComparator(final List<TR> receipts1, final List<TR> receipts2);

  protected abstract BlockHeader getHeader(final B block);

  protected abstract MockedBlock<B> mockBlock(final long number, final int txCount);

  protected record MockedBlock<B>(B block, List<TransactionReceipt> receipts) {}

  @Test
  public void testGetSubProtocol() {
    final var task =
        createTask(new Request<>(List.of(mockBlock(1, 1).block), List.of()), protocolSchedule);
    assertEquals(EthProtocol.get(), task.getSubProtocol());
  }

  @Test
  public void testGetRequestMessageETH69() {
    final List<MockedBlock<B>> mockedBlocks =
        List.of(mockBlock(1, 2), mockBlock(2, 1), mockBlock(3, 1));

    final TASK task =
        createTask(
            new Request<>(mockedBlocks.stream().map(MockedBlock::block).toList(), List.of()),
            protocolSchedule);

    final MessageData messageData = task.getRequestMessage(AGREED_CAPABILITIES_ETH69);
    final GetReceiptsMessage getReceiptsMessage = GetReceiptsMessage.readFrom(messageData);

    assertEquals(EthProtocolMessages.GET_RECEIPTS, getReceiptsMessage.getCode());

    final List<Hash> hashesInMessage = getReceiptsMessage.blockHashes();
    final List<Hash> expectedHashes =
        mockedBlocks.stream()
            .map(MockedBlock::block)
            .map(this::getHeader)
            .map(BlockHeader::getHash)
            .toList();

    assertThat(expectedHashes).containsExactlyElementsOf(hashesInMessage);

    assertThat(getReceiptsMessage.firstBlockReceiptIndex()).isEqualTo(-1);
  }

  @Test
  public void testGetRequestMessageLatest() {
    final List<MockedBlock<B>> mockedBlocks =
        List.of(mockBlock(1, 2), mockBlock(2, 1), mockBlock(3, 1));

    final TASK task =
        createTask(
            new Request<>(
                mockedBlocks.stream().map(MockedBlock::block).toList(),
                List.of(toResponseReceipt(mockedBlocks.getFirst().receipts.getFirst()))),
            protocolSchedule);

    final MessageData messageData = task.getRequestMessage(AGREED_CAPABILITIES_LATEST);
    final GetReceiptsMessage getReceiptsMessage = GetReceiptsMessage.readFrom(messageData);

    assertEquals(EthProtocolMessages.GET_RECEIPTS, getReceiptsMessage.getCode());

    final List<Hash> hashesInMessage = getReceiptsMessage.blockHashes();
    final List<Hash> expectedHashes =
        mockedBlocks.stream()
            .map(MockedBlock::block)
            .map(this::getHeader)
            .map(BlockHeader::getHash)
            .toList();

    assertThat(expectedHashes).containsExactlyElementsOf(hashesInMessage);

    assertThat(getReceiptsMessage.firstBlockReceiptIndex()).isEqualTo(1);
  }

  @Test
  public void testParseResponseWithNullResponseMessage() {
    final TASK task =
        createTask(new Request<>(List.of(mockBlock(1, 2).block), List.of()), protocolSchedule);
    assertThrows(InvalidPeerTaskResponseException.class, () -> task.processResponse(null));
  }

  @Test
  public void testParseResponse()
      throws InvalidPeerTaskResponseException, MalformedRlpFromPeerException {
    final List<MockedBlock<B>> mockedBlocks =
        List.of(mockBlock(1, 1), mockBlock(2, 1), mockBlock(3, 1), mockBlock(4, 0));

    final TASK task =
        createTask(
            new Request<>(mockedBlocks.stream().map(MockedBlock::block).toList(), List.of()),
            protocolSchedule);

    final ReceiptsMessage receiptsMessage =
        ReceiptsMessage.create(
            mockedBlocks.stream().map(MockedBlock::receipts).toList(),
            TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);

    final Response<B, TR> response = task.processResponse(receiptsMessage);

    assertThat(response.completeReceiptsByBlock().values())
        .usingElementComparator(this::receiptsComparator)
        .containsExactlyInAnyOrder(
            toResponseReceipts(mockedBlocks.get(0).receipts),
            toResponseReceipts(mockedBlocks.get(1).receipts),
            toResponseReceipts(mockedBlocks.get(2).receipts),
            toResponseReceipts(mockedBlocks.get(3).receipts));
  }

  @Test
  public void testParseResponseFailsWhenReceiptsForTooManyBlocksAreReturned() {
    final List<MockedBlock<B>> mockedBlocks =
        List.of(mockBlock(1, 1), mockBlock(2, 1), mockBlock(3, 1));

    final TASK task =
        createTask(
            new Request<>(mockedBlocks.stream().map(MockedBlock::block).toList(), List.of()),
            protocolSchedule);

    final MockedBlock<B> extraMockedBlock = mockBlock(4, 1);

    final ReceiptsMessage receiptsMessage =
        ReceiptsMessage.create(
            Stream.concat(mockedBlocks.stream(), Stream.of(extraMockedBlock))
                .map(MockedBlock::receipts)
                .toList(),
            TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);

    assertThatThrownBy(() -> task.processResponse(receiptsMessage))
        .isInstanceOf(InvalidPeerTaskResponseException.class)
        .hasMessageContaining("Too many result returned");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void testGetPeerRequirementFilter(final boolean isPoS) {
    reset(protocolSchedule);
    when(protocolSchedule.anyMatch(any())).thenReturn(isPoS);
    final List<MockedBlock<B>> mockedBlocks = List.of(mockBlock(1, 1), mockBlock(2, 1));

    final TASK task =
        createTask(
            new Request<>(mockedBlocks.stream().map(MockedBlock::block).toList(), List.of()),
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void validateResultFailsWhenNoResultAreReturned(
      final boolean hasFirstBlockPartialReceipts) {
    final MockedBlock<B> mockedBlock = mockBlock(1, 1);
    final TASK task =
        createTask(
            new Request<>(
                List.of(mockedBlock.block),
                hasFirstBlockPartialReceipts
                    ? toResponseReceipts(mockedBlock.receipts)
                    : emptyList()),
            protocolSchedule);

    assertEquals(
        PeerTaskValidationResponse.NO_RESULTS_RETURNED,
        task.validateResult(new Response<>(Map.of(), List.of())));
  }

  static List<Arguments> validateResultProvider() {
    return List.of(
        Arguments.of(false, false),
        Arguments.of(false, true),
        Arguments.of(true, false),
        Arguments.of(true, true));
  }

  @ParameterizedTest
  @MethodSource("validateResultProvider")
  public void testValidateResultForFullSuccess(
      final boolean hasFirstBlockPartialReceipts, final boolean lastBlockIncomplete) {
    final MockedBlock<B> mockedBlock = mockBlock(1, 1);
    final MockedBlock<B> lastMockedBlock = mockBlock(2, 3);

    final TASK task =
        createTask(
            new Request<>(
                List.of(mockedBlock.block, lastMockedBlock.block),
                hasFirstBlockPartialReceipts
                    ? List.of(toResponseReceipt(lastMockedBlock.receipts.getFirst()))
                    : emptyList()),
            protocolSchedule);

    final List<TR> expectedLastBlockPartialReceipts =
        lastBlockIncomplete
            ? toResponseReceipts(lastMockedBlock.receipts).subList(0, 1)
            : List.of();

    final Map<B, List<TR>> expectedCompletedBlocks = new HashMap<>();
    expectedCompletedBlocks.put(mockedBlock.block, toResponseReceipts(mockedBlock.receipts));
    if (!lastBlockIncomplete) {
      expectedCompletedBlocks.put(
          lastMockedBlock.block, toResponseReceipts(lastMockedBlock.receipts));
    }

    assertEquals(
        PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD,
        task.validateResult(
            new Response<>(expectedCompletedBlocks, expectedLastBlockPartialReceipts)));
  }

  @Test
  public void validateResultFailsReceiptRootDoesNotMatch() {
    final MockedBlock<B> mockedRequestedBlock = mockBlock(1, 1);

    final TASK task =
        createTask(new Request<>(List.of(mockedRequestedBlock.block), List.of()), protocolSchedule);

    final List<TransactionReceipt> anotherBlockReceipts = mockBlock(2, 1).receipts;

    assertEquals(
        PeerTaskValidationResponse.RESULTS_DO_NOT_MATCH_QUERY,
        task.validateResult(
            new Response<>(
                // for the requested block, receipts returned are from another block
                Map.of(mockedRequestedBlock.block, toResponseReceipts(anotherBlockReceipts)),
                List.of())));
  }

  @Test
  public void validateResultSuccessWhenPartialBlockIsIncomplete() {
    final MockedBlock<B> mockedBlock = mockBlock(1, 3);

    final TASK task =
        createTask(
            new Request<>(
                List.of(mockedBlock.block),
                List.of(toResponseReceipt(mockedBlock.receipts.getFirst()))),
            protocolSchedule);

    assertEquals(
        PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD,
        task.validateResult(
            new Response<>(Map.of(), toResponseReceipts(mockedBlock.receipts).subList(0, 2))));
  }

  protected static BlockHeader mockBlockHeader(
      final long blockNumber, final List<TransactionReceipt> receipts) {
    BlockHeader blockHeader = mock(BlockHeader.class);
    when(blockHeader.getNumber()).thenReturn(blockNumber);
    // second to last hex digit indicates the blockNumber,
    // last hex digit indicates the usage of the hash
    when(blockHeader.getHash())
        .thenReturn(Hash.fromHexString(StringUtils.repeat("00", 31) + blockNumber + "1"));
    when(blockHeader.getReceiptsRoot()).thenReturn(BodyValidation.receiptsRoot(receipts));

    return blockHeader;
  }

  protected static List<TransactionReceipt> mockTransactionReceipts(
      final long blockNumber, final int count) {

    return IntStream.rangeClosed(1, count)
        .mapToObj(
            i -> new TransactionReceipt(1, 123L * i + blockNumber, emptyList(), Optional.empty()))
        .toList();
  }

  private EthPeer mockPeer(final long chainHeight) {
    EthPeer ethPeer = mock(EthPeer.class);
    ChainState chainState = mock(ChainState.class);

    when(ethPeer.chainState()).thenReturn(chainState);
    when(chainState.getEstimatedHeight()).thenReturn(chainHeight);
    when(chainState.getEstimatedTotalDifficulty()).thenReturn(Difficulty.of(0));
    when(ethPeer.getReputation()).thenReturn(new PeerReputation());
    PeerConnection connection = mock(PeerConnection.class);
    when(ethPeer.getConnection()).thenReturn(connection);
    return ethPeer;
  }
}
