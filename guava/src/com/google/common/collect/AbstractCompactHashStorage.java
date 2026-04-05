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
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Abstract base class for compact hash storage implementations.
 *
 * <p>This class consolidates common storage management logic shared between
 * {@link CompactHashMapStorage} and {@link CompactHashSetStorage}, eliminating
 * code duplication while maintaining specialization for different data structures.
 *
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible
abstract class AbstractCompactHashStorage {

  /**
   * The hashtable object. This can be either:
   *
   * <ul>
   *   <li>a byte[], short[], or int[], with size a power of two, created by
   *       CompactHashing.createTable, whose values are either UNSET or one plus an index
   *   <li>a java.util.Map or java.util.Set delegate for hash flooding resistance
   *   <li>null, if no entries have yet been added
   * </ul>
   */
  transient @Nullable Object table;

  /**
   * Contains the logical entries. The high bits store the smeared hash not covered by the
   * hashtable mask, the low bits store the "next" pointer for bucket chain traversal.
   */
  @Nullable transient int @Nullable [] entries;

  /**
   * Keeps track of metadata like the hash table bits and modification count.
   * Before arrays are allocated, holds the expected size.
   */
  transient int metadata;

  /** The number of elements contained in the storage. */
  transient int size;

  /** Initialize storage with the specified expected size. */
  void init(int expectedSize) {
    Preconditions.checkArgument(expectedSize >= 0, "Expected size must be >= 0");
    this.metadata = Ints.constrainToRange(expectedSize, 1, CompactHashing.MAX_SIZE);
  }

  /** Returns whether arrays need to be allocated. */
  boolean needsAllocArrays() {
    return table == null;
  }

  /** Handle lazy allocation of internal arrays. */
  @CanIgnoreReturnValue
  int allocArrays() {
    Preconditions.checkState(needsAllocArrays(), "Arrays already allocated");

    int expectedSize = metadata;
    int buckets = CompactHashing.tableSize(expectedSize);
    this.table = CompactHashing.createTable(buckets);
    this.metadata =
        AbstractCompactHashStructure.updateHashTableMaskInMetadata(metadata, buckets - 1);
    this.entries = new int[expectedSize];

    allocateSpecializedArrays(expectedSize);

    return expectedSize;
  }

  /**
   * Allocate specialized arrays specific to subclass (keys/values for Map, elements for Set).
   *
   * @param expectedSize the initial size for the arrays
   */
  protected abstract void allocateSpecializedArrays(int expectedSize);

  /** Returns the entries array, throwing if not initialized. */
  int[] requireEntries() {
    return java.util.Objects.requireNonNull(entries);
  }

  /** Returns the table object, throwing if not initialized. */
  Object requireTable() {
    return java.util.Objects.requireNonNull(table);
  }

  /** Get the entry at the given index. */
  int entry(int index) {
    return requireEntries()[index];
  }

  /** Set the entry at the given index. */
  void setEntry(int index, int value) {
    requireEntries()[index] = value;
  }

  /** Update the hash table mask in metadata. */
  void setHashTableMask(int newMask) {
    metadata = AbstractCompactHashStructure.updateHashTableMaskInMetadata(metadata, newMask);
  }

  /** Increment the modification counter. */
  void incrementModCount() {
    metadata += CompactHashing.MODIFICATION_COUNT_INCREMENT;
  }

  /** Resize the entries array if necessary to accommodate newSize elements. */
  void resizeMeMaybe(int newSize) {
    int entriesSize = requireEntries().length;
    if (newSize > entriesSize) {
      int newCapacity =
          Math.min(
              CompactHashing.MAX_SIZE, (entriesSize + Math.max(1, entriesSize >>> 1)) | 1);
      if (newCapacity != entriesSize) {
        this.entries = Arrays.copyOf(requireEntries(), newCapacity);
        resizeSpecializedArrays(newCapacity);
      }
    }
  }

  /**
   * Resize specialized arrays to match entries capacity.
   *
   * @param newCapacity the new capacity for specialized arrays
   */
  protected abstract void resizeSpecializedArrays(int newCapacity);
}
