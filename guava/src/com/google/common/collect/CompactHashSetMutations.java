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

import static com.google.common.collect.CompactHashing.UNSET;
import static com.google.common.collect.Hashing.smearedHash;

import com.google.common.annotations.GwtIncompatible;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates mutation operations for {@link CompactHashSet}.
 *
 * <p>This class handles all operations that modify the set: add, remove, clear.
 *
 * @author Jon Noack
 */
@GwtIncompatible
class CompactHashSetMutations {

  static final Object NOT_FOUND = new Object();

  private CompactHashSetMutations() {}

  /**
   * Adds an element to the set.
   *
   * @return true if the element was added, false if already present
   */
  @CanIgnoreReturnValue
  static <E extends @Nullable Object> boolean add(
      E element, CompactHashSetStorage<E> storage, java.util.Set<E> parent) {
    if (storage.needsAllocArrays()) {
      storage.allocArrays();
    }
    java.util.Set<E> delegate = storage.delegateOrNull();
    if (delegate != null) {
      return delegate.add(element);
    }

    int newEntryIndex = storage.size;
    int newSize = newEntryIndex + 1;
    int hash = smearedHash(element);
    int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
    int tableIndex = hash & mask;
    int next = CompactHashing.tableGet(storage.requireTable(), tableIndex);
    if (next == UNSET) {
      if (newSize > mask) {
        mask = CompactHashSetInternals.resizeTable(storage, mask, CompactHashing.newCapacity(mask), hash, newEntryIndex);
      } else {
        CompactHashing.tableSet(storage.requireTable(), tableIndex, newEntryIndex + 1);
      }
    } else {
      int entryIndex;
      int entry;
      int hashPrefix = CompactHashing.getHashPrefix(hash, mask);
      int bucketLength = 0;
      do {
        entryIndex = next - 1;
        entry = storage.entry(entryIndex);
        if (CompactHashing.getHashPrefix(entry, mask) == hashPrefix
            && Objects.equals(element, storage.element(entryIndex))) {
          return false;
        }
        next = CompactHashing.getNext(entry, mask);
        bucketLength++;
      } while (next != UNSET);

      if (bucketLength >= CompactHashSetInternals.MAX_HASH_BUCKET_LENGTH) {
        return ((java.util.Set<E>) convertToHashFloodingResistantImplementation(storage)).add(element);
      }

      if (newSize > mask) {
        mask = CompactHashSetInternals.resizeTable(storage, mask, CompactHashing.newCapacity(mask), hash, newEntryIndex);
      } else {
        storage.setEntry(entryIndex, CompactHashing.maskCombine(entry, newEntryIndex + 1, mask));
      }
    }

    storage.resizeMeMaybe(newSize);
    CompactHashSetInternals.insertEntry(storage, newEntryIndex, element, hash, mask);
    storage.size = newSize;
    storage.incrementModCount();
    return true;
  }

  /**
   * Removes an element from the set.
   *
   * @return true if the element was removed, false if not present
   */
  @CanIgnoreReturnValue
  static <E extends @Nullable Object> boolean remove(
      @Nullable Object element, CompactHashSetStorage<E> storage) {
    java.util.Set<E> delegate = storage.delegateOrNull();
    if (delegate != null) {
      return delegate.remove(element);
    }
    
    if (storage.needsAllocArrays()) {
      return false;
    }
    
    int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
    int index = CompactHashing.remove(
        element,
        null,
        mask,
        storage.requireTable(),
        storage.requireEntries(),
        storage.requireElements(),
        null);
    
    if (index == -1) {
      return false;
    }

    CompactHashSetInternals.moveLastEntry(storage, index, mask);
    storage.size--;
    storage.incrementModCount();
    return true;
  }

  /**
   * Clears all elements from the set.
   */
  static <E extends @Nullable Object> void clear(CompactHashSetStorage<E> storage) {
    java.util.Set<E> delegate = storage.delegateOrNull();
    if (delegate != null) {
      delegate.clear();
    } else if (!storage.needsAllocArrays()) {
      CompactHashing.tableClear(storage.requireTable());
      java.util.Arrays.fill(storage.requireEntries(), 0);
      java.util.Arrays.fill(storage.requireElements(), null);
      storage.size = 0;
      storage.incrementModCount();
    }
  }

  static <E extends @Nullable Object> java.util.Set<E> convertToHashFloodingResistantImplementation(
      CompactHashSetStorage<E> storage) {
    java.util.Set<E> newDelegate = new java.util.LinkedHashSet<>(
        AbstractCompactHashStructure.extractHashTableMask(storage.metadata) + 1, 1.0f);
    for (int i = AbstractCompactHashStructure.computeFirstEntryIndex(storage.size);
        i >= 0;
        i = AbstractCompactHashStructure.computeSuccessor(i, storage.size)) {
      newDelegate.add(storage.element(i));
    }
    storage.table = newDelegate;
    storage.entries = null;
    storage.elements = null;
    storage.incrementModCount();
    return newDelegate;
  }
}
