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
import org.hyperledger.besu.plugin.services.BesuService;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

/** Block transaction selection service interface */
@Unstable
public interface BlockTransactionSelectionService extends BesuService {

  /**
   * Create a transaction selector plugin
   *
   * @return the transaction selector plugin
   */
  TransactionSelector createPluginTransactionSelector();

  BlockAwareOperationTracer createBlockAwareOperationTracer();

  BlockTransactionSelector createBlockTransactionSelector();

  /**
   * Registers the block transaction selector factory with the service
   *
   * @param blockTransactionSelectorFactory block transaction selector factory to be used
   */
  void registerPluginTransactionSelectorFactory(
      BlockTransactionSelectorFactory blockTransactionSelectorFactory);
}
