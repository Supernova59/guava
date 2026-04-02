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

import static com.google.common.collect.CollectPreconditions.checkNonnegative;
import static com.google.common.collect.CompactHashing.UNSET;

import com.google.common.annotations.GwtIncompatible;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates serialization operations for {@link CompactHashSet}.
 *
 * <p>This class handles all serialization and deserialization logic.
 *
 * @author Jon Noack
 */
@GwtIncompatible
final class CompactHashSetSerialization {

  private CompactHashSetSerialization() {}

  static <E extends @Nullable Object> void writeSet(
      CompactHashSetStorage<E> storage, ObjectOutputStream stream) throws IOException {
    for (int i = AbstractCompactHashStructure.computeFirstEntryIndex(storage.size);
         i >= 0;
         i = AbstractCompactHashStructure.computeSuccessor(i, storage.size)) {
      stream.writeObject(storage.element(i));
    }
  }

  static <E extends @Nullable Object> void readSet(
      CompactHashSetStorage<E> storage, ObjectInputStream stream, int size, CompactHashSet<E> set) throws IOException,
      ClassNotFoundException {
    checkNonnegative(size, "size");
    for (int i = 0; i < size; i++) {
      @SuppressWarnings("unchecked")
      E element = (E) stream.readObject();
      set.add(element);
    }
  }
}
