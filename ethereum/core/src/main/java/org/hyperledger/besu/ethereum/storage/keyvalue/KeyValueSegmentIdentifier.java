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
package org.hyperledger.besu.ethereum.storage.keyvalue;

import org.hyperledger.besu.plugin.services.storage.StorageFormat;
import org.hyperledger.besu.plugin.services.storage.SegmentIdentifier;

import java.util.EnumSet;

public enum KeyValueSegmentIdentifier implements SegmentIdentifier {
  BLOCKCHAIN(new byte[] {1}, true),
  WORLD_STATE(new byte[] {2}, StorageFormat.ALL_FOREST_FORMATS),
  PRIVATE_TRANSACTIONS(new byte[] {3}),
  PRIVATE_STATE(new byte[] {4}),
  @Deprecated
  PRUNING_STATE(new byte[] {5}, StorageFormat.ALL_FOREST_FORMATS),
  ACCOUNT_INFO_STATE(new byte[] {6}, StorageFormat.ALL_BONSAI_FORMATS),
  CODE_STORAGE(new byte[] {7}, StorageFormat.ALL_BONSAI_FORMATS),
  ACCOUNT_STORAGE_STORAGE(new byte[] {8}, StorageFormat.ALL_BONSAI_FORMATS),
  TRIE_BRANCH_STORAGE(new byte[] {9}, StorageFormat.ALL_BONSAI_FORMATS),
  TRIE_LOG_STORAGE(new byte[] {10}, StorageFormat.ALL_BONSAI_FORMATS),
  VARIABLES(new byte[] {11}, StorageFormat.ALL_VARIABLE_ENABLED_FORMATS), // formerly GOQUORUM_PRIVATE_WORLD_STATE

  // previously supported GoQuorum private states
  // no longer used but need to be retained for db backward compatibility
  GOQUORUM_PRIVATE_STORAGE(new byte[] {12}), // could be reused

  BACKWARD_SYNC_HEADERS(new byte[] {13}),
  BACKWARD_SYNC_BLOCKS(new byte[] {14}),
  BACKWARD_SYNC_CHAIN(new byte[] {15}),
  SNAPSYNC_MISSING_ACCOUNT_RANGE(new byte[] {16}),
  SNAPSYNC_ACCOUNT_TO_FIX(new byte[] {17}),
  CHAIN_PRUNER_STATE(new byte[] {18});

  private final byte[] id;
  private final EnumSet<StorageFormat> storageFormats;
  private final boolean containsStaticData;

  KeyValueSegmentIdentifier(final byte[] id) {
    this(id, StorageFormat.ALL_FORMATS);
  }

  KeyValueSegmentIdentifier(final byte[] id, final boolean containsStaticData) {
    this(id, StorageFormat.ALL_FORMATS, containsStaticData);
  }

  KeyValueSegmentIdentifier(final byte[] id, final EnumSet<StorageFormat> storageFormats) {
    this(id, storageFormats, false);
  }

  KeyValueSegmentIdentifier(
          final byte[] id, final EnumSet<StorageFormat> storageFormats, final boolean containsStaticData) {
    this.id = id;
    this.storageFormats = storageFormats;
    this.containsStaticData = containsStaticData;
  }

  @Override
  public String getName() {
    return name();
  }

  @Override
  public byte[] getId() {
    return id;
  }

  @Override
  public boolean containsStaticData() {
    return containsStaticData;
  }

  @Override
  public boolean includedInDatabaseFormat(final StorageFormat version) {
    return storageFormats.contains(version);
  }
}
