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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SuppressWarnings("rawtypes")
public class SelectorsStateManager {
  private final List<Map<TransactionSelector, DuplicableState>> unconfirmedStates =
      new ArrayList<>();
  private Map<TransactionSelector, DuplicableState> confirmedState = new HashMap<>();

  public <S extends DuplicableState> void createSelectorState(
      final TransactionSelector selector, final S initialValue) {
    confirmedState.put(selector, initialValue);
  }

  public void startNewEvaluation() {
    unconfirmedStates.add(duplicateLastState());
  }

  public Map<TransactionSelector, DuplicableState> duplicateLastState() {
    return getLast().entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().deepCopy()));
  }

  @SuppressWarnings("unchecked")
  public <S extends DuplicableState> S getSelectorWorkingState(final TransactionSelector selector) {
    return (S) unconfirmedStates.getLast().get(selector);
  }

  @SuppressWarnings("unchecked")
  public <S extends DuplicableState> S getSelectorConfirmedState(
      final TransactionSelector selector) {
    return (S) confirmedState.get(selector);
  }

  /**
   * Sets the state referred by the specified tx hash has the confirmed one, allowing to forget all
   * the preceding entries.
   */
  public void commit() {
    confirmedState = getLast();
    unconfirmedStates.clear();
    unconfirmedStates.add(duplicateLastState());
  }

  /** Discards the unconfirmed states starting from the specified tx hash. */
  public void rollback() {
    unconfirmedStates.clear();
    unconfirmedStates.add(duplicateLastState());
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
    return unconfirmedStates.getLast();
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
