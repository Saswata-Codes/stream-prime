package com.pedro.encoder.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSampleClockTest {

  @Test
  fun `48 kHz stereo AAC frames advance exactly by submitted samples`() {
    val clock = AudioSampleClock().apply { configure(48_000, true) }
    val frameBytes = PcmFrameSplitter.aacFrameBytes(true)

    assertEquals(0L, clock.nextTimestampUs(frameBytes, 0L))
    assertEquals(21_333L, clock.nextTimestampUs(frameBytes, 0L))
    assertEquals(42_666L, clock.nextTimestampUs(frameBytes, 0L))
    assertEquals(64_000L, clock.nextTimestampUs(frameBytes, 0L))
  }

  @Test
  fun `44 point 1 kHz remainder prevents cumulative rounding drift`() {
    val clock = AudioSampleClock().apply { configure(44_100, true) }
    val frameBytes = PcmFrameSplitter.aacFrameBytes(true)

    assertEquals(0L, clock.nextTimestampUs(frameBytes, 0L))
    assertEquals(23_219L, clock.nextTimestampUs(frameBytes, 0L))
    assertEquals(46_439L, clock.nextTimestampUs(frameBytes, 0L))
    assertEquals(69_659L, clock.nextTimestampUs(frameBytes, 0L))
  }

  @Test
  fun `real capture discontinuity catches sample clock up`() {
    val clock = AudioSampleClock().apply { configure(48_000, true) }
    val frameBytes = PcmFrameSplitter.aacFrameBytes(true)

    assertEquals(0L, clock.nextTimestampUs(frameBytes, 0L))
    assertEquals(1_000_000L, clock.nextTimestampUs(frameBytes, 1_000_000L))
  }
}
