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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.EthProtocolVersion;
import org.hyperledger.besu.ethereum.eth.transactions.CellMask;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAnnouncement;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.Capability;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    return capability.getVersion() >= EthProtocolVersion.V72
        ? TransactionAnnouncementEncoder::encodeForEth72
        : TransactionAnnouncementEncoder::encodeForEth68;
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

  /**
   * Encode a list of transactions for the NewPooledTransactionHashesMessage using eth/72.
   *
   * <p>format: [[type_0: B_1, type_1: B_1, ...], [size_0: P, size_1: P, ...], [hash_0: B_32,
   * hash_1: B_32, ...], cell_mask: B_16]
   *
   * @param transactions the list to encode
   * @return the encoded value. The message data will contain hashes, types, sizes and the
   *     transaction cell availability mask.
   */
  private static Bytes encodeForEth72(final List<Transaction> transactions) {
    return encodeAnnouncementsForEth72(TransactionAnnouncement.create(transactions));
  }

  public static Bytes encodeAnnouncementsForEth72(
      final List<TransactionAnnouncement> transactionAnnouncements) {
    final List<Integer> sizes = new ArrayList<>(transactionAnnouncements.size());
    final byte[] types = new byte[transactionAnnouncements.size()];
    final List<Hash> hashes = new ArrayList<>(transactionAnnouncements.size());
    Optional<CellMask> blobCellMask = Optional.empty();
    boolean sawBlobTransaction = false;

    for (int i = 0; i < transactionAnnouncements.size(); i++) {
      final TransactionAnnouncement announcement = transactionAnnouncements.get(i);
      types[i] = announcement.type().getEthSerializedType();
      sizes.add(announcement.size().intValue());
      hashes.add(announcement.hash());

      if (announcement.type() == TransactionType.BLOB) {
        if (!sawBlobTransaction) {
          sawBlobTransaction = true;
          blobCellMask = announcement.cellMask();
        } else if (!blobCellMask.equals(announcement.cellMask())) {
          throw new IllegalArgumentException(
              "All blob transaction announcements in an eth/72 message must use the same cell mask");
        }
      }
    }

    if (sawBlobTransaction && blobCellMask.isEmpty()) {
      throw new IllegalArgumentException("eth/72 blob transaction announcements require a cell mask");
    }

    return encodeForEth72(types, sizes, hashes, blobCellMask);
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

  @VisibleForTesting
  public static Bytes encodeForEth72(
      final byte[] types,
      final List<Integer> sizes,
      final List<Hash> hashes,
      final Optional<CellMask> cellMask) {
    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    if (!(types.length == hashes.size() && hashes.size() == sizes.size())) {
      throw new IllegalArgumentException(
          "Hashes, sizes and types must have the same number of elements");
    }
    out.startList();
    out.writeBytes(Bytes.wrap((types)));
    out.writeList(sizes, (h, w) -> w.writeUnsignedInt(h));
    out.writeList(hashes, (h, w) -> w.writeBytes(h.getBytes()));
    cellMask.ifPresentOrElse(mask -> out.writeBytes(mask.bytes()), out::writeNull);
    out.endList();
    return out.encoded();
  }
}
