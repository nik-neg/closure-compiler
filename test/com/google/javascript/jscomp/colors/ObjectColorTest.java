/*
 * Copyright 2020 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.colors;

import static com.google.common.truth.Truth.assertThat;
import static com.google.javascript.jscomp.testing.ColorSubject.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ObjectColorTest {
  @Test
  public void isPrimitiveReturnsFalse() {
    ObjectColor foo = ObjectColor.create("Foo", "test.js");

    assertThat(foo.isPrimitive()).isFalse();
  }

  @Test
  public void isObjectReturnsTrue() {
    ObjectColor foo = ObjectColor.create("Foo", "test.js");

    assertThat(foo).isObject();
  }

  @Test
  public void isUnionHandlesReturnsFalse() {
    ObjectColor foo = ObjectColor.create("Foo", "test.js");

    assertThat(foo.isUnion()).isFalse();
  }

  @Test
  public void objectEqualityBasedOnClassAndFileName() {
    assertThat(ObjectColor.create("Foo", "test.js"))
        .isEqualTo(ObjectColor.create("Foo", "test.js"));
  }
}