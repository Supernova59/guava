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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.annotations.GwtIncompatible;
import junit.framework.TestCase;
import org.jspecify.annotations.NullUnmarked;

/**
 * Tests for storage and helper classes created to decompose CompactHashMap and CompactHashSet.
 *
 * @author Noé Le Van Canh dit Ban
 */
@GwtIncompatible // depends on internals
@NullUnmarked
public class CompactHashStorageTest extends TestCase {

  public void testCompactHashMapStorageAllocation() {
    CompactHashMapStorage<String, String> storage =
        new CompactHashMapStorage<>(16);
    
    // Initially, arrays should be null (lazy allocation)
    assertThat(storage.needsAllocArrays()).isTrue();
    
    // Allocate arrays
    int expectedSize = storage.allocArrays();
    assertThat(expectedSize).isEqualTo(16);
    assertThat(storage.needsAllocArrays()).isFalse();
  }

  public void testCompactHashMapStorageResize() {
    CompactHashMapStorage<String, String> storage = new CompactHashMapStorage<>(4);
    storage.allocArrays();
    
    int originalCapacity = storage.requireEntries().length;
    assertThat(originalCapacity).isEqualTo(4);
    
    // Trigger resize
    storage.resizeMeMaybe(20);
    
    int newCapacity = storage.requireEntries().length;
    assertThat(newCapacity).isGreaterThan(originalCapacity);
  }

  public void testCompactHashMapStorageKeyValueAccess() {
    CompactHashMapStorage<String, String> storage = new CompactHashMapStorage<>(8);
    int allocSize = storage.allocArrays();
    
    // Test key/value setters and getters
    storage.setKey(0, "key1");
    storage.setValue(0, "value1");
    
    assertThat(storage.key(0)).isEqualTo("key1");
    assertThat(storage.value(0)).isEqualTo("value1");
  }

  public void testCompactHashSetStorageElementAccess() {
    CompactHashSetStorage<String> storage = new CompactHashSetStorage<>(8);
    int allocSize = storage.allocArrays();
    
    // Test element setters and getters
    storage.setElement(0, "element1");
    assertThat(storage.element(0)).isEqualTo("element1");
  }

  public void testCompactHashMapStorageMetadata() {
    CompactHashMapStorage<String, String> storage = new CompactHashMapStorage<>(8);
    storage.allocArrays();
    
    // Test increment modification count
    int metadataBefore = storage.metadata;
    storage.incrementModCount();
    
    assertThat(storage.metadata).isNotEqualTo(metadataBefore);
  }

  public void testCompactHashMapStorageEntry() {
    CompactHashMapStorage<String, String> storage = new CompactHashMapStorage<>(8);
    storage.allocArrays();
    
    // Test entry setters and getters
    storage.setEntry(0, 42);
    assertThat(storage.entry(0)).isEqualTo(42);
  }

  public void testAbstractCompactHashStorageInheritance() {
    // Verify that CompactHashMapStorage properly extends AbstractCompactHashStorage
    CompactHashMapStorage<String, String> mapStorage = new CompactHashMapStorage<>(4);
    assertThat(mapStorage).isInstanceOf(AbstractCompactHashStorage.class);
    
    // Verify that CompactHashSetStorage properly extends AbstractCompactHashStorage
    CompactHashSetStorage<String> setStorage = new CompactHashSetStorage<>(4);
    assertThat(setStorage).isInstanceOf(AbstractCompactHashStorage.class);
  }
}
