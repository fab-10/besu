/*
 * Copyright Hyperledger Besu Contributors.
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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.chain.VariablesStorage;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorageTransaction;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;

import com.google.common.collect.Lists;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class VariablesKeyValueStorage implements VariablesStorage {

  private static final Bytes CHAIN_HEAD_KEY =
      Bytes.wrap("chainHeadHash".getBytes(StandardCharsets.UTF_8));
  private static final Bytes FORK_HEADS_KEY =
      Bytes.wrap("forkHeads".getBytes(StandardCharsets.UTF_8));
  private static final Bytes FINALIZED_BLOCK_HASH_KEY =
      Bytes.wrap("finalizedBlockHash".getBytes(StandardCharsets.UTF_8));
  private static final Bytes SAFE_BLOCK_HASH_KEY =
      Bytes.wrap("safeBlockHash".getBytes(StandardCharsets.UTF_8));
  final KeyValueStorage variables;

  public VariablesKeyValueStorage(final KeyValueStorage variables) {
    this.variables = variables;
  }

  @Override
  public Optional<Hash> getChainHead() {
    return getVariable(CHAIN_HEAD_KEY).map(this::bytesToHash);
  }

  @Override
  public Collection<Hash> getForkHeads() {
    return getVariable(FORK_HEADS_KEY)
        .map(bytes -> RLP.input(bytes).readList(in -> this.bytesToHash(in.readBytes32())))
        .orElse(Lists.newArrayList());
  }

  @Override
  public Optional<Hash> getFinalized() {
    return getVariable(FINALIZED_BLOCK_HASH_KEY).map(this::bytesToHash);
  }

  @Override
  public Optional<Hash> getSafeBlock() {
    return getVariable(SAFE_BLOCK_HASH_KEY).map(this::bytesToHash);
  }

  @Override
  public Updater updater() {
    return new Updater(variables.startTransaction());
  }

  private Hash bytesToHash(final Bytes bytes) {
    return Hash.wrap(Bytes32.wrap(bytes, 0));
  }

  Optional<Bytes> getVariable(final Bytes key) {
    return variables.get(key.toArrayUnsafe()).map(Bytes::wrap);
  }

  public static class Updater implements VariablesStorage.Updater {

    private final KeyValueStorageTransaction variablesTransaction;

    Updater(final KeyValueStorageTransaction variablesTransaction) {
      this.variablesTransaction = variablesTransaction;
    }

    @Override
    public void setChainHead(final Hash blockHash) {
      setVariable(CHAIN_HEAD_KEY, blockHash);
    }

    @Override
    public void setForkHeads(final Collection<Hash> forkHeadHashes) {
      final Bytes data =
          RLP.encode(o -> o.writeList(forkHeadHashes, (val, out) -> out.writeBytes(val)));
      setVariable(FORK_HEADS_KEY, data);
    }

    @Override
    public void setFinalized(final Hash blockHash) {
      setVariable(FINALIZED_BLOCK_HASH_KEY, blockHash);
    }

    @Override
    public void setSafeBlock(final Hash blockHash) {
      setVariable(SAFE_BLOCK_HASH_KEY, blockHash);
    }

    @Override
    public void commit() {
      variablesTransaction.commit();
    }

    @Override
    public void rollback() {
      variablesTransaction.rollback();
    }

    void setVariable(final Bytes key, final Bytes value) {
      variablesTransaction.put(key.toArrayUnsafe(), value.toArrayUnsafe());
    }
  }
}
