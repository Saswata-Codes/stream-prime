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

/**
 * Generates PCM timestamps from the number of samples actually submitted to the encoder.
 *
 * <p>Using a sample clock avoids capture-thread scheduling jitter in live transports. The
 * remainder is retained so rates such as 44.1 kHz do not accumulate integer-rounding drift.</p>
 */
final class AudioSampleClock {

  private static final long MAX_CAPTURE_LAG_US = 500_000L;

  private int bytesPerSecond = 1;
  private long nextPtsUs = Long.MIN_VALUE;
  private long remainder = 0L;

  void configure(int sampleRate, boolean isStereo) {
    int channels = isStereo ? 2 : 1;
    bytesPerSecond = Math.max(1, sampleRate * channels * 2);
    reset();
  }

  void reset() {
    nextPtsUs = Long.MIN_VALUE;
    remainder = 0L;
  }

  long nextTimestampUs(int pcmBytes, long captureClockPtsUs) {
    long safeClockPtsUs = Math.max(0L, captureClockPtsUs);
    if (nextPtsUs == Long.MIN_VALUE) {
      nextPtsUs = safeClockPtsUs;
    } else if (safeClockPtsUs - nextPtsUs > MAX_CAPTURE_LAG_US) {
      // A real capture discontinuity should not leave the encoded track permanently behind.
      nextPtsUs = safeClockPtsUs;
      remainder = 0L;
    }

    long ptsUs = nextPtsUs;
    long durationNumerator = Math.max(0, pcmBytes) * 1_000_000L + remainder;
    nextPtsUs += durationNumerator / bytesPerSecond;
    remainder = durationNumerator % bytesPerSecond;
    return ptsUs;
  }
}
