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

package org.apache.lucene.sandbox.codecs.lucene90.randomaccess;

import org.apache.lucene.codecs.lucene90.Lucene90PostingsFormat.IntBlockTermState;
import org.apache.lucene.sandbox.codecs.lucene90.randomaccess.bitpacking.BitPacker;
import org.apache.lucene.sandbox.codecs.lucene90.randomaccess.bitpacking.BitUnpacker;
import org.apache.lucene.util.BytesRef;

interface TermStateCodec {

  /**
   * Encode the sequence of {@link IntBlockTermState}s with the given bitPacker into a block of
   * bytes.
   *
   * @return the metadata associated with the encoded bytes
   */
  byte[] encodeBlock(IntBlockTermState[] inputs, BitPacker bitPacker);

  /**
   * Decode out a {@link IntBlockTermState} with the provided bit-unpacker, metadata byte slice and
   * data byte slice, at the given index within an encoded block.
   *
   * <p>Note: This method expects dataBytes that starts at the start of the block. Also, dataBytes
   * should contain enough bytes (but not necessarily the whole block) to decode at the term state
   * at `index`.
   *
   * @return the decoded term state
   */
  IntBlockTermState decodeWithinBlock(
      BytesRef metadataBytes, BytesRef dataBytes, BitUnpacker bitUnpacker, int index);
}
