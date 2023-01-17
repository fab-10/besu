/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.core.feemarket;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;

@FunctionalInterface
public interface TransactionPriceCalculator {
  Wei price(Transaction transaction, ProcessableBlockHeader blockHeader);

  static TransactionPriceCalculator frontier() {
    return (transaction, blockHeader) -> transaction.getGasPrice().orElse(Wei.ZERO);
  }

  static TransactionPriceCalculator eip1559() {
    return (transaction, blockHeader) -> {
      final Wei baseFee = blockHeader.getBaseFee().orElseThrow();
      if (!transaction.getType().supports1559FeeMarket()) {
        return transaction.getGasPrice().orElse(Wei.ZERO);
      }
      final Wei maxPriorityFeePerGas = transaction.getMaxPriorityFeePerGas().orElseThrow();
      final Wei maxFeePerGas = transaction.getMaxFeePerGas().orElseThrow();
      Wei price = maxPriorityFeePerGas.add(baseFee);
      if (price.compareTo(maxFeePerGas) > 0) {
        price = maxFeePerGas;
      }
      return price;
    };
  }
}
