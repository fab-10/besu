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
package org.hyperledger.besu.plugin.services.txselection;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.PendingTransaction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.Collectors;

@SuppressWarnings("rawtypes")
public class SelectorsStateManager {
  private final SequencedMap<Hash, Map<TransactionSelector, DuplicableState>> unconfirmedStates =
      new LinkedHashMap<>();
  private Map<TransactionSelector, DuplicableState> confirmedState = new HashMap<>();
  private Map<TransactionSelector, DuplicableState> workingState;

  public <S extends DuplicableState> void createSelectorState(
      final TransactionSelector selector, final S initialValue) {
    confirmedState.put(selector, initialValue);
  }

  public void startNewEvaluation(
      final TransactionEvaluationContext<? extends PendingTransaction> evaluationContext) {
    workingState =
        getLast().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().deepCopy()));
    unconfirmedStates.putLast(
        evaluationContext.getPendingTransaction().getTransaction().getHash(), workingState);
  }

  @SuppressWarnings("unchecked")
  public <S extends DuplicableState> S getSelectorWorkingState(final TransactionSelector selector) {
    return (S) workingState.get(selector);
  }

  @SuppressWarnings("unchecked")
  public <S extends DuplicableState> S getSelectorConfirmedState(
      final TransactionSelector selector) {
    return (S) confirmedState.get(selector);
  }

  /**
   * Sets the state referred by the specified tx hash has the confirmed one, allowing to forget all
   * the preceding entries.
   *
   * @param txHash the tx hash, could not be present, in which case there is no change to the
   *     confirmed state
   */
  public void confirm(final Hash txHash) {
    final var it = unconfirmedStates.entrySet().iterator();
    while (it.hasNext()) {
      final var entry = it.next();
      it.remove();
      if (entry.getKey().equals(txHash)) {
        confirmedState = entry.getValue();
        break;
      }
    }
  }

  /**
   * Discards the unconfirmed states starting from the specified tx hash.
   *
   * @param txHash the tx hash, could not be present, in which case there is no change to the
   *     pending state
   */
  public void discard(final Hash txHash) {
    boolean afterRemoved = false;
    final var it = unconfirmedStates.entrySet().iterator();
    while (it.hasNext()) {
      final var entry = it.next();
      if (afterRemoved || entry.getKey().equals(txHash)) {
        it.remove();
        afterRemoved = true;
      }
    }
  }

  /**
   * Gets the latest, including unconfirmed, state. Note that the returned values could not yet be
   * confirmed and could be discarded in the future.
   *
   * @return a map with the line count per module
   */
  private Map<TransactionSelector, DuplicableState> getLast() {
    if (unconfirmedStates.isEmpty()) {
      return confirmedState;
    }
    return unconfirmedStates.lastEntry().getValue();
  }

  public abstract static class DuplicableState<V> {
    private V value;

    public DuplicableState(final V value) {
      this.value = value;
    }

    protected abstract DuplicableState<V> deepCopy();

    public V getValue() {
      return value;
    }

    public void setValue(final V value) {
      this.value = value;
    }
  }

  public static class DuplicableLongState extends DuplicableState<Long> {

    public DuplicableLongState(final Long value) {
      super(value);
    }

    @Override
    public DuplicableLongState deepCopy() {
      return new DuplicableLongState(getValue());
    }
  }
}
