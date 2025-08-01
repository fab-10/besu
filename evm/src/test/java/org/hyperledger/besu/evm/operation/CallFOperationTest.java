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
package org.hyperledger.besu.evm.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.evm.testutils.OperationsTestUtils.mockCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.code.CodeSection;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.ReturnStack;
import org.hyperledger.besu.evm.testutils.TestMessageFrameBuilder;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class CallFOperationTest {

  @Test
  void callFHappyPath() {
    final GasCalculator gasCalculator = mock(GasCalculator.class);
    final Code mockCode = mockCode("00" + "b0" + "0001" + "00");

    final CodeSection codeSection = new CodeSection(() -> 0, 1, 2, 3, 0);
    when(mockCode.getCodeSection(1)).thenReturn(codeSection);

    MessageFrame messageFrame =
        new TestMessageFrameBuilder()
            .code(mockCode)
            .pc(1)
            .initialGas(10L)
            .pushStackItem(Bytes.EMPTY)
            .pushStackItem(Bytes.EMPTY)
            .build();

    CallFOperation callF = new CallFOperation(gasCalculator);
    Operation.OperationResult callfResult = callF.execute(messageFrame, null);

    assertThat(callfResult.getHaltReason()).isNull();
    assertThat(callfResult.getPcIncrement()).isEqualTo(1);
    assertThat(messageFrame.getSection()).isEqualTo(1);
    assertThat(messageFrame.getPC()).isEqualTo(-1);
    assertThat(messageFrame.returnStackSize()).isEqualTo(1);
    assertThat(messageFrame.peekReturnStack()).isEqualTo(new ReturnStack.ReturnStackItem(0, 3));
  }
}
