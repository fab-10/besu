/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.mainnet;

import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.ARROW_GLACIER;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.BERLIN;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.BPO1;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.BPO2;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.BPO3;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.BPO4;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.BPO5;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.BYZANTIUM;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.CANCUN;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.CANCUN_EOF;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.CONSTANTINOPLE;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.DAO_RECOVERY_INIT;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.DAO_RECOVERY_TRANSITION;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.EXPERIMENTAL_EIPS;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.FRONTIER;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.FUTURE_EIPS;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.GRAY_GLACIER;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.HOMESTEAD;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.ISTANBUL;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.LONDON;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.MUIR_GLACIER;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.OSAKA;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.PARIS;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.PETERSBURG;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.PRAGUE;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.SHANGHAI;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.SPURIOUS_DRAGON;
import static org.hyperledger.besu.datatypes.HardforkId.MainnetHardforkId.TANGERINE_WHISTLE;
import static org.hyperledger.besu.ethereum.mainnet.requests.MainnetRequestsProcessor.pragueRequestsProcessors;

import org.hyperledger.besu.config.BlobSchedule;
import org.hyperledger.besu.config.BlobScheduleOptions;
import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.config.PowAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.BlobType;
import org.hyperledger.besu.datatypes.HardforkId;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.MainnetBlockValidatorBuilder;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.feemarket.CoinbaseFeePriceCalculator;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor.TransactionReceiptFactory;
import org.hyperledger.besu.ethereum.mainnet.blockhash.CancunPreExecutionProcessor;
import org.hyperledger.besu.ethereum.mainnet.blockhash.FrontierPreExecutionProcessor;
import org.hyperledger.besu.ethereum.mainnet.blockhash.PraguePreExecutionProcessor;
import org.hyperledger.besu.ethereum.mainnet.feemarket.BaseFeeMarket;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.mainnet.parallelization.MainnetParallelBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.requests.MainnetRequestsValidator;
import org.hyperledger.besu.ethereum.mainnet.requests.RequestContractAddresses;
import org.hyperledger.besu.ethereum.mainnet.transactionpool.OsakaTransactionPoolPreProcessor;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.contractvalidation.EOFValidationCodeRule;
import org.hyperledger.besu.evm.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.evm.contractvalidation.PrefixCodeRule;
import org.hyperledger.besu.evm.gascalculator.BerlinGasCalculator;
import org.hyperledger.besu.evm.gascalculator.ByzantiumGasCalculator;
import org.hyperledger.besu.evm.gascalculator.CancunGasCalculator;
import org.hyperledger.besu.evm.gascalculator.ConstantinopleGasCalculator;
import org.hyperledger.besu.evm.gascalculator.EOFGasCalculator;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.gascalculator.HomesteadGasCalculator;
import org.hyperledger.besu.evm.gascalculator.IstanbulGasCalculator;
import org.hyperledger.besu.evm.gascalculator.LondonGasCalculator;
import org.hyperledger.besu.evm.gascalculator.OsakaGasCalculator;
import org.hyperledger.besu.evm.gascalculator.PetersburgGasCalculator;
import org.hyperledger.besu.evm.gascalculator.PragueGasCalculator;
import org.hyperledger.besu.evm.gascalculator.ShanghaiGasCalculator;
import org.hyperledger.besu.evm.gascalculator.SpuriousDragonGasCalculator;
import org.hyperledger.besu.evm.gascalculator.TangerineWhistleGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.worldstate.CodeDelegationService;
import org.hyperledger.besu.evm.worldstate.WorldState;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.io.Resources;
import io.vertx.core.json.JsonArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides the various {@link ProtocolSpec}s on mainnet hard forks. */
public abstract class MainnetProtocolSpecs {

  private static final Address RIPEMD160_PRECOMPILE =
      Address.fromHexString("0x0000000000000000000000000000000000000003");

  private static final Supplier<SignatureAlgorithm> SIGNATURE_ALGORITHM =
      Suppliers.memoize(SignatureAlgorithmFactory::getInstance);

  // A consensus bug at Ethereum mainnet transaction 0xcf416c53
  // deleted an empty account even when the message execution scope
  // failed, but the transaction itself succeeded.
  private static final Set<Address> SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES =
      Set.of(RIPEMD160_PRECOMPILE);

  private static final Wei FRONTIER_BLOCK_REWARD = Wei.fromEth(5);

  private static final Wei BYZANTIUM_BLOCK_REWARD = Wei.fromEth(3);

  private static final Wei CONSTANTINOPLE_BLOCK_REWARD = Wei.fromEth(2);

  private static final Logger LOG = LoggerFactory.getLogger(MainnetProtocolSpecs.class);

  private MainnetProtocolSpecs() {}

  public static ProtocolSpecBuilder frontierDefinition(
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return new ProtocolSpecBuilder()
        .gasCalculator(FrontierGasCalculator::new)
        .gasLimitCalculatorBuilder(
            (feeMarket, gasCalculator, blobSchedule) -> new FrontierTargetingGasLimitCalculator())
        .evmBuilder(MainnetEVMs::frontier)
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::frontier)
        .messageCallProcessorBuilder(MessageCallProcessor::new)
        .contractCreationProcessorBuilder(
            evm ->
                new ContractCreationProcessor(
                    evm, false, Collections.singletonList(MaxCodeSizeRule.from(evm)), 0))
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(), gasLimitCalculator, false, Optional.empty()))
        .transactionProcessorBuilder(
            (gasCalculator,
                feeMarket,
                transactionValidatorFactory,
                contractCreationProcessor,
                messageCallProcessor) ->
                MainnetTransactionProcessor.builder()
                    .gasCalculator(gasCalculator)
                    .transactionValidatorFactory(transactionValidatorFactory)
                    .contractCreationProcessor(contractCreationProcessor)
                    .messageCallProcessor(messageCallProcessor)
                    .clearEmptyAccounts(false)
                    .warmCoinbase(false)
                    .maxStackSize(evmConfiguration.evmStackSize())
                    .feeMarket(FeeMarket.legacy())
                    .coinbaseFeePriceCalculator(CoinbaseFeePriceCalculator.frontier())
                    .build())
        .difficultyCalculator(MainnetDifficultyCalculators.FRONTIER)
        .blockHeaderValidatorBuilder(
            (feeMarket, gasCalculator, gasLimitCalculator) -> MainnetBlockHeaderValidator.create())
        .ommerHeaderValidatorBuilder(
            (feeMarket, gasCalculator, gasLimitCalculator) ->
                MainnetBlockHeaderValidator.createLegacyFeeMarketOmmerValidator())
        .blockBodyValidatorBuilder(MainnetBlockBodyValidator::new)
        .transactionReceiptFactory(new FrontierTransactionReceiptFactory())
        .blockReward(FRONTIER_BLOCK_REWARD)
        .skipZeroBlockRewards(false)
        .blockProcessorBuilder(
            isParallelTxProcessingEnabled
                ? new MainnetParallelBlockProcessor.ParallelBlockProcessorBuilder(metricsSystem)
                : MainnetBlockProcessor::new)
        .blockValidatorBuilder(MainnetBlockValidatorBuilder::frontier)
        .blockImporterBuilder(MainnetBlockImporter::new)
        .blockHeaderFunctions(new MainnetBlockHeaderFunctions())
        .miningBeneficiaryCalculator(BlockHeader::getCoinbase)
        .evmConfiguration(evmConfiguration)
        .preExecutionProcessor(new FrontierPreExecutionProcessor())
        .hardforkId(FRONTIER);
  }

  public static PoWHasher powHasher(final PowAlgorithm powAlgorithm) {
    if (powAlgorithm == null) {
      return PoWHasher.UNSUPPORTED;
    }
    return powAlgorithm == PowAlgorithm.ETHASH ? PoWHasher.ETHASH_LIGHT : PoWHasher.UNSUPPORTED;
  }

  public static ProtocolSpecBuilder homesteadDefinition(
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return frontierDefinition(evmConfiguration, isParallelTxProcessingEnabled, metricsSystem)
        .gasCalculator(HomesteadGasCalculator::new)
        .evmBuilder(MainnetEVMs::homestead)
        .contractCreationProcessorBuilder(
            evm ->
                new ContractCreationProcessor(
                    evm, true, Collections.singletonList(MaxCodeSizeRule.from(evm)), 0))
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(), gasLimitCalculator, true, Optional.empty()))
        .difficultyCalculator(MainnetDifficultyCalculators.HOMESTEAD)
        .hardforkId(HOMESTEAD);
  }

  public static ProtocolSpecBuilder daoRecoveryInitDefinition(
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return homesteadDefinition(evmConfiguration, isParallelTxProcessingEnabled, metricsSystem)
        .blockHeaderValidatorBuilder(
            (feeMarket, gasCalculator, gasLimitCalculator) ->
                MainnetBlockHeaderValidator.createDaoValidator())
        .blockProcessorBuilder(
            (transactionProcessor,
                transactionReceiptFactory,
                blockReward,
                miningBeneficiaryCalculator,
                skipZeroBlockRewards,
                protocolSchedule) ->
                new DaoBlockProcessor(
                    isParallelTxProcessingEnabled
                        ? new MainnetParallelBlockProcessor(
                            transactionProcessor,
                            transactionReceiptFactory,
                            blockReward,
                            miningBeneficiaryCalculator,
                            skipZeroBlockRewards,
                            protocolSchedule,
                            metricsSystem)
                        : new MainnetBlockProcessor(
                            transactionProcessor,
                            transactionReceiptFactory,
                            blockReward,
                            miningBeneficiaryCalculator,
                            skipZeroBlockRewards,
                            protocolSchedule)))
        .hardforkId(DAO_RECOVERY_INIT);
  }

  public static ProtocolSpecBuilder daoRecoveryTransitionDefinition(
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return daoRecoveryInitDefinition(evmConfiguration, isParallelTxProcessingEnabled, metricsSystem)
        .blockProcessorBuilder(
            isParallelTxProcessingEnabled
                ? new MainnetParallelBlockProcessor.ParallelBlockProcessorBuilder(metricsSystem)
                : MainnetBlockProcessor::new)
        .hardforkId(DAO_RECOVERY_TRANSITION);
  }

  public static ProtocolSpecBuilder tangerineWhistleDefinition(
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return homesteadDefinition(evmConfiguration, isParallelTxProcessingEnabled, metricsSystem)
        .gasCalculator(TangerineWhistleGasCalculator::new)
        .hardforkId(TANGERINE_WHISTLE);
  }

  public static ProtocolSpecBuilder spuriousDragonDefinition(
      final Optional<BigInteger> chainId,
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return tangerineWhistleDefinition(
            evmConfiguration, isParallelTxProcessingEnabled, metricsSystem)
        .isReplayProtectionSupported(true)
        .gasCalculator(SpuriousDragonGasCalculator::new)
        .skipZeroBlockRewards(true)
        .messageCallProcessorBuilder(
            (evm, precompileContractRegistry) ->
                new MessageCallProcessor(
                    evm,
                    precompileContractRegistry,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .contractCreationProcessorBuilder(
            evm ->
                new ContractCreationProcessor(
                    evm,
                    true,
                    Collections.singletonList(MaxCodeSizeRule.from(evm)),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(), gasLimitCalculator, true, chainId))
        .transactionProcessorBuilder(
            (gasCalculator,
                feeMarket,
                transactionValidator,
                contractCreationProcessor,
                messageCallProcessor) ->
                MainnetTransactionProcessor.builder()
                    .gasCalculator(gasCalculator)
                    .transactionValidatorFactory(transactionValidator)
                    .contractCreationProcessor(contractCreationProcessor)
                    .messageCallProcessor(messageCallProcessor)
                    .clearEmptyAccounts(true)
                    .warmCoinbase(false)
                    .maxStackSize(evmConfiguration.evmStackSize())
                    .feeMarket(feeMarket)
                    .coinbaseFeePriceCalculator(CoinbaseFeePriceCalculator.frontier())
                    .build())
        .hardforkId(SPURIOUS_DRAGON);
  }

  public static ProtocolSpecBuilder byzantiumDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return spuriousDragonDefinition(
            chainId, evmConfiguration, isParallelTxProcessingEnabled, metricsSystem)
        .gasCalculator(ByzantiumGasCalculator::new)
        .evmBuilder(MainnetEVMs::byzantium)
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::byzantium)
        .difficultyCalculator(MainnetDifficultyCalculators.BYZANTIUM)
        .transactionReceiptFactory(new ByzantiumTransactionReceiptFactory(enableRevertReason))
        .blockReward(BYZANTIUM_BLOCK_REWARD)
        .hardforkId(BYZANTIUM);
  }

  public static ProtocolSpecBuilder constantinopleDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return byzantiumDefinition(
            chainId,
            enableRevertReason,
            evmConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .difficultyCalculator(MainnetDifficultyCalculators.CONSTANTINOPLE)
        .gasCalculator(ConstantinopleGasCalculator::new)
        .evmBuilder(MainnetEVMs::constantinople)
        .blockReward(CONSTANTINOPLE_BLOCK_REWARD)
        .hardforkId(CONSTANTINOPLE);
  }

  public static ProtocolSpecBuilder petersburgDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return constantinopleDefinition(
            chainId,
            enableRevertReason,
            evmConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .gasCalculator(PetersburgGasCalculator::new)
        .hardforkId(PETERSBURG);
  }

  public static ProtocolSpecBuilder istanbulDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return petersburgDefinition(
            chainId,
            enableRevertReason,
            evmConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .gasCalculator(IstanbulGasCalculator::new)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.istanbul(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::istanbul)
        .contractCreationProcessorBuilder(
            evm ->
                new ContractCreationProcessor(
                    evm,
                    true,
                    Collections.singletonList(MaxCodeSizeRule.from(evm)),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .hardforkId(ISTANBUL);
  }

  static ProtocolSpecBuilder muirGlacierDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return istanbulDefinition(
            chainId,
            enableRevertReason,
            evmConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .difficultyCalculator(MainnetDifficultyCalculators.MUIR_GLACIER)
        .hardforkId(MUIR_GLACIER);
  }

  static ProtocolSpecBuilder berlinDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final EvmConfiguration evmConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return muirGlacierDefinition(
            chainId,
            enableRevertReason,
            evmConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .gasCalculator(BerlinGasCalculator::new)
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(),
                    gasLimitCalculator,
                    true,
                    chainId,
                    Set.of(TransactionType.FRONTIER, TransactionType.ACCESS_LIST)))
        .transactionReceiptFactory(new BerlinTransactionReceiptFactory(enableRevertReason))
        .hardforkId(BERLIN);
  }

  static ProtocolSpecBuilder londonDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    final long londonForkBlockNumber =
        genesisConfigOptions.getLondonBlockNumber().orElse(Long.MAX_VALUE);
    return berlinDefinition(
            chainId,
            enableRevertReason,
            evmConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .feeMarketBuilder(
            createFeeMarket(
                londonForkBlockNumber,
                genesisConfigOptions.isZeroBaseFee(),
                genesisConfigOptions.isFixedBaseFee(),
                miningConfiguration.getMinTransactionGasPrice(),
                (blobSchedule) ->
                    FeeMarket.london(
                        londonForkBlockNumber, genesisConfigOptions.getBaseFeePerGas())))
        .gasCalculator(LondonGasCalculator::new)
        .gasLimitCalculatorBuilder(
            (feeMarket, gasCalculator, blobSchedule) ->
                new LondonTargetingGasLimitCalculator(
                    londonForkBlockNumber, (BaseFeeMarket) feeMarket))
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(),
                    gasLimitCalculator,
                    feeMarket,
                    true,
                    chainId,
                    Set.of(
                        TransactionType.FRONTIER,
                        TransactionType.ACCESS_LIST,
                        TransactionType.EIP1559),
                    Integer.MAX_VALUE))
        .transactionProcessorBuilder(
            (gasCalculator,
                feeMarket,
                transactionValidatorFactory,
                contractCreationProcessor,
                messageCallProcessor) ->
                MainnetTransactionProcessor.builder()
                    .gasCalculator(gasCalculator)
                    .transactionValidatorFactory(transactionValidatorFactory)
                    .contractCreationProcessor(contractCreationProcessor)
                    .messageCallProcessor(messageCallProcessor)
                    .clearEmptyAccounts(true)
                    .warmCoinbase(false)
                    .maxStackSize(evmConfiguration.evmStackSize())
                    .feeMarket(feeMarket)
                    .coinbaseFeePriceCalculator(CoinbaseFeePriceCalculator.eip1559())
                    .build())
        .contractCreationProcessorBuilder(
            evm ->
                new ContractCreationProcessor(
                    evm,
                    true,
                    List.of(MaxCodeSizeRule.from(evm), PrefixCodeRule.of()),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES))
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.london(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .difficultyCalculator(MainnetDifficultyCalculators.LONDON)
        .blockHeaderValidatorBuilder(
            (feeMarket, gasCalculator, gasLimitCalculator) ->
                MainnetBlockHeaderValidator.createBaseFeeMarketValidator((BaseFeeMarket) feeMarket))
        .ommerHeaderValidatorBuilder(
            (feeMarket, gasCalculator, gasLimitCalculator) ->
                MainnetBlockHeaderValidator.createBaseFeeMarketOmmerValidator(
                    (BaseFeeMarket) feeMarket))
        .blockBodyValidatorBuilder(BaseFeeBlockBodyValidator::new)
        .hardforkId(LONDON);
  }

  static ProtocolSpecBuilder arrowGlacierDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return londonDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .difficultyCalculator(MainnetDifficultyCalculators.ARROW_GLACIER)
        .hardforkId(ARROW_GLACIER);
  }

  static ProtocolSpecBuilder grayGlacierDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return arrowGlacierDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .difficultyCalculator(MainnetDifficultyCalculators.GRAY_GLACIER)
        .hardforkId(GRAY_GLACIER);
  }

  static ProtocolSpecBuilder parisDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {

    return grayGlacierDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.paris(gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .difficultyCalculator(MainnetDifficultyCalculators.PROOF_OF_STAKE_DIFFICULTY)
        .blockHeaderValidatorBuilder(MainnetBlockHeaderValidator::mergeBlockHeaderValidator)
        .blockReward(Wei.ZERO)
        .skipZeroBlockRewards(true)
        .isPoS(true)
        .hardforkId(PARIS);
  }

  static ProtocolSpecBuilder shanghaiDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    return parisDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        // gas calculator has new code to support EIP-3860 limit and meter initcode
        .gasCalculator(ShanghaiGasCalculator::new)
        // EVM has a new operation for EIP-3855 PUSH0 instruction
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.shanghai(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        // we need to flip the Warm Coinbase flag for EIP-3651 warm coinbase
        .transactionProcessorBuilder(
            (gasCalculator,
                feeMarket,
                transactionValidatorFactory,
                contractCreationProcessor,
                messageCallProcessor) ->
                MainnetTransactionProcessor.builder()
                    .gasCalculator(gasCalculator)
                    .transactionValidatorFactory(transactionValidatorFactory)
                    .contractCreationProcessor(contractCreationProcessor)
                    .messageCallProcessor(messageCallProcessor)
                    .clearEmptyAccounts(true)
                    .warmCoinbase(true)
                    .maxStackSize(evmConfiguration.evmStackSize())
                    .feeMarket(feeMarket)
                    .coinbaseFeePriceCalculator(CoinbaseFeePriceCalculator.eip1559())
                    .build())
        // Contract creation rules for EIP-3860 Limit and meter intitcode
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(),
                    gasLimitCalculator,
                    feeMarket,
                    true,
                    chainId,
                    Set.of(
                        TransactionType.FRONTIER,
                        TransactionType.ACCESS_LIST,
                        TransactionType.EIP1559),
                    evm.getMaxInitcodeSize()))
        .withdrawalsProcessor(new WithdrawalsProcessor())
        .withdrawalsValidator(new WithdrawalsValidator.AllowedWithdrawals())
        .blockHeaderValidatorBuilder(MainnetBlockHeaderValidator::noBlobBlockHeaderValidator)
        .hardforkId(SHANGHAI);
  }

  static ProtocolSpecBuilder cancunDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    final long londonForkBlockNumber = genesisConfigOptions.getLondonBlockNumber().orElse(0L);

    return shanghaiDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .feeMarketBuilder(
            createFeeMarket(
                londonForkBlockNumber,
                genesisConfigOptions.isZeroBaseFee(),
                genesisConfigOptions.isFixedBaseFee(),
                miningConfiguration.getMinTransactionGasPrice(),
                (blobSchedule) ->
                    FeeMarket.cancun(
                        londonForkBlockNumber,
                        genesisConfigOptions.getBaseFeePerGas(),
                        blobSchedule)))
        .blobSchedule(
            genesisConfigOptions
                .getBlobScheduleOptions()
                .flatMap(BlobScheduleOptions::getCancun)
                .orElse(BlobSchedule.CANCUN_DEFAULT))
        // gas calculator for EIP-4844 blob gas
        .gasCalculator(CancunGasCalculator::new)
        // gas limit with EIP-4844 max blob gas per block
        .gasLimitCalculatorBuilder(
            (feeMarket, gasCalculator, blobSchedule) ->
                new CancunTargetingGasLimitCalculator(
                    londonForkBlockNumber,
                    (BaseFeeMarket) feeMarket,
                    gasCalculator,
                    blobSchedule.getMax(),
                    blobSchedule.getTarget()))
        // EVM changes to support EIP-1153: TSTORE and EIP-5656: MCOPY
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.cancun(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        // use Cancun fee market
        .transactionProcessorBuilder(
            (gasCalculator,
                feeMarket,
                transactionValidator,
                contractCreationProcessor,
                messageCallProcessor) ->
                MainnetTransactionProcessor.builder()
                    .gasCalculator(gasCalculator)
                    .transactionValidatorFactory(transactionValidator)
                    .contractCreationProcessor(contractCreationProcessor)
                    .messageCallProcessor(messageCallProcessor)
                    .clearEmptyAccounts(true)
                    .warmCoinbase(true)
                    .maxStackSize(evmConfiguration.evmStackSize())
                    .feeMarket(feeMarket)
                    .coinbaseFeePriceCalculator(CoinbaseFeePriceCalculator.eip1559())
                    .build())
        // change to check for max blob gas per block for EIP-4844
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(),
                    gasLimitCalculator,
                    feeMarket,
                    true,
                    chainId,
                    Set.of(
                        TransactionType.FRONTIER,
                        TransactionType.ACCESS_LIST,
                        TransactionType.EIP1559,
                        TransactionType.BLOB),
                    Set.of(BlobType.KZG_PROOF),
                    evm.getMaxInitcodeSize()))
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::cancun)
        .blockHeaderValidatorBuilder(MainnetBlockHeaderValidator::blobAwareBlockHeaderValidator)
        .preExecutionProcessor(new CancunPreExecutionProcessor())
        .hardforkId(CANCUN);
  }

  static ProtocolSpecBuilder cancunEOFDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {

    ProtocolSpecBuilder protocolSpecBuilder =
        cancunDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem);
    return addEOF(chainId, evmConfiguration, protocolSpecBuilder).hardforkId(CANCUN_EOF);
  }

  static ProtocolSpecBuilder pragueDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    ProtocolSpecBuilder pragueSpecBuilder =
        cancunDefinition(
                chainId,
                enableRevertReason,
                genesisConfigOptions,
                evmConfiguration,
                miningConfiguration,
                isParallelTxProcessingEnabled,
                metricsSystem)
            .blobSchedule(
                genesisConfigOptions
                    .getBlobScheduleOptions()
                    .flatMap(BlobScheduleOptions::getPrague)
                    .orElse(BlobSchedule.PRAGUE_DEFAULT))
            .gasCalculator(PragueGasCalculator::new)
            .evmBuilder(
                (gasCalculator, jdCacheConfig) ->
                    MainnetEVMs.prague(
                        gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))

            // EIP-2537 BLS12-381 precompiles
            .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::prague)

            // EIP-7002 Withdrawals / EIP-6610 Deposits / EIP-7685 Requests
            .requestsValidator(new MainnetRequestsValidator())

            // change to accept EIP-7702 transactions
            .transactionValidatorFactoryBuilder(
                (evm, gasLimitCalculator, feeMarket) ->
                    new TransactionValidatorFactory(
                        evm.getGasCalculator(),
                        gasLimitCalculator,
                        feeMarket,
                        true,
                        chainId,
                        Set.of(
                            TransactionType.FRONTIER,
                            TransactionType.ACCESS_LIST,
                            TransactionType.EIP1559,
                            TransactionType.BLOB,
                            TransactionType.DELEGATE_CODE),
                        Set.of(BlobType.KZG_PROOF),
                        evm.getMaxInitcodeSize()))
            // CodeDelegationProcessor
            .transactionProcessorBuilder(
                (gasCalculator,
                    feeMarket,
                    transactionValidator,
                    contractCreationProcessor,
                    messageCallProcessor) ->
                    MainnetTransactionProcessor.builder()
                        .gasCalculator(gasCalculator)
                        .transactionValidatorFactory(transactionValidator)
                        .contractCreationProcessor(contractCreationProcessor)
                        .messageCallProcessor(messageCallProcessor)
                        .clearEmptyAccounts(true)
                        .warmCoinbase(true)
                        .maxStackSize(evmConfiguration.evmStackSize())
                        .feeMarket(feeMarket)
                        .coinbaseFeePriceCalculator(CoinbaseFeePriceCalculator.eip1559())
                        .codeDelegationProcessor(
                            new CodeDelegationProcessor(
                                chainId,
                                SIGNATURE_ALGORITHM.get().getHalfCurveOrder(),
                                new CodeDelegationService()))
                        .build())
            // EIP-2935 Blockhash processor
            .preExecutionProcessor(new PraguePreExecutionProcessor())
            .hardforkId(PRAGUE);
    try {
      RequestContractAddresses requestContractAddresses =
          RequestContractAddresses.fromGenesis(genesisConfigOptions);

      pragueSpecBuilder.requestProcessorCoordinator(
          pragueRequestsProcessors(requestContractAddresses));
    } catch (NoSuchElementException nsee) {
      LOG.warn("Prague definitions require system contract addresses in genesis");
      throw nsee;
    }

    return pragueSpecBuilder;
  }

  static ProtocolSpecBuilder osakaDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    final long londonForkBlockNumber = genesisConfigOptions.getLondonBlockNumber().orElse(0L);

    return pragueDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .gasCalculator(OsakaGasCalculator::new)
        // tx gas limit cap EIP-7825
        .gasLimitCalculatorBuilder(
            (feeMarket, gasCalculator, blobSchedule) ->
                new OsakaTargetingGasLimitCalculator(
                    londonForkBlockNumber,
                    (BaseFeeMarket) feeMarket,
                    gasCalculator,
                    blobSchedule.getMax(),
                    blobSchedule.getTarget()))
        .evmBuilder(
            (gasCalculator, __) ->
                MainnetEVMs.osaka(gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .transactionValidatorFactoryBuilder(
            (evm, gasLimitCalculator, feeMarket) ->
                new TransactionValidatorFactory(
                    evm.getGasCalculator(),
                    gasLimitCalculator,
                    feeMarket,
                    true,
                    chainId,
                    Set.of(
                        TransactionType.FRONTIER,
                        TransactionType.ACCESS_LIST,
                        TransactionType.EIP1559,
                        TransactionType.BLOB,
                        TransactionType.DELEGATE_CODE),
                    Set.of(BlobType.KZG_CELL_PROOFS),
                    evm.getMaxInitcodeSize()))
        .transactionPoolPreProcessor(new OsakaTransactionPoolPreProcessor())
        .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::osaka)
        .blockValidatorBuilder(MainnetBlockValidatorBuilder::osaka)
        .hardforkId(OSAKA);
  }

  static ProtocolSpecBuilder bpo1Definition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    ProtocolSpecBuilder builder =
        osakaDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem);
    return applyBlobSchedule(builder, genesisConfigOptions, BlobScheduleOptions::getBpo1, BPO1);
  }

  static ProtocolSpecBuilder bpo2Definition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    ProtocolSpecBuilder builder =
        bpo1Definition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem);
    return applyBlobSchedule(builder, genesisConfigOptions, BlobScheduleOptions::getBpo2, BPO2);
  }

  static ProtocolSpecBuilder bpo3Definition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    ProtocolSpecBuilder builder =
        bpo2Definition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem);
    return applyBlobSchedule(builder, genesisConfigOptions, BlobScheduleOptions::getBpo3, BPO3);
  }

  static ProtocolSpecBuilder bpo4Definition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    ProtocolSpecBuilder builder =
        bpo3Definition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem);
    return applyBlobSchedule(builder, genesisConfigOptions, BlobScheduleOptions::getBpo4, BPO4);
  }

  static ProtocolSpecBuilder bpo5Definition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    ProtocolSpecBuilder builder =
        bpo4Definition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem);
    return applyBlobSchedule(builder, genesisConfigOptions, BlobScheduleOptions::getBpo5, BPO5);
  }

  private static ProtocolSpecBuilder applyBlobSchedule(
      final ProtocolSpecBuilder builder,
      final GenesisConfigOptions genesisConfigOptions,
      final Function<BlobScheduleOptions, Optional<BlobSchedule>> blobGetter,
      final HardforkId hardforkId) {
    genesisConfigOptions
        .getBlobScheduleOptions()
        .flatMap(blobGetter)
        .ifPresent(builder::blobSchedule);
    return builder.hardforkId(hardforkId);
  }

  private static ProtocolSpecBuilder addEOF(
      final Optional<BigInteger> chainId,
      final EvmConfiguration evmConfiguration,
      final ProtocolSpecBuilder protocolSpecBuilder) {
    return protocolSpecBuilder
        // EIP-7692 EOF v1 Gas calculator
        .gasCalculator(EOFGasCalculator::new)
        // EIP-7692 EOF v1 EVM and opcodes
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.futureEips(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        // EIP-7698 EOF v1 creation transaction
        .contractCreationProcessorBuilder(
            evm ->
                new ContractCreationProcessor(
                    evm,
                    true,
                    List.of(MaxCodeSizeRule.from(evm), EOFValidationCodeRule.from(evm)),
                    1,
                    SPURIOUS_DRAGON_FORCE_DELETE_WHEN_EMPTY_ADDRESSES));
  }

  static ProtocolSpecBuilder futureEipsDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {
    ProtocolSpecBuilder protocolSpecBuilder =
        bpo5Definition(
                chainId,
                enableRevertReason,
                genesisConfigOptions,
                evmConfiguration,
                miningConfiguration,
                isParallelTxProcessingEnabled,
                metricsSystem)
            .precompileContractRegistryBuilder(MainnetPrecompiledContractRegistries::futureEips)
            .hardforkId(FUTURE_EIPS);

    return addEOF(chainId, evmConfiguration, protocolSpecBuilder);
  }

  static ProtocolSpecBuilder experimentalEipsDefinition(
      final Optional<BigInteger> chainId,
      final boolean enableRevertReason,
      final GenesisConfigOptions genesisConfigOptions,
      final EvmConfiguration evmConfiguration,
      final MiningConfiguration miningConfiguration,
      final boolean isParallelTxProcessingEnabled,
      final MetricsSystem metricsSystem) {

    return futureEipsDefinition(
            chainId,
            enableRevertReason,
            genesisConfigOptions,
            evmConfiguration,
            miningConfiguration,
            isParallelTxProcessingEnabled,
            metricsSystem)
        .evmBuilder(
            (gasCalculator, jdCacheConfig) ->
                MainnetEVMs.experimentalEips(
                    gasCalculator, chainId.orElse(BigInteger.ZERO), evmConfiguration))
        .hardforkId(EXPERIMENTAL_EIPS);
  }

  private static class FrontierTransactionReceiptFactory implements TransactionReceiptFactory {

    @Override
    public TransactionReceipt create(
        final TransactionType transactionType,
        final TransactionProcessingResult result,
        final WorldState worldState,
        final long gasUsed) {
      return new TransactionReceipt(
          worldState.frontierRootHash(),
          gasUsed,
          result.getLogs(),
          Optional.empty()); // No revert reason in Frontier
    }

    @Override
    public TransactionReceipt create(
        final TransactionType transactionType,
        final TransactionProcessingResult result,
        final long gasUsed) {
      throw new UnsupportedOperationException("No stateless transaction receipt in Frontier");
    }
  }

  private abstract static class PostFrontierTransactionReceiptFactory
      implements TransactionReceiptFactory {
    protected final boolean revertReasonEnabled;

    public PostFrontierTransactionReceiptFactory(final boolean revertReasonEnabled) {
      this.revertReasonEnabled = revertReasonEnabled;
    }

    @Override
    public TransactionReceipt create(
        final TransactionType transactionType,
        final TransactionProcessingResult result,
        final WorldState worldState,
        final long gasUsed) {
      return create(transactionType, result, gasUsed);
    }
  }

  static class ByzantiumTransactionReceiptFactory extends PostFrontierTransactionReceiptFactory {
    public ByzantiumTransactionReceiptFactory(final boolean revertReasonEnabled) {
      super(revertReasonEnabled);
    }

    @Override
    public TransactionReceipt create(
        final TransactionType transactionType,
        final TransactionProcessingResult result,
        final long gasUsed) {
      return new TransactionReceipt(
          result.isSuccessful() ? 1 : 0,
          gasUsed,
          result.getLogs(),
          revertReasonEnabled ? result.getRevertReason() : Optional.empty());
    }
  }

  static class BerlinTransactionReceiptFactory extends PostFrontierTransactionReceiptFactory {

    public BerlinTransactionReceiptFactory(final boolean revertReasonEnabled) {
      super(revertReasonEnabled);
    }

    @Override
    public TransactionReceipt create(
        final TransactionType transactionType,
        final TransactionProcessingResult result,
        final long gasUsed) {
      return new TransactionReceipt(
          transactionType,
          result.isSuccessful() ? 1 : 0,
          gasUsed,
          result.getLogs(),
          revertReasonEnabled ? result.getRevertReason() : Optional.empty());
    }
  }

  private record DaoBlockProcessor(BlockProcessor wrapped) implements BlockProcessor {

    @Override
    public BlockProcessingResult processBlock(
        final ProtocolContext protocolContext,
        final Blockchain blockchain,
        final MutableWorldState worldState,
        final Block block) {
      updateWorldStateForDao(worldState);
      return wrapped.processBlock(
          protocolContext,
          blockchain,
          worldState,
          block,
          new AbstractBlockProcessor.PreprocessingFunction.NoPreprocessing());
    }

    @Override
    public BlockProcessingResult processBlock(
        final ProtocolContext protocolContext,
        final Blockchain blockchain,
        final MutableWorldState worldState,
        final Block block,
        final AbstractBlockProcessor.PreprocessingFunction preprocessingBlockFunction) {
      updateWorldStateForDao(worldState);
      return wrapped.processBlock(
          protocolContext, blockchain, worldState, block, preprocessingBlockFunction);
    }

    private static final Address DAO_REFUND_CONTRACT_ADDRESS =
        Address.fromHexString("0xbf4ed7b27f1d666546e30d74d50d173d20bca754");

    private void updateWorldStateForDao(final MutableWorldState worldState) {
      try {
        final JsonArray json =
            new JsonArray(
                Resources.toString(
                    Objects.requireNonNull(this.getClass().getResource("/daoAddresses.json")),
                    StandardCharsets.UTF_8));
        final List<Address> addresses =
            IntStream.range(0, json.size())
                .mapToObj(json::getString)
                .map(Address::fromHexString)
                .toList();
        final WorldUpdater worldUpdater = worldState.updater();
        final MutableAccount daoRefundContract =
            worldUpdater.getOrCreate(DAO_REFUND_CONTRACT_ADDRESS);
        for (final Address address : addresses) {
          final MutableAccount account = worldUpdater.getOrCreate(address);
          final Wei balance = account.getBalance();
          account.decrementBalance(balance);
          daoRefundContract.incrementBalance(balance);
        }
        worldUpdater.commit();
      } catch (final IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  static ProtocolSpecBuilder.FeeMarketBuilder createFeeMarket(
      final long londonForkBlockNumber,
      final boolean isZeroBaseFee,
      final boolean isFixedBaseFee,
      final Wei minTransactionGasPrice,
      final ProtocolSpecBuilder.FeeMarketBuilder feeMarketBuilder) {
    if (isZeroBaseFee) {
      return blobSchedule -> FeeMarket.zeroBaseFee(londonForkBlockNumber);
    }
    if (isFixedBaseFee) {
      return blobSchedule -> FeeMarket.fixedBaseFee(londonForkBlockNumber, minTransactionGasPrice);
    }
    return feeMarketBuilder;
  }
}
