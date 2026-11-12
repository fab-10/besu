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
package org.hyperledger.besu.evm.internal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.annotations.VisibleForTesting;

/**
 * A cancellation check for the EVM interpreter loops, cheap enough to stay compiled into them
 * permanently.
 *
 * <p>The interpreter loops call {@link #poll()} on every {@code JUMPDEST} and on every operation
 * that goes through the generic dispatch path. The poll is one opaque-mode read of a static flag
 * and a branch: opaque mode keeps the JIT from hoisting the load out of the loops while adding no
 * memory fence, so the disarmed cost is a single predicted-untaken branch on a shared read-only
 * cache line per poll. Benchmarked against both a build without the polls and a {@code
 * MutableCallSite} variant whose disarmed poll constant-folds to nothing, this guard was
 * indistinguishable from either, within 0.1% on the stable EVM v2 workloads.
 *
 * <p>The flag is deliberately not a JIT-constant (a {@code MutableCallSite} or {@code SwitchPoint}
 * target): arming a constant deoptimizes every compiled loop at a safepoint and each arm/disarm
 * cycle recompiles them, spending the method's {@code PerMethodRecompilationCutoff} budget (default
 * 400 decompiles, after which HotSpot leaves the method interpreted for good). A node can be armed
 * an unbounded number of times over its life, so the guard is compiled in once instead: {@link
 * #arm} and disarm are plain field writes, with no deoptimization, no recompilation and no warm-up
 * ever.
 *
 * <p>Which thread stops is decided by the Java interrupt status, not by the switch: {@code arm} is
 * global, but the armed check throws {@link EvmInterruptedException} only in threads whose
 * interrupt flag is set. Callers keep signalling cancellation exactly as before, with {@code
 * Future.cancel(true)} or {@link Thread#interrupt()}; they only add an {@code arm()} call right
 * before the interrupt. Like the {@code Thread.interrupted()} checks it replaces, a poll that
 * throws clears the interrupt flag.
 *
 * <p>Only the <em>disarmed</em> poll is free: while armed, every polling EVM thread reads its own
 * interrupt status on every poll, which is a real per-JUMPDEST cost on every EVM thread in the
 * process. The armed duty cycle must therefore stay low. Callers that can observe the interrupted
 * task exiting use {@link #armScoped()} and {@link #release()}, which keep the switch armed only
 * for the actual stop latency of the kill, typically a few milliseconds. Callers that cannot
 * observe the stop use {@link #arm()}, and the switch disarms itself once the armed period, default
 * one second, has elapsed with no further arms, so those call sites need no disarm bookkeeping. A
 * scoped arm whose {@code release} never arrives is treated as stale after the default armed
 * period, so a missed release degrades to timer behavior instead of leaving the switch armed
 * forever.
 *
 * <p>A thread that never reaches a poll while the switch is armed, because it is blocked in a
 * precompile, native code or storage I/O, is not stopped by this class; its interrupt flag stays
 * set and the next interruptible blocking call it makes will throw. That matches the guarantees of
 * the tracer-based interrupt checks this class replaces.
 */
public final class EvmKillSwitch {

  /** How long the switch stays armed after an {@link #arm()} call. */
  @VisibleForTesting static final Duration DEFAULT_ARMED_PERIOD = Duration.ofSeconds(1);

  // The delay added to each disarm check, so the check scheduled by the arm call holding the
  // latest armedUntilNanos always fires after it has lapsed.
  private static final long DISARM_SLACK_NANOS = Duration.ofMillis(10).toNanos();

  // Written with volatile semantics by the rare arm/disarm transitions, read with opaque
  // semantics by the hot polls.
  private static final AtomicBoolean ARMED = new AtomicBoolean();

  private static final ScheduledExecutorService DISARM_SCHEDULER =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            final Thread thread = new Thread(runnable, "evm-kill-switch-disarm");
            thread.setDaemon(true);
            return thread;
          });

  // Guarded by the EvmKillSwitch.class lock; poll() never takes the lock.
  private static long armedUntilNanos = Long.MIN_VALUE;

  // Scoped arms not yet released: interrupted tasks whose exit has not been observed yet.
  // Guarded by the EvmKillSwitch.class lock.
  private static int pendingKills;

  // When every current pending kill is considered stale: the safety net for a missed release.
  // Guarded by the EvmKillSwitch.class lock.
  private static long pendingKillsStaleNanos = Long.MIN_VALUE;

  private EvmKillSwitch() {}

  /**
   * The cancellation poll the interpreter loops call. One opaque read and a branch while the switch
   * is disarmed; while armed it also reads the calling thread's interrupt status and throws {@link
   * EvmInterruptedException}, clearing the status, if the thread was interrupted.
   */
  public static void poll() {
    if (ARMED.getOpaque()) {
      check();
    }
  }

  private static void check() {
    if (Thread.interrupted()) {
      throw new EvmInterruptedException("EVM execution cancelled by thread interrupt");
    }
  }

  /**
   * Arm the switch for the default period. Call right before interrupting a thread that may be
   * executing EVM code, for example before {@code Future.cancel(true)} on a transaction-processing
   * task.
   */
  public static void arm() {
    arm(DEFAULT_ARMED_PERIOD);
  }

  /**
   * Arm the switch, keeping it armed for at least the given period. Arming is a field write: arming
   * an already armed switch only extends the period, and any number of arm/disarm cycles is fine.
   *
   * <p>Prefer {@link #armScoped()} when the caller can observe the interrupted task exiting: a
   * timer arm keeps every EVM thread paying the armed poll cost for the whole period, orders of
   * magnitude longer than the stop itself.
   *
   * @param armedPeriod how long the switch must stay armed; use at least the time the caller is
   *     prepared to wait for the interrupted thread to stop
   */
  public static synchronized void arm(final Duration armedPeriod) {
    final long until = System.nanoTime() + armedPeriod.toNanos();
    if (until > armedUntilNanos) {
      armedUntilNanos = until;
    }
    ARMED.set(true);
    // Every arm schedules its own disarm check just after its period lapses. A check that fires
    // while a later arm keeps the switch armed is a no-op: that arm's own check is still pending.
    scheduleDisarmCheck(armedPeriod);
  }

  /**
   * Arm the switch until the matching {@link #release()}. Call right before interrupting a thread
   * whose exit the caller can observe; call {@code release} exactly once when the interrupted task
   * has stopped (or finished on its own). The switch stays armed only while kills are in flight,
   * typically a few milliseconds, instead of a fixed period.
   *
   * <p>If a release is missed, the pending kill is treated as stale after the default armed period
   * and the switch disarms anyway, so a pairing bug costs at most timer-arm behavior.
   */
  public static synchronized void armScoped() {
    pendingKills++;
    pendingKillsStaleNanos = System.nanoTime() + DEFAULT_ARMED_PERIOD.toNanos();
    ARMED.set(true);
    // The safety-net check, in case the release never arrives.
    scheduleDisarmCheck(DEFAULT_ARMED_PERIOD);
  }

  /**
   * Release one {@link #armScoped()} arm, because its interrupted task has been observed to exit.
   * Disarms the switch immediately when no other arm, scoped or timed, is keeping it armed.
   */
  public static synchronized void release() {
    if (pendingKills > 0) {
      pendingKills--;
    }
    maybeDisarm();
  }

  private static void scheduleDisarmCheck(final Duration delay) {
    DISARM_SCHEDULER.schedule(
        EvmKillSwitch::maybeDisarmScheduled,
        delay.toNanos() + DISARM_SLACK_NANOS,
        TimeUnit.NANOSECONDS);
  }

  private static synchronized void maybeDisarmScheduled() {
    maybeDisarm();
  }

  // Caller must hold the EvmKillSwitch.class lock.
  private static void maybeDisarm() {
    final long now = System.nanoTime();
    if (now < armedUntilNanos) {
      return;
    }
    if (pendingKills > 0 && now < pendingKillsStaleNanos) {
      return;
    }
    ARMED.set(false);
  }

  @VisibleForTesting
  static boolean isArmed() {
    return ARMED.get();
  }

  @VisibleForTesting
  static synchronized void disarmForTesting() {
    armedUntilNanos = Long.MIN_VALUE;
    pendingKills = 0;
    pendingKillsStaleNanos = Long.MIN_VALUE;
    ARMED.set(false);
  }
}
