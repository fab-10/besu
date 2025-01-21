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
package org.hyperledger.besu.services;

import org.hyperledger.besu.plugin.services.BlockTransactionSelectionService;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;
import org.hyperledger.besu.plugin.services.txselection.TransactionSelector;
import org.hyperledger.besu.plugin.services.txselection.BlockTransactionSelectorFactory;

import java.util.Optional;

/** The Transaction Selection service implementation. */
public class BlockTransactionSelectionServiceImpl implements BlockTransactionSelectionService {

  /** Default Constructor. */
  public BlockTransactionSelectionServiceImpl() {}

  // ToDo: set the default implementation here
  private Optional<BlockTransactionSelectorFactory> factory = Optional.empty();

  @Override
  public TransactionSelector createPluginTransactionSelector() {
    return factory
        .map(BlockTransactionSelectorFactory::createTransactionSelector)
        .orElse(TransactionSelector.ACCEPT_ALL);
  }

  @Override
  public BlockAwareOperationTracer createBlockAwareOperationTracer() {
    return factory.map(BlockTransactionSelectorFactory::createOperationTracer)
        .orElse(BlockAwareOperationTracer.NO_TRACING);
  }

  @Override
  public void registerPluginTransactionSelectorFactory(
      final BlockTransactionSelectorFactory blockTransactionSelectorFactory) {
    factory = Optional.ofNullable(blockTransactionSelectorFactory);
  }
}
