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

import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.ARROW_GLACIER;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.BERLIN;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.BYZANTIUM;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.CANCUN;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.CONSTANTINOPLE;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.DAO_FORK;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.EIP_150;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.EIP_158;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.EXPERIMENTAL_EIPS;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.FUTURE_EIPS;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.GRAY_GLACIER;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.HOMESTEAD;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.ISTANBUL;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.LONDON;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.MUIR_GLACIER;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.PETERSBURG;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.SHANGHAI;

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.config.GenesisConfigOptions.Fork;
import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.privacy.PrivateTransactionValidator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;

import java.math.BigInteger;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.auto.service.AutoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(ProtocolScheduleBuilder.class)
public class MainnetProtocolScheduleBuilder implements ProtocolScheduleBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(MainnetProtocolScheduleBuilder.class);
  //  private final GenesisConfigOptions config;
  //  private final Optional<BigInteger> defaultChainId;
  //  private final ProtocolSpecAdapters protocolSpecAdapters;
  //  private final PrivacyParameters privacyParameters;
  //  private final boolean isRevertReasonEnabled;
  //  private final EvmConfiguration evmConfiguration;
  private final BadBlockManager badBlockManager = new BadBlockManager();

  private DefaultProtocolSchedule protocolSchedule;

  public MainnetProtocolScheduleBuilder() {}

  //  public MainnetProtocolScheduleBuilder(
  //      final GenesisConfigOptions config,
  //      final BigInteger defaultChainId,
  //      final ProtocolSpecAdapters protocolSpecAdapters,
  //      final PrivacyParameters privacyParameters,
  //      final boolean isRevertReasonEnabled,
  //      final EvmConfiguration evmConfiguration) {
  //    this(
  //        config,
  //        Optional.of(defaultChainId),
  //        protocolSpecAdapters,
  //        privacyParameters,
  //        isRevertReasonEnabled,
  //        evmConfiguration);
  //  }
  //
  //  public MainnetProtocolScheduleBuilder(
  //      final GenesisConfigOptions config,
  //      final ProtocolSpecAdapters protocolSpecAdapters,
  //      final PrivacyParameters privacyParameters,
  //      final boolean isRevertReasonEnabled,
  //      final EvmConfiguration evmConfiguration) {
  //    this(
  //        config,
  //        Optional.empty(),
  //        protocolSpecAdapters,
  //        privacyParameters,
  //        isRevertReasonEnabled,
  //        evmConfiguration);
  //  }

  //  private MainnetProtocolScheduleBuilder(
  //      final GenesisConfigOptions config,
  //      final Optional<BigInteger> defaultChainId,
  //      final ProtocolSpecAdapters protocolSpecAdapters,
  //      final PrivacyParameters privacyParameters,
  //      final boolean isRevertReasonEnabled,
  //      final EvmConfiguration evmConfiguration) {
  //    this.config = config;
  //    this.protocolSpecAdapters = protocolSpecAdapters;
  //    this.privacyParameters = privacyParameters;
  //    this.isRevertReasonEnabled = isRevertReasonEnabled;
  //    this.evmConfiguration = evmConfiguration;
  //    this.defaultChainId = defaultChainId;
  //  }

  @Override
  public boolean matchGenesisConfig(final GenesisConfigOptions genesisConfig) {
    return Stream.concat(
            genesisConfig.getForkBlockNumbers().values().stream(),
            genesisConfig.getForkBlockTimestamps().values().stream())
        .allMatch(gf -> Stream.of(FORKS_ORDER).anyMatch(gf::equals));
  }

  @Override
  public ProtocolSchedule createProtocolSchedule(
      final GenesisConfigOptions config,
      final BigInteger defaultChainId,
      final ProtocolSpecAdapters protocolSpecAdapters,
      final PrivacyParameters privacyParameters,
      final boolean isRevertReasonEnabled,
      final EvmConfiguration evmConfiguration) {
    final Optional<BigInteger> chainId =
        config.getChainId().or(() -> Optional.ofNullable(defaultChainId));
    protocolSchedule = new DefaultProtocolSchedule(chainId);
    initSchedule(
        config,
        protocolSchedule,
        chainId,
        protocolSpecAdapters,
        privacyParameters,
        isRevertReasonEnabled,
        evmConfiguration);
    return protocolSchedule;
  }

  private void initSchedule(
      final GenesisConfigOptions config,
      final ProtocolSchedule protocolSchedule,
      final Optional<BigInteger> chainId,
      final ProtocolSpecAdapters protocolSpecAdapters,
      final PrivacyParameters privacyParameters,
      final boolean isRevertReasonEnabled,
      final EvmConfiguration evmConfiguration) {

    final MainnetProtocolSpecFactory specFactory =
        new MainnetProtocolSpecFactory(
            chainId,
            config.getContractSizeLimit(),
            config.getEvmStackSize(),
            isRevertReasonEnabled,
            config.getEcip1017EraRounds(),
            evmConfiguration);

    validateForkOrdering(config);

    final TreeMap<Long, Milestone> builders =
        buildMilestoneMap(config, specFactory, protocolSpecAdapters);

    // At this stage, all milestones are flagged with correct modifier, but ProtocolSpecs must be
    // inserted _AT_ the modifier block entry.
    if (!builders.isEmpty()) {
      protocolSpecAdapters.stream()
          .forEach(
              entry -> {
                final long modifierBlock = entry.getKey();
                final Milestone parent =
                    Optional.ofNullable(builders.floorEntry(modifierBlock))
                        .orElse(builders.firstEntry())
                        .getValue();
                builders.put(
                    modifierBlock,
                    new Milestone(
                        parent.type(), modifierBlock, parent.builder(), entry.getValue()));
              });
    }

    // Create the ProtocolSchedule, such that the Dao/fork milestones can be inserted
    builders
        .values()
        .forEach(
            e ->
                addProtocolSpec(
                    protocolSchedule,
                    e.type(),
                    e.blockIdentifier(),
                    e.builder(),
                    e.modifier(),
                    privacyParameters));

    // NOTE: It is assumed that Daofork blocks will not be used for private networks
    // as too many risks exist around inserting a protocol-spec between daoBlock and daoBlock+10.
    config
        .getDaoForkBlock()
        .ifPresent(
            daoBlockNumber -> {
              final Milestone previousSpecBuilder = builders.floorEntry(daoBlockNumber).getValue();
              final ProtocolSpec originalProtocolSpec =
                  getProtocolSpec(
                      protocolSchedule,
                      previousSpecBuilder.builder(),
                      previousSpecBuilder.modifier(),
                      privacyParameters);
              addProtocolSpec(
                  protocolSchedule,
                  Milestone.Type.BLOCK_NUMBER,
                  daoBlockNumber,
                  specFactory.daoRecoveryInitDefinition(),
                  protocolSpecAdapters.getModifierForBlock(daoBlockNumber),
                  privacyParameters);
              addProtocolSpec(
                  protocolSchedule,
                  Milestone.Type.BLOCK_NUMBER,
                  daoBlockNumber + 1L,
                  specFactory.daoRecoveryTransitionDefinition(),
                  protocolSpecAdapters.getModifierForBlock(daoBlockNumber + 1L),
                  privacyParameters);
              // Return to the previous protocol spec after the dao fork has completed.
              protocolSchedule.putBlockNumberMilestone(daoBlockNumber + 10, originalProtocolSpec);
            });

    // specs for classic network
    config
        .getClassicForkBlock()
        .ifPresent(
            classicBlockNumber -> {
              final Milestone previousSpecBuilder =
                  builders.floorEntry(classicBlockNumber).getValue();
              final ProtocolSpec originalProtocolSpec =
                  getProtocolSpec(
                      protocolSchedule,
                      previousSpecBuilder.builder(),
                      previousSpecBuilder.modifier(),
                      privacyParameters);
              addProtocolSpec(
                  protocolSchedule,
                  Milestone.Type.BLOCK_NUMBER,
                  classicBlockNumber,
                  ClassicProtocolSpecs.classicRecoveryInitDefinition(
                      config.getContractSizeLimit(), config.getEvmStackSize(), evmConfiguration),
                  Function.identity(),
                  privacyParameters);
              protocolSchedule.putBlockNumberMilestone(
                  classicBlockNumber + 1, originalProtocolSpec);
            });

    LOG.info("Protocol schedule created with milestones: {}", protocolSchedule.listMilestones());
  }

  private void validateForkOrdering(final GenesisConfigOptions config) {
    long lastForkBlock = 0;

    final var configMap = config.asMap();
    for (final Fork fork : FORKS_ORDER) {
      if (configMap.containsKey(fork.mapName())) {
        lastForkBlock =
            validateForkOrder(fork.toString(), (long) configMap.get(fork.mapName()), lastForkBlock);
      }
    }
  }

  private static final Fork[] FORKS_ORDER =
      new Fork[] {
        HOMESTEAD,
        DAO_FORK,
        EIP_150,
        EIP_158,
        BYZANTIUM,
        CONSTANTINOPLE,
        PETERSBURG,
        ISTANBUL,
        MUIR_GLACIER,
        BERLIN,
        LONDON,
        ARROW_GLACIER,
        GRAY_GLACIER,
        SHANGHAI,
        CANCUN,
        FUTURE_EIPS,
        EXPERIMENTAL_EIPS
      };

  //  private void validateEthereumForkOrdering() {
  //    long lastForkBlock = 0;
  //
  //    final var configMap = config.asMap();
  //    for(final Fork fork: FORKS_ORDER) {
  //      if(configMap.containsKey(fork.mapName())) {
  //        lastForkBlock = validateForkOrder(fork.toString(), (long)configMap.get(fork.mapName()),
  // lastForkBlock);
  //      }
  //    }

  //    lastForkBlock = validateForkOrder("Homestead", config.getHomesteadBlockNumber(),
  // lastForkBlock);
  //    lastForkBlock = validateForkOrder("DaoFork", config.getDaoForkBlock(), lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder(
  //            "TangerineWhistle", config.getTangerineWhistleBlockNumber(), lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder("SpuriousDragon", config.getSpuriousDragonBlockNumber(),
  // lastForkBlock);
  //    lastForkBlock = validateForkOrder("Byzantium", config.getByzantiumBlockNumber(),
  // lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder("Constantinople", config.getConstantinopleBlockNumber(),
  // lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder("Petersburg", config.getPetersburgBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Istanbul", config.getIstanbulBlockNumber(),
  // lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder("MuirGlacier", config.getMuirGlacierBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Berlin", config.getBerlinBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("London", config.getLondonBlockNumber(), lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder("ArrowGlacier", config.getArrowGlacierBlockNumber(), lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder("GrayGlacier", config.getGrayGlacierBlockNumber(), lastForkBlock);
  //    // Begin timestamp forks
  //    lastForkBlock = validateForkOrder("Shanghai", config.getShanghaiTime(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Cancun", config.getCancunTime(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("FutureEips", config.getFutureEipsTime(), lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder("ExperimentalEips", config.getExperimentalEipsTime(), lastForkBlock);
  //    assert (lastForkBlock >= 0);
  //  }
  //
  //  private void validateClassicForkOrdering() {
  //    long lastForkBlock = 0;
  //    lastForkBlock = validateForkOrder("Homestead", config.getHomesteadBlockNumber(),
  // lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder(
  //            "ClassicTangerineWhistle", config.getEcip1015BlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("DieHard", config.getDieHardBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Gotham", config.getGothamBlockNumber(), lastForkBlock);
  //    lastForkBlock =
  //        validateForkOrder(
  //            "DefuseDifficultyBomb", config.getDefuseDifficultyBombBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Atlantis", config.getAtlantisBlockNumber(),
  // lastForkBlock);
  //    lastForkBlock = validateForkOrder("Agharta", config.getAghartaBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Phoenix", config.getPhoenixBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Thanos", config.getThanosBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Magneto", config.getMagnetoBlockNumber(), lastForkBlock);
  //    lastForkBlock = validateForkOrder("Mystique", config.getMystiqueBlockNumber(),
  // lastForkBlock);
  //    assert (lastForkBlock >= 0);
  //  }

  private long validateForkOrder(
      final String forkName, final long thisForkBlock, final long lastForkBlock) {
    if (lastForkBlock > thisForkBlock) {
      throw new RuntimeException(
          String.format(
              "Genesis Config Error: '%s' is scheduled for milestone %d but it must be on or after milestone %d.",
              forkName, thisForkBlock, lastForkBlock));
    }
    return thisForkBlock;
  }

  private TreeMap<Long, Milestone> buildMilestoneMap(
      final GenesisConfigOptions config,
      final MainnetProtocolSpecFactory specFactory,
      final ProtocolSpecAdapters protocolSpecAdapters) {
    return createMilestones(config, specFactory, protocolSpecAdapters)
        .flatMap(Optional::stream)
        .collect(
            Collectors.toMap(
                Milestone::blockIdentifier,
                b -> b,
                (existing, replacement) -> replacement,
                TreeMap::new));
  }

  private Stream<Optional<Milestone>> createMilestones(
      final GenesisConfigOptions config,
      final MainnetProtocolSpecFactory specFactory,
      final ProtocolSpecAdapters protocolSpecAdapters) {
    return Stream.of(
        blockNumberMilestone(
            OptionalLong.of(0), specFactory.frontierDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getHomesteadBlockNumber(),
            specFactory.homesteadDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getTangerineWhistleBlockNumber(),
            specFactory.tangerineWhistleDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getSpuriousDragonBlockNumber(),
            specFactory.spuriousDragonDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getByzantiumBlockNumber(),
            specFactory.byzantiumDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getConstantinopleBlockNumber(),
            specFactory.constantinopleDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getPetersburgBlockNumber(),
            specFactory.petersburgDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getIstanbulBlockNumber(),
            specFactory.istanbulDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getMuirGlacierBlockNumber(),
            specFactory.muirGlacierDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getBerlinBlockNumber(), specFactory.berlinDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getLondonBlockNumber(),
            specFactory.londonDefinition(config),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getArrowGlacierBlockNumber(),
            specFactory.arrowGlacierDefinition(config),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getGrayGlacierBlockNumber(),
            specFactory.grayGlacierDefinition(config),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getMergeNetSplitBlockNumber(),
            specFactory.parisDefinition(config),
            protocolSpecAdapters),
        // Timestamp Forks
        timestampMilestone(
            config.getShanghaiTime(), specFactory.shanghaiDefinition(config), protocolSpecAdapters),
        timestampMilestone(
            config.getCancunTime(), specFactory.cancunDefinition(config), protocolSpecAdapters),
        timestampMilestone(
            config.getFutureEipsTime(),
            specFactory.futureEipsDefinition(config),
            protocolSpecAdapters),
        timestampMilestone(
            config.getExperimentalEipsTime(),
            specFactory.experimentalEipsDefinition(config),
            protocolSpecAdapters),

        // Classic Milestones
        blockNumberMilestone(
            config.getEcip1015BlockNumber(),
            specFactory.tangerineWhistleDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getDieHardBlockNumber(), specFactory.dieHardDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getGothamBlockNumber(), specFactory.gothamDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getDefuseDifficultyBombBlockNumber(),
            specFactory.defuseDifficultyBombDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getAtlantisBlockNumber(),
            specFactory.atlantisDefinition(),
            protocolSpecAdapters),
        blockNumberMilestone(
            config.getAghartaBlockNumber(), specFactory.aghartaDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getPhoenixBlockNumber(), specFactory.phoenixDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getThanosBlockNumber(), specFactory.thanosDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getMagnetoBlockNumber(), specFactory.magnetoDefinition(), protocolSpecAdapters),
        blockNumberMilestone(
            config.getMystiqueBlockNumber(),
            specFactory.mystiqueDefinition(),
            protocolSpecAdapters));
  }

  private Optional<Milestone> timestampMilestone(
      final OptionalLong blockIdentifier,
      final ProtocolSpecBuilder builder,
      final ProtocolSpecAdapters protocolSpecAdapters) {
    return createMilestone(
        blockIdentifier, builder, Milestone.Type.TIMESTAMP, protocolSpecAdapters);
  }

  private Optional<Milestone> blockNumberMilestone(
      final OptionalLong blockIdentifier,
      final ProtocolSpecBuilder builder,
      final ProtocolSpecAdapters protocolSpecAdapters) {
    return createMilestone(
        blockIdentifier, builder, Milestone.Type.BLOCK_NUMBER, protocolSpecAdapters);
  }

  private Optional<Milestone> createMilestone(
      final OptionalLong blockIdentifier,
      final ProtocolSpecBuilder builder,
      final Milestone.Type type,
      final ProtocolSpecAdapters protocolSpecAdapters) {
    if (blockIdentifier.isEmpty()) {
      return Optional.empty();
    }
    final long blockVal = blockIdentifier.getAsLong();
    return Optional.of(
        new Milestone(type, blockVal, builder, protocolSpecAdapters.getModifierForBlock(blockVal)));
  }

  private ProtocolSpec getProtocolSpec(
      final ProtocolSchedule protocolSchedule,
      final ProtocolSpecBuilder definition,
      final Function<ProtocolSpecBuilder, ProtocolSpecBuilder> modifier,
      final PrivacyParameters privacyParameters) {
    definition
        .badBlocksManager(badBlockManager)
        .privacyParameters(privacyParameters)
        .privateTransactionValidatorBuilder(
            () -> new PrivateTransactionValidator(protocolSchedule.getChainId()));

    return modifier.apply(definition).build(protocolSchedule);
  }

  private void addProtocolSpec(
      final ProtocolSchedule protocolSchedule,
      final Milestone.Type type,
      final long blockNumberOrTimestamp,
      final ProtocolSpecBuilder definition,
      final Function<ProtocolSpecBuilder, ProtocolSpecBuilder> modifier,
      final PrivacyParameters privacyParameters) {

    switch (type) {
      case BLOCK_NUMBER -> protocolSchedule.putBlockNumberMilestone(
          blockNumberOrTimestamp,
          getProtocolSpec(protocolSchedule, definition, modifier, privacyParameters));
      case TIMESTAMP -> protocolSchedule.putTimestampMilestone(
          blockNumberOrTimestamp,
          getProtocolSpec(protocolSchedule, definition, modifier, privacyParameters));
      default -> throw new IllegalStateException(
          "Unexpected milestoneType: " + type + " for milestone: " + blockNumberOrTimestamp);
    }
  }
}
