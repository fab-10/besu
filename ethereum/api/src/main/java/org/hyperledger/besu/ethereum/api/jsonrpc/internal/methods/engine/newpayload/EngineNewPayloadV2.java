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

import static java.util.stream.Collectors.toList;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.datatypes.HardforkId;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine.EngineCallListener;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.WithdrawalParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.engine.ExecutionPayloadV2;
import org.hyperledger.besu.ethereum.core.Withdrawal;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.List;
import java.util.Optional;

import io.vertx.core.Vertx;

/**
 * {@code engine_newPayloadV2} — Shanghai (Withdrawals).
 *
 * <p>Extends V1 by accepting {@link ExecutionPayloadV2} (V1 + withdrawals) and reading the
 * withdrawals from the typed payload. Also flips the invalid-block-hash status from {@link
 * EngineStatus#INVALID_BLOCK_HASH} to {@link EngineStatus#INVALID}.
 *
 * <p>Parameterised so V3 can extend this class while narrowing the payload type further.
 */
public sealed class EngineNewPayloadV2<EP extends ExecutionPayloadV2> extends EngineNewPayloadV1<EP>
    permits EngineNewPayloadV3 {

  EngineNewPayloadV2(
      final Vertx vertx,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final MergeMiningCoordinator mergeCoordinator,
      final EthPeers ethPeers,
      final EngineCallListener engineCallListener,
      final MetricsSystem metricsSystem,
      final HardforkId minSupportedFork,
      final HardforkId firstUnsupportedFork) {
    super(
        vertx,
        protocolSchedule,
        protocolContext,
        mergeCoordinator,
        ethPeers,
        engineCallListener,
        metricsSystem,
        minSupportedFork,
        firstUnsupportedFork);
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_NEW_PAYLOAD_V2.getMethodName();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Class<EP> getEnginePayloadParameterClass() {
    return (Class<EP>) ExecutionPayloadV2.class;
  }

  @Override
  protected EngineStatus getInvalidBlockHashStatus() {
    return EngineStatus.INVALID;
  }

  @Override
  protected Optional<List<Withdrawal>> extractWithdrawals(final EP payload) {
    return Optional.ofNullable(payload.getWithdrawals())
        .map(ws -> ws.stream().map(WithdrawalParameter::toWithdrawal).collect(toList()));
  }
}
