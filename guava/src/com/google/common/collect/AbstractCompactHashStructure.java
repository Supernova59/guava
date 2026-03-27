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

/**
 * Helper class providing shared implementations for compact hash-based collections.
 * 
 * This class eliminates code duplication between {@link CompactHashMap} and 
 * {@link CompactHashSet} by having both reference these common algorithm implementations.
 * Both classes use identical logic for managing hash table structure, even though they store
 * different types of data (key-value pairs vs. just elements).
 *
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible
final class AbstractCompactHashStructure {
  private AbstractCompactHashStructure() {}

  /**
   * Converts a hash table mask to the number of bits needed to represent it.
   *
   * @param mask the hash table mask
   * @return the number of bits needed
   */
  static int maskToNumBits(int mask) {
    return Integer.SIZE - Integer.numberOfLeadingZeros(mask);
  }

  /**
   * Combines hash table bits with other metadata fields minimally.
   * 
   * <p>This encumbers the bits in the metadata field. Used by both CompactHashMap and
   * CompactHashSet to update the hash table size information stored compactly.
   *
   * @param metadata the current metadata value
   * @param mask the new hash table mask
   * @return updated metadata value
   */
  static int updateHashTableMaskInMetadata(int metadata, int mask) {
    int hashTableBits = maskToNumBits(mask);
    return CompactHashing.maskCombine(metadata, hashTableBits, CompactHashing.HASH_TABLE_BITS_MASK);
  }

  /**
   * Extracts the hash table mask from metadata field.
   * 
   * <p>This was duplicated identically in both CompactHashMap and CompactHashSet.
   * Computes (1 << bits) - 1 where bits are stored in the metadata field.
   *
   * @param metadata the metadata value containing hash table bits
   * @return the hash table mask
   */
  static int extractHashTableMask(int metadata) {
    return (1 << (metadata & CompactHashing.HASH_TABLE_BITS_MASK)) - 1;
  }

  /**
   * Returns the index of the first entry in the collection.
   * 
   * <p>This was duplicated identically in both CompactHashMap and CompactHashSet.
   * Entries are stored sequentially in compact arrays, so first entry is at index 0 if not empty.
   *
   * @param size the number of elements in the collection
   * @return 0 if size > 0, otherwise -1
   */
  static int computeFirstEntryIndex(int size) {
    return size > 0 ? 0 : -1;
  }

  /**
   * Returns the successor index of the given entry.
   * 
   * <p>This was duplicated identically in both CompactHashMap and CompactHashSet.
   * In the compact structure, entries are stored sequentially, so successor is entryIndex + 1.
   *
   * @param entryIndex the current entry index
   * @param size the total number of entries
   * @return entryIndex + 1 if valid, otherwise -1
   */
  static int computeSuccessor(int entryIndex, int size) {
    return (entryIndex + 1 < size) ? entryIndex + 1 : -1;
  }

  /**
   * Computes the adjusted entry index after a removal.
   * 
   * <p>This was duplicated identically in both CompactHashMap and CompactHashSet.
   * When an entry is removed from the middle of the array, subsequent entries shift down by one.
   * This returns the new index for entries that were before the removed one.
   *
   * @param indexBeforeRemove the index before the removal operation
   * @return the adjusted index
   */
  static int computeAdjustedIndex(int indexBeforeRemove) {
    return indexBeforeRemove - 1;
  }
}

