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

import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Manages the internal storage and state of a {@link CompactHashMap}.
 *
 * <p>This class encapsulates all mutable state and allocation/resizing logic for the compact
 * hash map's internal arrays. Extends {@link AbstractCompactHashStorage} to share common
 * storage management with {@link CompactHashSetStorage}.
 *
 * <p>The storage model consists of four paired arrays:
 * <ul>
 *   <li>{@code table}: The hash table (byte[], short[], or int[])</li>
 *   <li>{@code entries}: Combined hash prefix and next pointer for each entry</li>
 *   <li>{@code keys}: The actual keys stored</li>
 *   <li>{@code values}: The actual values stored</li>
 * </ul>
 *
 * @author Louis Wasserman
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible
class CompactHashMapStorage<K extends @Nullable Object, V extends @Nullable Object>
    extends AbstractCompactHashStorage {

  /**
   * The keys of the entries in the map, in the range of [0, size()). The keys in [size(),
   * keys.length) are all {@code null}.
   */
  @VisibleForTesting transient @Nullable Object @Nullable [] keys;

  /**
   * The values of the entries in the map, in the range of [0, size()). The values in [size(),
   * values.length) are all {@code null}.
   */
  @VisibleForTesting transient @Nullable Object @Nullable [] values;

  /** Creates storage with the specified expected size. */
  CompactHashMapStorage(int expectedSize) {
    init(expectedSize);
  }

  @Override
  protected void allocateSpecializedArrays(int expectedSize) {
    this.keys = new Object[expectedSize];
    this.values = new Object[expectedSize];
  }

  @Override
  protected void resizeSpecializedArrays(int newCapacity) {
    this.keys = Arrays.copyOf(requireKeys(), newCapacity);
    this.values = Arrays.copyOf(requireValues(), newCapacity);
  }

  // Helper methods for safe access to internal Map-specific arrays

  @Nullable
  Object[] requireKeys() {
    return java.util.Objects.requireNonNull(keys);
  }

  @Nullable
  Object[] requireValues() {
    return java.util.Objects.requireNonNull(values);
  }

  // Accessors and mutators for Map-specific data

  @SuppressWarnings("unchecked")
  K key(int index) {
    return (K) requireKeys()[index];
  }

  void setKey(int index, K value) {
    requireKeys()[index] = value;
  }

  @SuppressWarnings("unchecked")
  @Nullable
  V value(int index) {
    return (V) requireValues()[index];
  }

  void setValue(int index, @Nullable V value) {
    requireValues()[index] = value;
  }

  @SuppressWarnings("unchecked")
  java.util.@Nullable Map<K, V> delegateOrNull() {
    if (table instanceof java.util.Map) {
      return (java.util.Map<K, V>) table;
    }
    return null;
  }
}
