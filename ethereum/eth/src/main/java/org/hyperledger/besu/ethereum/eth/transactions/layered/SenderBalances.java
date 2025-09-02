/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.ethereum.eth.transactions.layered;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SenderBalances {
  private static final Logger LOG_FOR_REPLAY = LoggerFactory.getLogger("LOG_FOR_REPLAY");
  private final ProtocolSchedule protocolSchedule;
  private final WorldStateArchive worldStateArchive;
  private final Blockchain blockchain;

  public SenderBalances(
      final ProtocolSchedule protocolSchedule, final ProtocolContext protocolContext) {
    this.protocolSchedule = protocolSchedule;
    this.worldStateArchive = protocolContext.getWorldStateArchive();
    this.blockchain = protocolContext.getBlockchain();
  }

  public boolean hasEnoughBalanceFor(final PendingTransaction pendingTransaction) {
    final var tx = pendingTransaction.getTransaction();
    final var sender = tx.getSender();
    final var maybeAccount = worldStateArchive.getWorldState().get(sender);
    if (maybeAccount != null) {
      final var gasCalculator =
          protocolSchedule.getByBlockHeader(blockchain.getChainHeadHeader()).getGasCalculator();
      final var upfrontCost = tx.getUpfrontCost(gasCalculator.blobGasCost(tx.getBlobCount()));
      final var senderBalance = maybeAccount.getBalance();
      logSenderBalance(sender, senderBalance);
      return senderBalance.greaterOrEqualThan(upfrontCost);
    }
    logSenderBalance(sender, Wei.ZERO);
    return false;
  }

  private void logSenderBalance(final Address sender, final Wei balance) {
    LOG_FOR_REPLAY
        .atTrace()
        .setMessage("SB,{},{}")
        .addArgument(sender)
        .addArgument(balance::toShortHexString)
        .log();
  }
}
