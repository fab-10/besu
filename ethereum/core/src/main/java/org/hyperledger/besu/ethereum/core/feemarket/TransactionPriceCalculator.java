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
import org.hyperledger.besu.ethereum.mainnet.feemarket.LondonFeeMarket;
import org.hyperledger.besu.util.Slf4jLambdaHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;

import static org.hyperledger.besu.util.Slf4jLambdaHelper.traceLambda;

public interface TransactionPriceCalculator {
  Logger LOG = LoggerFactory.getLogger(TransactionPriceCalculator.class);
  BigInteger MIN_DATA_GAS_PRICE = BigInteger.ONE;
  BigInteger DATA_GAS_PRICE_UPDATE_FRACTION = BigInteger.valueOf(2225652L);

  Wei price(Transaction transaction, ProcessableBlockHeader blockHeader);

  default Wei dataPrice(final Transaction transaction, final ProcessableBlockHeader blockHeader) {
    return Wei.ZERO;
  }

  class Frontier implements TransactionPriceCalculator {
      @Override
      public Wei price(final Transaction transaction, final ProcessableBlockHeader blockHeader) {
        return transaction.getGasPrice().orElse(Wei.ZERO);
      }

      @Override
      public Wei dataPrice(
          final Transaction transaction, final ProcessableBlockHeader blockHeader) {
        return Wei.ZERO;
      }
    }


  class EIP1559 implements TransactionPriceCalculator {
      @Override
      public Wei price(Transaction transaction, ProcessableBlockHeader blockHeader) {
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
      }
    }


  class EIP4844 extends EIP1559 {
    private static final Logger LOG = LoggerFactory.getLogger(EIP4844.class);
      @Override
      public Wei dataPrice(Transaction transaction, ProcessableBlockHeader blockHeader) {
        final var excessDataGas = blockHeader.getExcessDataGas().orElseThrow();

        final var dataGasPrice =
                Wei.of(
                        fakeExponential(
                                MIN_DATA_GAS_PRICE,
                                excessDataGas.toBigInteger(),
                                DATA_GAS_PRICE_UPDATE_FRACTION));
        traceLambda(LOG,
                "block #{} parentExcessDataGas: {} dataGasPrice: {}",
                blockHeader::getNumber,
                excessDataGas::toShortHexString,
                dataGasPrice::toHexString);

        return dataGasPrice;
      }

      private BigInteger fakeExponential(
              final BigInteger factor, final BigInteger numerator, final BigInteger denominator) {
        BigInteger i = BigInteger.ONE;
        BigInteger output = BigInteger.ZERO;
        BigInteger numeratorAccumulator = factor.multiply(denominator);
        while (numeratorAccumulator.compareTo(BigInteger.ZERO) > 0) {
          output = output.add(numeratorAccumulator);
          numeratorAccumulator =
                  (numeratorAccumulator.multiply(numerator)).divide(denominator.multiply(i));
          i.add(BigInteger.ONE);
        }
        return output.divide(denominator);
      }
  }

}
