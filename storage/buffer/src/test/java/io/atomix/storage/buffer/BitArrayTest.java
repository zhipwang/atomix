/*
 * Copyright 2015-present Open Networking Laboratory
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
package io.atomix.storage.buffer;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Direct memory bit set test.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@Test
public class BitArrayTest {

  /**
   * Tests the bit array.
   */
  public void testBitArray() {
    BitArray bits = BitArray.allocate(1024);

    for (int i = 0; i < 1024; i++) {
      assertFalse(bits.get(i));
    }

    for (int i = 0; i < 64; i++) {
      bits.set(i);
    }

    for (int i = 64; i < 1024; i++) {
      assertFalse(bits.get(i));
    }

    for (int i = 0; i < 1024; i++) {
      bits.set(i);
    }

    assertEquals(bits.count(), 1024);

    for (int i = 0; i < 1024; i++) {
      assertTrue(bits.get(i));
    }
  }

}
