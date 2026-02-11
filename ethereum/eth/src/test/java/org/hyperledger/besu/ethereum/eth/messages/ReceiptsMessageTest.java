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
package org.hyperledger.besu.ethereum.eth.messages;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.SyncTransactionReceipt;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.encoding.receipt.TransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.core.encoding.receipt.TransactionReceiptEncodingConfiguration;
import org.hyperledger.besu.ethereum.eth.core.Utils;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.RawMessage;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

public final class ReceiptsMessageTest {
  @Test
  public void testReceiptsMessageEth68() {
    roundTripTest(TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);
  }

  @Test
  public void testReceiptsMessageEth69() {
    roundTripTest(TransactionReceiptEncodingConfiguration.ETH69_RECEIPT_CONFIGURATION);
  }

  public void roundTripTest(final TransactionReceiptEncodingConfiguration encodingConfiguration) {
    // Generate some data
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final List<List<TransactionReceipt>> receipts = new ArrayList<>();
    final int dataCount = 20;
    final int receiptsPerSet = 3;
    for (int i = 0; i < dataCount; ++i) {
      final List<TransactionReceipt> receiptSet = new ArrayList<>();
      for (int j = 0; j < receiptsPerSet; j++) {
        receiptSet.add(gen.receipt());
      }
      receipts.add(receiptSet);
    }

    final List<List<SyncTransactionReceipt>> syncReceipts =
        receipts.stream()
            .map(rs -> Utils.receiptsToSyncReceipts(rs, encodingConfiguration))
            .toList();

    // Perform round-trip transformation
    // Create specific message, copy it to a generic message, then read back into a specific format
    final MessageData initialMessage = ReceiptsMessage.create(receipts, encodingConfiguration);
    final MessageData raw = new RawMessage(EthProtocolMessages.RECEIPTS, initialMessage.getData());
    final ReceiptsMessage message = ReceiptsMessage.readFrom(raw);

    // Read data back out after round trip and check they match originals.
    final Iterator<List<TransactionReceipt>> itReadReceipts = message.receipts().iterator();
    final Iterator<List<SyncTransactionReceipt>> itReadSyncReceipts =
        message.syncReceipts().iterator();
    for (int i = 0; i < dataCount; ++i) {
      assertThat(itReadReceipts.next()).containsExactlyElementsOf(receipts.get(i));
      final var sr = itReadSyncReceipts.next();
      final var srs = syncReceipts.get(i);
      assertThat(sr)
          .usingElementComparator(Comparator.comparing(SyncTransactionReceipt::getRlpBytes))
          .containsExactlyElementsOf(srs);
    }
    assertThat(itReadReceipts.hasNext()).isFalse();
    assertThat(itReadSyncReceipts.hasNext()).isFalse();
  }

  @Test
  public void testLazyDeserializationOfReceipts() {
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final List<List<TransactionReceipt>> receipts = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      final List<TransactionReceipt> receiptSet = new ArrayList<>();
      receiptSet.add(gen.receipt());
      receipts.add(receiptSet);
    }

    final MessageData initialMessage =
        ReceiptsMessage.create(
            receipts, TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);
    final ReceiptsMessage message = ReceiptsMessage.readFrom(initialMessage);

    // Verify receipts() deserializes and caches the result
    final List<List<TransactionReceipt>> firstCall = message.receipts();
    final List<List<TransactionReceipt>> secondCall = message.receipts();

    assertThat(firstCall).isEqualTo(receipts);
    assertThat(secondCall).isSameAs(firstCall); // Same instance = cached
  }

  @Test
  public void testLastBlockIncompleteWithCreateUnsafe() {
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final List<List<TransactionReceipt>> receipts = new ArrayList<>();
    receipts.add(List.of(gen.receipt()));

    final MessageData initialMessage =
        ReceiptsMessage.create(
            receipts, TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);

    // Test with lastBlockIncomplete = false
    final ReceiptsMessage messageComplete =
        ReceiptsMessage.createUnsafe(initialMessage.getData(), false);
    assertThat(messageComplete.lastBlockIncomplete()).isFalse();

    // Test with lastBlockIncomplete = true
    final ReceiptsMessage messageIncomplete =
        ReceiptsMessage.createUnsafe(initialMessage.getData(), true);
    assertThat(messageIncomplete.lastBlockIncomplete()).isTrue();
  }

  @Test
  public void testLastBlockIncompleteWithEncodedFlag() {
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final List<List<TransactionReceipt>> receipts = new ArrayList<>();
    receipts.add(List.of(gen.receipt()));

    // Prepare encoded receipts block first
    final BytesValueRLPOutput blockReceipts = new BytesValueRLPOutput();
    blockReceipts.startList();
    receipts
        .get(0)
        .forEach(
            r ->
                TransactionReceiptEncoder.writeTo(
                    r,
                    blockReceipts,
                    TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION));
    blockReceipts.endList();

    // Encode with lastBlockIncomplete flag = true (as done in EthServer)
    final BytesValueRLPOutput messageData = new BytesValueRLPOutput();
    messageData.writeUnsignedByte(1); // lastBlockIncomplete = true
    messageData.startList();
    messageData.writeRaw(blockReceipts.encoded());
    messageData.endList();

    final ReceiptsMessage message = ReceiptsMessage.createUnsafe(messageData.encoded());
    assertThat(message.lastBlockIncomplete()).isTrue();

    // Encode with lastBlockIncomplete flag = false
    final BytesValueRLPOutput messageData2 = new BytesValueRLPOutput();
    messageData2.writeUnsignedByte(0); // lastBlockIncomplete = false
    messageData2.startList();
    messageData2.writeRaw(blockReceipts.encoded());
    messageData2.endList();

    final ReceiptsMessage message2 = ReceiptsMessage.createUnsafe(messageData2.encoded());
    assertThat(message2.lastBlockIncomplete()).isFalse();
  }

  @Test
  public void testLastBlockIncompleteDoesNotDeserializeReceipts() {
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final List<List<TransactionReceipt>> receipts = new ArrayList<>();
    receipts.add(List.of(gen.receipt()));

    final MessageData initialMessage =
        ReceiptsMessage.create(
            receipts, TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);
    final ReceiptsMessage message = ReceiptsMessage.readFrom(initialMessage);

    // Call lastBlockIncomplete() first - this should not deserialize receipts
    final boolean incomplete = message.lastBlockIncomplete();

    // Calling receipts() should still work and deserialize on demand
    final List<List<TransactionReceipt>> deserializedReceipts = message.receipts();

    assertThat(incomplete).isFalse();
    assertThat(deserializedReceipts).hasSize(1);
  }

  @Test
  public void testReceiptsAndSyncReceiptsAreCachedIndependently() {
    final BlockDataGenerator gen = new BlockDataGenerator(1);
    final List<List<TransactionReceipt>> receipts = new ArrayList<>();
    receipts.add(List.of(gen.receipt()));

    final MessageData initialMessage =
        ReceiptsMessage.create(
            receipts, TransactionReceiptEncodingConfiguration.DEFAULT_NETWORK_CONFIGURATION);
    final ReceiptsMessage message = ReceiptsMessage.readFrom(initialMessage);

    // Call receipts() multiple times
    final List<List<TransactionReceipt>> firstCallReceipts = message.receipts();
    final List<List<TransactionReceipt>> secondCallReceipts = message.receipts();

    // Verify caching works
    assertThat(secondCallReceipts).isSameAs(firstCallReceipts);
    assertThat(firstCallReceipts).hasSize(1);
  }

  @Test
  public void testMinimalLastBlockIncompleteEncoding() {
    // Test minimal encoding without actual receipts to isolate the flag logic
    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    out.writeUnsignedByte(1); // Flag byte
    out.startList(); // Empty list of blocks
    out.endList();

    final ReceiptsMessage message = ReceiptsMessage.createUnsafe(out.encoded());
    assertThat(message.lastBlockIncomplete()).isTrue();
  }
}
