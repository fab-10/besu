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
package org.hyperledger.besu.cli.options;

import static java.util.Arrays.asList;
import static org.hyperledger.besu.ethereum.core.MiningParameters.MutableInitValues.DEFAULT_EXTRA_DATA;
import static org.hyperledger.besu.ethereum.core.MiningParameters.MutableInitValues.DEFAULT_MIN_BLOCK_OCCUPANCY_RATIO;
import static org.hyperledger.besu.ethereum.core.MiningParameters.MutableInitValues.DEFAULT_MIN_TRANSACTION_GAS_PRICE;
import static org.hyperledger.besu.ethereum.core.MiningParameters.Unstable.DEFAULT_MAX_OMMERS_DEPTH;
import static org.hyperledger.besu.ethereum.core.MiningParameters.Unstable.DEFAULT_POS_BLOCK_CREATION_MAX_TIME;
import static org.hyperledger.besu.ethereum.core.MiningParameters.Unstable.DEFAULT_POS_BLOCK_CREATION_REPETITION_MIN_DURATION;
import static org.hyperledger.besu.ethereum.core.MiningParameters.Unstable.DEFAULT_POW_JOB_TTL;
import static org.hyperledger.besu.ethereum.core.MiningParameters.Unstable.DEFAULT_REMOTE_SEALERS_LIMIT;
import static org.hyperledger.besu.ethereum.core.MiningParameters.Unstable.DEFAULT_REMOTE_SEALERS_TTL;
import static org.hyperledger.besu.ethereum.core.MiningParameters.Unstable.DEFAULT_TXS_SELECTION_MAX_TIME;

import org.hyperledger.besu.cli.util.CommandLineUtils;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.ImmutableMiningParameters;
import org.hyperledger.besu.ethereum.core.ImmutableMiningParameters.MutableInitValues;
import org.hyperledger.besu.ethereum.core.MiningParameters;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

/** The Mining CLI options. */
public class MiningOptions implements CLIOptions<MiningParameters> {

  @Option(
      names = {"--miner-enabled"},
      description = "Set if node will perform mining (default: ${DEFAULT-VALUE})")
  private Boolean isMiningEnabled = false;

  @Option(
      names = {"--miner-stratum-enabled"},
      description = "Set if node will perform Stratum mining (default: ${DEFAULT-VALUE})")
  private Boolean iStratumMiningEnabled = false;

  @Option(
      names = {"--miner-stratum-host"},
      description = "Host for Stratum network mining service (default: ${DEFAULT-VALUE})")
  private String stratumNetworkInterface = "0.0.0.0";

  @Option(
      names = {"--miner-stratum-port"},
      description = "Stratum port binding (default: ${DEFAULT-VALUE})")
  private Integer stratumPort = 8008;

  @Option(
      names = {"--miner-coinbase"},
      description =
          "Account to which mining rewards are paid. You must specify a valid coinbase if "
              + "mining is enabled using --miner-enabled option",
      arity = "1")
  private Address coinbase = null;

  @Option(
      names = {"--miner-extra-data"},
      description =
          "A hex string representing the (32) bytes to be included in the extra data "
              + "field of a mined block (default: ${DEFAULT-VALUE})",
      arity = "1")
  private Bytes extraData = DEFAULT_EXTRA_DATA;

  @Option(
      names = {"--min-block-occupancy-ratio"},
      description = "Minimum occupancy ratio for a mined block (default: ${DEFAULT-VALUE})",
      arity = "1")
  private Double minBlockOccupancyRatio = DEFAULT_MIN_BLOCK_OCCUPANCY_RATIO;

  @Option(
      names = {"--min-gas-price"},
      description =
          "Minimum price (in Wei) offered by a transaction for it to be included in a mined "
              + "block (default: ${DEFAULT-VALUE})",
      arity = "1")
  private Wei minTransactionGasPrice = DEFAULT_MIN_TRANSACTION_GAS_PRICE;

  @Option(
      names = {"--target-gas-limit"},
      description =
          "Sets target gas limit per block. If set, each block's gas limit will approach this setting over time if the current gas limit is different.")
  private Long targetGasLimit = null;

  @CommandLine.ArgGroup(validate = false)
  private final Unstable unstableOptions = new Unstable();

  static class Unstable {
    @CommandLine.Option(
        hidden = true,
        names = {"--Xminer-remote-sealers-limit"},
        description =
            "Limits the number of remote sealers that can submit their hashrates (default: ${DEFAULT-VALUE})")
    private Integer remoteSealersLimit = DEFAULT_REMOTE_SEALERS_LIMIT;

    @CommandLine.Option(
        hidden = true,
        names = {"--Xminer-remote-sealers-hashrate-ttl"},
        description =
            "Specifies the lifetime of each entry in the cache. An entry will be automatically deleted if no update has been received before the deadline (default: ${DEFAULT-VALUE} minutes)")
    private Long remoteSealersTimeToLive = DEFAULT_REMOTE_SEALERS_TTL;

    @CommandLine.Option(
        hidden = true,
        names = {"--Xminer-pow-job-ttl"},
        description =
            "Specifies the time PoW jobs are kept in cache and will accept a solution from miners (default: ${DEFAULT-VALUE} milliseconds)")
    private Long powJobTimeToLive = DEFAULT_POW_JOB_TTL;

    @CommandLine.Option(
        hidden = true,
        names = {"--Xmax-ommers-depth"},
        description =
            "Specifies the depth of ommer blocks to accept when receiving solutions (default: ${DEFAULT-VALUE})")
    private Integer maxOmmersDepth = DEFAULT_MAX_OMMERS_DEPTH;

    @CommandLine.Option(
        hidden = true,
        names = {"--Xminer-stratum-extranonce"},
        description = "Extranonce for Stratum network miners (default: ${DEFAULT-VALUE})")
    private String stratumExtranonce = "080c";

    @CommandLine.Option(
        hidden = true,
        names = {"--Xpos-block-creation-max-time"},
        description =
            "Specifies the maximum time, in milliseconds, a PoS block creation jobs is allowed to run. Must be positive and ≤ 12000 (default: ${DEFAULT-VALUE} milliseconds)")
    private Long posBlockCreationMaxTime = DEFAULT_POS_BLOCK_CREATION_MAX_TIME;

    @CommandLine.Option(
        hidden = true,
        names = {"--Xpos-block-creation-repetition-min-duration"},
        description =
            "If a PoS block creation repetition takes less than this duration, in milliseconds,"
                + " then it waits before next repetition. Must be positive and ≤ 2000 (default: ${DEFAULT-VALUE} milliseconds)")
    private Long posBlockCreationRepetitionMinDuration =
        DEFAULT_POS_BLOCK_CREATION_REPETITION_MIN_DURATION;

    @CommandLine.Option(
        hidden = true,
        names = {"--Xtxs-selection-max-time"},
        description =
            "Specifies the maximum time, in milliseconds, that could be spent selecting transactions to be included in the block."
                + " Must be positive and ≤  (default: ${DEFAULT-VALUE} milliseconds)")
    private Long txsSelectionMaxTime = DEFAULT_TXS_SELECTION_MAX_TIME;
  }

  private MiningOptions() {}

  /**
   * Create mining options.
   *
   * @return the mining options
   */
  public static MiningOptions create() {
    return new MiningOptions();
  }

  /**
   * Validate that there are no inconsistencies in the specified options. For example that the
   * options are valid for the selected implementation.
   *
   * @param commandLine the full commandLine to check all the options specified by the user
   * @param logger the logger
   * @param isMergeEnabled is the Merge enabled?
   * @param isEthHash is EthHash?
   */
  public void validate(
      final CommandLine commandLine,
      final Logger logger,
      final boolean isMergeEnabled,
      final boolean isEthHash) {
    if (Boolean.TRUE.equals(isMiningEnabled) && coinbase == null) {
      throw new ParameterException(
          commandLine,
          "Unable to mine without a valid coinbase. Either disable mining (remove --miner-enabled) "
              + "or specify the beneficiary of mining (via --miner-coinbase <Address>)");
    }
    if (Boolean.FALSE.equals(isMiningEnabled) && Boolean.TRUE.equals(iStratumMiningEnabled)) {
      throw new ParameterException(
          commandLine,
          "Unable to mine with Stratum if mining is disabled. Either disable Stratum mining (remove --miner-stratum-enabled) "
              + "or specify mining is enabled (--miner-enabled)");
    }

    // Check that block producer options work
    if (!isMergeEnabled && isEthHash) {
      CommandLineUtils.checkOptionDependencies(
          logger,
          commandLine,
          "--miner-enabled",
          !isMiningEnabled,
          asList(
              "--miner-coinbase",
              "--min-gas-price",
              "--min-block-occupancy-ratio",
              "--miner-extra-data"));

      // Check that mining options are able to work
      CommandLineUtils.checkOptionDependencies(
          logger,
          commandLine,
          "--miner-enabled",
          !isMiningEnabled,
          asList(
              "--miner-stratum-enabled",
              "--Xminer-remote-sealers-limit",
              "--Xminer-remote-sealers-hashrate-ttl"));
    }

    if (unstableOptions.posBlockCreationMaxTime <= 0
        || unstableOptions.posBlockCreationMaxTime > DEFAULT_POS_BLOCK_CREATION_MAX_TIME) {
      throw new ParameterException(
          commandLine,
          "--Xpos-block-creation-max-time must be positive and ≤ "
              + DEFAULT_POS_BLOCK_CREATION_MAX_TIME);
    }

    if (unstableOptions.posBlockCreationRepetitionMinDuration <= 0
        || unstableOptions.posBlockCreationRepetitionMinDuration > 2000) {
      throw new ParameterException(
          commandLine, "--Xpos-block-creation-repetition-min-duration must be positive and ≤ 2000");
    }

    if (unstableOptions.txsSelectionMaxTime <= 0
        || unstableOptions.txsSelectionMaxTime > DEFAULT_TXS_SELECTION_MAX_TIME) {
      throw new ParameterException(
          commandLine,
          "--Xtxs-selection-max-time must be positive and ≤ " + DEFAULT_TXS_SELECTION_MAX_TIME);
    }
  }

  static MiningOptions fromConfig(final MiningParameters miningParameters) {
    final MiningOptions miningOptions = MiningOptions.create();
    miningOptions.isMiningEnabled = miningParameters.isMiningEnabled();
    miningOptions.iStratumMiningEnabled = miningParameters.isStratumMiningEnabled();
    miningOptions.stratumNetworkInterface = miningParameters.getStratumNetworkInterface();
    miningOptions.stratumPort = miningParameters.getStratumPort();
    miningOptions.extraData = miningParameters.getExtraData();
    miningOptions.minTransactionGasPrice = miningParameters.getMinTransactionGasPrice();
    miningOptions.minBlockOccupancyRatio = miningParameters.getMinBlockOccupancyRatio();
    miningOptions.unstableOptions.remoteSealersLimit =
        miningParameters.getUnstable().getRemoteSealersLimit();
    miningOptions.unstableOptions.remoteSealersTimeToLive =
        miningParameters.getUnstable().getRemoteSealersTimeToLive();
    miningOptions.unstableOptions.powJobTimeToLive =
        miningParameters.getUnstable().getPowJobTimeToLive();
    miningOptions.unstableOptions.maxOmmersDepth =
        miningParameters.getUnstable().getMaxOmmerDepth();
    miningOptions.unstableOptions.stratumExtranonce =
        miningParameters.getUnstable().getStratumExtranonce();
    miningOptions.unstableOptions.posBlockCreationMaxTime =
        miningParameters.getUnstable().getPosBlockCreationMaxTime();
    miningOptions.unstableOptions.posBlockCreationRepetitionMinDuration =
        miningParameters.getUnstable().getPosBlockCreationRepetitionMinDuration();
    miningOptions.unstableOptions.txsSelectionMaxTime =
        miningParameters.getUnstable().getTxsSelectionMaxTime();

    miningParameters.getCoinbase().ifPresent(coinbase -> miningOptions.coinbase = coinbase);
    miningParameters.getTargetGasLimit().ifPresent(tgl -> miningOptions.targetGasLimit = tgl);
    return miningOptions;
  }

  @Override
  public MiningParameters toDomainObject() {
    final var updatableInitValuesBuilder =
        MutableInitValues.builder()
            .isMiningEnabled(isMiningEnabled)
            .extraData(extraData)
            .minTransactionGasPrice(minTransactionGasPrice)
            .minBlockOccupancyRatio(minBlockOccupancyRatio);

    if (targetGasLimit != null) {
      updatableInitValuesBuilder.targetGasLimit(targetGasLimit);
    }
    if (coinbase != null) {
      updatableInitValuesBuilder.coinbase(coinbase);
    }

    final var miningParametersBuilder =
        ImmutableMiningParameters.builder()
            .mutableInitValues(updatableInitValuesBuilder.build())
            .isStratumMiningEnabled(iStratumMiningEnabled)
            .stratumNetworkInterface(stratumNetworkInterface)
            .stratumPort(stratumPort)
            .unstable(
                ImmutableMiningParameters.Unstable.builder()
                    .remoteSealersLimit(unstableOptions.remoteSealersLimit)
                    .remoteSealersTimeToLive(unstableOptions.remoteSealersTimeToLive)
                    .powJobTimeToLive(unstableOptions.powJobTimeToLive)
                    .maxOmmerDepth(unstableOptions.maxOmmersDepth)
                    .stratumExtranonce(unstableOptions.stratumExtranonce)
                    .posBlockCreationMaxTime(unstableOptions.posBlockCreationMaxTime)
                    .posBlockCreationRepetitionMinDuration(
                        unstableOptions.posBlockCreationRepetitionMinDuration)
                    .txsSelectionMaxTime(unstableOptions.txsSelectionMaxTime)
                    .build());

    return miningParametersBuilder.build();
  }

  @Override
  public List<String> getCLIOptions() {
    return CommandLineUtils.getCLIOptions(this, new MiningOptions());
  }
}
