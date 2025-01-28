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
package org.hyperledger.besu.plugin.services.txselection;

import org.hyperledger.besu.plugin.services.txselection.SelectorsStateManager.DuplicableState;

/**
 * This class represents an abstract plugin transaction selector which provides manage the selector
 * state.
 *
 * @param <S> The type of the state used by the selector
 */
public abstract class AbstractPluginTransactionSelector<S extends DuplicableState<?>>
    implements PluginTransactionSelector {
  private final SelectorsStateManager selectorsStateManager;

  /**
   * Initialize the plugin state to an initial value
   *
   * @param selectorsStateManager the selectors state manager
   * @param initialState the initial value of the state
   */
  public AbstractPluginTransactionSelector(
      final SelectorsStateManager selectorsStateManager, final S initialState) {
    this.selectorsStateManager = selectorsStateManager;
    selectorsStateManager.createSelectorState(this, initialState);
  }

  /**
   * Get the working state for this selector. A working state contains changes that have not yet
   * commited
   *
   * @return the working state of this selector
   */
  protected S getWorkingState() {
    return selectorsStateManager.getSelectorWorkingState(this);
  }

  /**
   * Get the commited state for this selector. A commited state contains changes that have been
   * commited
   *
   * @return the commited state of this selector
   */
  protected S getCommitedState() {
    return selectorsStateManager.getSelectorCommittedState(this);
  }
}
