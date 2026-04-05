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
 * <p>This class encapsulates all mutable state and allocation/resizing logic for the compact
 * hash set's internal arrays. Extends {@link AbstractCompactHashStorage} to share common
 * storage management with {@link CompactHashMapStorage}.
 *
 * @author Jon Noack
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible
class CompactHashSetStorage<E extends @Nullable Object> extends AbstractCompactHashStorage {

  @Nullable transient Object @Nullable [] elements;

  CompactHashSetStorage(int expectedSize) {
    init(expectedSize);
  }

  @Override
  protected void allocateSpecializedArrays(int expectedSize) {
    this.elements = new Object[expectedSize];
  }

  @Override
  protected void resizeSpecializedArrays(int newCapacity) {
    this.elements = Arrays.copyOf(requireElements(), newCapacity);
  }

  void resizeMeMaybe(int newSize) {
    int entriesSize = requireEntries().length;
    if (newSize > entriesSize) {
      int newCapacity =
          Math.min(
              CompactHashing.MAX_SIZE, (entriesSize + Math.max(1, entriesSize >>> 1)) | 1);
      if (newCapacity != entriesSize) {
        super.resizeMeMaybe(newCapacity);
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Nullable Set<E> delegateOrNull() {
    if (table instanceof java.util.Set) {
      return (java.util.Set<E>) table;
    }
    return null;
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
}
