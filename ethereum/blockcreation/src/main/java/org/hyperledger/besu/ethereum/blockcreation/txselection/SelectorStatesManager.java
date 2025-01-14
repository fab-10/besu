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
package org.hyperledger.besu.ethereum.blockcreation.txselection;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.blockcreation.txselection.selectors.AbstractTransactionSelector;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

public class SelectorStatesManager {
  private final SequencedMap<Hash, Map<AbstractTransactionSelector, Object>> selectorStates =
      new LinkedHashMap<>();
  private Map<AbstractTransactionSelector, Object> initialState = new HashMap<>();
  private Map<AbstractTransactionSelector, Object> workingState;

  public <S> void createSelectorState(
      final AbstractTransactionSelector selector, final S initialValue) {
    initialState.put(selector, initialValue);
  }

  public void startNewEvaluation(final TransactionEvaluationContext evaluationContext) {
    workingState = new HashMap<>(getLast());
    selectorStates.putLast(evaluationContext.getTransaction().getHash(), workingState);
  }

  public <S> void updateSelectorState(
      final AbstractTransactionSelector selector, final S newValue) {
    workingState.put(selector, newValue);
  }

  @SuppressWarnings("unchecked")
  public <S> S getSelectorState(final AbstractTransactionSelector selector) {
    return (S) workingState.get(selector);
  }

  //  public void endEvaluation(final TransactionEvaluationContext evaluationContext) {
  //    pendingState.putLast(evaluationContext.getTransaction().getHash(), workingState);
  //  }
  //
  //
  //  /**
  //   * Append the unconfirmed state related to the passed tx hash to the pending list. The
  // appended
  //   * value remains pending, meaning it could be discarded, until {@link
  // SelectionStateManager#confirm(Hash)}
  //   * is called for the same tx hash or a following one.
  //   *
  //   * @param txHash Hash of the transaction
  //   * @param unconfirmedState The state to set as the last unconfirmed
  //   */
  //  public void appendUnconfirmed(final Hash txHash, final T unconfirmedState) {
  //    pendingState.putLast(txHash, unconfirmedState);
  //  }

  /**
   * Sets the state referred by the specified tx hash has the confirmed one, allowing to forget all
   * the preceding entries.
   *
   * @param txHash the tx hash, could not be present, in which case there is no change to the
   *     confirmed state
   */
  public void confirm(final Hash txHash) {

    final var it = selectorStates.entrySet().iterator();
    while (it.hasNext()) {
      final var entry = it.next();
      it.remove();
      if (entry.getKey().equals(txHash)) {
        initialState = entry.getValue();
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
    final var it = selectorStates.entrySet().iterator();
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
  public Map<AbstractTransactionSelector, Object> getLast() {
    if (selectorStates.isEmpty()) {
      return initialState;
    }
    return selectorStates.lastEntry().getValue();
  }
}
