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
package org.hyperledger.besu.ethereum.blockcreation.txselection.selectors;

import org.hyperledger.besu.ethereum.blockcreation.txselection.BlockSelectionContext;
import org.hyperledger.besu.ethereum.blockcreation.txselection.SelectorStatesManager;

/**
 * This class represents an abstract transaction selector which provides methods to evaluate
 * transactions.
 */
public abstract class AbstractStatefulTransactionSelector<S> extends AbstractTransactionSelector {
  private final SelectorStatesManager selectorStatesManager;

  public AbstractStatefulTransactionSelector(
      final BlockSelectionContext context,
      final SelectorStatesManager selectorStatesManager,
      final S initialState) {
    super(context);
    this.selectorStatesManager = selectorStatesManager;
    selectorStatesManager.createSelectorState(this, initialState);
  }

  protected void updateState(final S newState) {
    selectorStatesManager.updateSelectorState(this, newState);
  }

  protected S getState() {
    return selectorStatesManager.getSelectorState(this);
  }
}
