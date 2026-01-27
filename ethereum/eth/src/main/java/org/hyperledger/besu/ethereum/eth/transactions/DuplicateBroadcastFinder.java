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
package org.hyperledger.besu.ethereum.eth.transactions;

import org.hyperledger.besu.datatypes.Hash;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DuplicateBroadcastFinder {
  private static final Logger LOG = LoggerFactory.getLogger(DuplicateBroadcastFinder.class);
  private static final Cache<Hash, Instant> cache =
      Caffeine.newBuilder().maximumSize(1_000_000).expireAfterAccess(Duration.ofMinutes(2)).build();
  private static final ExecutorService executor = Executors.newSingleThreadExecutor();
  private static final ExecutorService asyncLog = Executors.newSingleThreadExecutor();

  public static void getPooledTransactionsFromPeer(final List<Hash> hashes) {
    executor.submit(
        () -> {
          final var alreadyRequested = cache.getAllPresent(hashes);
          if (!alreadyRequested.isEmpty()) {
            asyncLog.submit(
                () -> {
                  final var now = Instant.now();
                  LOG.info(
                      "getPooledTransactionsFromPeer already requested hashes count={}",
                      alreadyRequested.size());
                  alreadyRequested.forEach(
                      (hash, instant) ->
                          LOG.atDebug()
                              .setMessage(
                                  "getPooledTransactionsFromPeer already requested hash={}, first request {} ago")
                              .addArgument(hash)
                              .addArgument(() -> Duration.between(instant, now))
                              .log());
                });
          }
          final var now = Instant.now();
          hashes.stream()
              .filter(hash -> !alreadyRequested.containsKey(hash))
              .forEach(hash -> cache.put(hash, now));
        });
  }
}
