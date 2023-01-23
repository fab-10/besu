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
package org.hyperledger.besu.ethereum.mainnet.feemarket;

import org.hyperledger.besu.datatypes.DataGas;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.util.Slf4jLambdaHelper;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CancunFeeMarket extends LondonFeeMarket implements DataFeeMarket {
  private static final Logger LOG = LoggerFactory.getLogger(CancunFeeMarket.class);

  private final int dataGasPerBlob;
  private final DataGas targetDataGasPerBlock;

  public CancunFeeMarket(
      final long londonForkBlockNumber,
      final Optional<Wei> baseFeePerGasOverride,
      final int dataGasPerBlob,
      final int targetDataGasPerBlock) {
    super(londonForkBlockNumber, baseFeePerGasOverride);
    this.dataGasPerBlob = dataGasPerBlob;
    this.targetDataGasPerBlock = DataGas.of(targetDataGasPerBlock);
  }

  @Override
  public boolean implementsDataFee() {
    return true;
  }

  /**
   * Compute the new excess data gas for the block, using the parent value and the number of new
   * blobs
   *
   * @param blockNumber the number of the new block
   * @param parentExcessDataGas the excess data gas value from the parent block
   * @param newBlobs the number of blobs in the new block
   * @return the new excess data gas value
   */
  @Override
  public DataGas computeExcessDataGas(
      final long blockNumber, final DataGas parentExcessDataGas, final int newBlobs) {
    final int consumedDataGas = newBlobs * dataGasPerBlob;
    final DataGas currentExcessDataGas = parentExcessDataGas.add(consumedDataGas);
    Slf4jLambdaHelper.debugLambda(
        LOG,
        "Target data gas per block {}, current excess data gas {}, parent excess data gas {}, new blobs {}",
        targetDataGasPerBlock::toShortHexString,
        currentExcessDataGas::toShortHexString,
        parentExcessDataGas::toShortHexString,
        () -> newBlobs);
    if (currentExcessDataGas.lessThan(targetDataGasPerBlock)) {
      return DataGas.ZERO;
    }
    return currentExcessDataGas.add(targetDataGasPerBlock);
  }
}
