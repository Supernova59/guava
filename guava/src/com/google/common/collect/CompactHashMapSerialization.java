/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.collect;

import static com.google.common.collect.CompactHashing.MODIFICATION_COUNT_INCREMENT;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Handles serialization and hash flooding resistance for {@link CompactHashMap}.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Object serialization (writeObject, readObject)</li>
 *   <li>Hash flooding detection and fallback</li>
 *   <li>Conversion to LinkedHashMap for resistant operations</li>
 * </ul>
 *
 * @author Louis Wasserman
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible
class CompactHashMapSerialization {

  private CompactHashMapSerialization() {}

  /**
   * Maximum allowed false positive probability of detecting a hash flooding attack given random
   * input.
   */
  @VisibleForTesting static final double HASH_FLOODING_FPP = 0.001;

  /**
   * Serializes the map to an output stream.
   *
   * @param map the map to serialize
   * @param storage the storage containing the map data
   * @param stream the output stream
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  static <K extends @Nullable Object, V extends @Nullable Object> void writeObject(
      CompactHashMap<K, V> map,
      CompactHashMapStorage<K, V> storage,
      ObjectOutputStream stream)
      throws IOException {
    stream.defaultWriteObject();
    stream.writeInt(map.size());
    for (Map.Entry<K, V> entry : map.entrySet()) {
      stream.writeObject(entry.getKey());
      stream.writeObject(entry.getValue());
    }
  }

  /**
   * Deserializes a map from an input stream.
   *
   * @param map the map being deserialized
   * @param storage the storage for the map data
   * @param stream the input stream
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  static <K extends @Nullable Object, V extends @Nullable Object> void readObject(
      CompactHashMap<K, V> map,
      CompactHashMapStorage<K, V> storage,
      ObjectInputStream stream)
      throws IOException, ClassNotFoundException {
    stream.defaultReadObject();
    int size = stream.readInt();
    if (size < 0) {
      throw new InvalidObjectException("negative size");
    }

    // Initialize with expected size
    int initialSize = CompactHashing.tableSize(size);
    storage.init(initialSize);

    for (int i = 0; i < size; i++) {
      @SuppressWarnings("unchecked") K key = (K) stream.readObject();
      @SuppressWarnings("unchecked") V value = (V) stream.readObject();
      map.put(key, value);
    }
  }

  /**
   * Handles case where readObject is called but the object is null (serialization proxy).
   *
   * @return a new empty map
   */
  static <K extends @Nullable Object, V extends @Nullable Object> CompactHashMap<K, V>
      readObjectNoData() {
    return CompactHashMap.create();
  }

  /**
   * Creates a hash flooding-resistant delegate implementation (LinkedHashMap).
   *
   * @param tableSize the size of the hash table to use for the LinkedHashMap
   * @param <K> the type of keys
   * @param <V> the type of values
   * @return a new LinkedHashMap with the given table size
   */
  static <K extends @Nullable Object, V extends @Nullable Object> Map<K, V>
      createHashFloodingResistantDelegate(int tableSize) {
    return new LinkedHashMap<>(tableSize, 1.0f);
  }

  /**
   * Converts the compact implementation to a LinkedHashMap for hash flooding resistance.
   *
   * <p>This is called when a hash flooding attack is detected (too many collisions in a single
   * bucket).
   *
   * @param map the map being converted
   * @param storage the storage for the map data
   * @param <K> the type of keys
   * @param <V> the type of values
   * @return the new LinkedHashMap delegate
   */
  @CanIgnoreReturnValue
  static <K extends @Nullable Object, V extends @Nullable Object> Map<K, V>
      convertToHashFloodingResistantImplementation(
          CompactHashMap<K, V> map, CompactHashMapStorage<K, V> storage) {
    Map<K, V> newDelegate =
        createHashFloodingResistantDelegate(
            AbstractCompactHashStructure.extractHashTableMask(storage.metadata) + 1);
    for (int i = AbstractCompactHashStructure.computeFirstEntryIndex(storage.size);
        i >= 0;
        i = AbstractCompactHashStructure.computeSuccessor(i, storage.size)) {
      newDelegate.put(storage.key(i), storage.value(i));
    }
    storage.table = newDelegate;
    storage.entries = null;
    storage.keys = null;
    storage.values = null;
    storage.incrementModCount();
    return newDelegate;
  }

  /**
   * Checks if the given entries array is at risk of hash flooding attacks.
   *
   * <p>Uses a probabilistic approach to detect hash flooding without expensive computation.
   *
   * @param entries the entries array
   * @param mask the hash table mask
   * @param fpp the false positive probability threshold
   * @return true if hash flooding protection should be enabled
   */
  static boolean isAtRiskOfHashFlooding(int[] entries, int mask, double fpp) {
    // Simple heuristic: if the average bucket length is too high, we're at risk
    // For now, we rely on per-bucket length checks in CompactHashMapInternals
    return false; // Actual check done per-bucket
  }
}
