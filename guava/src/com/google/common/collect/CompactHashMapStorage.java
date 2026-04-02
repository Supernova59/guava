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
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Manages the internal storage and state of a {@link CompactHashMap}.
 *
 * <p>This class encapsulates all mutable state and the allocation/resizing logic for the compact
 * hash map's internal arrays. It separates storage concerns from lookup and mutation logic.
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
class CompactHashMapStorage<K extends @Nullable Object, V extends @Nullable Object> {

  /**
   * The hashtable object. This can be either:
   *
   * <ul>
   *   <li>a byte[], short[], or int[], with size a power of two, created by
   *       CompactHashing.createTable, whose values are either
   *       <ul>
   *         <li>UNSET, meaning "null pointer"
   *         <li>one plus an index into the keys, values, and entries arrays
   *       </ul>
   *   <li>another java.util.Map delegate implementation for hash flooding resistance
   *   <li>null, if no entries have yet been added to the map
   * </ul>
   */
  transient @Nullable Object table;

  /**
   * Contains the logical entries, in the range of [0, size()). The high bits of each int are the
   * part of the smeared hash of the key not covered by the hashtable mask, whereas the low bits are
   * the "next" pointer (pointing to the next entry in the bucket chain), which will always be less
   * than or equal to the hashtable mask.
   */
  @VisibleForTesting transient int @Nullable [] entries;

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

  /**
   * Keeps track of metadata like the number of hash table bits and modifications of this data
   * structure. Once arrays are allocated, the value of {@code metadata} combines the number of bits
   * in the "short hash" with a modification count used to detect concurrent modification during
   * iteration.
   *
   * <p>Before arrays are allocated, {@code metadata} holds the expected size.
   */
  transient int metadata;

  /** The number of elements contained in the map. */
  transient int size;

  /** Creates storage with the specified expected size. */
  CompactHashMapStorage(int expectedSize) {
    init(expectedSize);
  }

  /** Pseudoconstructor for serialization support. */
  void init(int expectedSize) {
    Preconditions.checkArgument(expectedSize >= 0, "Expected size must be >= 0");
    this.metadata = Ints.constrainToRange(expectedSize, 1, CompactHashing.MAX_SIZE);
  }

  /** Returns whether arrays need to be allocated. */
  boolean needsAllocArrays() {
    return table == null;
  }

  /** Handle lazy allocation of arrays. */
  @CanIgnoreReturnValue
  int allocArrays() {
    Preconditions.checkState(needsAllocArrays(), "Arrays already allocated");

    int expectedSize = metadata;
    int buckets = CompactHashing.tableSize(expectedSize);
    this.table = CompactHashing.createTable(buckets);
    this.metadata =
        AbstractCompactHashStructure.updateHashTableMaskInMetadata(metadata, buckets - 1);

    this.entries = new int[expectedSize];
    this.keys = new Object[expectedSize];
    this.values = new Object[expectedSize];

    return expectedSize;
  }

  /**
   * Resizes the entries storage if necessary.
   *
   * @param newSize the desired new size
   */
  void resizeMeMaybe(int newSize) {
    int entriesSize = requireEntries().length;
    if (newSize > entriesSize) {
      // 1.5x but round up to nearest odd (this is optimal for memory consumption on Android)
      int newCapacity =
          Math.min(
              CompactHashing.MAX_SIZE, (entriesSize + Math.max(1, entriesSize >>> 1)) | 1);
      if (newCapacity != entriesSize) {
        resizeEntries(newCapacity);
      }
    }
  }

  /**
   * Resizes the internal entries array to the specified capacity, which may be greater or less than
   * the current capacity.
   */
  void resizeEntries(int newCapacity) {
    this.entries = Arrays.copyOf(requireEntries(), newCapacity);
    this.keys = Arrays.copyOf(requireKeys(), newCapacity);
    this.values = Arrays.copyOf(requireValues(), newCapacity);
  }

  // Helper methods for safe access to internal arrays

  Object requireTable() {
    return Preconditions.checkNotNull(table);
  }

  int[] requireEntries() {
    return Preconditions.checkNotNull(entries);
  }

  @Nullable
  Object[] requireKeys() {
    return Preconditions.checkNotNull(keys);
  }

  @Nullable
  Object[] requireValues() {
    return Preconditions.checkNotNull(values);
  }

  // Accessors and mutators for entries

  int entry(int index) {
    return requireEntries()[index];
  }

  void setEntry(int index, int value) {
    requireEntries()[index] = value;
  }

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

  void setHashTableMask(int newMask) {
    metadata = AbstractCompactHashStructure.updateHashTableMaskInMetadata(metadata, newMask);
  }

  void incrementModCount() {
    metadata += CompactHashing.MODIFICATION_COUNT_INCREMENT;
  }
}
