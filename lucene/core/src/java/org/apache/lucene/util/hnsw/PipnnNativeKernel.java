/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.hnsw;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Experimental: fp16 dot-product kernels supplied by a native library (see {@code
 * bench/pipnn_f16.c}), loaded from the path in the {@code pipnn.nativeLib} system property. When
 * the property is unset, {@link #AVAILABLE} is false and float16 vectors are decoded to float32 at
 * gather time instead.
 */
@SuppressWarnings("restricted")
public final class PipnnNativeKernel {
  public static final boolean AVAILABLE;
  private static final MethodHandle DOT16;
  private static final MethodHandle DOT_PRODUCTS16;

  static {
    String lib = System.getProperty("pipnn.nativeLib");
    MethodHandle dot = null, dots = null;
    if (lib != null) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(lib), Arena.global());
      dot =
          linker.downcallHandle(
              lookup.find("pipnn_dot16").orElseThrow(),
              FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, ADDRESS, JAVA_INT),
              Linker.Option.critical(true));
      dots =
          linker.downcallHandle(
              lookup.find("pipnn_dotproducts16").orElseThrow(),
              FunctionDescriptor.ofVoid(
                  ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                  ADDRESS),
              Linker.Option.critical(true));
    }
    DOT16 = dot;
    DOT_PRODUCTS16 = dots;
    AVAILABLE = dot != null;
  }

  private PipnnNativeKernel() {}

  /** Dot product of {@code dim} fp16 values of {@code a} from {@code aOff} and of {@code b}. */
  public static float dot16(short[] a, int aOff, short[] b, int bOff, int dim) {
    try {
      return (float)
          DOT16.invokeExact(
              MemorySegment.ofArray(a).asSlice(2L * aOff),
              MemorySegment.ofArray(b).asSlice(2L * bOff),
              dim);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** Same contract as {@link org.apache.lucene.util.VectorUtil#dotProducts}, over fp16 rows. */
  public static void dotProducts16(
      short[] a,
      int aOffset,
      int aCount,
      short[] b,
      int bOffset,
      int bCount,
      int dim,
      int stride,
      float[] out) {
    try {
      DOT_PRODUCTS16.invokeExact(
          MemorySegment.ofArray(a),
          aOffset,
          aCount,
          MemorySegment.ofArray(b),
          bOffset,
          bCount,
          dim,
          stride,
          MemorySegment.ofArray(out));
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
