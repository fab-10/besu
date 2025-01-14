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
import org.hyperledger.besu.ethereum.blockcreation.txselection.TransactionEvaluationContext;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;

/**
 * This class represents an abstract transaction selector which provides methods to evaluate
 * transactions.
 */
public abstract class AbstractStatefulTransactionSelector<T> extends AbstractTransactionSelector {
  protected final SelectorState<T> selectorState;

  public AbstractStatefulTransactionSelector(
      final BlockSelectionContext context, final T initialState) {
    super(context);
    selectorState = new SelectorState<>(initialState);
  }

  /**
   * Method called when a transaction is selected to be added to a block.
   *
   * @param evaluationContext The current selection context
   * @param processingResult The result of processing the selected transaction.
   */
  @Override
  public void onTransactionSelected(
      final TransactionEvaluationContext evaluationContext,
      final TransactionProcessingResult processingResult) {
    selectorState.confirm(evaluationContext.getPendingTransaction().getTransaction().getHash());
  }

  /**
   * Method called when a transaction is not selected to be added to a block.
   *
   * @param evaluationContext The current selection context
   * @param transactionSelectionResult The transaction selection result
   */
  @Override
  public void onTransactionNotSelected(
      final TransactionEvaluationContext evaluationContext,
      final TransactionSelectionResult transactionSelectionResult) {
    selectorState.discard(evaluationContext.getPendingTransaction().getTransaction().getHash());
  }
}
