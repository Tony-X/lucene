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
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.automaton.ByteRunnable;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.Transition;
import org.apache.lucene.util.automaton.TransitionAccessor;
import org.apache.lucene.util.fst.PrimitiveLongFST.PrimitiveLongArc;

/**
 * Can next() through the terms defined by the intersection of a {@link PrimitiveLongFST}
 *
 * <p>and {@link org.apache.lucene.util.automaton.CompiledAutomaton}.
 *
 * <p><b>Note: this can only seek forward.</b>
 *
 * @lucene.experimental
 */
public final class PrimitiveLongFSTIntersectEnum {

  private final PrimitiveLongFST fst;

  private final FST.BytesReader fstBytesReader;

  private final ByteRunnable byteRunnable;

  private final TransitionAccessor transitionAccessor;

  /** DFS traversal states */
  private int currentLevel;

  private Frame[] stack;

  private BytesRefBuilder term = new BytesRefBuilder();

  private long fstOutput;

  boolean pending;

  public PrimitiveLongFSTIntersectEnum(
      PrimitiveLongFST fst, CompiledAutomaton automaton, BytesRef startTerm) throws IOException {
    this.fst = fst;
    this.fstBytesReader = fst.getBytesReader();
    this.byteRunnable = automaton.getByteRunnable();
    this.transitionAccessor = automaton.getTransitionAccessor();
    this.stack = new Frame[16];

    var firstFrame = new Frame();
    firstFrame.fstNode = new PrimitiveLongArc();
    fst.getFirstArc(firstFrame.fstNode);
    firstFrame.fsaState = 0;
    stack[0] = firstFrame;

    if (startTerm != null) {
      seekToStartTerm(startTerm);
    }
  }

  public boolean next() throws IOException {
    while (currentLevel >= 0) {
      Frame currentFrame = stack[currentLevel];

      if (hasDescendants(currentFrame.fstNode, currentFrame.fsaState)
          || currentFrame.fstCandidateNode != null) {
        // current frame has candidates
        if (findNextIntersection(currentFrame)) {
          term.grow(currentLevel + 1);
          term.setByteAt(currentLevel, (byte) currentFrame.fstCandidateNode.label());
          term.setLength(currentLevel + 1);
          // early prune - only push a new frame when the candidate has descendants
          if (hasDescendants(currentFrame.fstCandidateNode, currentFrame.fsaTransition.dest)) {
            Frame nextFrame = new Frame();
            nextFrame.fstNode = currentFrame.fstCandidateNode;
            nextFrame.fsaState = currentFrame.fsaTransition.dest;
            nextFrame.output = currentFrame.output + currentFrame.fstNode.output();
            ensureStackCapacity();
            stack[++currentLevel] = nextFrame;
          }
          // setup output
          if (isAccept(currentFrame.fstCandidateNode, currentFrame.fsaTransition.dest)) {
            fstOutput =
                currentFrame.output // output before this node
                    + currentFrame.fstNode.output() // output of this node
                    // then output of the candidate
                    + currentFrame.fstCandidateNode.output()
                    + currentFrame.fstCandidateNode.nextFinalOutput();
            return true;
          }
        } else {
          // no more intersection at this frame, pop frame
          popFrame();
        }
      } else {
        // pop frame as the frame has no candidates
        popFrame();
      }
    }
    return false;
  }

  private void ensureStackCapacity() {
    stack = ArrayUtil.grow(stack, currentLevel + 2);
  }

  private void seekToStartTerm(BytesRef startTerm) throws IOException {
    int length = startTerm.length;

    while (currentLevel < length) {
      Frame currentFrame = stack[currentLevel];
      int target = startTerm.bytes[startTerm.offset + currentLevel];

      if (hasDescendants(currentFrame.fstNode, currentFrame.fsaState)) {
        initArcAndTransition(currentFrame);
        currentFrame.transitionUpto = 0;
        if (fstAdvanceCeil(target, currentFrame.fstCandidateNode)
            && fsaAdvanceCeil(currentFrame, target)) {
          if (currentFrame.fstCandidateNode.label() == target) {
            term.append((byte) target);
            Frame nextFrame = new Frame();
            nextFrame.fstNode = currentFrame.fstCandidateNode;
            nextFrame.fsaState = currentFrame.fsaTransition.dest;
            nextFrame.output = currentFrame.output + currentFrame.fstNode.output();
            ensureStackCapacity();
            stack[++currentLevel] = nextFrame;
          } else {
            // we went past the target of FST, put the current candidate as pending.
            pending = true;
            break;
          }
        } else {
          break;
        }
      } else {
        break;
      }
    }
  }

  private void popFrame() {
    currentLevel--;
    term.setLength(currentLevel);
  }

  private boolean isAccept(PrimitiveLongArc fstNode, int fsaState) {
    return byteRunnable.isAccept(fsaState) && fstNode.isFinal();
  }

  private boolean hasDescendants(PrimitiveLongArc fstNode, int fsaState) {
    return transitionAccessor.getNumTransitions(fsaState) > 0
        && PrimitiveLongFST.targetHasArcs(fstNode);
  }

  private void initArcAndTransition(Frame frame) throws IOException {
    frame.fstCandidateNode = new PrimitiveLongArc();
    fst.readFirstRealTargetArc(frame.fstNode.target(), frame.fstCandidateNode, fstBytesReader);

    frame.fsaTransition = new Transition();
    frame.numTransitions = transitionAccessor.initTransition(frame.fsaState, frame.fsaTransition);
    transitionAccessor.getNextTransition(frame.fsaTransition);
    frame.transitionUpto++;
  }

  private boolean findNextIntersection(Frame frame) throws IOException {
    if (frame.fstCandidateNode == null) {
      // when called first time, init first FST arc and the FSA transition
      initArcAndTransition(frame);
    } else if (pending) {
      pending = false;
    } else {
      // subsequent call, which implies we previously found an intersection.
      // we need to advance the FST to avoid returning the same state.
      // Advance FST not the FSA because FST arc has a single label,
      // where FSA transition may accept a range of lables
      if (frame.fstCandidateNode.isLast()) {
        return false;
      }
      frame.fstCandidateNode = fst.readNextRealArc(frame.fstCandidateNode, fstBytesReader);
    }

    while (true) {
      if (frame.fstCandidateNode.label() < frame.fsaTransition.min) {
        // advance FST
        if (frame.fstCandidateNode.isLast()) {
          // no more eligible FST arc at this level
          return false;
        }
        // TODO: advance to first arc that has label >= fsaTransition.min
        //        frame.fstCandidateNode =
        //                fst.readNextRealArc(frame.fstCandidateNode, fstBytesReader);
        fstAdvanceCeil(frame.fsaTransition.min, frame.fstCandidateNode);
      } else if (frame.fstCandidateNode.label() > frame.fsaTransition.max) {
        // advance FSA
        if (frame.transitionUpto == frame.numTransitions) {
          // no more eligible FSA transitions at this level
          return false;
        }
        // TODO: advance FSA with binary search to fstNode.label()
        //        transitionAccessor.getNextTransition(frame.fsaTransition);
        //        frame.transitionUpto++;
        if (fsaAdvanceCeil(frame, frame.fstCandidateNode.label()) == false) {
          return false;
        }
      } else {
        // can go deeper
        return true;
      }
    }
  }

  public BytesRef getTerm() {
    return term.get();
  }

  public long getFSTOutput() {
    return fstOutput;
  }

  /**
   * Advance to the arc whose label is greater or equal to the provided target.
   *
   * @return true, if found.
   */
  private boolean fstAdvanceCeil(int target, PrimitiveLongArc /* mutates */ arc)
      throws IOException {
    if (arc.bytesPerArc() != 0 && arc.label() != PrimitiveLongFST.END_LABEL) {
      if (arc.nodeFlags() == PrimitiveLongFST.ARCS_FOR_CONTINUOUS) {
        int targetIndex = target - arc.label() + arc.arcIdx();
        if (targetIndex >= arc.numArcs() || targetIndex < 0) {
          return false;
        } else {
          fst.readArcByContinuous(arc, fstBytesReader, targetIndex);
          return true;
        }
      } else if (arc.nodeFlags() == PrimitiveLongFST.ARCS_FOR_DIRECT_ADDRESSING) {
        // Fixed length arcs in a direct addressing node.
        int targetIndex = target - arc.label() + arc.arcIdx();
        if (targetIndex >= arc.numArcs() || targetIndex < 0) {
          return false;
        } else {
          if (PrimitiveLongArc.BitTable.isBitSet(targetIndex, arc, fstBytesReader)) {
            fst.readArcByDirectAddressing(arc, fstBytesReader, targetIndex);
          } else {
            int ceilIndex = PrimitiveLongArc.BitTable.nextBitSet(targetIndex, arc, fstBytesReader);
            if (ceilIndex == -1) {
              return false;
            }
            fst.readArcByDirectAddressing(arc, fstBytesReader, ceilIndex);
          }
          return true;
        }
      }
      // Fixed length arcs in a binary search node.
      int idx = Util.binarySearch(fst, arc, target);
      if (idx >= 0) {
        fst.readArcByIndex(arc, fstBytesReader, idx);
        return true;
      }
      idx = -1 - idx;
      if (idx == arc.numArcs()) {
        // DEAD END!
        return false;
      }
      fst.readArcByIndex(arc, fstBytesReader, idx);
      return true;
    }

    // Variable length arcs in a linear scan list,
    // or special arc with label == FST.END_LABEL.
    while (true) {
      if (arc.label() >= target) {
        return true;
      } else if (arc.isLast()) {
        return false;
      } else {
        fst.readNextRealArc(arc, fstBytesReader);
      }
    }
  }

  private boolean fsaAdvanceCeil(Frame frame, int target) {
    int low = frame.transitionUpto;
    int high = frame.numTransitions;
    Transition t = frame.fsaTransition;

    // invariant: target is between the min of [low, high)
    int mid = 0;
    while (high - low > 1) {
      mid = (high + low) >>> 1;
      transitionAccessor.getTransition(frame.fsaState, mid, t);
      if (t.min > target) {
        high = mid;
      } else if (t.min < target) {
        low = mid;
      } else {
        frame.transitionUpto = mid + 1;
        return true;
      }
    }
    if (low != mid) {
      transitionAccessor.getTransition(frame.fsaState, low, t);
    }
    if (target <= t.max) {
      frame.transitionUpto = low + 1;
      return true;
    } else {
      return false;
    }
  }

  /**
   * We will maintain the state of conventional recursive DFS traversal algorithm, which is stack of
   * frames. This class capture the state at each level.
   */
  static final class Frame {
    PrimitiveLongArc fstNode;

    PrimitiveLongArc fstCandidateNode;

    int fsaState;

    long output;

    Transition fsaTransition;

    int transitionUpto;

    int numTransitions;
  }
}
