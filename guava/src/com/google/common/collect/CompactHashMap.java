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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.CollectPreconditions.checkRemove;
import static com.google.common.collect.CompactHashing.UNSET;
import static com.google.common.collect.Hashing.smearedHash;
import static com.google.common.collect.NullnessCasts.uncheckedCastNullableTToT;
import static com.google.common.collect.NullnessCasts.unsafeNull;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.J2ktIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.LazyInit;
import com.google.j2objc.annotations.WeakOuter;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * CompactHashMap is an implementation of a Map. All optional operations (put and remove) are
 * supported. Null keys and values are supported.
 *
 * <p>{@code containsKey(k)}, {@code put(k, v)} and {@code remove(k)} are all (expected and
 * amortized) constant time operations. Expected in the hashtable sense (depends on the hash
 * function doing a good job of distributing the elements to the buckets to a distribution not far
 * from uniform), and amortized since some operations can trigger a hash table resize.
 *
 * <p>Unlike {@code java.util.HashMap}, iteration is only proportional to the actual {@code size()},
 * which is optimal, and <i>not</i> the size of the internal hashtable, which could be much larger
 * than {@code size()}. Furthermore, this structure places significantly reduced load on the garbage
 * collector by only using a constant number of internal objects.
 *
 * <p>If there are no removals, then iteration order for the {@link #entrySet}, {@link #keySet}, and
 * {@link #values} views is the same as insertion order. Any removal invalidates any ordering
 * guarantees.
 *
 * <p>This class should not be assumed to be universally superior to {@code java.util.HashMap}.
 * Generally speaking, this class reduces object allocation and memory consumption at the price of
 * moderately increased constant factors of CPU. Only use this class when there is a specific reason
 * to prioritize memory over CPU.
 *
 * @author Louis Wasserman
 * @author Jon Noack
 */
@GwtIncompatible // not worth using in GWT for now
class CompactHashMap<K extends @Nullable Object, V extends @Nullable Object>
    extends AbstractMap<K, V> implements Serializable {
  /*
   * TODO: Make this a drop-in replacement for j.u. versions, actually drop them in, and test the
   * world. Figure out what sort of space-time tradeoff we're actually going to get here with the
   * *Map variants. This class is particularly hard to benchmark, because the benefit is not only in
   * less allocation, but also having the GC do less work to scan the heap because of fewer
   * references, which is particularly hard to quantify.
   */

  /** Creates an empty {@code CompactHashMap} instance. */
  public static <K extends @Nullable Object, V extends @Nullable Object>
      CompactHashMap<K, V> create() {
    return new CompactHashMap<>();
  }

  /**
   * Creates a {@code CompactHashMap} instance, with a high enough "initial capacity" that it
   * <i>should</i> hold {@code expectedSize} elements without growth.
   *
   * @param expectedSize the number of elements you expect to add to the returned set
   * @return a new, empty {@code CompactHashMap} with enough capacity to hold {@code expectedSize}
   *     elements without resizing
   * @throws IllegalArgumentException if {@code expectedSize} is negative
   */
  public static <K extends @Nullable Object, V extends @Nullable Object>
      CompactHashMap<K, V> createWithExpectedSize(int expectedSize) {
    return new CompactHashMap<>(expectedSize);
  }

  private static final Object NOT_FOUND = new Object();

  // Delegate to external storage (outside base class since it's Map-specific)
  private transient CompactHashMapStorage<K, V> storage;

  // Note: All hash table structure management (table, entries, elements, metadata, size)
  // is now inherited from AbstractCompactHash to eliminate duplication with CompactHashSet

  /** Constructs a new empty instance of {@code CompactHashMap}. */
  CompactHashMap() {
    this.storage = new CompactHashMapStorage<>(CompactHashing.DEFAULT_SIZE);
  }

  /**
   * Constructs a new instance of {@code CompactHashMap} with the specified capacity.
   *
   * @param expectedSize the initial capacity of this {@code CompactHashMap}.
   */
  CompactHashMap(int expectedSize) {
    this.storage = new CompactHashMapStorage<>(expectedSize);
  }

  /** Pseudoconstructor for serialization support. */
  void init(int expectedSize) {
    this.storage = new CompactHashMapStorage<>(expectedSize);
  }

  /** Returns whether arrays need to be allocated. */
  boolean needsAllocArrays() {
    return storage.needsAllocArrays();
  }

  /** Handle lazy allocation of arrays. */
  @CanIgnoreReturnValue
  int allocArrays() {
    return storage.allocArrays();
  }

  @SuppressWarnings("unchecked")
  @VisibleForTesting
  @Nullable Map<K, V> delegateOrNull() {
    return storage.delegateOrNull();
  }

  Object requireTable() {
    return storage.requireTable();
  }

  int[] requireEntries() {
    return storage.requireEntries();
  }

  @Nullable
  Object[] requireKeys() {
    return storage.requireKeys();
  }

  @Nullable
  Object[] requireValues() {
    return storage.requireValues();
  }

  int entry(int index) {
    return storage.entry(index);
  }

  void setEntry(int index, int value) {
    storage.setEntry(index, value);
  }

  K key(int index) {
    return storage.key(index);
  }

  void setKey(int index, K value) {
    storage.setKey(index, value);
  }

  @Nullable
  V value(int index) {
    return storage.value(index);
  }

  void setValue(int index, @Nullable V value) {
    storage.setValue(index, value);
  }

  Map<K, V> createHashFloodingResistantDelegate(int tableSize) {
    return CompactHashMapSerialization.createHashFloodingResistantDelegate(tableSize);
  }

  @CanIgnoreReturnValue
  Map<K, V> convertToHashFloodingResistantImplementation() {
    return CompactHashMapSerialization.convertToHashFloodingResistantImplementation(this, storage);
  }

  void incrementModCount() {
    storage.incrementModCount();
  }

  /**
   * Mark an access of the specified entry. Used only in {@code CompactLinkedHashMap} for LRU
   * ordering.
   */
  void accessEntry(int index) {
    // no-op by default
  }

  @CanIgnoreReturnValue
  @Override
  public @Nullable V put(@ParametricNullness K key, @ParametricNullness V value) {
    Object result = CompactHashMapMutations.put(key, value, storage, this);
    if (result == CompactHashMapMutations.NOT_FOUND) {
      return null;
    }
    @SuppressWarnings("unchecked")
    V oldValue = (V) result;
    return oldValue;
  }

  /**
   * Creates a fresh entry with the specified object at the specified position in the entry arrays.
   */
  void insertEntry(
      int entryIndex, @ParametricNullness K key, @ParametricNullness V value, int hash, int mask) {
    this.setEntry(entryIndex, CompactHashing.maskCombine(hash, UNSET, mask));
    this.setKey(entryIndex, key);
    this.setValue(entryIndex, value);
  }

  /** Resizes the entries storage if necessary. */
  private void resizeMeMaybe(int newSize) {
    int entriesSize = requireEntries().length;
    if (newSize > entriesSize) {
      // 1.5x but round up to nearest odd (this is optimal for memory consumption on Android)
      int newCapacity = min(CompactHashing.MAX_SIZE, (entriesSize + max(1, entriesSize >>> 1)) | 1);
      if (newCapacity != entriesSize) {
        resizeEntries(newCapacity);
      }
    }
  }

  /**
   * Resizes the internal entries array to the specified capacity, which may be greater or less than
   * the current capacity.
   */
  void resizeEntries(int newCapacity) {
    storage.entries = Arrays.copyOf(requireEntries(), newCapacity);
    storage.keys = Arrays.copyOf(requireKeys(), newCapacity);
    storage.values = Arrays.copyOf(requireValues(), newCapacity);
  }

  @CanIgnoreReturnValue
  private int resizeTable(int oldMask, int newCapacity, int targetHash, int targetEntryIndex) {
    Object newTable = CompactHashing.createTable(newCapacity);
    int newMask = newCapacity - 1;

    if (targetEntryIndex != UNSET) {
      // Add target first; it must be last in the chain because its entry hasn't yet been created
      CompactHashing.tableSet(newTable, targetHash & newMask, targetEntryIndex + 1);
    }

    Object oldTable = requireTable();
    int[] entries = requireEntries();

    // Loop over `oldTable` to construct its replacement, ``newTable`. The entries do not move, so
    // the `keys` and `values` arrays do not need to change. But because the "short hash" now has a
    // different number of bits, we must rewrite each element of `entries` so that its contribution
    // to the full hashcode reflects the change, and so that its `next` link corresponds to the new
    // linked list of entries with the new short hash.
    for (int oldTableIndex = 0; oldTableIndex <= oldMask; oldTableIndex++) {
      int oldNext = CompactHashing.tableGet(oldTable, oldTableIndex);
      // Each element of `oldTable` is the head of a (possibly empty) linked list of elements in
      // `entries`. The `oldNext` loop is going to traverse that linked list.
      // We need to rewrite the `next` link of each of the elements so that it is in the appropriate
      // linked list starting from `newTable`. In general, each element from the old linked list
      // belongs to a different linked list from `newTable`. We insert each element in turn at the
      // head of its appropriate `newTable` linked list.
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

  private int indexOf(@Nullable Object key) {
    return CompactHashMapInternals.indexOf(key, storage);
  }

  @Override
  public boolean containsKey(@Nullable Object key) {
    Map<K, V> delegate = delegateOrNull();
    return (delegate != null) ? delegate.containsKey(key) : indexOf(key) != -1;
  }

  @Override
  public @Nullable V get(@Nullable Object key) {
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      return delegate.get(key);
    }
    int index = indexOf(key);
    if (index == -1) {
      return null;
    }
    accessEntry(index);
    return value(index);
  }

  @CanIgnoreReturnValue
  @SuppressWarnings("unchecked") // known to be a V
  @Override
  public @Nullable V remove(@Nullable Object key) {
    Object result = CompactHashMapMutations.remove(key, storage);
    return (V) result;
  }

  /**
   * Moves the last entry in the entry array into {@code dstIndex}, and nulls out its old position.
   */
  void moveLastEntry(int dstIndex, int mask) {
    Object table = requireTable();
    int[] entries = requireEntries();
    @Nullable Object[] keys = requireKeys();
    @Nullable Object[] values = requireValues();
    int srcIndex = size() - 1;
    if (dstIndex < srcIndex) {
      // move last entry to deleted spot
      Object key = keys[srcIndex];
      keys[dstIndex] = key;
      values[dstIndex] = values[srcIndex];
      keys[srcIndex] = null;
      values[srcIndex] = null;

      // move the last entry to the removed spot, just like we moved the element
      entries[dstIndex] = entries[srcIndex];
      entries[srcIndex] = 0;

      // also need to update whoever's "next" pointer was pointing to the last entry place
      int tableIndex = smearedHash(key) & mask;
      int next = CompactHashing.tableGet(table, tableIndex);
      int srcNext = srcIndex + 1;
      if (next == srcNext) {
        // we need to update the root pointer
        CompactHashing.tableSet(table, tableIndex, dstIndex + 1);
      } else {
        // we need to update a pointer in an entry
        int entryIndex;
        int entry;
        do {
          entryIndex = next - 1;
          entry = entries[entryIndex];
          next = CompactHashing.getNext(entry, mask);
        } while (next != srcNext);
        // here, entries[entryIndex] points to the old entry location; update it
        entries[entryIndex] = CompactHashing.maskCombine(entry, dstIndex + 1, mask);
      }
    } else {
      keys[dstIndex] = null;
      values[dstIndex] = null;
      entries[dstIndex] = 0;
    }
  }

  int firstEntryIndex() {
    return AbstractCompactHashStructure.computeFirstEntryIndex(storage.size);
  }

  int getSuccessor(int entryIndex) {
    return AbstractCompactHashStructure.computeSuccessor(entryIndex, storage.size);
  }

  /**
   * Updates the index an iterator is pointing to after a call to remove: returns the index of the
   * entry that should be looked at after a removal on indexRemoved, with indexBeforeRemove as the
   * index that *was* the next entry that would be looked at.
   */
  int adjustAfterRemove(int indexBeforeRemove, @SuppressWarnings("unused") int indexRemoved) {
    return AbstractCompactHashStructure.computeAdjustedIndex(indexBeforeRemove);
  }

  private abstract class Itr<T extends @Nullable Object> implements Iterator<T> {
    int expectedMetadata = storage.metadata;
    int currentIndex = firstEntryIndex();
    int indexToRemove = -1;

    @Override
    public boolean hasNext() {
      return currentIndex >= 0;
    }

    @ParametricNullness
    abstract T getOutput(int entry);

    @Override
    @ParametricNullness
    public T next() {
      checkForConcurrentModification();
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      indexToRemove = currentIndex;
      T result = getOutput(currentIndex);
      currentIndex = getSuccessor(currentIndex);
      return result;
    }

    @Override
    public void remove() {
      checkForConcurrentModification();
      checkRemove(indexToRemove >= 0);
      incrementExpectedModCount();
      CompactHashMap.this.remove(key(indexToRemove));
      currentIndex = adjustAfterRemove(currentIndex, indexToRemove);
      indexToRemove = -1;
    }

    void incrementExpectedModCount() {
      expectedMetadata += CompactHashing.MODIFICATION_COUNT_INCREMENT;
    }

    private void checkForConcurrentModification() {
      if (storage.metadata != expectedMetadata) {
        throw new ConcurrentModificationException();
      }
    }
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    checkNotNull(function);
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      delegate.replaceAll(function);
    } else {
      for (int i = 0; i < storage.size; i++) {
        setValue(i, function.apply(key(i), value(i)));
      }
    }
  }

  @LazyInit private transient @Nullable Set<K> keySetView;

  @Override
  public Set<K> keySet() {
    return (keySetView == null) ? keySetView = createKeySet() : keySetView;
  }

  Set<K> createKeySet() {
    return new KeySetView();
  }

  @WeakOuter
  class KeySetView extends Maps.KeySet<K, V> {
    KeySetView() {
      super(CompactHashMap.this);
    }

    @Override
    public @Nullable Object[] toArray() {
      if (needsAllocArrays()) {
        return new Object[0];
      }
      Map<K, V> delegate = delegateOrNull();
      return (delegate != null)
          ? delegate.keySet().toArray()
          : ObjectArrays.copyAsObjectArray(requireKeys(), 0, storage.size);
    }

    @Override
    @SuppressWarnings("nullness") // b/192354773 in our checker affects toArray declarations
    public <T extends @Nullable Object> T[] toArray(T[] a) {
      if (needsAllocArrays()) {
        if (a.length > 0) {
          @Nullable Object[] unsoundlyCovariantArray = a;
          unsoundlyCovariantArray[0] = null;
        }
        return a;
      }
      Map<K, V> delegate = delegateOrNull();
      return (delegate != null)
          ? delegate.keySet().toArray(a)
          : ObjectArrays.toArrayImpl(requireKeys(), 0, storage.size, a);
    }

    @Override
    public boolean remove(@Nullable Object o) {
      Map<K, V> delegate = delegateOrNull();
      if (delegate != null) {
        return delegate.keySet().remove(o);
      }
      Object result = CompactHashMapMutations.remove(o, storage);
      return result != null;
    }

    @Override
    public Iterator<K> iterator() {
      return keySetIterator();
    }

    @Override
    public Spliterator<K> spliterator() {
      if (needsAllocArrays()) {
        return Spliterators.spliterator(new Object[0], Spliterator.DISTINCT | Spliterator.ORDERED);
      }
      Map<K, V> delegate = delegateOrNull();
      return (delegate != null)
          ? delegate.keySet().spliterator()
          : Spliterators.spliterator(
              requireKeys(), 0, storage.size, Spliterator.DISTINCT | Spliterator.ORDERED);
    }

    @Override
    public void forEach(Consumer<? super K> action) {
      checkNotNull(action);
      Map<K, V> delegate = delegateOrNull();
      if (delegate != null) {
        delegate.keySet().forEach(action);
      } else {
        for (int i = firstEntryIndex(); i >= 0; i = getSuccessor(i)) {
          action.accept(key(i));
        }
      }
    }
  }

  Iterator<K> keySetIterator() {
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      return delegate.keySet().iterator();
    }
    return new Itr<K>() {
      @Override
      @ParametricNullness
      K getOutput(int entry) {
        return key(entry);
      }
    };
  }

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    checkNotNull(action);
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      delegate.forEach(action);
    } else {
      for (int i = firstEntryIndex(); i >= 0; i = getSuccessor(i)) {
        action.accept(key(i), value(i));
      }
    }
  }

  @LazyInit private transient @Nullable Set<Entry<K, V>> entrySetView;

  @Override
  public Set<Entry<K, V>> entrySet() {
    return (entrySetView == null) ? entrySetView = createEntrySet() : entrySetView;
  }

  Set<Entry<K, V>> createEntrySet() {
    return new EntrySetView();
  }

  @WeakOuter
  class EntrySetView extends Maps.EntrySet<K, V> {
    @Override
    Map<K, V> map() {
      return CompactHashMap.this;
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return entrySetIterator();
    }

    @Override
    public Spliterator<Entry<K, V>> spliterator() {
      Map<K, V> delegate = delegateOrNull();
      return (delegate != null)
          ? delegate.entrySet().spliterator()
          : CollectSpliterators.indexed(
              storage.size, Spliterator.DISTINCT | Spliterator.ORDERED, MapEntry::new);
    }

    @Override
    public boolean contains(@Nullable Object o) {
      Map<K, V> delegate = delegateOrNull();
      if (delegate != null) {
        return delegate.entrySet().contains(o);
      } else if (o instanceof Entry) {
        Entry<?, ?> entry = (Entry<?, ?>) o;
        int index = indexOf(entry.getKey());
        return index != -1 && Objects.equals(value(index), entry.getValue());
      }
      return false;
    }

    @Override
    public boolean remove(@Nullable Object o) {
      Map<K, V> delegate = delegateOrNull();
      if (delegate != null) {
        return delegate.entrySet().remove(o);
      } else if (o instanceof Entry) {
        Entry<?, ?> entry = (Entry<?, ?>) o;
        if (needsAllocArrays()) {
          return false;
        }
        int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
        int index =
            CompactHashing.remove(
                entry.getKey(),
                entry.getValue(),
                mask,
                requireTable(),
                requireEntries(),
                requireKeys(),
                requireValues());
        if (index == -1) {
          return false;
        }

        moveLastEntry(index, mask);
        storage.size--;
        incrementModCount();

        return true;
      }
      return false;
    }
  }

  Iterator<Entry<K, V>> entrySetIterator() {
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      return delegate.entrySet().iterator();
    }
    return new Itr<Entry<K, V>>() {
      @Override
      Entry<K, V> getOutput(int entry) {
        return new MapEntry(entry);
      }
    };
  }

  final class MapEntry extends AbstractMapEntry<K, V> {
    @ParametricNullness private final K key;

    private int lastKnownIndex;

    MapEntry(int index) {
      this.key = key(index);
      this.lastKnownIndex = index;
    }

    @Override
    @ParametricNullness
    public K getKey() {
      return key;
    }

    private void updateLastKnownIndex() {
      if (lastKnownIndex == -1
          || lastKnownIndex >= size()
          || !Objects.equals(key, key(lastKnownIndex))) {
        lastKnownIndex = indexOf(key);
      }
    }

    @Override
    @ParametricNullness
    public V getValue() {
      Map<K, V> delegate = delegateOrNull();
      if (delegate != null) {
        /*
         * The cast is safe because the entry is present in the map. Or, if it has been removed by a
         * concurrent modification, behavior is undefined.
         */
        return uncheckedCastNullableTToT(delegate.get(key));
      }
      updateLastKnownIndex();
      /*
       * If the entry has been removed from the map, we return null, even though that might not be a
       * valid value. That's the best we can do, short of holding a reference to the most recently
       * seen value. And while we *could* do that, we aren't required to: Map.Entry explicitly says
       * that behavior is undefined when the backing map is modified through another API. (It even
       * permits us to throw IllegalStateException. Maybe we should have done that, but we probably
       * shouldn't change now for fear of breaking people.)
       */
      return (lastKnownIndex == -1) ? unsafeNull() : value(lastKnownIndex);
    }

    @Override
    @ParametricNullness
    public V setValue(@ParametricNullness V value) {
      Map<K, V> delegate = delegateOrNull();
      if (delegate != null) {
        return uncheckedCastNullableTToT(delegate.put(key, value)); // See discussion in getValue().
      }
      updateLastKnownIndex();
      if (lastKnownIndex == -1) {
        put(key, value);
        return unsafeNull(); // See discussion in getValue().
      } else {
        V old = value(lastKnownIndex);
        CompactHashMap.this.setValue(lastKnownIndex, value);
        return old;
      }
    }
  }

  @Override
  public int size() {
    Map<K, V> delegate = delegateOrNull();
    return (delegate != null) ? delegate.size() : storage.size;
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public boolean containsValue(@Nullable Object value) {
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      return delegate.containsValue(value);
    }
    for (int i = 0; i < storage.size; i++) {
      if (Objects.equals(value, value(i))) {
        return true;
      }
    }
    return false;
  }

  @LazyInit private transient @Nullable Collection<V> valuesView;

  @Override
  public Collection<V> values() {
    return (valuesView == null) ? valuesView = createValues() : valuesView;
  }

  Collection<V> createValues() {
    return new ValuesView();
  }

  @WeakOuter
  class ValuesView extends Maps.Values<K, V> {
    ValuesView() {
      super(CompactHashMap.this);
    }

    @Override
    public Iterator<V> iterator() {
      return valuesIterator();
    }

    @Override
    public void forEach(Consumer<? super V> action) {
      checkNotNull(action);
      Map<K, V> delegate = delegateOrNull();
      if (delegate != null) {
        delegate.values().forEach(action);
      } else {
        for (int i = firstEntryIndex(); i >= 0; i = getSuccessor(i)) {
          action.accept(value(i));
        }
      }
    }

    @Override
    public Spliterator<V> spliterator() {
      if (needsAllocArrays()) {
        return Spliterators.spliterator(new Object[0], Spliterator.ORDERED);
      }
      Map<K, V> delegate = delegateOrNull();
      return (delegate != null)
          ? delegate.values().spliterator()
          : Spliterators.spliterator(requireValues(), 0, storage.size, Spliterator.ORDERED);
    }

    @Override
    public @Nullable Object[] toArray() {
      if (needsAllocArrays()) {
        return new Object[0];
      }
      Map<K, V> delegate = delegateOrNull();
      return (delegate != null)
          ? delegate.values().toArray()
          : ObjectArrays.copyAsObjectArray(requireValues(), 0, storage.size);
    }

    @Override
    @SuppressWarnings("nullness") // b/192354773 in our checker affects toArray declarations
    public <T extends @Nullable Object> T[] toArray(T[] a) {
      if (needsAllocArrays()) {
        if (a.length > 0) {
          @Nullable Object[] unsoundlyCovariantArray = a;
          unsoundlyCovariantArray[0] = null;
        }
        return a;
      }
      Map<K, V> delegate = delegateOrNull();
      return (delegate != null)
          ? delegate.values().toArray(a)
          : ObjectArrays.toArrayImpl(requireValues(), 0, storage.size, a);
    }
  }

  Iterator<V> valuesIterator() {
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      return delegate.values().iterator();
    }
    return new Itr<V>() {
      @Override
      @ParametricNullness
      V getOutput(int entry) {
        return value(entry);
      }
    };
  }

  /**
   * Ensures that this {@code CompactHashMap} has the smallest representation in memory, given its
   * current size.
   */
  public void trimToSize() {
    if (needsAllocArrays()) {
      return;
    }
    Map<K, V> delegate = delegateOrNull();
    if (delegate != null) {
      Map<K, V> newDelegate = createHashFloodingResistantDelegate(size());
      newDelegate.putAll(delegate);
      storage.table = newDelegate;
      return;
    }
    int size = storage.size;
    if (size < requireEntries().length) {
      resizeEntries(size);
    }
    int minimumTableSize = CompactHashing.tableSize(size);
    int mask = AbstractCompactHashStructure.extractHashTableMask(storage.metadata);
    if (minimumTableSize < mask) { // smaller table size will always be less than current mask
      resizeTable(mask, minimumTableSize, UNSET, UNSET);
    }
  }

  @Override
  public void clear() {
    CompactHashMapMutations.clear(storage);
  }

  @J2ktIncompatible
  private void writeObject(ObjectOutputStream stream) throws IOException {
    stream.defaultWriteObject();
    stream.writeInt(size());
    Iterator<Entry<K, V>> entryIterator = entrySetIterator();
    while (entryIterator.hasNext()) {
      Entry<K, V> e = entryIterator.next();
      stream.writeObject(e.getKey());
      stream.writeObject(e.getValue());
    }
  }

  @SuppressWarnings("unchecked")
  @J2ktIncompatible
  private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
    stream.defaultReadObject();
    int elementCount = stream.readInt();
    if (elementCount < 0) {
      throw new InvalidObjectException("Invalid size: " + elementCount);
    }
    init(elementCount);
    for (int i = 0; i < elementCount; i++) {
      K key = (K) stream.readObject();
      V value = (V) stream.readObject();
      put(key, value);
    }
  }

  /*
   * The following methods are safe to call as long as both of the following hold:
   *
   * - allocArrays() has been called. Callers can confirm this by checking needsAllocArrays().
   *
   * - The map has not switched to delegating to a java.util implementation to mitigate hash
   *   flooding. Callers can confirm this by null-checking delegateOrNull().
   *
   * In an ideal world, we would document why we know those things are true every time we call these
   * methods. But that is a bit too painful....
   */
}
