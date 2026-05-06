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

import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.ACCEPTED;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.INVALID;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.INVALID_BLOCK_HASH;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.SYNCING;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.VALID;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.RequestValidatorProvider.getRequestsValidator;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.WithdrawalsValidatorProvider.getWithdrawalsValidator;
import static org.hyperledger.besu.metrics.BesuMetricCategory.BLOCK_PROCESSING;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.HardforkId;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.RequestType;
import org.hyperledger.besu.datatypes.VersionedHash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcRequestException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineCallListener;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.ForkSupportHelper;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.engine.ExecutionPayloadV1;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadStatusResult;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderFunctions;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.Request;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Withdrawal;
import org.hyperledger.besu.ethereum.core.encoding.EncodingContext;
import org.hyperledger.besu.ethereum.core.encoding.TransactionDecoder;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.rlp.RLPException;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.exception.StorageException;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code engine_newPayloadV1} — Paris (The Merge).
 *
 * <p>Concrete base of the {@code engine_newPayload} sealed hierarchy. Owns the full request
 * pipeline; later versions extend and override specific hooks ({@link #validateParameters}, {@link
 * #validateBlobs}, {@link #extractBlockAccessList}, {@link #getInvalidBlockHashStatus}, plus
 * version-specific accessor hooks {@link #extractWithdrawals}, {@link #payloadBlobGasUsed}, {@link
 * #payloadExcessBlobGas}, {@link #payloadSlotNumber}) to introduce their own concepts without
 * modifying this class.
 *
 * <p>Parameterised so subversions can narrow the payload type via an upper bound on {@code EP}.
 *
 * <p>Fork-range validation is owned exclusively by V1 via {@link ForkSupportHelper}: the
 * constructor takes {@code (HardforkId minSupportedFork, HardforkId firstUnsupportedFork)} and
 * passing {@code null} for either side means "no bound on that side". Concrete subversions never
 * call {@link ForkSupportHelper} directly.
 *
 * @param <EP> the {@link ExecutionPayloadV1} subtype this version accepts as RPC parameter index 0
 */
public sealed class EngineNewPayloadV1<EP extends ExecutionPayloadV1>
    extends ExecutionEngineJsonRpcMethod permits EngineNewPayloadV2 {

  private static final Hash OMMERS_HASH_CONSTANT = Hash.EMPTY_LIST_HASH;
  private static final Logger LOG = LoggerFactory.getLogger(EngineNewPayloadV1.class);
  private static final BlockHeaderFunctions headerFunctions = new MainnetBlockHeaderFunctions();
  private final MergeMiningCoordinator mergeCoordinator;
  private final EthPeers ethPeers;
  private long lastExecutionTimeInNs = 0L;

  private final HardforkId minSupportedFork;
  private final HardforkId firstUnsupportedFork;
  private final Optional<Long> minForkMilestone;
  private final Optional<Long> maxForkMilestone;

  /**
   * Creates an {@code engine_newPayloadV1} method.
   *
   * @param vertx the Vert.x instance used for the consensus engine worker pool
   * @param protocolSchedule the protocol schedule, queried for fork milestones
   * @param protocolContext the protocol context
   * @param mergeCoordinator the merge mining coordinator that executes payloads
   * @param ethPeers used to report current peer count in the imported-block log
   * @param engineCallListener listener that records every engine API call
   * @param metricsSystem registers the {@code execution_time_head} gauge
   * @param minSupportedFork lower-bound fork (inclusive); {@code null} means no lower bound
   * @param firstUnsupportedFork upper-bound fork (exclusive); {@code null} means no upper bound
   */
  public EngineNewPayloadV1(
      final Vertx vertx,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final MergeMiningCoordinator mergeCoordinator,
      final EthPeers ethPeers,
      final EngineCallListener engineCallListener,
      final MetricsSystem metricsSystem,
      final HardforkId minSupportedFork,
      final HardforkId firstUnsupportedFork) {
    super(vertx, protocolSchedule, protocolContext, engineCallListener);
    this.mergeCoordinator = mergeCoordinator;
    this.ethPeers = ethPeers;
    metricsSystem.createLongGauge(
        BLOCK_PROCESSING,
        "execution_time_head",
        "The execution time of the last block (head)",
        this::getLastExecutionTime);

    this.minSupportedFork = minSupportedFork;
    this.firstUnsupportedFork = firstUnsupportedFork;
    this.minForkMilestone =
        minSupportedFork != null
            ? protocolSchedule.milestoneFor(minSupportedFork)
            : Optional.empty();
    this.maxForkMilestone =
        firstUnsupportedFork != null
            ? protocolSchedule.milestoneFor(firstUnsupportedFork)
            : Optional.empty();
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_NEW_PAYLOAD_V1.getMethodName();
  }

  /**
   * Returns the concrete {@link ExecutionPayloadV1} subclass to use when deserializing RPC
   * parameter index 0 for this method version. Each subversion overrides to narrow the type.
   *
   * @return the payload class
   */
  @SuppressWarnings("unchecked")
  protected Class<EP> getEnginePayloadParameterClass() {
    return (Class<EP>) ExecutionPayloadV1.class;
  }

  @Override
  public JsonRpcResponse syncResponse(final JsonRpcRequestContext requestContext) {
    engineCallListener.executionEngineCalled();
    final EP blockParam;
    try {
      blockParam = requestContext.getRequiredParameter(0, getEnginePayloadParameterClass());
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcRequestException(
          "Invalid engine payload parameter (index 0)",
          RpcErrorType.INVALID_ENGINE_NEW_PAYLOAD_PARAMS,
          e);
    }

    final Optional<List<String>> maybeVersionedHashParam;
    try {
      maybeVersionedHashParam = requestContext.getOptionalList(1, String.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcRequestException(
          "Invalid versioned hash parameters (index 1)",
          RpcErrorType.INVALID_VERSIONED_HASH_PARAMS,
          e);
    }

    final Object reqId = requestContext.getRequest().getId();

    Optional<String> maybeParentBeaconBlockRootParam;
    try {
      maybeParentBeaconBlockRootParam = requestContext.getOptionalParameter(2, String.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcRequestException(
          "Invalid parent beacon block root parameters (index 2)",
          RpcErrorType.INVALID_PARENT_BEACON_BLOCK_ROOT_PARAMS,
          e);
    }
    final Optional<Bytes32> maybeParentBeaconBlockRoot =
        maybeParentBeaconBlockRootParam.map(Bytes32::fromHexString);

    final Optional<List<String>> maybeRequestsParam;
    try {
      maybeRequestsParam = requestContext.getOptionalList(3, String.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcRequestException(
          "Invalid execution request parameters (index 3)",
          RpcErrorType.INVALID_EXECUTION_REQUESTS_PARAMS,
          e);
    }

    final ValidationResult<RpcErrorType> parameterValidationResult =
        validateParameters(
            blockParam,
            maybeVersionedHashParam,
            maybeParentBeaconBlockRootParam,
            maybeRequestsParam);
    if (!parameterValidationResult.isValid()) {
      return new JsonRpcErrorResponse(reqId, parameterValidationResult);
    }

    final ValidationResult<RpcErrorType> forkValidationResult =
        validateForkSupported(blockParam.getTimestamp());
    if (!forkValidationResult.isValid()) {
      return new JsonRpcErrorResponse(reqId, forkValidationResult);
    }

    final Optional<List<VersionedHash>> maybeVersionedHashes;
    try {
      maybeVersionedHashes = extractVersionedHashes(maybeVersionedHashParam);
    } catch (RuntimeException ex) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator.getLatestValidAncestor(blockParam.getParentHash()).orElse(null),
          INVALID,
          "Invalid versionedHash");
    }

    final Optional<BlockHeader> maybeParentHeader =
        protocolContext.getBlockchain().getBlockHeader(blockParam.getParentHash());

    LOG.atTrace()
        .setMessage("blockparam: {}")
        .addArgument(() -> Json.encodePrettily(blockParam))
        .log();

    final Optional<List<Withdrawal>> maybeWithdrawals = extractWithdrawals(blockParam);

    if (!getWithdrawalsValidator(
            protocolSchedule.get(), blockParam.getTimestamp(), blockParam.getBlockNumber())
        .validateWithdrawals(maybeWithdrawals)) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.INVALID_WITHDRAWALS_PARAMS);
    }

    final Optional<List<Request>> maybeRequests;
    try {
      maybeRequests = extractRequests(maybeRequestsParam);
    } catch (RequestType.InvalidRequestTypeException ex) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator.getLatestValidAncestor(blockParam.getParentHash()).orElse(null),
          INVALID,
          "Invalid execution requests");
    } catch (Exception ex) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.INVALID_EXECUTION_REQUESTS_PARAMS);
    }

    if (!getRequestsValidator(
            protocolSchedule.get(), blockParam.getTimestamp(), blockParam.getBlockNumber())
        .validate(maybeRequests)) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.INVALID_EXECUTION_REQUESTS_PARAMS);
    }

    final Optional<BlockAccessList> maybeBlockAccessList;
    try {
      maybeBlockAccessList = extractBlockAccessList(blockParam);
    } catch (final InvalidBlockAccessListException e) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator.getLatestValidAncestor(blockParam.getParentHash()).orElse(null),
          INVALID,
          e.getMessage());
    }

    if (mergeContext.get().isSyncing()) {
      LOG.debug("We are syncing");
      return respondWith(reqId, blockParam, null, SYNCING);
    }

    final List<Transaction> transactions;
    try {
      transactions =
          blockParam.getTransactions().stream()
              .map(Bytes::fromHexString)
              .map(in -> TransactionDecoder.decodeOpaqueBytes(in, EncodingContext.BLOCK_BODY))
              .toList();
      precomputeSenders(transactions);
    } catch (final RLPException | IllegalArgumentException e) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator.getLatestValidAncestor(blockParam.getParentHash()).orElse(null),
          INVALID,
          "Failed to decode transactions from block parameter");
    }

    if (blockParam.getExtraData() == null) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator.getLatestValidAncestor(blockParam.getParentHash()).orElse(null),
          INVALID,
          "Field extraData must not be null");
    }

    final BlockHeader newBlockHeader =
        new BlockHeader(
            blockParam.getParentHash(),
            OMMERS_HASH_CONSTANT,
            blockParam.getFeeRecipient(),
            blockParam.getStateRoot(),
            BodyValidation.transactionsRoot(transactions),
            blockParam.getReceiptsRoot(),
            blockParam.getLogsBloom(),
            Difficulty.ZERO,
            blockParam.getBlockNumber(),
            blockParam.getGasLimit(),
            blockParam.getGasUsed(),
            blockParam.getTimestamp(),
            Bytes.fromHexString(blockParam.getExtraData()),
            blockParam.getBaseFeePerGas(),
            blockParam.getPrevRandao(),
            0,
            maybeWithdrawals.map(BodyValidation::withdrawalsRoot).orElse(null),
            payloadBlobGasUsed(blockParam),
            payloadExcessBlobGas(blockParam),
            maybeParentBeaconBlockRoot.orElse(null),
            maybeRequests.map(BodyValidation::requestsHash).orElse(null),
            maybeBlockAccessList.map(BodyValidation::balHash).orElse(null),
            payloadSlotNumber(blockParam),
            headerFunctions);

    // ensure the block hash matches the blockParam hash
    // this must be done before any other check
    if (!newBlockHeader.getHash().equals(blockParam.getBlockHash())) {
      String errorMessage =
          String.format(
              "Computed block hash %s does not match block hash parameter %s",
              newBlockHeader.getBlockHash(), blockParam.getBlockHash());
      LOG.debug(errorMessage);
      return respondWithInvalid(reqId, blockParam, null, getInvalidBlockHashStatus(), errorMessage);
    }

    final var blobTransactions =
        transactions.stream().filter(transaction -> transaction.getType().supportsBlob()).toList();

    ValidationResult<RpcErrorType> blobValidationResult =
        validateBlobs(
            blobTransactions,
            newBlockHeader,
            maybeParentHeader,
            maybeVersionedHashes,
            protocolSchedule.get().getByBlockHeader(newBlockHeader));
    if (!blobValidationResult.isValid()) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator.getLatestValidAncestor(blockParam.getParentHash()).orElse(null),
          getInvalidBlockHashStatus(),
          blobValidationResult.getErrorMessage());
    }

    // do we already have this payload
    if (protocolContext.getBlockchain().getBlockByHash(newBlockHeader.getBlockHash()).isPresent()) {
      LOG.debug("block already present");
      return respondWith(reqId, blockParam, blockParam.getBlockHash(), VALID);
    }
    if (mergeCoordinator.isBadBlock(blockParam.getBlockHash())) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator
              .getLatestValidHashOfBadBlock(blockParam.getBlockHash())
              .orElse(Hash.ZERO),
          INVALID,
          "Block already present in bad block manager.");
    }

    if (maybeParentHeader.isPresent()
        && (Long.compareUnsigned(maybeParentHeader.get().getTimestamp(), blockParam.getTimestamp())
            >= 0)) {
      return respondWithInvalid(
          reqId,
          blockParam,
          mergeCoordinator.getLatestValidAncestor(blockParam.getParentHash()).orElse(null),
          INVALID,
          "block timestamp not greater than parent");
    }

    final var block =
        new Block(
            newBlockHeader, new BlockBody(transactions, Collections.emptyList(), maybeWithdrawals));

    if (maybeParentHeader.isEmpty()) {
      LOG.atDebug()
          .setMessage("Parent of block {} is not present, append it to backward sync")
          .addArgument(block::toLogString)
          .log();
      mergeCoordinator.appendNewPayloadToSync(block);
      return respondWith(reqId, blockParam, null, SYNCING);
    }

    final var latestValidAncestor = mergeCoordinator.getLatestValidAncestor(newBlockHeader);

    if (latestValidAncestor.isEmpty()) {
      return respondWith(reqId, blockParam, null, ACCEPTED);
    }

    // execute block and return result response
    final long startTimeNs = System.nanoTime();
    final BlockProcessingResult executionResult =
        mergeCoordinator.rememberBlock(block, maybeBlockAccessList);
    if (executionResult.isSuccessful()) {
      lastExecutionTimeInNs = System.nanoTime() - startTimeNs;
      logImportedBlockInfo(
          block,
          blobTransactions.stream()
              .map(Transaction::getVersionedHashes)
              .flatMap(Optional::stream)
              .mapToInt(List::size)
              .sum(),
          lastExecutionTimeInNs,
          executionResult.getNbParallelizedTransactions());
      return respondWith(reqId, blockParam, newBlockHeader.getHash(), VALID);
    } else {
      if (executionResult.causedBy().isPresent()) {
        Throwable causedBy = executionResult.causedBy().get();
        if (causedBy instanceof StorageException || causedBy instanceof MerkleTrieException) {
          RpcErrorType error = RpcErrorType.INTERNAL_ERROR;
          JsonRpcErrorResponse response = new JsonRpcErrorResponse(reqId, error);
          return response;
        }
      }
      LOG.debug("New payload is invalid: {}", executionResult.errorMessage.get());
      return respondWithInvalid(
          reqId,
          blockParam,
          latestValidAncestor.get(),
          INVALID,
          executionResult.errorMessage.get());
    }
  }

  private void precomputeSenders(final List<Transaction> transactions) {
    transactions.forEach(
        transaction -> {
          mergeCoordinator
              .getEthScheduler()
              .scheduleComputationTask(
                  () -> {
                    final var sender = transaction.getSender();
                    LOG.atTrace()
                        .setMessage("The sender for transaction {} is calculated : {}")
                        .addArgument(transaction::getHash)
                        .addArgument(sender)
                        .log();
                    return sender;
                  });
          if (transaction.getType().supportsDelegateCode()) {
            precomputeAuthorities(transaction);
          }
        });
  }

  private void precomputeAuthorities(final Transaction transaction) {
    final var codeDelegations = transaction.getCodeDelegationList().get();
    int index = 0;
    for (final var codeDelegation : codeDelegations) {
      final var constIndex = index++;
      mergeCoordinator
          .getEthScheduler()
          .scheduleComputationTask(
              () -> {
                final var authority = codeDelegation.authorizer();
                LOG.atTrace()
                    .setMessage(
                        "The code delegation authority at index {} for transaction {} is calculated : {}")
                    .addArgument(constIndex)
                    .addArgument(transaction::getHash)
                    .addArgument(authority)
                    .log();
                return authority;
              });
    }
  }

  JsonRpcResponse respondWith(
      final Object requestId,
      final EP param,
      final Hash latestValidHash,
      final EngineStatus status) {
    if (INVALID.equals(status) || INVALID_BLOCK_HASH.equals(status)) {
      throw new IllegalArgumentException(
          "Don't call respondWith() with invalid status of " + status.toString());
    }
    LOG.atDebug()
        .setMessage(
            "New payload: number: {}, hash: {}, parentHash: {}, latestValidHash: {}, status: {}")
        .addArgument(param::getBlockNumber)
        .addArgument(param::getBlockHash)
        .addArgument(param::getParentHash)
        .addArgument(
            () -> latestValidHash == null ? null : latestValidHash.getBytes().toHexString())
        .addArgument(status::name)
        .log();
    return new JsonRpcSuccessResponse(
        requestId, new EnginePayloadStatusResult(status, latestValidHash, Optional.empty()));
  }

  // engine api calls are synchronous, no need for volatile
  private long lastInvalidWarn = 0;

  JsonRpcResponse respondWithInvalid(
      final Object requestId,
      final EP param,
      final Hash latestValidHash,
      final EngineStatus invalidStatus,
      final String validationError) {
    if (!INVALID.equals(invalidStatus) && !INVALID_BLOCK_HASH.equals(invalidStatus)) {
      throw new IllegalArgumentException(
          "Don't call respondWithInvalid() with non-invalid status of " + invalidStatus.toString());
    }
    final String invalidBlockLogMessage =
        String.format(
            "Invalid new payload: number: %s, hash: %s, parentHash: %s, latestValidHash: %s, status: %s, validationError: %s",
            param.getBlockNumber(),
            param.getBlockHash(),
            param.getParentHash(),
            latestValidHash == null ? null : latestValidHash.getBytes().toHexString(),
            invalidStatus.name(),
            validationError);
    // always log invalid at DEBUG
    LOG.debug(invalidBlockLogMessage);
    // periodically log at WARN
    if (lastInvalidWarn + ENGINE_API_LOGGING_THRESHOLD < System.currentTimeMillis()) {
      lastInvalidWarn = System.currentTimeMillis();
      LOG.warn(invalidBlockLogMessage);
    }
    return new JsonRpcSuccessResponse(
        requestId,
        new EnginePayloadStatusResult(
            invalidStatus, latestValidHash, Optional.of(validationError)));
  }

  /**
   * Returns the {@link EngineStatus} returned when the computed block hash does not match the
   * payload's claimed hash, or when blob validation fails. V1 returns {@link
   * EngineStatus#INVALID_BLOCK_HASH}; V2+ returns {@link EngineStatus#INVALID}.
   *
   * @return the status to use for invalid-block-hash responses
   */
  protected EngineStatus getInvalidBlockHashStatus() {
    return INVALID_BLOCK_HASH;
  }

  /**
   * Validates version-specific shape of the request parameters. V1 has no version-specific
   * requirements; later versions override to enforce the JSON-RPC parameters introduced by their
   * fork (parent beacon block root, execution requests, etc.).
   *
   * @param parameter the typed payload parameter (index 0)
   * @param maybeVersionedHashParam the versioned hash list (index 1)
   * @param maybeBeaconBlockRootParam the parent beacon block root (index 2)
   * @param maybeRequestsParam the execution requests list (index 3)
   * @return validation result; non-valid causes the request to be rejected
   */
  protected ValidationResult<RpcErrorType> validateParameters(
      final EP parameter,
      final Optional<List<String>> maybeVersionedHashParam,
      final Optional<String> maybeBeaconBlockRootParam,
      final Optional<List<String>> maybeRequestsParam) {
    return ValidationResult.valid();
  }

  /**
   * Extracts the withdrawal list from the payload. V1 returns {@link Optional#empty()} (V1 doesn't
   * carry withdrawals); V2+ overrides to read the withdrawal field from its typed payload.
   *
   * @param payload the typed payload parameter
   * @return the parsed withdrawals, or {@link Optional#empty()} when this version does not carry
   *     them
   */
  protected Optional<List<Withdrawal>> extractWithdrawals(final EP payload) {
    return Optional.empty();
  }

  /**
   * Returns the payload's {@code blobGasUsed} field. V1/V2 return {@code null} (the field is not
   * part of their payloads); V3+ overrides to read the typed payload's value.
   *
   * @param payload the typed payload parameter
   * @return the blob gas used, or {@code null} when not part of this version's payload
   */
  protected Long payloadBlobGasUsed(final EP payload) {
    return null;
  }

  /**
   * Returns the payload's {@code excessBlobGas} parsed into a {@link BlobGas}. V1/V2 return {@code
   * null}; V3+ overrides to parse the hex value from the typed payload.
   *
   * @param payload the typed payload parameter
   * @return the excess blob gas, or {@code null} when not part of this version's payload
   */
  protected BlobGas payloadExcessBlobGas(final EP payload) {
    return null;
  }

  /**
   * Returns the payload's {@code slotNumber} field. V1–V4 return {@code null}; V5 overrides to read
   * the typed payload's value.
   *
   * @param payload the typed payload parameter
   * @return the slot number, or {@code null} when not part of this version's payload
   */
  protected Long payloadSlotNumber(final EP payload) {
    return null;
  }

  /**
   * Decodes and returns the optional block access list (BAL) from the payload parameter. V1 does
   * not support BAL; V5 overrides to decode the field and reject when missing or malformed.
   *
   * @param payloadParameter the typed payload parameter
   * @return the decoded BAL, or {@link Optional#empty()} when this version does not support it
   * @throws InvalidBlockAccessListException when the field is missing or malformed in versions that
   *     require it
   */
  protected Optional<BlockAccessList> extractBlockAccessList(final EP payloadParameter)
      throws InvalidBlockAccessListException {
    return Optional.empty();
  }

  /** Signals that the block access list field is missing or has malformed encoding. */
  protected static class InvalidBlockAccessListException extends Exception {
    /**
     * Creates an exception with the given message.
     *
     * @param message the detail message
     */
    InvalidBlockAccessListException(final String message) {
      super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    InvalidBlockAccessListException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Validates fork-range support for the given block timestamp. Owned exclusively by V1; concrete
   * subversions never override this. The constructor-injected {@code minSupportedFork} and {@code
   * firstUnsupportedFork} drive the decision.
   *
   * @param blockTimestamp the block timestamp
   * @return validation result; non-valid yields {@code UNSUPPORTED_FORK}
   */
  @Override
  protected ValidationResult<RpcErrorType> validateForkSupported(final long blockTimestamp) {
    return ForkSupportHelper.validateForkSupported(
        minSupportedFork, minForkMilestone, firstUnsupportedFork, maxForkMilestone, blockTimestamp);
  }

  /**
   * Validates blob-related fields against the transactions, header, and parent. V1 returns valid
   * unconditionally (no blob support); V3 introduces the full implementation.
   *
   * @param blobTransactions the blob-bearing transactions in the payload
   * @param header the new block header
   * @param maybeParentHeader the parent header, if known
   * @param maybeVersionedHashes the versioned hashes parameter, if provided
   * @param protocolSpec the protocol spec resolved for the new header
   * @return validation result
   */
  protected ValidationResult<RpcErrorType> validateBlobs(
      final List<Transaction> blobTransactions,
      final BlockHeader header,
      final Optional<BlockHeader> maybeParentHeader,
      final Optional<List<VersionedHash>> maybeVersionedHashes,
      final ProtocolSpec protocolSpec) {
    return ValidationResult.valid();
  }

  private Optional<List<VersionedHash>> extractVersionedHashes(
      final Optional<List<String>> maybeVersionedHashParam) {
    return maybeVersionedHashParam.map(
        versionedHashes ->
            versionedHashes.stream()
                .map(Bytes32::fromHexString)
                .map(
                    hash -> {
                      try {
                        return new VersionedHash(hash);
                      } catch (InvalidParameterException e) {
                        throw new RuntimeException(e);
                      }
                    })
                .collect(Collectors.toList()));
  }

  private Optional<List<Request>> extractRequests(final Optional<List<String>> maybeRequestsParam) {
    if (maybeRequestsParam.isEmpty()) {
      return Optional.empty();
    }
    return maybeRequestsParam.map(
        requests ->
            requests.stream()
                .map(
                    s -> {
                      final Bytes request = Bytes.fromHexString(s);
                      final Bytes requestData = request.slice(1);
                      if (requestData.isEmpty()) {
                        throw new IllegalArgumentException("Request data cannot be empty");
                      }
                      return new Request(RequestType.of(request.get(0)), requestData);
                    })
                .collect(Collectors.toList()));
  }

  private void logImportedBlockInfo(
      final Block block,
      final int blobCount,
      final long timeInNs,
      final Optional<Integer> nbParallelizedTransactions) {
    final StringBuilder message = new StringBuilder();
    final int nbTransactions = block.getBody().getTransactions().size();
    message.append("Imported #%,d  (%s)| %4d tx");
    final List<Object> messageArgs =
        new ArrayList<>(
            List.of(
                block.getHeader().getNumber(), block.getHash().toShortLogString(), nbTransactions));
    if (nbParallelizedTransactions.isPresent()) {
      double parallelizedTxPercentage =
          (double) (nbParallelizedTransactions.get() * 100) / nbTransactions;
      message.append(" (%5.1f%% parallel)");
      messageArgs.add(parallelizedTxPercentage);
    }
    if (block.getBody().getWithdrawals().isPresent()) {
      message.append("| %2d ws");
      messageArgs.add(block.getBody().getWithdrawals().get().size());
    }
    double mgasPerSec =
        (timeInNs != 0) ? (double) (block.getHeader().getGasUsed() * 1_000) / timeInNs : 0;
    double timeInMs = (double) timeInNs / 1_000_000;
    boolean timeOverOrEq1second = timeInMs >= 1_000;
    if (timeOverOrEq1second) {
      message.append(
          "| %2d blobs| %s bfee| %,11d (%5.1f%%) gas used| %01.3fs exec| %6.2f Mgas/s| %2d peers");
    } else {
      message.append(
          "| %2d blobs| %s bfee| %,11d (%5.1f%%) gas used| %03.1fms exec| %6.2f Mgas/s| %2d peers");
    }
    messageArgs.addAll(
        List.of(
            blobCount,
            block.getHeader().getBaseFee().map(Wei::toHumanReadablePaddedString).orElse("N/A"),
            block.getHeader().getGasUsed(),
            (block.getHeader().getGasUsed() * 100.0) / block.getHeader().getGasLimit(),
            timeOverOrEq1second ? timeInMs / 1_000 : timeInMs,
            mgasPerSec,
            ethPeers.peerCount()));
    LOG.info(String.format(message.toString(), messageArgs.toArray()));
  }

  private long getLastExecutionTime() {
    return this.lastExecutionTimeInNs;
  }
}
