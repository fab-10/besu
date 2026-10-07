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
package org.hyperledger.besu.ethereum.eth;

import org.hyperledger.besu.ethereum.eth.messages.EthProtocolMessages;

import java.util.List;
import java.util.Optional;

/**
 * Eth protocol versions as defined in <a
 * href="https://github.com/ethereum/devp2p/blob/master/caps/eth.md">Ethereum Wire Protocol
 * (ETH)</a>, with the messages each of them supports.
 */
public enum EthProtocolVersion {
  /** eth/68 */
  V68(68, 17, Messages.ETH68),
  /**
   * eth/69 EIP-7642
   *
   * <p>Version 69 added the BlockRangeUpdate message.
   */
  V69(69, 18, Messages.ETH69),
  /** eth/70 uses the same messages as eth/69 */
  V70(70, 18, Messages.ETH69),
  /** eth/71 */
  V71(71, 20, Messages.ETH71);

  private final int version;
  private final int messageSpace;
  private final List<Integer> supportedMessages;

  EthProtocolVersion(
      final int version, final int messageSpace, final List<Integer> supportedMessages) {
    this.version = version;
    this.messageSpace = messageSpace;
    this.supportedMessages = supportedMessages;
  }

  /**
   * The version number as exchanged on the wire.
   *
   * @return the version number
   */
  public int getVersion() {
    return version;
  }

  /**
   * The number of message codes reserved by this version.
   *
   * @return the message space size
   */
  public int getMessageSpace() {
    return messageSpace;
  }

  /**
   * The codes of the messages supported by this version.
   *
   * @return a list containing the codes of supported messages
   */
  public List<Integer> getSupportedMessages() {
    return supportedMessages;
  }

  /**
   * Whether a raw version number, possibly not a known one, uses the eth/69+ status layout.
   *
   * @param protocolVersion the raw protocol version number
   * @return true if the version is 69 or later
   */
  public static boolean hasBlockRange(final int protocolVersion) {
    return protocolVersion >= V69.version;
  }

  /**
   * Finds the protocol version matching a raw version number.
   *
   * @param protocolVersion the raw protocol version number
   * @return the matching version, or empty if it is not a known one
   */
  public static Optional<EthProtocolVersion> fromVersion(final int protocolVersion) {
    for (final EthProtocolVersion v : values()) {
      if (v.version == protocolVersion) {
        return Optional.of(v);
      }
    }
    return Optional.empty();
  }

  // Held in a nested class since an enum constant can't reference the enum's own static fields
  private static final class Messages {
    private static final List<Integer> ETH68 =
        List.of(
            EthProtocolMessages.STATUS,
            EthProtocolMessages.NEW_BLOCK_HASHES,
            EthProtocolMessages.TRANSACTIONS,
            EthProtocolMessages.GET_BLOCK_HEADERS,
            EthProtocolMessages.BLOCK_HEADERS,
            EthProtocolMessages.GET_BLOCK_BODIES,
            EthProtocolMessages.BLOCK_BODIES,
            EthProtocolMessages.NEW_BLOCK,
            EthProtocolMessages.GET_RECEIPTS,
            EthProtocolMessages.RECEIPTS,
            EthProtocolMessages.NEW_POOLED_TRANSACTION_HASHES,
            EthProtocolMessages.GET_POOLED_TRANSACTIONS,
            EthProtocolMessages.POOLED_TRANSACTIONS);

    private static final List<Integer> ETH69 =
        List.of(
            EthProtocolMessages.STATUS,
            EthProtocolMessages.NEW_BLOCK_HASHES,
            EthProtocolMessages.TRANSACTIONS,
            EthProtocolMessages.GET_BLOCK_HEADERS,
            EthProtocolMessages.BLOCK_HEADERS,
            EthProtocolMessages.GET_BLOCK_BODIES,
            EthProtocolMessages.BLOCK_BODIES,
            EthProtocolMessages.NEW_BLOCK,
            EthProtocolMessages.GET_RECEIPTS,
            EthProtocolMessages.RECEIPTS,
            EthProtocolMessages.NEW_POOLED_TRANSACTION_HASHES,
            EthProtocolMessages.GET_POOLED_TRANSACTIONS,
            EthProtocolMessages.POOLED_TRANSACTIONS,
            EthProtocolMessages.BLOCK_RANGE_UPDATE);

    private static final List<Integer> ETH71 =
        List.of(
            EthProtocolMessages.STATUS,
            EthProtocolMessages.NEW_BLOCK_HASHES,
            EthProtocolMessages.TRANSACTIONS,
            EthProtocolMessages.GET_BLOCK_HEADERS,
            EthProtocolMessages.BLOCK_HEADERS,
            EthProtocolMessages.GET_BLOCK_BODIES,
            EthProtocolMessages.BLOCK_BODIES,
            EthProtocolMessages.NEW_BLOCK,
            EthProtocolMessages.GET_RECEIPTS,
            EthProtocolMessages.RECEIPTS,
            EthProtocolMessages.NEW_POOLED_TRANSACTION_HASHES,
            EthProtocolMessages.GET_POOLED_TRANSACTIONS,
            EthProtocolMessages.POOLED_TRANSACTIONS,
            EthProtocolMessages.BLOCK_RANGE_UPDATE,
            EthProtocolMessages.GET_BLOCK_ACCESS_LISTS,
            EthProtocolMessages.BLOCK_ACCESS_LISTS);
  }
}
