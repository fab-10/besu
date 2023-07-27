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
package org.hyperledger.besu.config;

import static java.util.Collections.emptyMap;
import static java.util.Objects.isNull;
import static org.hyperledger.besu.config.GenesisConfigOptions.Fork.HOMESTEAD;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import org.apache.tuweni.units.bigints.UInt256;

/** The Json genesis config options. */
public class JsonGenesisConfigOptions implements GenesisConfigOptions {

  private static final String ETHASH_CONFIG_KEY = "ethash";
  private static final String IBFT_LEGACY_CONFIG_KEY = "ibft";
  private static final String IBFT2_CONFIG_KEY = "ibft2";
  private static final String QBFT_CONFIG_KEY = "qbft";
  private static final String CLIQUE_CONFIG_KEY = "clique";
  private static final String EC_CURVE_CONFIG_KEY = "eccurve";
  private static final String TRANSITIONS_CONFIG_KEY = "transitions";
  private static final String DISCOVERY_CONFIG_KEY = "discovery";
  private static final String CHECKPOINT_CONFIG_KEY = "checkpoint";
  private static final String ZERO_BASE_FEE_KEY = "zerobasefee";
  private static final String DEPOSIT_CONTRACT_ADDRESS_KEY = "depositcontractaddress";

  private final ObjectNode configRoot;
  private final Map<String, String> configOverrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private final TransitionsConfigOptions transitions;

  /**
   * From json object json genesis config options.
   *
   * @param configRoot the config root
   * @return the json genesis config options
   */
  public static JsonGenesisConfigOptions fromJsonObject(final ObjectNode configRoot) {
    return fromJsonObjectWithOverrides(configRoot, emptyMap());
  }

  /**
   * From json object with overrides json genesis config options.
   *
   * @param configRoot the config root
   * @param configOverrides the config overrides
   * @return the json genesis config options
   */
  static JsonGenesisConfigOptions fromJsonObjectWithOverrides(
      final ObjectNode configRoot, final Map<String, String> configOverrides) {
    final TransitionsConfigOptions transitionsConfigOptions;
    transitionsConfigOptions = loadTransitionsFrom(configRoot);
    return new JsonGenesisConfigOptions(configRoot, configOverrides, transitionsConfigOptions);
  }

  private static TransitionsConfigOptions loadTransitionsFrom(final ObjectNode parentNode) {
    final Optional<ObjectNode> transitionsNode =
        JsonUtil.getObjectNode(parentNode, TRANSITIONS_CONFIG_KEY);
    if (transitionsNode.isEmpty()) {
      return new TransitionsConfigOptions(JsonUtil.createEmptyObjectNode());
    }

    return new TransitionsConfigOptions(transitionsNode.get());
  }

  /**
   * Instantiates a new Json genesis config options.
   *
   * @param maybeConfig the optional config
   * @param configOverrides the config overrides map
   * @param transitionsConfig the transitions configuration
   */
  JsonGenesisConfigOptions(
      final ObjectNode maybeConfig,
      final Map<String, String> configOverrides,
      final TransitionsConfigOptions transitionsConfig) {
    this.configRoot = isNull(maybeConfig) ? JsonUtil.createEmptyObjectNode() : maybeConfig;
    if (configOverrides != null) {
      this.configOverrides.putAll(configOverrides);
    }
    this.transitions = transitionsConfig;
  }

  @Override
  public String getConsensusEngine() {
    if (isEthHash()) {
      return ETHASH_CONFIG_KEY;
    } else if (isIbft2()) {
      return IBFT2_CONFIG_KEY;
    } else if (isQbft()) {
      return QBFT_CONFIG_KEY;
    } else if (isClique()) {
      return CLIQUE_CONFIG_KEY;
    } else {
      return "unknown";
    }
  }

  @Override
  public boolean isEthHash() {
    return configRoot.has(ETHASH_CONFIG_KEY);
  }

  @Override
  public boolean isIbftLegacy() {
    return configRoot.has(IBFT_LEGACY_CONFIG_KEY);
  }

  @Override
  public boolean isClique() {
    return configRoot.has(CLIQUE_CONFIG_KEY);
  }

  @Override
  public boolean isIbft2() {
    return configRoot.has(IBFT2_CONFIG_KEY);
  }

  @Override
  public boolean isQbft() {
    return configRoot.has(QBFT_CONFIG_KEY);
  }

  @Override
  public boolean isPoa() {
    return isQbft() || isClique() || isIbft2() || isIbftLegacy();
  }

  @Override
  public BftConfigOptions getBftConfigOptions() {
    final String fieldKey = isIbft2() ? IBFT2_CONFIG_KEY : QBFT_CONFIG_KEY;
    return JsonUtil.getObjectNode(configRoot, fieldKey)
        .map(JsonBftConfigOptions::new)
        .orElse(JsonBftConfigOptions.DEFAULT);
  }

  @Override
  public QbftConfigOptions getQbftConfigOptions() {
    return JsonUtil.getObjectNode(configRoot, QBFT_CONFIG_KEY)
        .map(JsonQbftConfigOptions::new)
        .orElse(JsonQbftConfigOptions.DEFAULT);
  }

  @Override
  public DiscoveryOptions getDiscoveryOptions() {
    return JsonUtil.getObjectNode(configRoot, DISCOVERY_CONFIG_KEY)
        .map(DiscoveryOptions::new)
        .orElse(DiscoveryOptions.DEFAULT);
  }

  @Override
  public CheckpointConfigOptions getCheckpointOptions() {
    return JsonUtil.getObjectNode(configRoot, CHECKPOINT_CONFIG_KEY)
        .map(CheckpointConfigOptions::new)
        .orElse(CheckpointConfigOptions.DEFAULT);
  }

  @Override
  public CliqueConfigOptions getCliqueConfigOptions() {
    return JsonUtil.getObjectNode(configRoot, CLIQUE_CONFIG_KEY)
        .map(CliqueConfigOptions::new)
        .orElse(CliqueConfigOptions.DEFAULT);
  }

  @Override
  public EthashConfigOptions getEthashConfigOptions() {
    return JsonUtil.getObjectNode(configRoot, ETHASH_CONFIG_KEY)
        .map(EthashConfigOptions::new)
        .orElse(EthashConfigOptions.DEFAULT);
  }

  @Override
  public TransitionsConfigOptions getTransitions() {
    return transitions;
  }

  @Override
  public OptionalLong getHomesteadBlockNumber() {
    return getOptionalLong(HOMESTEAD.jsonName());
  }

  @Override
  public OptionalLong getDaoForkBlock() {
    final OptionalLong block = getOptionalLong(Fork.DAO_FORK.jsonName());
    if (block.isPresent() && block.getAsLong() <= 0) {
      return OptionalLong.empty();
    }
    return block;
  }

  @Override
  public OptionalLong getTangerineWhistleBlockNumber() {
    return getOptionalLong(Fork.EIP_150.jsonName());
  }

  @Override
  public OptionalLong getSpuriousDragonBlockNumber() {
    return getOptionalLong(Fork.EIP_158.jsonName());
  }

  @Override
  public OptionalLong getByzantiumBlockNumber() {
    return getOptionalLong(Fork.BYZANTIUM.jsonName());
  }

  @Override
  public OptionalLong getConstantinopleBlockNumber() {
    return getOptionalLong(Fork.CONSTANTINOPLE.jsonName());
  }

  @Override
  public OptionalLong getPetersburgBlockNumber() {
    final OptionalLong petersburgBlock = getOptionalLong(Fork.PETERSBURG.jsonName());
    final OptionalLong constantinopleFixBlock = getOptionalLong(Fork.CONSTANTINOPLE_FIX.jsonName());
    if (constantinopleFixBlock.isPresent()) {
      if (petersburgBlock.isPresent()) {
        throw new RuntimeException(
            "Genesis files cannot specify both petersburgBlock and constantinopleFixBlock.");
      }
      return constantinopleFixBlock;
    }
    return petersburgBlock;
  }

  @Override
  public OptionalLong getIstanbulBlockNumber() {
    return getOptionalLong(Fork.ISTANBUL.jsonName());
  }

  @Override
  public OptionalLong getMuirGlacierBlockNumber() {
    return getOptionalLong(Fork.MUIR_GLACIER.jsonName());
  }

  @Override
  public OptionalLong getBerlinBlockNumber() {
    return getOptionalLong(Fork.BERLIN.jsonName());
  }

  @Override
  public OptionalLong getLondonBlockNumber() {
    return getOptionalLong(Fork.LONDON.jsonName());
  }

  @Override
  public OptionalLong getArrowGlacierBlockNumber() {
    return getOptionalLong(Fork.ARROW_GLACIER.jsonName());
  }

  @Override
  public OptionalLong getGrayGlacierBlockNumber() {
    return getOptionalLong(Fork.GRAY_GLACIER.jsonName());
  }

  @Override
  public OptionalLong getMergeNetSplitBlockNumber() {
    return getOptionalLong(Fork.MERGE_NET_SPLIT.jsonName());
  }

  @Override
  public OptionalLong getShanghaiTime() {
    return getOptionalLong(Fork.SHANGHAI.jsonName());
  }

  @Override
  public OptionalLong getCancunTime() {
    return getOptionalLong(Fork.CANCUN.jsonName());
  }

  @Override
  public OptionalLong getFutureEipsTime() {
    return getOptionalLong(Fork.FUTURE_EIPS.jsonName());
  }

  @Override
  public OptionalLong getExperimentalEipsTime() {
    return getOptionalLong(Fork.EXPERIMENTAL_EIPS.jsonName());
  }

  @Override
  public Optional<Wei> getBaseFeePerGas() {
    return Optional.ofNullable(configOverrides.get("baseFeePerGas"))
        .map(Wei::fromHexString)
        .map(Optional::of)
        .orElse(Optional.empty());
  }

  @Override
  public Optional<UInt256> getTerminalTotalDifficulty() {
    return getOptionalBigInteger("terminaltotaldifficulty").map(UInt256::valueOf);
  }

  @Override
  public OptionalLong getTerminalBlockNumber() {
    return getOptionalLong("terminalblocknumber");
  }

  @Override
  public Optional<Hash> getTerminalBlockHash() {
    return getOptionalHash("terminalblockhash");
  }

  @Override
  public OptionalLong getClassicForkBlock() {
    return getOptionalLong(Fork.CLASSIC_FORK.jsonName());
  }

  @Override
  public OptionalLong getEcip1015BlockNumber() {
    return getOptionalLong(Fork.ECIP_1015.jsonName());
  }

  @Override
  public OptionalLong getDieHardBlockNumber() {
    return getOptionalLong(Fork.DIE_HARD.jsonName());
  }

  @Override
  public OptionalLong getGothamBlockNumber() {
    return getOptionalLong(Fork.GOTHAM.jsonName());
  }

  @Override
  public OptionalLong getDefuseDifficultyBombBlockNumber() {
    return getOptionalLong(Fork.ECIP_1041.jsonName());
  }

  @Override
  public OptionalLong getAtlantisBlockNumber() {
    return getOptionalLong(Fork.ATLANTIS.jsonName());
  }

  @Override
  public OptionalLong getAghartaBlockNumber() {
    return getOptionalLong(Fork.AGHARTA.jsonName());
  }

  @Override
  public OptionalLong getPhoenixBlockNumber() {
    return getOptionalLong(Fork.PHOENIX.jsonName());
  }

  @Override
  public OptionalLong getThanosBlockNumber() {
    return getOptionalLong(Fork.THANOS.jsonName());
  }

  @Override
  public OptionalLong getMagnetoBlockNumber() {
    return getOptionalLong(Fork.MAGNETO.jsonName());
  }

  @Override
  public OptionalLong getMystiqueBlockNumber() {
    return getOptionalLong(Fork.MYSTIQUE.jsonName());
  }

  @Override
  public Optional<BigInteger> getChainId() {
    return getOptionalBigInteger("chainid");
  }

  @Override
  public OptionalInt getContractSizeLimit() {
    return getOptionalInt("contractsizelimit");
  }

  @Override
  public OptionalInt getEvmStackSize() {
    return getOptionalInt("evmstacksize");
  }

  @Override
  public OptionalLong getEcip1017EraRounds() {
    return getOptionalLong("ecip1017erarounds");
  }

  @Override
  public PowAlgorithm getPowAlgorithm() {
    return isEthHash() ? PowAlgorithm.ETHASH : PowAlgorithm.UNSUPPORTED;
  }

  @Override
  public Optional<String> getEcCurve() {
    return JsonUtil.getString(configRoot, EC_CURVE_CONFIG_KEY);
  }

  @Override
  public boolean isZeroBaseFee() {
    return getOptionalBoolean(ZERO_BASE_FEE_KEY).orElse(false);
  }

  @Override
  public Optional<Address> getDepositContractAddress() {
    Optional<String> inputAddress = JsonUtil.getString(configRoot, DEPOSIT_CONTRACT_ADDRESS_KEY);
    return inputAddress.map(Address::fromHexString);
  }

  @Override
  public Map<String, Object> asMap() {
    final ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    getChainId().ifPresent(chainId -> builder.put("chainId", chainId));

    for (final Fork fork : Fork.values()) {
      getOptionalLong(fork.jsonName()).ifPresent(l -> builder.put(fork.mapName(), l));
    }
    //
    //    // mainnet fork blocks
    //    getHomesteadBlockNumber().ifPresent(l -> builder.put(HOMESTEAD.mapName(), l));
    //    getDaoForkBlock().ifPresent(l -> builder.put(Fork.DAO_FORK.mapName(), l));
    //    getTangerineWhistleBlockNumber().ifPresent(l -> builder.put(Fork.EIP_150.mapName(), l));
    //    getSpuriousDragonBlockNumber().ifPresent(l -> builder.put(Fork.EIP_158.mapName(), l));
    //    getByzantiumBlockNumber().ifPresent(l -> builder.put(Fork.BYZANTIUM.mapName(), l));
    //    getConstantinopleBlockNumber().ifPresent(l -> builder.put(Fork.CONSTANTINOPLE.mapName(),
    // l));
    //    getPetersburgBlockNumber().ifPresent(l -> builder.put(Fork.PETERSBURG.mapName(), l));
    //    getIstanbulBlockNumber().ifPresent(l -> builder.put(Fork.ISTANBUL.mapName(), l));
    //    getMuirGlacierBlockNumber().ifPresent(l -> builder.put(Fork.MUIR_GLACIER.mapName(), l));
    //    getBerlinBlockNumber().ifPresent(l -> builder.put(Fork.BERLIN.mapName(), l));
    //    getLondonBlockNumber().ifPresent(l -> builder.put(Fork.LONDON.mapName(), l));
    //    getArrowGlacierBlockNumber().ifPresent(l -> builder.put(Fork.ARROW_GLACIER.mapName(), l));
    //    getGrayGlacierBlockNumber().ifPresent(l -> builder.put(Fork.GRAY_GLACIER.mapName(), l));
    //    getMergeNetSplitBlockNumber().ifPresent(l -> builder.put(Fork.MERGE_NET_SPLIT.mapName(),
    // l));
    //    getShanghaiTime().ifPresent(l -> builder.put(Fork.SHANGHAI.mapName(), l));
    //    getCancunTime().ifPresent(l -> builder.put(Fork.CANCUN.mapName(), l));
    getTerminalBlockNumber().ifPresent(l -> builder.put("terminalBlockNumber", l));
    getTerminalBlockHash().ifPresent(h -> builder.put("terminalBlockHash", h.toHexString()));
    //    getFutureEipsTime().ifPresent(l -> builder.put(Fork.FUTURE_EIPS.mapName(), l));
    //    getExperimentalEipsTime().ifPresent(l -> builder.put(Fork.EXPERIMENTAL_EIPS.mapName(),
    // l));

    // classic fork blocks
    //    getClassicForkBlock().ifPresent(l -> builder.put(Fork.CLASSIC_FORK.mapName(), l));
    //    getEcip1015BlockNumber().ifPresent(l -> builder.put(Fork.ECIP_1015.mapName(), l));
    //    getDieHardBlockNumber().ifPresent(l -> builder.put(Fork.DIE_HARD.mapName(), l));
    //    getGothamBlockNumber().ifPresent(l -> builder.put(Fork.GOTHAM.mapName(), l));
    //    getDefuseDifficultyBombBlockNumber().ifPresent(l -> builder.put(Fork.ECIP_1041.mapName(),
    // l));
    //    getAtlantisBlockNumber().ifPresent(l -> builder.put(Fork.ATLANTIS.mapName(), l));
    //    getAghartaBlockNumber().ifPresent(l -> builder.put(Fork.AGHARTA.mapName(), l));
    //    getPhoenixBlockNumber().ifPresent(l -> builder.put(Fork.PHOENIX.mapName(), l));
    //    getThanosBlockNumber().ifPresent(l -> builder.put(Fork.THANOS.mapName(), l));
    //    getMagnetoBlockNumber().ifPresent(l -> builder.put(Fork.MAGNETO.mapName(), l));
    //    getMystiqueBlockNumber().ifPresent(l -> builder.put(Fork.MYSTIQUE.mapName(), l));

    getContractSizeLimit().ifPresent(l -> builder.put("contractSizeLimit", l));
    getEvmStackSize().ifPresent(l -> builder.put("evmstacksize", l));
    getEcip1017EraRounds().ifPresent(l -> builder.put("ecip1017EraRounds", l));

    getDepositContractAddress().ifPresent(l -> builder.put("depositContractAddress", l));

    if (isClique()) {
      builder.put("clique", getCliqueConfigOptions().asMap());
    }
    if (isEthHash()) {
      builder.put("ethash", getEthashConfigOptions().asMap());
    }
    if (isIbft2()) {
      builder.put("ibft2", getBftConfigOptions().asMap());
    }
    if (isQbft()) {
      builder.put("qbft", getQbftConfigOptions().asMap());
    }

    if (isZeroBaseFee()) {
      builder.put("zeroBaseFee", true);
    }

    return builder.build();
  }

  private OptionalLong getOptionalLong(final String key) {
    if (configOverrides.containsKey(key)) {
      final String value = configOverrides.get(key);
      return value == null || value.isEmpty()
          ? OptionalLong.empty()
          : OptionalLong.of(Long.valueOf(configOverrides.get(key), 10));
    } else {
      return JsonUtil.getLong(configRoot, key);
    }
  }

  private OptionalInt getOptionalInt(final String key) {
    if (configOverrides.containsKey(key)) {
      final String value = configOverrides.get(key);
      return value == null || value.isEmpty()
          ? OptionalInt.empty()
          : OptionalInt.of(Integer.valueOf(configOverrides.get(key), 10));
    } else {
      return JsonUtil.getInt(configRoot, key);
    }
  }

  private Optional<BigInteger> getOptionalBigInteger(final String key) {
    if (configOverrides.containsKey(key)) {
      final String value = configOverrides.get(key);
      return value == null || value.isEmpty()
          ? Optional.empty()
          : Optional.of(new BigInteger(value));
    } else {
      return JsonUtil.getValueAsString(configRoot, key).map(s -> new BigInteger(s, 10));
    }
  }

  private Optional<Boolean> getOptionalBoolean(final String key) {
    if (configOverrides.containsKey(key)) {
      final String value = configOverrides.get(key);
      return value == null || value.isEmpty()
          ? Optional.empty()
          : Optional.of(Boolean.valueOf(configOverrides.get(key)));
    } else {
      return JsonUtil.getBoolean(configRoot, key);
    }
  }

  private Optional<Hash> getOptionalHash(final String key) {
    if (configOverrides.containsKey(key)) {
      final String overrideHash = configOverrides.get(key);
      return Optional.of(Hash.fromHexString(overrideHash));
    } else {
      return JsonUtil.getValueAsString(configRoot, key).map(Hash::fromHexString);
    }
  }

  @Override
  public SortedMap<Long, Fork> getForkByType(final Fork.Type type) {
    final TreeMap<Long, Fork> blockForks = new TreeMap<>();

    Stream.of(Fork.values())
        .filter(f -> f.getType().equals(type))
        .forEach(fork -> getOptionalLong(fork.jsonName()).ifPresent(l -> blockForks.put(l, fork)));

    return blockForks;
  }

  //  public SortedMap<Long, Fork> getForkBlockNumbers() {
  //    return getForksByType(Fork.Type.BLOCK);
  //
  //    Stream<Map.Entry<OptionalLong, Fork>> forkBlockNumbers =
  //        Stream.of(
  //            new SimpleEntry<>(getHomesteadBlockNumber(), HOMESTEAD),
  //                new SimpleEntry<>(getDaoForkBlock(),DAO_FORK),
  //                        new SimpleEntry<>(getTangerineWhistleBlockNumber(), EIP_150),
  //                                new SimpleEntry<>(getSpuriousDragonBlockNumber(), EIP_158),
  //                                        new SimpleEntry<>(getByzantiumBlockNumber(), BYZANTIUM),
  //                                                new
  // SimpleEntry<>(getConstantinopleBlockNumber(), CONSTANTINOPLE),
  //                new SimpleEntry<>(getPetersburgBlockNumber(), PETERSBURG),
  //                        new SimpleEntry<>(getIstanbulBlockNumber(), ISTANBUL),
  //                                new SimpleEntry<>(getMuirGlacierBlockNumber(), MUIR_GLACIER),
  //                                        new SimpleEntry<>(getBerlinBlockNumber(), BERLIN),
  //                                                new SimpleEntry<>(getLondonBlockNumber(),
  // LONDON),
  //                                                        new
  // SimpleEntry<>(getArrowGlacierBlockNumber(), ARROW_GLACIER),
  //                                                                new
  // SimpleEntry<>(getGrayGlacierBlockNumber(), GRAY_GLACIER),
  //                new SimpleEntry<>(getMergeNetSplitBlockNumber(), MERGE_NET_SPLIT),
  //                        new SimpleEntry<>(getEcip1015BlockNumber(), ECIP_1015),
  //                                new SimpleEntry<>(getDieHardBlockNumber(), DIE_HARD),
  //                                        new SimpleEntry<>(getGothamBlockNumber(), GOTHAM),
  //                                                new
  // SimpleEntry<>(getDefuseDifficultyBombBlockNumber(), ECIP_1041),
  //                                                        new
  // SimpleEntry<>(getAtlantisBlockNumber(), ATLANTIS),
  //                new SimpleEntry<>(getAghartaBlockNumber(), AGHARTA),
  //                        new SimpleEntry<>(getPhoenixBlockNumber(), PHOENIX),
  //                                new SimpleEntry<>(getThanosBlockNumber(), THANOS),
  //                                        new SimpleEntry<>(getMagnetoBlockNumber(), MAGNETO),
  //                                                new SimpleEntry<>(getMystiqueBlockNumber(),
  // MYSTIQUE));
  // when adding forks add an entry to ${REPO_ROOT}/config/src/test/resources/all_forks.json

  //    return forkBlockNumbers
  //        .filter(e -> e.getKey().isPresent())
  //        .map(e -> new SimpleEntry<>(e.getKey().getAsLong(), e.getValue()))
  //        .distinct()
  //        .sorted(Comparator.comparingLong(SimpleEntry::getKey))
  //        .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue, (a,b) -> a,
  // TreeMap::new));
  //  }

  //  @Override
  //  public List<Long> getForkBlockTimestamps() {
  //      return getForksByType(Fork.Type.BLOCK);
  //
  //    Stream<OptionalLong> forkBlockTimestamps =
  //        Stream.of(
  //            getShanghaiTime(), getCancunTime(), getFutureEipsTime(), getExperimentalEipsTime());
  //    // when adding forks add an entry to ${REPO_ROOT}/config/src/test/resources/all_forks.json
  //
  //    return forkBlockTimestamps
  //        .filter(OptionalLong::isPresent)
  //        .map(OptionalLong::getAsLong)
  //        .distinct()
  //        .sorted()
  //        .collect(Collectors.toList());
  //  }

}
