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
package org.hyperledger.besu.plugin.services.storage;

import java.util.Arrays;
import java.util.EnumSet;

public enum StorageFormat {
  FOREST(1),
  BONSAI(2),
  FOREST_VARIABLES(3),
  BONSAI_VARIABLES(4);

  private final int id;

  StorageFormat(final int id) {
    this.id = id;
  }

  public int getId() {
    return id;
  }

  public static StorageFormat fromId(final int id) {
    return Arrays.stream(values()).filter(storageFormat -> storageFormat.id == id).findFirst().orElseThrow();
  }

  public final static EnumSet<StorageFormat> ALL_FORMATS = EnumSet.allOf(StorageFormat.class);
  public final static EnumSet<StorageFormat> ALL_FOREST_FORMATS = EnumSet.of(FOREST, FOREST_VARIABLES);
  public final static EnumSet<StorageFormat> ALL_BONSAI_FORMATS = EnumSet.of(BONSAI, BONSAI_VARIABLES);
  public final static EnumSet<StorageFormat> ALL_VARIABLE_ENABLED_FORMATS = EnumSet.of(FOREST_VARIABLES, BONSAI_VARIABLES);
}
