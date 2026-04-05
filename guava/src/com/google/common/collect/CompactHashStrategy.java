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
import org.jspecify.annotations.Nullable;

/**
 * Strategy interface for hash table operations on compact collections.
 *
 * <p>This interface encapsulates the algorithms for hash table manipulation, allowing
 * different implementations to be swapped based on collection type (Map vs Set).
 * 
 * <p>This design pattern (Strategy) replaces static utility classes and provides
 * better separation of concerns while eliminating tight coupling between
 * hash table algorithms and specific collection types.
 *
 * @param <E> the element type
 * @author Jon Noack
 */
@GwtIncompatible
interface CompactHashStrategy<E extends @Nullable Object> {

  /**
   * Finds the index of an element in the hash table.
   *
   * @param element the element to find
   * @param storage the compact hash storage
   * @return the index if found, -1 otherwise
   */
  int indexOf(@Nullable Object element, CompactHashSetStorage<E> storage);

  /**
   * Resizes the hash table to a new capacity.
   *
   * @param storage the storage to resize
   * @param oldMask the current hash table mask
   * @param newCapacity the new capacity
   * @param targetHash the hash of the element being inserted (if any)
   * @param targetEntryIndex the index where new entry will be placed
   * @return the new mask after resizing
   */
  int resizeTable(
      CompactHashSetStorage<E> storage,
      int oldMask,
      int newCapacity,
      int targetHash,
      int targetEntryIndex);

  /**
   * Moves the last entry to fill a gap after removal.
   *
   * @param storage the storage
   * @param dstIndex the index to fill
   * @param mask the hash table mask
   */
  void moveLastEntry(CompactHashSetStorage<E> storage, int dstIndex, int mask);

  /**
   * Inserts a new entry into the hash table.
   *
   * @param storage the storage
   * @param entryIndex the entry index
   * @param element the element value
   * @param hash the smeared hash
   * @param mask the hash table mask
   */
  void insertEntry(
      CompactHashSetStorage<E> storage,
      int entryIndex,
      E element,
      int hash,
      int mask);
}
