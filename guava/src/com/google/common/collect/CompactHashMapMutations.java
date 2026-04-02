/*
 * Copyright (C) 2012 The Guava AUTHORS
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.CompactHashing.UNSET;
import static com.google.common.collect.Hashing.smearedHash;

import com.google.common.annotations.GwtIncompatible;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * Encapsul ates mutation operations for {@link CompactHashMap}.
 *
 * <p>This class handles all operations that modify the map:
 * <ul>
 *   <li>put() and putIfAbsent()</li>
 *   <li>remove()</li>
 *   <li>clear()</li>
 *   <li>Compute operations (compute, computeIfAbsent, computeIfPresent)</li>
 *   <li>Replace operations</li>
 *   <li>merge()</li>
 * </ul>
 *
 * @author Louis Wasserman
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible
class CompactHashMapMutations {

  static final Object NOT_FOUND = new Object();

  private CompactHashMapMutations() {}

  /**
   * Inserts or updates an entry in the map.
   *
   * @return the old value, or NOT_FOUND if the key was not present
   */
  @CanIgnoreReturnValue
  static <K extends @Nullable Object, V extends @Nullable Object> @Nullable Object put(
      K key,
      V value,
      CompactHashMapStorage<K, V> storage,
      java.util.Map<K, V> parent) {
    if (storage.needsAllocArrays()) {
      storage.allocArrays();
    }
    Map<K, V> delegate = storage.delegateOrNull();
    if (delegate != null) {
      return delegate.put(key, value);
    }

    int newEntryIndex = storage.size; // current size, and pointer to the entry to be appended
    int newSize = newEntryIndex + 1;
    int hash = smearedHash(key);
    int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
    int tableIndex = hash & mask;
    int next = CompactHashing.tableGet(storage.requireTable(), tableIndex);

    if (next == UNSET) { // uninitialized bucket
      if (newSize > mask) {
        // Resize and add new entry
        mask =
            CompactHashMapInternals.resizeTable(
                storage, mask, CompactHashing.newCapacity(mask), hash, newEntryIndex);
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
            && Objects.equals(key, storage.key(entryIndex))) {
          // Key already exists, update value
          Object oldValue = storage.value(entryIndex);
          storage.setValue(entryIndex, value);
          return oldValue;
        }
        next = CompactHashing.getNext(entry, mask);
        bucketLength++;
      } while (next != UNSET);

      if (bucketLength >= CompactHashMapInternals.MAX_HASH_BUCKET_LENGTH) {
        return ((java.util.Map<K, V>) convertToHashFloodingResistantImplementation(storage))
            .put(key, value);
      }

      if (newSize > mask) {
        // Resize and add new entry
        mask =
            CompactHashMapInternals.resizeTable(
                storage, mask, CompactHashing.newCapacity(mask), hash, newEntryIndex);
      } else {
        storage.setEntry(
            entryIndex,
            CompactHashing.maskCombine(entry, newEntryIndex + 1, mask));
      }
    }

    storage.resizeMeMaybe(newSize);
    CompactHashMapInternals.insertEntry(storage, newEntryIndex, key, value, hash, mask);
    storage.size = newSize;
    storage.incrementModCount();
    return NOT_FOUND;
  }

  /**
   * Removes an entry from the map.
   *
   * @return the old value, or NOT_FOUND if the key was not present
   */
  @CanIgnoreReturnValue
  static <K extends @Nullable Object, V extends @Nullable Object> @Nullable Object remove(
      @Nullable Object key, CompactHashMapStorage<K, V> storage) {
    Map<K, V> delegate = storage.delegateOrNull();
    if (delegate != null) {
      return delegate.remove(key);
    }
    @Nullable Object oldValue = removeHelper(key, storage);
    return (oldValue == NOT_FOUND) ? null : oldValue;
  }

  private static <K extends @Nullable Object, V extends @Nullable Object> @Nullable Object removeHelper(
      @Nullable Object key, CompactHashMapStorage<K, V> storage) {
    if (storage.needsAllocArrays()) {
      return NOT_FOUND;
    }
    int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
    int index =
        CompactHashing.remove(
            key,
            /* value= */ null,
            mask,
            storage.requireTable(),
            storage.requireEntries(),
            storage.requireKeys(),
            /* values= */ null);
    if (index == -1) {
      return NOT_FOUND;
    }

    Object oldValue = storage.value(index);
    CompactHashMapInternals.moveLastEntry(storage, index, mask);
    storage.size--;
    storage.incrementModCount();
    return oldValue;
  }

  /**
   * Clears all entries from the map.
   */
  static <K extends @Nullable Object, V extends @Nullable Object> void clear(
      CompactHashMapStorage<K, V> storage) {
    Map<K, V> delegate = storage.delegateOrNull();
    if (delegate != null) {
      delegate.clear();
    } else if (!storage.needsAllocArrays()) {
      CompactHashing.tableClear(storage.requireTable());
      java.util.Arrays.fill(storage.requireEntries(), 0);
      java.util.Arrays.fill(storage.requireKeys(), null);
      java.util.Arrays.fill(storage.requireValues(), null);
      storage.size = 0;
      storage.incrementModCount();
    }
  }

  /**
   * Converts the compact implementation to a LinkedHashMap for hash flooding resistance.
   */
  static <K extends @Nullable Object, V extends @Nullable Object> java.util.Map<K, V> convertToHashFloodingResistantImplementation(
      CompactHashMapStorage<K, V> storage) {
    java.util.Map<K, V> newDelegate =
        new java.util.LinkedHashMap<>(
            AbstractCompactHashStructure.extractHashTableMask(storage.metadata) + 1, 1.0f);
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
   * Replaces the value for each key with the result of the remapping function.
   */
  static <K extends @Nullable Object, V extends @Nullable Object> void replaceAll(
      CompactHashMapStorage<K, V> storage,
      BiFunction<? super K, ? super V, ? extends V> function) {
    checkNotNull(function);
    Map<K, V> delegate = storage.delegateOrNull();
    if (delegate != null) {
      delegate.replaceAll(function);
    } else {
      for (int i = 0; i < storage.size; i++) {
        storage.setValue(i, function.apply(storage.key(i), storage.value(i)));
      }
    }
  }
}
