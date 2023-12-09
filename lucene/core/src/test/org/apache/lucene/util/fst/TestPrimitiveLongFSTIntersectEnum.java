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

package org.apache.lucene.util.fst;

import java.io.IOException;
import java.util.HashMap;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunnable;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.RegExp;
import org.apache.lucene.util.automaton.Transition;
import org.apache.lucene.util.automaton.TransitionAccessor;

public class TestPrimitiveLongFSTIntersectEnum extends LuceneTestCase {

  public void testBasics() throws IOException {
    String[] testTerms = {
      "aaa", "abb", "acc", "accd", "acce", "accf", "ada", "adf", "alf", "azf", "bfg", "caa", "ceb",
      "de", "ff", "gg", "hh", "zz", "zzx", "zzz"
    };

    HashMap<String, Long> termOutputs = new HashMap<>();

    IntsRefBuilder scratchInts = new IntsRefBuilder();
    FSTCompiler<Long> fstCompiler =
        new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, PositiveIntOutputs.getSingleton()).build();

    for (var term : testTerms) {
      long output = random().nextLong(1, 1024);
      termOutputs.put(term, output);
      fstCompiler.add(Util.toIntsRef(new BytesRef(term), scratchInts), output);
      //      System.out.println(term + ": " + output);
    }

    var boxedFst = fstCompiler.compile();

    byte[] metaBytes = new byte[4096];
    byte[] dataBytes = new byte[4096];
    DataOutput metaOut = new ByteArrayDataOutput(metaBytes);
    DataOutput dataOutput = new ByteArrayDataOutput(dataBytes);

    boxedFst.save(metaOut, dataOutput);

    PrimitiveLongFST primitiveLongFst =
        new PrimitiveLongFST(
            PrimitiveLongFST.readMetadata(
                new ByteArrayDataInput(metaBytes),
                PrimitiveLongFST.PrimitiveLongFSTOutputs.getSingleton()),
            new ByteArrayDataInput(dataBytes));

    //    RegExp regExp = new RegExp("a([a-f]|[j-z])c", RegExp.NONE);
    RegExp regExp = new RegExp("a([a-b]|[d-e]|[g-h]|[j-l]|[n-o]|[q-r]|[t-x]|z)f", RegExp.NONE);
    Automaton a = regExp.toAutomaton();
    CompiledAutomaton compiledAutomaton = new CompiledAutomaton(a);

    var byteRunnable = compiledAutomaton.getByteRunnable();
    var transitionAccessor = compiledAutomaton.getTransitionAccessor();
    //    dfsAutomaton(byteRunnable, transitionAccessor, 0, "");

    PrimitiveLongFST.PrimitiveLongArc firstArc = new PrimitiveLongFST.PrimitiveLongArc();
    System.out.println("---- recursive algo ----");
    dfsIntersectFsaFst(
        primitiveLongFst,
        primitiveLongFst.getBytesReader(),
        primitiveLongFst.getFirstArc(firstArc),
        "",
        0,
        byteRunnable,
        transitionAccessor,
        0);

    System.out.println("---- non-recursive algo ----");
    var intersectEnum =
        new PrimitiveLongFSTIntersectEnum(primitiveLongFst, compiledAutomaton, new BytesRef("ahg"));
    while (intersectEnum.next()) {
      String term = intersectEnum.getTerm().utf8ToString();
      long actualOutput = intersectEnum.getFSTOutput();
      System.out.println(
          term + " expected output:" + termOutputs.get(term) + " actual: " + actualOutput);
    }
  }

  void dfs(
      PrimitiveLongFST fst,
      FST.BytesReader in,
      PrimitiveLongFST.PrimitiveLongArc currentLevelNode,
      String path,
      long acc)
      throws IOException {
    if (currentLevelNode.isFinal()) {
      long output = acc + currentLevelNode.output() + currentLevelNode.nextFinalOutput();
      System.out.println(path + (char) currentLevelNode.label() + "raw output: " + output);
    }

    if (PrimitiveLongFST.targetHasArcs(currentLevelNode)) {
      String pathNext =
          currentLevelNode.label() > 0 ? path + (char) currentLevelNode.label() : path;
      long accNext = currentLevelNode.label() > 0 ? acc + currentLevelNode.output() : acc;
      var nextLevelNode = new PrimitiveLongFST.PrimitiveLongArc();
      fst.readFirstRealTargetArc(currentLevelNode.target(), nextLevelNode, in);
      dfs(fst, in, nextLevelNode, pathNext, accNext);
    }

    if (currentLevelNode.isLast() == false) {
      fst.readNextRealArc(currentLevelNode, in);
      dfs(fst, in, currentLevelNode, path, acc);
    }
  }

  public void testAutomaton() {
    RegExp regExp = new RegExp("a([a-f]|[j-z])c", RegExp.NONE);
    Automaton a = regExp.toAutomaton();
    CompiledAutomaton compiledAutomaton = new CompiledAutomaton(a);
    System.out.println("isFinite: " + compiledAutomaton.finite);

    var byteRunnable = compiledAutomaton.getByteRunnable();
    var transitionAccessor = compiledAutomaton.getTransitionAccessor();
    dfsAutomaton(byteRunnable, transitionAccessor, 0, "");
  }

  void dfsAutomaton(
      ByteRunnable a, TransitionAccessor transitionAccessor, int currentLevelState, String path) {
    if (a.isAccept(currentLevelState)) {
      System.out.println(path);
    }

    Transition t = new Transition();
    int currentLevelSize = transitionAccessor.initTransition(currentLevelState, t);
    for (int i = 0; i < currentLevelSize; i++) {
      transitionAccessor.getNextTransition(t);
      System.out.println("At: " + t);
      for (int label = t.min; label <= t.max && Character.isAlphabetic(label); label++) {
        dfsAutomaton(a, transitionAccessor, t.dest, path + (char) label);
      }
    }
  }

  void dfsIntersectFsaFst(
      PrimitiveLongFST fst,
      FST.BytesReader in,
      PrimitiveLongFST.PrimitiveLongArc fstNode,
      String path,
      long acc,
      ByteRunnable a,
      TransitionAccessor transitionAccessor,
      int fsaState)
      throws IOException {

    if (a.isAccept(fsaState) && fstNode.isFinal()) {
      // found
      System.out.println(path + ": " + (acc + fstNode.output() + fstNode.nextFinalOutput()));
    }

    Transition fsaTransition = new Transition();
    int numTransitions = transitionAccessor.initTransition(fsaState, fsaTransition);

    if (numTransitions <= 0 || !PrimitiveLongFST.targetHasArcs(fstNode)) {
      return;
    }

    int transitionUpto = 0;
    var nextLevelFstNode = new PrimitiveLongFST.PrimitiveLongArc();
    fst.readFirstRealTargetArc(fstNode.target(), nextLevelFstNode, in);
    transitionAccessor.getNextTransition(fsaTransition);
    transitionUpto++;

    while (true) {
      if (nextLevelFstNode.label() < fsaTransition.min) {
        // advance FST
        if (nextLevelFstNode.isLast()) {
          // no more eligible FST arc at this level
          break;
        }
        // TODO: advance to first arc that has label >= fsaTransition.min
        nextLevelFstNode = fst.readNextRealArc(nextLevelFstNode, in);
      } else if (nextLevelFstNode.label() > fsaTransition.max) {
        // advance FSA
        if (transitionUpto == numTransitions) {
          // no more eligible FSA transitions at this level
          return;
        }
        // TODO: advance FSA with binary search to fstNode.label()
        transitionAccessor.getNextTransition(fsaTransition);
        transitionUpto++;
      } else {
        // can go deeper
        String pathNext = path + (char) nextLevelFstNode.label();
        long accNext = acc + fstNode.output();
        int nextFsaState = fsaTransition.dest;
        dfsIntersectFsaFst(
            fst, in, nextLevelFstNode, pathNext, accNext, a, transitionAccessor, nextFsaState);
        if (nextLevelFstNode.isLast()) {
          // no more candidate at this prefix
          return;
        } else {
          // TODO: advance to first arc that has label >= fsaTransition.min
          nextLevelFstNode = fst.readNextRealArc(nextLevelFstNode, in);
        }
      }
    }
  }
}
