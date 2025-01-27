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

import org.hyperledger.besu.plugin.Unstable;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

/** Interface for a factory that creates transaction selectors */
@Unstable
public interface PluginTransactionSelectorFactory {
  PluginTransactionSelectorFactory NO_OP_FACTORY = new PluginTransactionSelectorFactory() {};

  /**
   * Create a transaction selector
   *
   * @return the transaction selector
   */
  default PluginTransactionSelector create(final SelectorsStateManager selectorsStateManager) {
    return PluginTransactionSelector.ACCEPT_ALL;
  }

  /**
   * Method that returns an OperationTracer that will be used when executing transactions that are
   * candidates to be added to a block.
   *
   * @return OperationTracer to be used to trace candidate transactions
   */
  default BlockAwareOperationTracer createOperationTracer() {
    return BlockAwareOperationTracer.NO_TRACING;
  }

  default void selectPendingTransactions(
      final BlockTransactionSelectionService blockTransactionSelectionService) {}
}
