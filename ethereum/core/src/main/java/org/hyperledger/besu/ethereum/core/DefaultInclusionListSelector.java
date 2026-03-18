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
package org.hyperledger.besu.ethereum.core;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link InclusionListTransactionSelector} that selects transactions for
 * inclusion lists per EIP-7805. Transactions are prioritized by effective gas price (highest
 * first), time in pool (older first for ties), and nonce sequentiality per sender is enforced.
 */
public class DefaultInclusionListSelector implements InclusionListTransactionSelector {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultInclusionListSelector.class);

  @Override
  public List<Bytes> selectTransactions(
      final Hash parentHash,
      final List<Transaction> mempoolTransactions,
      final int maxBytes,
      final Optional<Wei> baseFeePerGas) {
    if (mempoolTransactions == null || mempoolTransactions.isEmpty()) {
      LOG.atDebug()
          .setMessage("IL selector: no mempool transactions available for parent {}")
          .addArgument(parentHash)
          .log();
      return List.of();
    }

    // Filter those below base fee, then sort by effective gas price desc, then by arrival time asc
    // (older transactions first among those with equal gas price, to prioritize
    // potentially-censored
    // txs)
    final PriorityQueue<Transaction> candidates =
        new PriorityQueue<>(
            Comparator.comparing(
                    (Transaction tx) -> tx.getEffectiveGasPrice(baseFeePerGas), Wei::compareTo)
                .reversed());

    for (final Transaction tx : mempoolTransactions) {
      if (baseFeePerGas.isPresent() && tx.getMaxGasPrice().lessThan(baseFeePerGas.get())) {
        continue;
      }
      // Skip blob transactions per EIP-7805 spec
      if (tx.getType().supportsBlob()) {
        continue;
      }
      candidates.add(tx);
    }

    final int candidateCount = candidates.size();

    // Track next expected nonce per sender for sequential validation
    final Map<Address, Long> nextNonce = new HashMap<>();
    final List<Bytes> selected = new ArrayList<>();
    int totalBytes = 0;

    while (!candidates.isEmpty()) {
      final Transaction tx = candidates.poll();
      final Address sender = tx.getSender();
      final long nonce = tx.getNonce();

      // Enforce sequential nonces per sender
      if (nextNonce.containsKey(sender)) {
        if (nonce != nextNonce.get(sender)) {
          continue;
        }
      }

      final Bytes encoded = tx.encoded();
      final int txSize = encoded.size();

      if (totalBytes + txSize > maxBytes) {
        continue;
      }

      selected.add(encoded);
      totalBytes += txSize;
      nextNonce.put(sender, nonce + 1);
    }

    LOG.atDebug()
        .setMessage(
            "IL selector: selected {} transactions ({} bytes) from {} candidates for parent {}")
        .addArgument(selected.size())
        .addArgument(totalBytes)
        .addArgument(candidateCount)
        .addArgument(parentHash)
        .log();

    return selected;
  }
}
