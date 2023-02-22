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
package org.hyperledger.besu.cli.options.unstable;

import static org.hyperledger.besu.cli.util.CommandLineUtils.DEPRECATION_WARNING_MSG;

import org.hyperledger.besu.Besu;
import org.hyperledger.besu.cli.converter.FractionConverter;
import org.hyperledger.besu.cli.options.CLIOptions;
import org.hyperledger.besu.cli.options.OptionParser;
import org.hyperledger.besu.ethereum.eth.transactions.ImmutableTransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/** The Transaction pool Cli options. */
public class TransactionPoolOptions
    implements CLIOptions<ImmutableTransactionPoolConfiguration.Builder> {
  private static final Logger LOG = LoggerFactory.getLogger(Besu.class);

  private static final String TX_MESSAGE_KEEP_ALIVE_SEC_FLAG =
      "--Xincoming-tx-messages-keep-alive-seconds";

  private static final String ETH65_TX_ANNOUNCED_BUFFERING_PERIOD_FLAG =
      "--Xeth65-tx-announced-buffering-period-milliseconds";

  private static final String STRICT_TX_REPLAY_PROTECTION_ENABLED_FLAG =
      "--strict-tx-replay-protection-enabled";

  private static final String TX_POOL_LIMIT_BY_ACCOUNT_PERCENTAGE =
      "--tx-pool-limit-by-account-percentage";

  private static final String LAYERED_TX_POOL_ENABLED_FLAG = "--Xlayered-tx-pool";
  private static final String LAYERED_TX_POOL_MAX_CAPACITY = "--Xlayered-tx-pool-max-capacity";
  private static final String LAYERED_TX_POOL_MAX_PRIORITIZED =
      "--Xlayered-tx-pool-max-prioritized";
  private static final String LAYERED_TX_POOL_MAX_FUTURE_BY_SENDER =
      "--Xlayered-tx-pool-max-future-by-sender";

  @CommandLine.Option(
      names = {STRICT_TX_REPLAY_PROTECTION_ENABLED_FLAG},
      paramLabel = "<Boolean>",
      description =
          "Require transactions submitted via JSON-RPC to use replay protection in accordance with EIP-155 (default: ${DEFAULT-VALUE})",
      fallbackValue = "true",
      arity = "0..1")
  private Boolean strictTxReplayProtectionEnabled = false;

  @CommandLine.Option(
      names = {TX_MESSAGE_KEEP_ALIVE_SEC_FLAG},
      paramLabel = "<INTEGER>",
      hidden = true,
      description =
          "Keep alive of incoming transaction messages in seconds (default: ${DEFAULT-VALUE})",
      arity = "1")
  private Integer txMessageKeepAliveSeconds =
      TransactionPoolConfiguration.DEFAULT_TX_MSG_KEEP_ALIVE;

  @CommandLine.Option(
      names = {ETH65_TX_ANNOUNCED_BUFFERING_PERIOD_FLAG},
      paramLabel = "<LONG>",
      hidden = true,
      description =
          "The period for which the announced transactions remain in the buffer before being requested from the peers in milliseconds (default: ${DEFAULT-VALUE})",
      arity = "1")
  private long eth65TrxAnnouncedBufferingPeriod =
      TransactionPoolConfiguration.ETH65_TRX_ANNOUNCED_BUFFERING_PERIOD.toMillis();

  @CommandLine.Option(
      names = {TX_POOL_LIMIT_BY_ACCOUNT_PERCENTAGE},
      paramLabel = "<DOUBLE>",
      converter = FractionConverter.class,
      description =
          "Maximum portion of the transaction pool which a single account may occupy with future transactions (default: ${DEFAULT-VALUE})",
      arity = "1")
  private Float txPoolLimitByAccountPercentage =
      TransactionPoolConfiguration.DEFAULT_LIMIT_TX_POOL_BY_ACCOUNT_PERCENTAGE;

  @CommandLine.Option(
      names = {LAYERED_TX_POOL_ENABLED_FLAG},
      paramLabel = "<Boolean>",
      hidden = true,
      description = "Enable the Layered Transaction Pool (default: ${DEFAULT-VALUE})",
      arity = "0..1")
  private Boolean layeredTxPoolEnabled =
      TransactionPoolConfiguration.DEFAULT_LAYERED_TX_POOL_ENABLED;

  @CommandLine.Option(
      names = {LAYERED_TX_POOL_MAX_CAPACITY},
      paramLabel = "<Long>",
      hidden = true,
      description =
          "Max amount of memory space, in bytes, that pending transactions could occupy (default: ${DEFAULT-VALUE})",
      arity = "1")
  private long layeredTxPoolMaxCapacity =
      TransactionPoolConfiguration.DEFAULT_PENDING_TRANSACTIONS_MAX_CAPACITY_BYTES;

  @CommandLine.Option(
      names = {LAYERED_TX_POOL_MAX_PRIORITIZED},
      paramLabel = "<Int>",
      hidden = true,
      description =
          "Max number of pending transactions that are prioritized and thus kept sorted (default: ${DEFAULT-VALUE})",
      arity = "1")
  private int layeredTxPoolMaxPrioritized =
      TransactionPoolConfiguration.DEFAULT_MAX_PRIORITIZED_TRANSACTIONS;

  @CommandLine.Option(
      names = {LAYERED_TX_POOL_MAX_FUTURE_BY_SENDER},
      paramLabel = "<Int>",
      hidden = true,
      description =
          "Max number future pending transactions that are allow for a single sender (default: ${DEFAULT-VALUE})",
      arity = "1")
  private int layeredTxPoolMaxFutureBySender =
      TransactionPoolConfiguration.DEFAULT_MAX_FUTURE_BY_SENDER;

  @CommandLine.Option(
      hidden = true,
      names = {"--tx-pool-future-max-by-account"},
      description = "Deprecated parameter, see instead: --tx-pool-limit-by-account-percentage")
  @SuppressWarnings({"FieldCanBeFinal", "UnusedVariable"})
  private Integer maxFutureTransactionsByAccount = -1;

  private TransactionPoolOptions() {}

  /**
   * Create transaction pool options.
   *
   * @return the transaction pool options
   */
  public static TransactionPoolOptions create() {
    return new TransactionPoolOptions();
  }

  /**
   * Create Transaction Pool Options from Transaction Pool Configuration.
   *
   * @param config the Transaction Pool Configuration
   * @return the transaction pool options
   */
  public static TransactionPoolOptions fromConfig(final TransactionPoolConfiguration config) {
    final TransactionPoolOptions options = TransactionPoolOptions.create();
    options.txMessageKeepAliveSeconds = config.getTxMessageKeepAliveSeconds();
    options.eth65TrxAnnouncedBufferingPeriod =
        config.getEth65TrxAnnouncedBufferingPeriod().toMillis();
    options.strictTxReplayProtectionEnabled = config.getStrictTransactionReplayProtectionEnabled();
    options.txPoolLimitByAccountPercentage = config.getTxPoolLimitByAccountPercentage();
    options.layeredTxPoolEnabled = config.getLayeredTxPoolEnabled();
    options.layeredTxPoolMaxCapacity = config.getPendingTransactionsMaxCapacityBytes();
    options.layeredTxPoolMaxPrioritized = config.getMaxPrioritizedTransactions();
    options.layeredTxPoolMaxFutureBySender = config.getMaxFutureBySender();
    return options;
  }

  @Override
  public ImmutableTransactionPoolConfiguration.Builder toDomainObject() {
    if (maxFutureTransactionsByAccount > 0) {
      LOG.warn(
          DEPRECATION_WARNING_MSG,
          "--tx-pool-future-max-by-account",
          "--tx-pool-limit-by-account-percentage");
    }

    if (layeredTxPoolEnabled) {
      LOG.warn(
          "Layered transaction pool enabled, ignoring settings for "
              + "--tx-pool-max-size and --tx-pool-limit-by-account-percentage");
    }

    return ImmutableTransactionPoolConfiguration.builder()
        .strictTransactionReplayProtectionEnabled(strictTxReplayProtectionEnabled)
        .txMessageKeepAliveSeconds(txMessageKeepAliveSeconds)
        .eth65TrxAnnouncedBufferingPeriod(Duration.ofMillis(eth65TrxAnnouncedBufferingPeriod))
        .txPoolLimitByAccountPercentage(txPoolLimitByAccountPercentage)
        .layeredTxPoolEnabled(layeredTxPoolEnabled)
        .pendingTransactionsMaxCapacityBytes(layeredTxPoolMaxCapacity)
        .maxPrioritizedTransactions(layeredTxPoolMaxPrioritized)
        .maxFutureBySender(layeredTxPoolMaxFutureBySender);
  }

  @Override
  public List<String> getCLIOptions() {
    return Arrays.asList(
        STRICT_TX_REPLAY_PROTECTION_ENABLED_FLAG + "=" + strictTxReplayProtectionEnabled,
        TX_POOL_LIMIT_BY_ACCOUNT_PERCENTAGE,
        OptionParser.format(txPoolLimitByAccountPercentage),
        TX_MESSAGE_KEEP_ALIVE_SEC_FLAG,
        OptionParser.format(txMessageKeepAliveSeconds),
        ETH65_TX_ANNOUNCED_BUFFERING_PERIOD_FLAG,
        OptionParser.format(eth65TrxAnnouncedBufferingPeriod),
        LAYERED_TX_POOL_ENABLED_FLAG + "=" + layeredTxPoolEnabled,
        LAYERED_TX_POOL_MAX_CAPACITY,
        OptionParser.format(layeredTxPoolMaxCapacity),
        LAYERED_TX_POOL_MAX_PRIORITIZED,
        OptionParser.format(layeredTxPoolMaxPrioritized),
        LAYERED_TX_POOL_MAX_FUTURE_BY_SENDER,
        OptionParser.format(layeredTxPoolMaxFutureBySender));
  }
}
