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
package org.hyperledger.besu.ethereum.eth.encoding;

import static com.google.common.base.Preconditions.checkArgument;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.kzg.CellMask;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

import java.util.ArrayList;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;

public class TransactionAnnouncementEncoder {

  @FunctionalInterface
  public interface Encoder {
    Bytes encode(List<Transaction> transaction);
  }

  /**
   * Returns the correct encoder given an Eth Capability
   *
   * <p>See <a href="https://eips.ethereum.org/EIPS/eip-5793">EIP-5793</a>
   *
   * @param capability the version of the eth protocol
   * @return the correct encoder
   */
  public static Encoder getEncoder(final Capability capability) {
    if (EthProtocol.isEth72Compatible(capability)) {
      return TransactionAnnouncementEncoder::encodeForEth72;
    }
    return TransactionAnnouncementEncoder::encodeForEth68;
  }

  /**
   * Encode a list of transactions for the NewPooledTransactionHashesMessage using eth/72.
   *
   * <p>format: {@code [txtypes: B, [txsize1: P, ...], [txhash1: B_32, ...], cells: B_16]}
   *
   * <p>Two things differ from eth/68. The trailing {@code cells} bitmap announces which cell
   * indices the sender holds for every blob transaction in the message, and the sizes are those of
   * the blob-elided encoding, because that is what an eth/72 {@code GetPooledTransactions} response
   * returns.
   *
   * @param transactions the list to encode
   * @return the encoded value
   */
  private static Bytes encodeForEth72(final List<Transaction> transactions) {
    final List<Integer> sizes = new ArrayList<>(transactions.size());
    final byte[] types = new byte[transactions.size()];
    final List<Hash> hashes = new ArrayList<>(transactions.size());

    for (int i = 0; i < transactions.size(); i++) {
      final Transaction transaction = transactions.get(i);
      types[i] = transaction.getType().getEthSerializedType();
      sizes.add(transaction.getSizeForEth72Announcement());
      hashes.add(transaction.getHash());
    }

    return encodeForEth72(types, sizes, hashes, messageCellMask(transactions));
  }

  /**
   * The single cell mask carried by the message, describing availability for every blob transaction
   * announced in it.
   *
   * <p>The wire format has room for only one mask per message, so callers must group announcements
   * such that a message holds transactions of a single availability; see {@code
   * TransactionBroadcaster#orderTransactions} and {@code NewPooledTransactionHashesMessageSender}.
   * A violation is a wire-level bug that peers would see as us lying about what we hold, so it
   * fails loudly here rather than silently announcing the wrong availability.
   *
   * @param transactions the transactions being announced
   * @return the shared mask, or an all-zero mask when no blob transaction is announced
   */
  private static CellMask messageCellMask(final List<Transaction> transactions) {
    CellMask messageMask = null;
    for (final Transaction transaction : transactions) {
      if (!transaction.getType().supportsBlob()) {
        continue;
      }
      final CellMask txMask = transaction.getBlobsWithCommitments().orElseThrow().getCellMask();
      if (messageMask == null) {
        messageMask = txMask;
      } else {
        checkArgument(
            messageMask.equals(txMask),
            "Blob transactions announced together must share a cell mask, got %s and %s",
            messageMask,
            txMask);
      }
    }
    // The bitmap is ignored by receivers when no blob transaction is announced, but it is a
    // fixed-width field, so an all-zero mask is written rather than omitting it.
    return messageMask == null ? CellMask.EMPTY : messageMask;
  }

  @VisibleForTesting
  public static Bytes encodeForEth72(
      final byte[] types,
      final List<Integer> sizes,
      final List<Hash> hashes,
      final CellMask cellMask) {
    if (!(types.length == hashes.size() && hashes.size() == sizes.size())) {
      throw new IllegalArgumentException(
          "Hashes, sizes and types must have the same number of elements");
    }
    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    out.startList();
    out.writeBytes(Bytes.wrap(types));
    out.writeList(sizes, (h, w) -> w.writeUnsignedInt(h));
    out.writeList(hashes, (h, w) -> w.writeBytes(h.getBytes()));
    out.writeBytes(cellMask.toBytes());
    out.endList();
    return out.encoded();
  }

  /**
   * Encode a list of transactions for the NewPooledTransactionHashesMessage using the Eth/68
   *
   * <p>format: [[type_0: B_1, type_1: B_1, ...], [size_0: P, size_1: P, ...], ...]
   *
   * @param transactions the list to encode
   * @return the encoded value. The message data will contain hashes, types and sizes.
   */
  private static Bytes encodeForEth68(final List<Transaction> transactions) {
    final List<Integer> sizes = new ArrayList<>(transactions.size());
    final byte[] types = new byte[transactions.size()];
    final List<Hash> hashes = new ArrayList<>(transactions.size());

    for (int i = 0; i < transactions.size(); i++) {
      final TransactionType type = transactions.get(i).getType();
      types[i] = type.getEthSerializedType();
      sizes.add(transactions.get(i).getSizeForAnnouncement());
      hashes.add(transactions.get(i).getHash());
    }

    return encodeForEth68(types, sizes, hashes);
  }

  @VisibleForTesting
  public static Bytes encodeForEth68(
      final List<TransactionType> types, final List<Integer> sizes, final List<Hash> hashes) {

    final byte[] byteTypes = new byte[types.size()];
    for (int i = 0; i < types.size(); i++) {
      final TransactionType type = types.get(i);
      byteTypes[i] = type.getEthSerializedType();
    }
    return encodeForEth68(byteTypes, sizes, hashes);
  }

  @VisibleForTesting
  public static Bytes encodeForEth68(
      final byte[] types, final List<Integer> sizes, final List<Hash> hashes) {
    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    // Check if lists have the same size
    if (!(types.length == hashes.size() && hashes.size() == sizes.size())) {
      throw new IllegalArgumentException(
          "Hashes, sizes and types must have the same number of elements");
    }
    out.startList();
    out.writeBytes(Bytes.wrap((types)));
    out.writeList(sizes, (h, w) -> w.writeUnsignedInt(h));
    out.writeList(hashes, (h, w) -> w.writeBytes(h.getBytes()));
    out.endList();
    return out.encoded();
  }
}
