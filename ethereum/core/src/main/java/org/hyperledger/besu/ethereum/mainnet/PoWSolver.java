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

import org.hyperledger.besu.ethereum.chain.PoWObserver;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.util.Subscribers;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.concurrent.ExpiringMap;
import org.apache.tuweni.units.bigints.UInt256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PoWSolver {

  private static final Logger LOG = LoggerFactory.getLogger(PoWSolver.class);

  private final MiningConfiguration miningConfiguration;

  public static class PoWSolverJob {

    private final PoWSolverInputs inputs;
    private final CompletableFuture<PoWSolution> nonceFuture;

    PoWSolverJob(final PoWSolverInputs inputs, final CompletableFuture<PoWSolution> nonceFuture) {
      this.inputs = inputs;
      this.nonceFuture = nonceFuture;
    }

    public static PoWSolverJob createFromInputs(final PoWSolverInputs inputs) {
      return new PoWSolverJob(inputs, new CompletableFuture<>());
    }

    PoWSolverInputs getInputs() {
      return inputs;
    }

    public boolean isDone() {
      return nonceFuture.isDone();
    }

    void solvedWith(final PoWSolution solution) {
      nonceFuture.complete(solution);
    }

    public void cancel() {
      nonceFuture.cancel(false);
    }

    public void failed(final Throwable ex) {
      nonceFuture.completeExceptionally(ex);
    }

    PoWSolution getSolution() throws InterruptedException, ExecutionException {
      return nonceFuture.get();
    }
  }

  private final long NO_MINING_CONDUCTED = -1;

  private final PoWHasher poWHasher;
  private volatile long hashesPerSecond = NO_MINING_CONDUCTED;
  private final EpochCalculator epochCalculator;
  private volatile Optional<PoWSolverJob> currentJob = Optional.empty();
  private final ExpiringMap<Bytes, PoWSolverJob> currentJobs = new ExpiringMap<>();

  public PoWSolver(
      final MiningConfiguration miningConfiguration,
      final PoWHasher poWHasher,
      final Subscribers<PoWObserver> ethHashObservers,
      final EpochCalculator epochCalculator) {
    this.miningConfiguration = miningConfiguration;
    this.poWHasher = poWHasher;
    ethHashObservers.forEach(observer -> observer.setSubmitWorkCallback(this::submitSolution));
    this.epochCalculator = epochCalculator;
  }

  public PoWSolution solveFor(final PoWSolverJob job)
      throws InterruptedException, ExecutionException {
    currentJob = Optional.of(job);
    currentJobs.put(
        job.getInputs().getPrePowHash(),
        job,
        System.currentTimeMillis() + miningConfiguration.getUnstable().getPowJobTimeToLive());
    LOG.debug("solving with cpu miner");
    findValidNonce();
    return job.getSolution();
  }

  private void findValidNonce() {
    final Stopwatch operationTimer = Stopwatch.createStarted();
    final PoWSolverJob job = currentJob.get();
    long hashesExecuted = 0;
    for (final Long n : miningConfiguration.getNonceGenerator().get()) {

      if (job.isDone()) {
        return;
      }

      final Optional<PoWSolution> solution = testNonce(job.getInputs(), n);
      solution.ifPresent(job::solvedWith);

      hashesExecuted++;
      final double operationDurationSeconds = operationTimer.elapsed(TimeUnit.NANOSECONDS) / 1e9;
      hashesPerSecond = (long) (hashesExecuted / operationDurationSeconds);
    }
    job.failed(new IllegalStateException("No valid nonce found."));
  }

  private Optional<PoWSolution> testNonce(final PoWSolverInputs inputs, final long nonce) {
    return Optional.ofNullable(
            poWHasher.hash(nonce, inputs.getBlockNumber(), epochCalculator, inputs.getPrePowHash()))
        .filter(sol -> UInt256.fromBytes(sol.getSolution()).compareTo(inputs.getTarget()) <= 0);
  }

  public void cancel() {
    currentJob.ifPresent(PoWSolverJob::cancel);
  }

  public Optional<PoWSolverInputs> getWorkDefinition() {
    return currentJob.flatMap(job -> Optional.of(job.getInputs()));
  }

  public Optional<Long> hashesPerSecond() {
    if (hashesPerSecond == NO_MINING_CONDUCTED) {
      return Optional.empty();
    }
    return Optional.of(hashesPerSecond);
  }

  public boolean submitSolution(final PoWSolution solution) {
    final Optional<PoWSolverJob> jobSnapshot = currentJob;
    PoWSolverJob jobToTestWith = null;
    if (jobSnapshot.isEmpty()) {
      LOG.debug("No current job, rejecting miner work");
      return false;
    }

    PoWSolverJob headJob = jobSnapshot.get();
    if (headJob.getInputs().getPrePowHash().equals(solution.getPowHash())) {
      LOG.debug("Head job matches the solution pow hash {}", solution.getPowHash());
      jobToTestWith = headJob;
    }
    if (jobToTestWith == null) {
      PoWSolverJob ommerCandidate = currentJobs.get(solution.getPowHash());
      if (ommerCandidate != null) {
        long distanceToHead =
            headJob.getInputs().getBlockNumber() - ommerCandidate.getInputs().getBlockNumber();
        LOG.debug(
            "Found ommer candidate {} with block number {}, distance to head {}",
            solution.getPowHash(),
            ommerCandidate.getInputs().getBlockNumber(),
            distanceToHead);
        if (distanceToHead <= miningConfiguration.getUnstable().getMaxOmmerDepth()) {
          jobToTestWith = ommerCandidate;
        } else {
          LOG.debug("Discarded ommer solution as too far from head {}", distanceToHead);
        }
      }
    }
    if (jobToTestWith == null) {
      LOG.debug("No matching job found for hash {}, rejecting solution", solution.getPowHash());
      return false;
    }
    if (jobToTestWith.isDone()) {
      LOG.debug("Matching job found for hash {}, but already solved", solution.getPowHash());
      return false;
    }
    final PoWSolverInputs inputs = jobToTestWith.getInputs();

    final Optional<PoWSolution> calculatedSolution = testNonce(inputs, solution.getNonce());

    if (calculatedSolution.isPresent()) {
      LOG.debug("Accepting a solution from a miner");
      currentJobs.remove(solution.getPowHash());
      jobToTestWith.solvedWith(calculatedSolution.get());
      return true;
    }
    LOG.debug("Rejecting a solution from a miner");
    return false;
  }

  public Iterable<Long> getNonceGenerator() {
    return miningConfiguration.getNonceGenerator().get();
  }
}
