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
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates internal hash table operations for {@link CompactHashSet}.
 *
 * <p>Provides low-level algorithms for hash table lookup, resizing, and bucket management.
 *
 * @author Jon Noack
 */
@GwtIncompatible
class CompactHashSetInternals {

  static final int MAX_HASH_BUCKET_LENGTH = 9;

  private CompactHashSetInternals() {}

  /**
   * Finds the index of an element in the set.
   *
   * @return the index, or -1 if not found
   */
  static <E extends @Nullable Object> int indexOf(
      @Nullable Object element, CompactHashSetStorage<E> storage) {
    if (storage.needsAllocArrays()) {
      return -1;
    }
    int hash = smearedHash(element);
    int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
    int next = CompactHashing.tableGet(storage.requireTable(), hash & mask);
    if (next == UNSET) {
      return -1;
    }
    int hashPrefix = CompactHashing.getHashPrefix(hash, mask);
    int[] entries = storage.requireEntries();
    Object[] elements = storage.requireElements();
    do {
      int entryIndex = next - 1;
      int entry = entries[entryIndex];
      if (CompactHashing.getHashPrefix(entry, mask) == hashPrefix
          && Objects.equals(element, elements[entryIndex])) {
        return entryIndex;
      }
      next = CompactHashing.getNext(entry, mask);
    } while (next != UNSET);
    return -1;
  }

  /**
   * Resizes the hash table.
   *
   * @return the new mask
   */
  static <E extends @Nullable Object> int resizeTable(
      CompactHashSetStorage<E> storage, int oldMask, int newCapacity, int targetHash, int targetEntryIndex) {
    Object newTable = CompactHashing.createTable(newCapacity);
    int newMask = newCapacity - 1;

    if (targetEntryIndex != UNSET) {
      CompactHashing.tableSet(newTable, targetHash & newMask, targetEntryIndex + 1);
    }

    Object oldTable = storage.requireTable();
    int[] entries = storage.requireEntries();

    for (int oldTableIndex = 0; oldTableIndex <= oldMask; oldTableIndex++) {
      int oldNext = CompactHashing.tableGet(oldTable, oldTableIndex);
      while (oldNext != UNSET) {
        int entryIndex = oldNext - 1;
        int oldEntry = entries[entryIndex];

        int hash = CompactHashing.getHashPrefix(oldEntry, oldMask) | oldTableIndex;

        int newTableIndex = hash & newMask;
        int newNext = CompactHashing.tableGet(newTable, newTableIndex);
        CompactHashing.tableSet(newTable, newTableIndex, oldNext);
        entries[entryIndex] = CompactHashing.maskCombine(hash, newNext, newMask);

        oldNext = CompactHashing.getNext(oldEntry, oldMask);
      }
    }

    storage.table = newTable;
    storage.setHashTableMask(newMask);
    return newMask;
  }

  /**
   * Moves the last entry to the destination index for compact array maintenance.
   */
  static <E extends @Nullable Object> void moveLastEntry(
      CompactHashSetStorage<E> storage, int dstIndex, int mask) {
    Object table = storage.requireTable();
    int[] entries = storage.requireEntries();
    Object[] elements = storage.requireElements();
    int srcIndex = storage.size - 1;
    if (dstIndex < srcIndex) {
      Object element = elements[srcIndex];
      elements[dstIndex] = element;
      elements[srcIndex] = null;

      entries[dstIndex] = entries[srcIndex];
      entries[srcIndex] = 0;

      int tableIndex = smearedHash(element) & mask;
      int next = CompactHashing.tableGet(table, tableIndex);
      int srcNext = srcIndex + 1;
      if (next == srcNext) {
        CompactHashing.tableSet(table, tableIndex, dstIndex + 1);
      } else {
        int entryIndex;
        int entry;
        do {
          entryIndex = next - 1;
          entry = entries[entryIndex];
          next = CompactHashing.getNext(entry, mask);
        } while (next != srcNext);
        entries[entryIndex] = CompactHashing.maskCombine(entry, dstIndex + 1, mask);
      }
    } else {
      elements[dstIndex] = null;
      entries[dstIndex] = 0;
    }
  }

  /**
   * Inserts a new entry into the set.
   */
  static <E extends @Nullable Object> void insertEntry(
      CompactHashSetStorage<E> storage, int entryIndex, E element, int hash, int mask) {
    storage.setEntry(entryIndex, CompactHashing.maskCombine(hash, UNSET, mask));
    storage.setElement(entryIndex, element);
  }
}
