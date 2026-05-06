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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.newpayload;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.CANCUN;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.PRAGUE;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.INVALID;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineTestSupport.fromErrorResp;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType.INVALID_PARAMS;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType.UNSUPPORTED_FORK;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.WithdrawalParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.engine.ExecutionPayloadV1;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.engine.ExecutionPayloadV3;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadStatusResult;
import org.hyperledger.besu.ethereum.core.BlobTestFixture;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.core.Withdrawal;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.encoding.TransactionEncoder;
import org.hyperledger.besu.ethereum.core.kzg.BlobsWithCommitments;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.CancunTargetingGasLimitCalculator;
import org.hyperledger.besu.ethereum.mainnet.ScheduledProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.feemarket.ExcessBlobGasCalculator;
import org.hyperledger.besu.evm.gascalculator.CancunGasCalculator;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EngineNewPayloadV3Test extends EngineNewPayloadV2Test {

  private static final SignatureAlgorithm SIGNATURE_ALGORITHM =
      SignatureAlgorithmFactory.getInstance();
  protected static final KeyPair senderKeys = SIGNATURE_ALGORITHM.generateKeyPair();

  protected Set<ScheduledProtocolSpec.Hardfork> supportedHardforks() {
    return Set.of(cancunHardfork);
  }

  @Override
  protected EngineNewPayloadV1<?> createMethodInstance() {
    return new EngineNewPayloadV3<ExecutionPayloadV3>(
        vertx,
        protocolSchedule,
        protocolContext,
        mergeCoordinator,
        ethPeers,
        engineCallListener,
        new NoOpMetricsSystem(),
        CANCUN,
        PRAGUE);
  }

  @BeforeEach
  @Override
  public void before() {
    super.before();
    maybeParentBeaconBlockRoot = Optional.of(Bytes32.ZERO);
    lenient().when(protocolSpec.getGasCalculator()).thenReturn(new CancunGasCalculator());
    lenient()
        .when(protocolSpec.getGasLimitCalculator())
        .thenReturn(mock(CancunTargetingGasLimitCalculator.class));
    createMethod();
  }

  @Override
  @Test
  public void shouldReturnExpectedMethodName() {
    assertThat(method.getName()).isEqualTo("engine_newPayloadV3");
  }

  @Override
  public void shouldReturnUnsupportedForkIfBlockTimestampIsAfterCancunMilestone() {
    // only relevant for v2
  }

  @Test
  public void shouldReturnUnsupportedForkIfBlockTimestampIsAfterPragueMilestone() {
    final BlockHeader pragueHeader =
        createBlockHeaderFixture(Optional.of(emptyList()))
            .timestamp(pragueHardfork.milestone())
            .excessBlobGas(BlobGas.ZERO)
            .blobGasUsed(0L)
            .buildHeader();

    var resp = resp(mockEnginePayload(pragueHeader, emptyList(), null));
    final JsonRpcError jsonRpcError = fromErrorResp(resp);
    assertThat(jsonRpcError.getCode()).isEqualTo(UNSUPPORTED_FORK.getCode());
    verify(engineCallListener, times(1)).executionEngineCalled();
  }

  @Test
  public void shouldReturnUnsupportedForkIfBlockTimestampIsBeforeCancunMilestone() {
    final BlockHeader shanghaiHeader =
        createBlockHeaderFixture(Optional.of(emptyList()))
            .timestamp(cancunHardfork.milestone() - 1)
            .excessBlobGas(BlobGas.ZERO)
            .blobGasUsed(0L)
            .buildHeader();

    var resp = resp(mockEnginePayload(shanghaiHeader, emptyList(), null));
    final JsonRpcError jsonRpcError = fromErrorResp(resp);
    assertThat(jsonRpcError.getCode()).isEqualTo(UNSUPPORTED_FORK.getCode());
    verify(engineCallListener, times(1)).executionEngineCalled();
  }

  @Test
  public void shouldReturnInvalidParamsIfBlobGasUsedIsNullBeforeCancun() {
    final BlockHeader preCancunHeader =
        createBlockHeaderFixture(Optional.of(emptyList()))
            .timestamp(cancunHardfork.milestone() - 1)
            .excessBlobGas(BlobGas.ZERO)
            .blobGasUsed(null)
            .buildHeader();

    var resp = resp(mockEnginePayload(preCancunHeader, emptyList(), null));
    final JsonRpcError jsonRpcError = fromErrorResp(resp);
    assertThat(jsonRpcError.getCode()).isEqualTo(INVALID_PARAMS.getCode());
    verify(engineCallListener, times(1)).executionEngineCalled();
  }

  @Test
  public void shouldReturnInvalidParamsIfExcessBlobGasIsNullBeforeCancun() {
    final BlockHeader preCancunHeader =
        createBlockHeaderFixture(Optional.of(emptyList()))
            .timestamp(cancunHardfork.milestone() - 1)
            .excessBlobGas(null)
            .blobGasUsed(0L)
            .buildHeader();

    var resp = resp(mockEnginePayload(preCancunHeader, emptyList(), null));
    final JsonRpcError jsonRpcError = fromErrorResp(resp);
    assertThat(jsonRpcError.getCode()).isEqualTo(INVALID_PARAMS.getCode());
    verify(engineCallListener, times(1)).executionEngineCalled();
  }

  @Test
  public void shouldInvalidVersionedHash_whenShortVersionedHash() {
    final Bytes shortHash = Bytes.fromHexString("0x" + "69".repeat(31));

    final ExecutionPayloadV3 payload = mock(ExecutionPayloadV3.class);
    when(payload.getTimestamp()).thenReturn(cancunHardfork.milestone());
    when(payload.getExcessBlobGas()).thenReturn("99");
    when(payload.getBlobGasUsed()).thenReturn(9l);

    final EngineNewPayloadV3<ExecutionPayloadV3> methodV3 =
        new EngineNewPayloadV3<ExecutionPayloadV3>(
            vertx,
            protocolSchedule,
            protocolContext,
            mergeCoordinator,
            ethPeers,
            engineCallListener,
            new NoOpMetricsSystem(),
            CANCUN,
            PRAGUE);
    final JsonRpcResponse badParam =
        methodV3.response(
            new JsonRpcRequestContext(
                new JsonRpcRequest(
                    "2.0",
                    RpcMethod.ENGINE_NEW_PAYLOAD_V3.getMethodName(),
                    new Object[] {
                      payload,
                      List.of(shortHash.toHexString()),
                      "0x0000000000000000000000000000000000000000000000000000000000000000"
                    })));
    final EnginePayloadStatusResult res = fromSuccessResp(badParam);
    assertThat(res.getStatusAsString()).isEqualTo(INVALID.name());
    assertThat(res.getError()).isEqualTo("Invalid versionedHash");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void validateVersionedHash_whenListIsPresentAndEmpty() {
    final BlockHeader mockHeader =
        setupValidPayload(
            new BlockProcessingResult(Optional.of(new BlockProcessingOutputs(null, List.of()))),
            Optional.empty());
    final ExecutionPayloadV3 payload = mockEnginePayload(mockHeader, emptyList(), null);

    ValidationResult<RpcErrorType> res =
        ((EngineNewPayloadV3) method)
            .validateParameters(
                payload,
                Optional.of(List.of()),
                Optional.of(
                    "0x0000000000000000000000000000000000000000000000000000000000000000"),
                Optional.empty());
    assertThat(res.isValid()).isTrue();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void validateExecutionRequests_whenPresent() {
    final BlockHeader mockHeader =
        setupValidPayload(
            new BlockProcessingResult(Optional.of(new BlockProcessingOutputs(null, List.of()))),
            Optional.empty());
    final ExecutionPayloadV3 payload = mockEnginePayload(mockHeader, emptyList(), null);

    ValidationResult<RpcErrorType> res =
        ((EngineNewPayloadV3) method)
            .validateParameters(
                payload,
                Optional.of(List.of()),
                Optional.of(
                    "0x0000000000000000000000000000000000000000000000000000000000000000"),
                Optional.of(emptyList()));
    assertThat(res.isValid()).isFalse();
    assertThat(res.getInvalidReason()).isEqualTo(RpcErrorType.INVALID_EXECUTION_REQUESTS_PARAMS);
  }

  @Override
  protected BlockHeader createBlockHeader(final Optional<List<Withdrawal>> maybeWithdrawals) {
    BlockHeader parentBlockHeader =
        new BlockHeaderTestFixture()
            .baseFeePerGas(Wei.ONE)
            .timestamp(super.cancunHardfork.milestone())
            .buildHeader();

    when(blockchain.getBlockHeader(parentBlockHeader.getBlockHash()))
        .thenReturn(Optional.of(parentBlockHeader));
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    BlockHeader mockHeader =
        new BlockHeaderTestFixture()
            .baseFeePerGas(Wei.ONE)
            .parentHash(parentBlockHeader.getParentHash())
            .number(parentBlockHeader.getNumber() + 1)
            .timestamp(parentBlockHeader.getTimestamp() + 12)
            .withdrawalsRoot(maybeWithdrawals.map(BodyValidation::withdrawalsRoot).orElse(null))
            .excessBlobGas(BlobGas.ZERO)
            .blobGasUsed(0L)
            .parentBeaconBlockRoot(
                maybeParentBeaconBlockRoot.isPresent() ? maybeParentBeaconBlockRoot : null)
            .buildHeader();
    return mockHeader;
  }

  @Override
  @Test
  public void shouldReturnValidIfProtocolScheduleIsEmpty() {
    // no longer the case, blob validation requires a protocol schedule
  }

  @Test
  public void shouldValidateBlobGasUsedCorrectly() {
    BlockHeader blockHeader =
        createBlockHeaderFixture(Optional.of(emptyList()))
            .timestamp(getTimestampForSupportedFork())
            .excessBlobGas(BlobGas.MAX_BLOB_GAS)
            .blobGasUsed(null)
            .buildHeader();

    var resp = resp(mockEnginePayload(blockHeader, emptyList(), List.of()));

    final JsonRpcError jsonRpcError = fromErrorResp(resp);
    assertThat(jsonRpcError.getCode()).isEqualTo(INVALID_PARAMS.getCode());
    assertThat(jsonRpcError.getData()).isEqualTo("Missing blob gas used field");
    verify(engineCallListener, times(1)).executionEngineCalled();
  }

  @Test
  public void shouldValidateExcessBlobGasCorrectly() {
    BlockHeader blockHeader =
        createBlockHeaderFixture(Optional.of(emptyList()))
            .timestamp(getTimestampForSupportedFork())
            .excessBlobGas(null)
            .blobGasUsed(100L)
            .buildHeader();

    var resp = resp(mockEnginePayload(blockHeader, emptyList(), List.of()));

    final JsonRpcError jsonRpcError = fromErrorResp(resp);
    assertThat(jsonRpcError.getCode()).isEqualTo(INVALID_PARAMS.getCode());
    assertThat(jsonRpcError.getData()).isEqualTo("Missing excess blob gas field");
    verify(engineCallListener, times(1)).executionEngineCalled();
  }

  @Test
  public void shouldRejectTransactionsWithFullBlobs() {
    Bytes transactionWithBlobsBytes =
        TransactionEncoder.encodeOpaqueBytes(
            createTransactionWithBlobs(), EncodingContext.POOLED_TRANSACTION);

    List<String> transactions = List.of(transactionWithBlobsBytes.toString());

    BlockHeader mockHeader =
        setupValidPayload(
            new BlockProcessingResult(Optional.of(new BlockProcessingOutputs(null, List.of()))),
            Optional.empty());
    var resp = resp(mockEnginePayload(mockHeader, transactions));

    EnginePayloadStatusResult res = fromSuccessResp(resp);
    assertThat(res.getStatusAsString()).isEqualTo(INVALID.name());
    assertThat(res.getError()).isEqualTo("Failed to decode transactions from block parameter");
    verify(engineCallListener, times(1)).executionEngineCalled();
  }

  private Transaction createTransactionWithBlobs() {
    BlobTestFixture blobTestFixture = new BlobTestFixture();
    BlobsWithCommitments bwc = blobTestFixture.createBlobsWithCommitments(1);

    return new TransactionTestFixture()
        .to(Optional.of(Address.fromHexString("0xDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF")))
        .type(TransactionType.BLOB)
        .chainId(Optional.of(BigInteger.ONE))
        .maxFeePerGas(Optional.of(Wei.of(15)))
        .maxFeePerBlobGas(Optional.of(Wei.of(128)))
        .maxPriorityFeePerGas(Optional.of(Wei.of(1)))
        .blobsWithCommitments(Optional.of(bwc))
        .versionedHashes(Optional.of(bwc.getVersionedHashes()))
        .createTransaction(senderKeys);
  }

  @Override
  protected JsonRpcResponse resp(final ExecutionPayloadV1 payload) {
    Object[] params =
        maybeParentBeaconBlockRoot
            .map(bytes32 -> new Object[] {payload, emptyList(), bytes32.toHexString()})
            .orElseGet(() -> new Object[] {payload});
    return method.response(
        new JsonRpcRequestContext(new JsonRpcRequest("2.0", this.method.getName(), params)));
  }

  @Override
  protected ExecutionPayloadV3 mockEnginePayload(
      final BlockHeader header, final List<String> txs) {
    return mockEnginePayload(header, txs, null);
  }

  @Override
  protected ExecutionPayloadV3 mockEnginePayload(
      final BlockHeader header,
      final List<String> txs,
      final List<WithdrawalParameter> withdrawals) {
    return new ExecutionPayloadV3(
        header.getHash(),
        header.getParentHash(),
        header.getCoinbase(),
        header.getStateRoot(),
        new org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter(header.getNumber()),
        header.getBaseFee().map(w -> w.toHexString()).orElse("0x0"),
        new org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter(header.getGasLimit()),
        new org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter(header.getGasUsed()),
        new org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter(header.getTimestamp()),
        header.getExtraData() == null ? null : header.getExtraData().toHexString(),
        header.getReceiptsRoot(),
        header.getLogsBloom(),
        header.getPrevRandao().map(Bytes32::toHexString).orElse("0x0"),
        txs,
        withdrawals,
        header
            .getBlobGasUsed()
            .map(org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter::new)
            .orElse(null),
        header.getExcessBlobGas().map(BlobGas::toHexString).orElse(null));
  }

  protected long getTimestampForSupportedFork() {
    final int index = new Random().nextInt(supportedHardforks().size());
    return supportedHardforks().stream().toList().get(index).milestone();
  }

  // ---- folded-in validation tests (formerly AbstractEngineNewPayloadValidationTest) ----
  // Exercise the protected validateBlobs / package-private validateExcessBlobGas /
  // validateBlobGasUsed methods on a real V3 instance. Same-package access lets us call them
  // directly.

  @Test
  void validateExcessBlobGas_valid_returnsEmpty() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final BlockHeader localParent = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);

    final BlobGas expected = BlobGas.of(1000);
    when(localHeader.getExcessBlobGas()).thenReturn(Optional.of(BlobGas.of(1000)));

    try (MockedStatic<ExcessBlobGasCalculator> mocked = mockStatic(ExcessBlobGasCalculator.class)) {
      mocked
          .when(() -> ExcessBlobGasCalculator.calculateExcessBlobGasForParent(localSpec, localParent))
          .thenReturn(expected);

      Optional<BlobGas> result = v3.validateExcessBlobGas(localHeader, localParent, localSpec);
      assertThat(result).isEmpty();
    }
  }

  @Test
  void validateExcessBlobGas_invalid_returnsCalculated() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final BlockHeader localParent = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);

    final BlobGas calculated = BlobGas.of(1000);
    when(localHeader.getExcessBlobGas()).thenReturn(Optional.of(BlobGas.of(800)));

    try (MockedStatic<ExcessBlobGasCalculator> mocked = mockStatic(ExcessBlobGasCalculator.class)) {
      mocked
          .when(() -> ExcessBlobGasCalculator.calculateExcessBlobGasForParent(localSpec, localParent))
          .thenReturn(calculated);

      Optional<BlobGas> result = v3.validateExcessBlobGas(localHeader, localParent, localSpec);
      assertThat(result).contains(calculated);
    }
  }

  @Test
  void validateBlobGasUsed_validSingleBlob_returnsEmpty() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);
    final GasCalculator localGasCalc = mock(GasCalculator.class);

    when(localSpec.getGasCalculator()).thenReturn(localGasCalc);
    when(localGasCalc.blobGasCost(1)).thenReturn(131072L);
    when(localHeader.getBlobGasUsed()).thenReturn(Optional.of(131072L));

    List<VersionedHash> versionedHashes = List.of(createValidVersionedHash());

    Optional<Long> result = v3.validateBlobGasUsed(localHeader, versionedHashes, localSpec);
    assertThat(result).isEmpty();
  }

  @Test
  void validateBlobGasUsed_invalid_returnsCalculated() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);
    final GasCalculator localGasCalc = mock(GasCalculator.class);

    when(localSpec.getGasCalculator()).thenReturn(localGasCalc);
    when(localGasCalc.blobGasCost(1)).thenReturn(131072L);
    when(localHeader.getBlobGasUsed()).thenReturn(Optional.of(100000L));

    List<VersionedHash> versionedHashes = List.of(createValidVersionedHash());

    Optional<Long> result = v3.validateBlobGasUsed(localHeader, versionedHashes, localSpec);
    assertThat(result).contains(131072L);
  }

  @Test
  void validateBlobGasUsed_invalidMultipleBlobs_returnsCalculated() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);
    final GasCalculator localGasCalc = mock(GasCalculator.class);

    when(localSpec.getGasCalculator()).thenReturn(localGasCalc);
    when(localGasCalc.blobGasCost(3)).thenReturn(131072L * 3);
    when(localHeader.getBlobGasUsed()).thenReturn(Optional.of(200000L));

    List<VersionedHash> versionedHashes =
        List.of(createValidVersionedHash(), createValidVersionedHash(), createValidVersionedHash());

    Optional<Long> result = v3.validateBlobGasUsed(localHeader, versionedHashes, localSpec);
    assertThat(result).contains(131072L * 3L);
  }

  @Test
  void validateBlobs_reportsEnhancedMessage_forExcessBlobGasMismatch() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final BlockHeader localParent = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);

    when(localHeader.getExcessBlobGas()).thenReturn(Optional.of(BlobGas.of(800)));

    var mockGasLimitCalculator = mock(org.hyperledger.besu.ethereum.GasLimitCalculator.class);
    when(localSpec.getGasLimitCalculator()).thenReturn(mockGasLimitCalculator);
    when(mockGasLimitCalculator.transactionBlobGasLimitCap()).thenReturn(1000000L);
    when(mockGasLimitCalculator.currentBlobGasLimit()).thenReturn(1000000L);

    try (MockedStatic<ExcessBlobGasCalculator> mocked = mockStatic(ExcessBlobGasCalculator.class)) {
      mocked
          .when(() -> ExcessBlobGasCalculator.calculateExcessBlobGasForParent(localSpec, localParent))
          .thenReturn(BlobGas.of(1000));

      var result =
          v3.validateBlobs(
              List.of(), localHeader, Optional.of(localParent), Optional.empty(), localSpec);

      assertThat(result.isValid()).isFalse();
      assertThat(result.getErrorMessage()).contains("Expected", "1000");
      assertThat(result.getErrorMessage()).contains("got", "800");
    }
  }

  @Test
  void validateBlobs_reportsEnhancedMessage_forBlobGasUsedMismatch() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final BlockHeader localParent = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);
    final GasCalculator localGasCalc = mock(GasCalculator.class);

    when(localSpec.getGasCalculator()).thenReturn(localGasCalc);
    when(localGasCalc.blobGasCost(2L)).thenReturn(262144L);
    when(localGasCalc.blobGasCost(1L)).thenReturn(131072L);

    var mockGasLimitCalculator = mock(org.hyperledger.besu.ethereum.GasLimitCalculator.class);
    when(localSpec.getGasLimitCalculator()).thenReturn(mockGasLimitCalculator);
    when(mockGasLimitCalculator.transactionBlobGasLimitCap()).thenReturn(1000000L);
    when(mockGasLimitCalculator.currentBlobGasLimit()).thenReturn(1000000L);

    when(localHeader.getBlobGasUsed()).thenReturn(Optional.of(100000L));

    List<VersionedHash> versionedHashes =
        List.of(createValidVersionedHash(1), createValidVersionedHash(2));

    Transaction blobTx1 = mock(Transaction.class);
    Transaction blobTx2 = mock(Transaction.class);
    when(blobTx1.getVersionedHashes()).thenReturn(Optional.of(List.of(versionedHashes.get(0))));
    when(blobTx2.getVersionedHashes()).thenReturn(Optional.of(List.of(versionedHashes.get(1))));
    List<Transaction> blobTransactions = List.of(blobTx1, blobTx2);

    var result =
        v3.validateBlobs(
            blobTransactions,
            localHeader,
            Optional.of(localParent),
            Optional.of(versionedHashes),
            localSpec);

    assertThat(result.isValid()).isFalse();
    assertThat(result.getErrorMessage()).contains("Expected", "262144");
    assertThat(result.getErrorMessage()).contains("got", "100000");
  }

  @Test
  void validateBlobs_invalidWhenVersionedHashesMismatch() {
    final EngineNewPayloadV3<?> v3 = (EngineNewPayloadV3<?>) method;
    final BlockHeader localHeader = mock(BlockHeader.class);
    final BlockHeader localParent = mock(BlockHeader.class);
    final org.hyperledger.besu.ethereum.mainnet.ProtocolSpec localSpec =
        mock(org.hyperledger.besu.ethereum.mainnet.ProtocolSpec.class);
    final GasCalculator localGasCalc = mock(GasCalculator.class);

    when(localSpec.getGasCalculator()).thenReturn(localGasCalc);
    when(localGasCalc.blobGasCost(1L)).thenReturn(131072L);

    var mockGasLimitCalculator = mock(org.hyperledger.besu.ethereum.GasLimitCalculator.class);
    when(localSpec.getGasLimitCalculator()).thenReturn(mockGasLimitCalculator);
    when(mockGasLimitCalculator.transactionBlobGasLimitCap()).thenReturn(1000000L);

    Transaction blobTx = mock(Transaction.class);
    List<VersionedHash> txHashes = List.of(createValidVersionedHash(1));
    when(blobTx.getVersionedHashes()).thenReturn(Optional.of(txHashes));

    List<Transaction> blobTransactions = List.of(blobTx);
    List<VersionedHash> provided = List.of(createValidVersionedHash(2));

    var result =
        v3.validateBlobs(
            blobTransactions,
            localHeader,
            Optional.of(localParent),
            Optional.of(provided),
            localSpec);

    assertThat(result.isValid()).isFalse();
    assertThat(result.getErrorMessage()).isNotNull();
  }

  private VersionedHash createValidVersionedHash() {
    byte[] validHash = new byte[32];
    validHash[0] = 0x01;
    for (int i = 1; i < 32; i++) {
      validHash[i] = (byte) (i % 256);
    }
    return new VersionedHash(Bytes32.wrap(validHash));
  }

  private VersionedHash createValidVersionedHash(final int seed) {
    byte[] validHash = new byte[32];
    validHash[0] = 0x01;
    for (int i = 1; i < 32; i++) {
      validHash[i] = (byte) ((i + seed) % 256);
    }
    return new VersionedHash(Bytes32.wrap(validHash));
  }
}
