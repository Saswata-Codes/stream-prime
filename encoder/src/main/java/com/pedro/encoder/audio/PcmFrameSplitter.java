/*
 * Copyright (C) 2024 pedroSG94.
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

package com.pedro.encoder.audio;

import com.pedro.encoder.Frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Converts reusable AudioRecord buffers into immutable AAC-sized PCM frames. */
final class PcmFrameSplitter {

  static final int AAC_SAMPLES_PER_CHANNEL = 1024;
  private static final int PCM_16_BYTES_PER_SAMPLE = 2;

  private PcmFrameSplitter() {
  }

  static int aacFrameBytes(boolean isStereo) {
    int channels = isStereo ? 2 : 1;
    return AAC_SAMPLES_PER_CHANNEL * channels * PCM_16_BYTES_PER_SAMPLE;
  }

  static List<Frame> splitForAac(Frame source, int sampleRate, boolean isStereo) {
    if (source == null || source.getBuffer() == null || source.getSize() <= 0) {
      return Collections.emptyList();
    }

    byte[] sourceBuffer = source.getBuffer();
    int start = Math.max(0, source.getOffset());
    int end = Math.min(sourceBuffer.length, start + source.getSize());
    if (start >= end) return Collections.emptyList();

    int channels = isStereo ? 2 : 1;
    long bytesPerSecond = Math.max(1L, sampleRate * channels * PCM_16_BYTES_PER_SAMPLE);
    int maxFrameBytes = aacFrameBytes(isStereo);
    List<Frame> frames = new ArrayList<>((end - start + maxFrameBytes - 1) / maxFrameBytes);
    int consumedBytes = 0;

    for (int position = start; position < end; position += maxFrameBytes) {
      int chunkEnd = Math.min(end, position + maxFrameBytes);
      byte[] immutablePcm = Arrays.copyOfRange(sourceBuffer, position, chunkEnd);
      long timestampOffsetUs = consumedBytes * 1_000_000L / bytesPerSecond;
      frames.add(new Frame(
          immutablePcm,
          0,
          immutablePcm.length,
          source.getTimeStamp() + timestampOffsetUs
      ));
      consumedBytes += immutablePcm.length;
    }
    return frames;
  }

  static Frame immutableCopy(Frame source) {
    if (source == null || source.getBuffer() == null || source.getSize() <= 0) return null;
    int start = Math.max(0, source.getOffset());
    int end = Math.min(source.getBuffer().length, start + source.getSize());
    if (start >= end) return null;
    byte[] immutablePcm = Arrays.copyOfRange(source.getBuffer(), start, end);
    return new Frame(immutablePcm, 0, immutablePcm.length, source.getTimeStamp());
  }
}
