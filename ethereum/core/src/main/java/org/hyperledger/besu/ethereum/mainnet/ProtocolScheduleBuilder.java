package org.hyperledger.besu.ethereum.mainnet;

import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.evm.internal.EvmConfiguration;

import java.math.BigInteger;
import java.util.function.Function;

public interface ProtocolScheduleBuilder {
  boolean matchGenesisConfig(GenesisConfigOptions genesisConfig);

  ProtocolSchedule createProtocolSchedule(
      GenesisConfigOptions config,
      BigInteger defaultChainId,
      ProtocolSpecAdapters protocolSpecAdapters,
      PrivacyParameters privacyParameters,
      boolean isRevertReasonEnabled,
      EvmConfiguration evmConfiguration);

  default ProtocolSchedule createProtocolSchedule(
      final GenesisConfigOptions config,
      final ProtocolSpecAdapters protocolSpecAdapters,
      final PrivacyParameters privacyParameters,
      final boolean isRevertReasonEnabled,
      final EvmConfiguration evmConfiguration) {
    return createProtocolSchedule(
        config,
        null,
        protocolSpecAdapters,
        privacyParameters,
        isRevertReasonEnabled,
        evmConfiguration);
  }

  record Milestone(
      Type type,
      long blockIdentifier,
      ProtocolSpecBuilder builder,
      Function<ProtocolSpecBuilder, ProtocolSpecBuilder> modifier) {

    enum Type {
      BLOCK_NUMBER,
      TIMESTAMP
    }
  }
}
