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

/**
 * This class represents an abstract plugin transaction selector which provides manage the selector
 * state.
 */
public abstract class AbstractPluginTransactionSelector<S> implements PluginTransactionSelector {
  private final SelectorsStateManager selectorsStateManager;

  public AbstractPluginTransactionSelector(
      final SelectorsStateManager selectorsStateManager, final S initialState) {
    this.selectorsStateManager = selectorsStateManager;
    selectorsStateManager.createSelectorState(this, initialState);
  }

  protected void updateState(final S newState) {
    selectorsStateManager.updateSelectorState(this, newState);
  }

  protected S getState() {
    return selectorsStateManager.getSelectorState(this);
  }

  protected S getConfirmedState() {
    return selectorsStateManager.getSelectorConfirmedState(this);
  }
}
