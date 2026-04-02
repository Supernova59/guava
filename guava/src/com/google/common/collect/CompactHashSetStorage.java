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
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Manages the internal storage and state of a {@link CompactHashSet}.
 *
 * <p>This class encapsulates all mutable state and the allocation/resizing logic for the compact
 * hash set's internal arrays. Parallel to {@link CompactHashMapStorage} for Maps.
 *
 * @author Jon Noack
 */
@GwtIncompatible
class CompactHashSetStorage<E extends @Nullable Object> {

  transient @Nullable Object table;
  @Nullable transient int @Nullable [] entries;
  @Nullable transient Object @Nullable [] elements;
  transient int metadata;
  transient int size;

  CompactHashSetStorage(int expectedSize) {
    init(expectedSize);
  }

  void init(int expectedSize) {
    Preconditions.checkArgument(expectedSize >= 0, "Expected size must be >= 0");
    this.metadata = Ints.constrainToRange(expectedSize, 1, CompactHashing.MAX_SIZE);
  }

  boolean needsAllocArrays() {
    return table == null;
  }

  @CanIgnoreReturnValue
  int allocArrays() {
    Preconditions.checkState(needsAllocArrays(), "Arrays already allocated");

    int expectedSize = metadata;
    int buckets = CompactHashing.tableSize(expectedSize);
    this.table = CompactHashing.createTable(buckets);
    setHashTableMask(buckets - 1);

    this.entries = new int[expectedSize];
    this.elements = new Object[expectedSize];

    return expectedSize;
  }

  @SuppressWarnings("unchecked")
  @Nullable Set<E> delegateOrNull() {
    if (table instanceof java.util.Set) {
      return (java.util.Set<E>) table;
    }
    return null;
  }

  Object requireTable() {
    return java.util.Objects.requireNonNull(table);
  }

  int[] requireEntries() {
    return java.util.Objects.requireNonNull(entries);
  }

  @Nullable
  Object[] requireElements() {
    return java.util.Objects.requireNonNull(elements);
  }

  E element(int index) {
    @SuppressWarnings("unchecked")
    E result = (E) requireElements()[index];
    return result;
  }

  void setElement(int index, E value) {
    requireElements()[index] = value;
  }

  int entry(int index) {
    return requireEntries()[index];
  }

  void setEntry(int index, int value) {
    requireEntries()[index] = value;
  }

  void setHashTableMask(int newMask) {
    metadata = AbstractCompactHashStructure.updateHashTableMaskInMetadata(metadata, newMask);
  }

  void incrementModCount() {
    metadata += CompactHashing.MODIFICATION_COUNT_INCREMENT;
  }

  void resizeMeMaybe(int newSize) {
    int entriesSize = requireEntries().length;
    if (newSize > entriesSize) {
      int newCapacity = java.lang.Math.min(CompactHashing.MAX_SIZE, (entriesSize + java.lang.Math.max(1, entriesSize >>> 1)) | 1);
      if (newCapacity != entriesSize) {
        this.entries = Arrays.copyOf(requireEntries(), newCapacity);
        this.elements = Arrays.copyOf(requireElements(), newCapacity);
      }
    }
  }
}
