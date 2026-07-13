package com.pedro.rtmp.rtmp

import com.pedro.common.frame.MediaFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeTimestampNormalizerTest {

  @Test
  fun `future media timestamps are clamped without waiting`() {
    var nowUs = 1_000_000L
    val normalizer = RealtimeTimestampNormalizer { nowUs }

    assertEquals(0L, normalizer.normalize(5_000_000L, MediaFrame.Type.VIDEO))
    nowUs += 20_000L
    assertEquals(20_000L, normalizer.normalize(7_000_000L, MediaFrame.Type.VIDEO))
    nowUs += 30_000L
    assertEquals(50_000L, normalizer.normalize(8_000_000L, MediaFrame.Type.VIDEO))
  }

  @Test
  fun `audio and video remain independently monotonic`() {
    var nowUs = 10_000_000L
    val normalizer = RealtimeTimestampNormalizer { nowUs }

    normalizer.normalize(1_000_000L, MediaFrame.Type.VIDEO)
    nowUs += 2_000_000L
    assertEquals(2_000_000L, normalizer.normalize(4_000_000L, MediaFrame.Type.VIDEO))
    assertEquals(500_000L, normalizer.normalize(1_500_000L, MediaFrame.Type.AUDIO))
    assertEquals(2_000_000L, normalizer.normalize(2_000_000L, MediaFrame.Type.VIDEO))
    assertEquals(500_000L, normalizer.normalize(1_400_000L, MediaFrame.Type.AUDIO))
  }

  @Test
  fun `reset starts a fresh zero based timeline`() {
    var nowUs = 1_000L
    val normalizer = RealtimeTimestampNormalizer { nowUs }

    normalizer.normalize(5_000L, MediaFrame.Type.AUDIO)
    nowUs += 1_000L
    assertEquals(1_000L, normalizer.normalize(6_000L, MediaFrame.Type.AUDIO))
    normalizer.reset()
    assertEquals(0L, normalizer.normalize(30_000L, MediaFrame.Type.AUDIO))
  }
}
