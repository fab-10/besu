/*
 * Copyright Hyperledger Besu Contributors.
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
package org.hyperledger.besu.ethereum.blockcreation.txselection;

import static org.hyperledger.besu.plugin.data.TransactionSelectionResult.BLOCK_SELECTION_TIMEOUT;
import static org.hyperledger.besu.plugin.data.TransactionSelectionResult.INTERNAL_ERROR;
import static org.hyperledger.besu.plugin.data.TransactionSelectionResult.SELECTED;
import static org.hyperledger.besu.plugin.data.TransactionSelectionResult.TX_EVALUATION_TIMEOUT;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.GasLimitCalculator;
import org.hyperledger.besu.ethereum.blockcreation.txselection.selectors.AbstractTransactionSelector;
import org.hyperledger.besu.ethereum.blockcreation.txselection.selectors.BlobPriceTransactionSelector;
import org.hyperledger.besu.ethereum.blockcreation.txselection.selectors.BlockSizeTransactionSelector;
import org.hyperledger.besu.ethereum.blockcreation.txselection.selectors.MinPriorityFeePerGasTransactionSelector;
import org.hyperledger.besu.ethereum.blockcreation.txselection.selectors.PriceTransactionSelector;
import org.hyperledger.besu.ethereum.blockcreation.txselection.selectors.ProcessingResultTransactionSelector;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPInput;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.vm.BlockHashLookup;
import org.hyperledger.besu.ethereum.vm.CachingBlockHashLookup;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;
import org.hyperledger.besu.plugin.services.txselection.PluginTransactionSelector;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for extracting transactions from PendingTransactions and determining if the
 * transaction is suitable for inclusion in the block defined by the provided
 * ProcessableBlockHeader.
 *
 * <p>If a transaction is suitable for inclusion, the world state must be updated, and a receipt
 * generated.
 *
 * <p>The output from this class's execution will be:
 *
 * <ul>
 *   <li>A list of transactions to include in the block being constructed.
 *   <li>A list of receipts for inclusion in the block.
 *   <li>The root hash of the world state at the completion of transaction execution.
 *   <li>The amount of gas consumed when executing all transactions.
 *   <li>A list of transactions evaluated but not included in the block being constructed.
 * </ul>
 *
 * Once "used" this class must be discarded and another created. This class contains state which is
 * not cleared between executions of buildTransactionListForBlock().
 */
public class BlockTransactionSelector {
  private static final Logger LOG = LoggerFactory.getLogger(BlockTransactionSelector.class);
  private final Supplier<Boolean> isCancelled;
  private final MainnetTransactionProcessor transactionProcessor;
  private final Blockchain blockchain;
  private final MutableWorldState worldState;
  private final AbstractBlockProcessor.TransactionReceiptFactory transactionReceiptFactory;
  private final BlockSelectionContext blockSelectionContext;
  private final TransactionSelectionResults transactionSelectionResults =
      new TransactionSelectionResults();
  private final List<AbstractTransactionSelector> transactionSelectors;
  private final PluginTransactionSelector pluginTransactionSelector;
  private final BlockAwareOperationTracer pluginOperationTracer;
  private final EthScheduler ethScheduler;
  private final AtomicBoolean isBlockTimeout = new AtomicBoolean(false);
  private final long txsSelectionMaxTime;
  private final long txEvaluationMaxTime;
  private FileWriter txFile = null;
  private final boolean fileWrite = false;
  private final ExecutorService txFileExecutor = Executors.newSingleThreadExecutor();
  private List<? extends PendingTransaction> replayTxs = List.of();
  private final long ts = System.currentTimeMillis();

  public BlockTransactionSelector(
      final MiningParameters miningParameters,
      final MainnetTransactionProcessor transactionProcessor,
      final Blockchain blockchain,
      final MutableWorldState worldState,
      final TransactionPool transactionPool,
      final ProcessableBlockHeader processableBlockHeader,
      final AbstractBlockProcessor.TransactionReceiptFactory transactionReceiptFactory,
      final Supplier<Boolean> isCancelled,
      final Address miningBeneficiary,
      final Wei blobGasPrice,
      final FeeMarket feeMarket,
      final GasCalculator gasCalculator,
      final GasLimitCalculator gasLimitCalculator,
      final PluginTransactionSelector pluginTransactionSelector,
      final EthScheduler ethScheduler) {
    this.transactionProcessor = transactionProcessor;
    this.blockchain = blockchain;
    this.worldState = worldState;
    this.transactionReceiptFactory = transactionReceiptFactory;
    this.isCancelled = isCancelled;
    this.ethScheduler = ethScheduler;
    this.blockSelectionContext =
        new BlockSelectionContext(
            miningParameters,
            gasCalculator,
            gasLimitCalculator,
            processableBlockHeader,
            feeMarket,
            blobGasPrice,
            miningBeneficiary,
            transactionPool);
    transactionSelectors = createTransactionSelectors(blockSelectionContext);
    this.pluginTransactionSelector = pluginTransactionSelector;
    this.pluginOperationTracer = pluginTransactionSelector.getOperationTracer();
    txsSelectionMaxTime = miningParameters.getUnstable().getBlockTxsSelectionMaxTime();
    txEvaluationMaxTime = miningParameters.getUnstable().getBlockTxsSelectionPerTxMaxTime();
    if (fileWrite) {
      try {
        txFile =
            new FileWriter(
                "/data/besu/block-selection-log/" + processableBlockHeader.getNumber() + "." + ts,
                StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else {
      try (var br =
          new BufferedReader(
              new FileReader(
                  "/data/besu/block-selection-log/18542525.1699631116289",
                  StandardCharsets.UTF_8))) {
        replayTxs =
            br.lines()
                    .map(line -> line.split("="))
                    .map(a -> a[1])
                .map(
                    hex ->
                        Transaction.readFrom(
                            new BytesValueRLPInput(Bytes.fromHexString(hex), false)))
                .map(PendingTransaction.Remote::new)
                .toList();
      } catch (FileNotFoundException e) {
        throw new RuntimeException(e);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private List<AbstractTransactionSelector> createTransactionSelectors(
      final BlockSelectionContext context) {
    return List.of(
        new BlockSizeTransactionSelector(context),
        new PriceTransactionSelector(context),
        new BlobPriceTransactionSelector(context),
        new MinPriorityFeePerGasTransactionSelector(context),
        new ProcessingResultTransactionSelector(context));
  }

  /**
   * Builds a list of transactions for a block by iterating over all transactions in the
   * PendingTransactions pool. This operation can be long-running and, if executed in a separate
   * thread, can be cancelled via the isCancelled supplier, which would result in a
   * CancellationException.
   *
   * @return The {@code TransactionSelectionResults} containing the results of transaction
   *     evaluation.
   */
  public TransactionSelectionResults buildTransactionListForBlock() {
    LOG.atDebug()
        .setMessage("Ts [{}] Transaction pool stats {}")
            .addArgument(ts)
        .addArgument(blockSelectionContext.transactionPool().logStats())
        .log();
    timeLimitedSelection();
    LOG.atTrace()
        .setMessage("Transaction selection result {}")
        .addArgument(transactionSelectionResults::toTraceLog)
        .log();
    return transactionSelectionResults;
  }

  private void timeLimitedSelection() {
    final var selectionFuture =
        ethScheduler.scheduleBlockCreationTask(
            () -> {
              if (fileWrite) {
                blockSelectionContext
                    .transactionPool()
                    .selectTransactions(this::timeLimitedEvaluateTransaction);
              } else {
                replayTxs.forEach(this::timeLimitedEvaluateTransaction);
              }
            });

    try {
      selectionFuture.get(txsSelectionMaxTime, TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException e) {
      if (isCancelled.get()) {
        throw new CancellationException("Cancelled during transactions selection");
      }
      LOG.warn("Error during block transactions selection", e);
    } catch (TimeoutException e) {
      // synchronize since we want to be sure that there is no concurrent state update
      synchronized (worldState) {
        isBlockTimeout.set(true);
      }
      LOG.warn(
          "Interrupting transactions selection since it is taking more than the max configured time of "
              + txsSelectionMaxTime
              + "ms",
          e);
    } finally {
      if (fileWrite) {
        txFileExecutor.submit(
            () -> {
              try {
                txFile.close();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
      }
    }
  }

  /**
   * Evaluates a list of transactions and updates the selection results accordingly. If a
   * transaction is not selected during the evaluation, it is updated as not selected in the
   * transaction selection results.
   *
   * @param transactions The list of transactions to be evaluated.
   * @return The {@code TransactionSelectionResults} containing the results of the transaction
   *     evaluations.
   */
  public TransactionSelectionResults evaluateTransactions(final List<Transaction> transactions) {
    transactions.forEach(
        transaction -> {
          evaluateTransaction(
              new PendingTransaction.Local.Priority(transaction),
              new TxEvaluationState(worldState));
        });
    return transactionSelectionResults;
  }

  private TransactionSelectionResult timeLimitedEvaluateTransaction(
      final PendingTransaction pendingTransaction) {
    if (fileWrite) {
      txFileExecutor.submit(
          () -> {
            var rlpOut = new BytesValueRLPOutput();
            pendingTransaction.getTransaction().writeTo(rlpOut);
            try {
              txFile.write(pendingTransaction.getHash().toHexString());
              txFile.write('=');
              txFile.write(rlpOut.encoded().toHexString());
              txFile.write('\n');
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }

    if (isBlockTimeout.get()) {
      LOG.atTrace()
          .setMessage(
              "Skipping evaluation of tx {}, since there was already a block selection timeout")
          .addArgument(pendingTransaction::toTraceLog)
          .log();
      return BLOCK_SELECTION_TIMEOUT;
    }

    LOG.trace("Before: state root {}, tx {}", worldState.frontierRootHash(), pendingTransaction.getHash());
    final var txEvaluationState = new TxEvaluationState(worldState);
    final var evaluationFuture =
        ethScheduler.scheduleBlockCreationTask(
            () -> evaluateTransaction(pendingTransaction, txEvaluationState));
    try {
      evaluationFuture.get(txEvaluationMaxTime, TimeUnit.MILLISECONDS);
      return txEvaluationState.getSelectionResult();
    } catch (InterruptedException | ExecutionException e) {
      if (isCancelled.get()) {
        throw new CancellationException("Cancelled during transaction evaluation");
      }
      LOG.warn(
          "Error during transaction evaluation of tx with hash: "
              + pendingTransaction.getHash()
              + ", removing it from the pool",
          e);
      return INTERNAL_ERROR;
    } catch (TimeoutException e) {
      // synchronize since we want to be sure that there is no concurrent state update
      synchronized (worldState) {
        // in the meantime the tx could have been evaluated, in that case the selection result is
        // present
        if (txEvaluationState.hasSelectionResult()) {
          LOG.atTrace()
              .setMessage("Result for tx {} arrived while evaluation went in timeout, including it")
              .addArgument(pendingTransaction::getHash)
              .log();
          return txEvaluationState.getSelectionResult();
        }
        LOG.warn(
            "Interrupting transaction evaluation since it is taking more than the max configured time of "
                + txEvaluationMaxTime
                + "ms. Hash: "
                + pendingTransaction.getHash(),
            e);
        txEvaluationState.triggerTimeout();
        return TX_EVALUATION_TIMEOUT;
      }
    } finally {
      LOG.trace("After: State root {}, tx {}", worldState.frontierRootHash(), pendingTransaction.getHash());
    }
  }

  /**
   * Passed into the PendingTransactions, and is called on each transaction until sufficient
   * transactions are found which fill a block worth of gas. This function will continue to be
   * called until the block under construction is suitably full (in terms of gasLimit) and the
   * provided transaction's gasLimit does not fit within the space remaining in the block.
   *
   * @param pendingTransaction The transaction to be evaluated.
   * @throws CancellationException if the transaction selection process is cancelled.
   */
  private void evaluateTransaction(
      final PendingTransaction pendingTransaction, final TxEvaluationState txEvaluationState) {
    checkCancellation();

    TransactionSelectionResult selectionResult = evaluatePreProcessing(pendingTransaction);
    if (!selectionResult.selected()) {
      txEvaluationState.setSelectionResult(selectionResult);
      handleTransactionNotSelected(pendingTransaction, txEvaluationState);
    } else {

      final WorldUpdater txWorldStateUpdater = txEvaluationState.getTxWorldStateUpdater();
      final TransactionProcessingResult processingResult =
          processTransaction(pendingTransaction, txWorldStateUpdater);

      var postProcessingSelectionResult =
          evaluatePostProcessing(pendingTransaction, processingResult);
      txEvaluationState.setSelectionResult(postProcessingSelectionResult);

      if (postProcessingSelectionResult.selected()) {
        handleTransactionSelected(pendingTransaction, processingResult, txEvaluationState);
      } else {
        handleTransactionNotSelected(pendingTransaction, txEvaluationState);
      }
    }
  }

  /**
   * This method evaluates a transaction by pre-processing it through a series of selectors. It
   * first processes the transaction through internal selectors, and if the transaction is selected,
   * it then processes it through external selectors. If the transaction is selected by all
   * selectors, it returns SELECTED.
   *
   * @param pendingTransaction The transaction to be evaluated.
   * @return The result of the transaction selection process.
   */
  private TransactionSelectionResult evaluatePreProcessing(
      final PendingTransaction pendingTransaction) {

    for (var selector : transactionSelectors) {
      TransactionSelectionResult result =
          selector.evaluateTransactionPreProcessing(
              pendingTransaction, transactionSelectionResults);
      if (!result.equals(SELECTED)) {
        return result;
      }
    }
    return pluginTransactionSelector.evaluateTransactionPreProcessing(pendingTransaction);
  }

  /**
   * This method evaluates a transaction by processing it through a series of selectors. Each
   * selector may use the transaction and/or the result of the transaction processing to decide
   * whether the transaction should be included in a block. If the transaction is selected by all
   * selectors, it returns SELECTED.
   *
   * @param pendingTransaction The transaction to be evaluated.
   * @param processingResult The result of the transaction processing.
   * @return The result of the transaction selection process.
   */
  private TransactionSelectionResult evaluatePostProcessing(
      final PendingTransaction pendingTransaction,
      final TransactionProcessingResult processingResult) {

    for (var selector : transactionSelectors) {
      TransactionSelectionResult result =
          selector.evaluateTransactionPostProcessing(
              pendingTransaction, transactionSelectionResults, processingResult);
      if (!result.equals(SELECTED)) {
        return result;
      }
    }
    return pluginTransactionSelector.evaluateTransactionPostProcessing(
        pendingTransaction, processingResult);
  }

  /**
   * Processes a transaction
   *
   * @param pendingTransaction The transaction to be processed.
   * @param worldStateUpdater The world state updater.
   * @return The result of the transaction processing.
   */
  private TransactionProcessingResult processTransaction(
      final PendingTransaction pendingTransaction, final WorldUpdater worldStateUpdater) {
    final BlockHashLookup blockHashLookup =
        new CachingBlockHashLookup(blockSelectionContext.processableBlockHeader(), blockchain);
    return transactionProcessor.processTransaction(
        blockchain,
        worldStateUpdater,
        blockSelectionContext.processableBlockHeader(),
        pendingTransaction.getTransaction(),
        blockSelectionContext.miningBeneficiary(),
        pluginOperationTracer,
        blockHashLookup,
        false,
        TransactionValidationParams.mining(),
        blockSelectionContext.blobGasPrice());
  }

  /**
   * Handles a selected transaction by committing the world state updates, creating a transaction
   * receipt, updating the TransactionSelectionResults with the selected transaction, and notifying
   * the external transaction selector.
   *
   * @param pendingTransaction The pending transaction.
   * @param processingResult The result of the transaction processing.
   */
  private void handleTransactionSelected(
      final PendingTransaction pendingTransaction,
      final TransactionProcessingResult processingResult,
      final TxEvaluationState txEvaluationState) {
    final Transaction transaction = pendingTransaction.getTransaction();

    final long gasUsedByTransaction =
        transaction.getGasLimit() - processingResult.getGasRemaining();
    final long cumulativeGasUsed =
        transactionSelectionResults.getCumulativeGasUsed() + gasUsedByTransaction;
    final long blobGasUsed =
        blockSelectionContext.gasCalculator().blobGasCost(transaction.getBlobCount());

    final boolean blockTooLate;
    final boolean txTooLate;

    // only add this tx to the selected set if it is not too late,
    // this need to be done synchronously to avoid that a concurrent timeout
    // could start packing a block while we are updating the state here
    synchronized (worldState) {
      blockTooLate = isBlockTimeout.get();
      txTooLate = txEvaluationState.isTimeout();

      if (blockTooLate || txTooLate) {
        LOG.atTrace()
            .setMessage("Timeout block={}, tx={}, when processing {}")
            .addArgument(blockTooLate)
            .addArgument(txTooLate)
            .addArgument(transaction::toTraceLog)
            .log();
      } else {
        txEvaluationState.commit();
        final TransactionReceipt receipt =
            transactionReceiptFactory.create(
                transaction.getType(), processingResult, worldState, cumulativeGasUsed);

        transactionSelectionResults.updateSelected(
            transaction, receipt, gasUsedByTransaction, blobGasUsed);
      }
    }

    if (txTooLate) {
      // this tx took much time to be evaluated and processed, and will not be included in the block
      // and will be removed from the txpool, so we need to treat it as not selected
      LOG.atTrace()
          .setMessage(
              "{} took too much to process, and will not be included in the block and will be removed from the txpool")
          .addArgument(transaction::toTraceLog)
          .log();
      // do not rely on the presence of this result, since by the time it is added, the code
      // reading it could have been already executed by another thread
      txEvaluationState.setSelectionResult(TX_EVALUATION_TIMEOUT);
      handleTransactionNotSelected(pendingTransaction, txEvaluationState);
    } else if (blockTooLate) {
      // even if this tx passed all the checks, it is too late to include it in this block,
      // so we need to treat it as not selected
      LOG.atTrace()
          .setMessage(
              "{} processed after a block selection timeout, and will not be included in the block")
          .addArgument(transaction::toTraceLog)
          .log();
      // do not rely on the presence of this result, since by the time it is added, the code
      // reading it could have been already executed by another thread
      txEvaluationState.setSelectionResult(BLOCK_SELECTION_TIMEOUT);
      handleTransactionNotSelected(pendingTransaction, txEvaluationState);
    } else {

      pluginTransactionSelector.onTransactionSelected(pendingTransaction, processingResult);
      LOG.atTrace()
          .setMessage("Selected {} for block creation")
          .addArgument(transaction::toTraceLog)
          .log();
    }
  }

  /**
   * Handles the scenario when a transaction is not selected. It updates the
   * TransactionSelectionResults with the unselected transaction, and notifies the external
   * transaction selector.
   *
   * @param pendingTransaction The unselected pending transaction.
   * @param txEvaluationState The state of the transaction selection process.
   */
  private void handleTransactionNotSelected(
      final PendingTransaction pendingTransaction, final TxEvaluationState txEvaluationState) {

    transactionSelectionResults.updateNotSelected(
        pendingTransaction.getTransaction(), txEvaluationState.getSelectionResult());
    pluginTransactionSelector.onTransactionNotSelected(
        pendingTransaction, txEvaluationState.getSelectionResult());
  }

  private void checkCancellation() {
    if (isCancelled.get()) {
      throw new CancellationException("Cancelled during transaction selection.");
    }
  }

  private static class TxEvaluationState {
    final MutableWorldState worldState;
    final AtomicBoolean isTimeout = new AtomicBoolean(false);
    volatile TransactionSelectionResult selectionResult;
    WorldUpdater blockWorldStateUpdater;
    WorldUpdater txWorldStateUpdater;

    TxEvaluationState(final MutableWorldState worldState) {
      this.worldState = worldState;
    }

    WorldUpdater getTxWorldStateUpdater() {
      synchronized (worldState) {
        blockWorldStateUpdater = worldState.updater();
        txWorldStateUpdater = blockWorldStateUpdater.updater();
        return txWorldStateUpdater;
      }
    }

    void triggerTimeout() {
      isTimeout.set(true);
    }

    boolean isTimeout() {
      return isTimeout.get();
    }

    void setSelectionResult(final TransactionSelectionResult selectionResult) {
      this.selectionResult = selectionResult;
    }

    boolean hasSelectionResult() {
      return selectionResult != null;
    }

    TransactionSelectionResult getSelectionResult() {
      return selectionResult;
    }

    void commit() {
      txWorldStateUpdater.commit();
      blockWorldStateUpdater.commit();
    }
  }
}
