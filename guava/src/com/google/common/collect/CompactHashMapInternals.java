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
import static java.lang.Math.max;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates internal hash table operations and lookup logic for {@link CompactHashMap}.
 *
 * <p>This class handles:
 * <ul>
 *   <li>Hash table resizing and rehashing</li>
 *   <li>Entry lookup by key</li>
 *   <li>Collision detection and handling</li>
 *   <li>Hash flooding detection</li>
 * </ul>
 *
 * @author Louis Wasserman
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible
class CompactHashMapInternals {

  private CompactHashMapInternals() {}

  /** Maximum allowed length of a hash table bucket before falling back to LinkedHashMap. */
  static final int MAX_HASH_BUCKET_LENGTH = 9;

  /**
   * Maximum allowed false positive probability of detecting a hash flooding attack given random
   * input.
   */
  @VisibleForTesting static final double HASH_FLOODING_FPP = 0.001;

  /**
   * Finds the index of an entry with the given key in the storage, or -1 if not found.
   *
   * @param key the key to search for
   * @param storage the storage containing the hash map data
   * @param <K> the type of keys
   * @param <V> the type of values
   * @return the entry index, or -1 if not found
   */
  static <K extends @Nullable Object, V extends @Nullable Object> int indexOf(
      @Nullable Object key, CompactHashMapStorage<K, V> storage) {
    if (storage.needsAllocArrays()) {
      return -1;
    }
    int hash = smearedHash(key);
    int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
    int next = CompactHashing.tableGet(storage.requireTable(), hash & mask);
    if (next == UNSET) {
      return -1;
    }
    int hashPrefix = CompactHashing.getHashPrefix(hash, mask);
    int[] entries = storage.requireEntries();
    do {
      int entryIndex = next - 1;
      int entry = entries[entryIndex];
      if (CompactHashing.getHashPrefix(entry, mask) == hashPrefix
          && Objects.equals(key, storage.key(entryIndex))) {
        return entryIndex;
      }
      next = CompactHashing.getNext(entry, mask);
    } while (next != UNSET);
    return -1;
  }

  /**
   * Resizes the hash table and inserts a new entry.
   *
   * @param storage the storage to resize
   * @param oldMask the old hash table mask
   * @param newCapacity the new hash table capacity (must be power of 2)
   * @param targetHash the hash of the entry to be inserted
   * @param targetEntryIndex the index of the entry to be inserted, or UNSET if N/A
   * @param <K> the type of keys
   * @param <V> the type of values
   * @return the new hash table mask
   */
  @CanIgnoreReturnValue
  static <K extends @Nullable Object, V extends @Nullable Object> int resizeTable(
      CompactHashMapStorage<K, V> storage,
      int oldMask,
      int newCapacity,
      int targetHash,
      int targetEntryIndex) {
    Object newTable = CompactHashing.createTable(newCapacity);
    int newMask = newCapacity - 1;

    if (targetEntryIndex != UNSET) {
      // Add target first; it must be last in the chain because its entry hasn't yet been created
      CompactHashing.tableSet(newTable, targetHash & newMask, targetEntryIndex + 1);
    }

    Object oldTable = storage.requireTable();
    int[] entries = storage.requireEntries();

    // Loop over oldTable to construct newTable. Entries don't move, but the "short hash" has a
    // different number of bits now, so we must rewrite each entry's hash prefix and next pointer.
    for (int oldTableIndex = 0; oldTableIndex <= oldMask; oldTableIndex++) {
      int oldNext = CompactHashing.tableGet(oldTable, oldTableIndex);
      // Each element of oldTable is the head of a (possibly empty) linked list of elements in
      // entries. We need to rewrite the next link of each element so it's in the appropriate
      // linked list starting from newTable.
      while (oldNext != UNSET) {
        int entryIndex = oldNext - 1;
        int oldEntry = entries[entryIndex];

        // Rebuild the full 32-bit hash using entry hashPrefix and oldTableIndex ("hashSuffix").
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
   * Moves the last entry in the entry arrays to dstIndex, updating all pointers accordingly.
   *
   * <p>This is used when removing an entry to maintain the compact property of the array.
   *
   * @param storage the storage containing the hash map data
   * @param dstIndex the destination index for the last entry
   * @param mask the current hash table mask
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  static <K extends @Nullable Object, V extends @Nullable Object> void moveLastEntry(
      CompactHashMapStorage<K, V> storage, int dstIndex, int mask) {
    Object table = storage.requireTable();
    int[] entries = storage.requireEntries();
    int srcIndex = storage.size - 1;
    if (dstIndex < srcIndex) {
      // move last entry to deleted spot
      K key = storage.key(srcIndex);
      storage.setKey(dstIndex, key);
      storage.setValue(dstIndex, storage.value(srcIndex));
      storage.setKey(srcIndex, null);
      storage.setValue(srcIndex, null);

      // move the last entry to the removed spot
      entries[dstIndex] = entries[srcIndex];
      entries[srcIndex] = 0;

      // update whoever's "next" pointer was pointing to the last entry place
      int tableIndex = smearedHash(key) & mask;
      int next = CompactHashing.tableGet(table, tableIndex);
      int srcNext = srcIndex + 1;
      if (next == srcNext) {
        // Update the root pointer
        CompactHashing.tableSet(table, tableIndex, dstIndex + 1);
      } else {
        // Update a pointer in an entry
        int entryIndex;
        int entry;
        do {
          entryIndex = next - 1;
          entry = entries[entryIndex];
          next = CompactHashing.getNext(entry, mask);
        } while (next != srcNext);
        // Update entries[entryIndex] to point to the new location
        entries[entryIndex] = CompactHashing.maskCombine(entry, dstIndex + 1, mask);
      }
    } else {
      storage.setKey(dstIndex, null);
      storage.setValue(dstIndex, null);
      entries[dstIndex] = 0;
    }
  }

  /**
   * Checks if the bucket at the given hash is long enough to trigger hash flooding detection.
   *
   * @param hash the hash value
   * @param mask the current hash table mask
   * @param storage the storage
   * @param maxBucketLength the maximum allowed bucket length
   * @param <K> the type of keys
   * @param <V> the type of values
   * @return true if the bucket is long enough to warrant conversion to a more resistant
   *     implementation
   */
  static <K extends @Nullable Object, V extends @Nullable Object> boolean isBucketTooLong(
      int hash,
      int mask,
      CompactHashMapStorage<K, V> storage,
      int maxBucketLength,
      int bucketLength) {
    return bucketLength >= maxBucketLength;
  }

  /**
   * Inserts an entry into the storage at the given index with the given key and value.
   *
   * @param storage the storage containing the hash map data
   * @param entryIndex the position to insert
   * @param key the key to insert
   * @param value the value to insert
   * @param hash the hash of the key
   * @param mask the current hash table mask
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  static <K extends @Nullable Object, V extends @Nullable Object> void insertEntry(
      CompactHashMapStorage<K, V> storage,
      int entryIndex,
      K key,
      V value,
      int hash,
      int mask) {
    storage.setEntry(entryIndex, CompactHashing.maskCombine(hash, UNSET, mask));
    storage.setKey(entryIndex, key);
    storage.setValue(entryIndex, value);
  }
}
