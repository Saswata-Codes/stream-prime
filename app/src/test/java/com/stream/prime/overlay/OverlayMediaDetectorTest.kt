package com.stream.prime.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMediaDetectorTest {

  @Test
  fun `gif animation cadence is capped independently at thirty fps`() {
    assertEquals(30, OVERLAY_ANIMATION_MAX_FPS)
    assertEquals(33L, OVERLAY_ANIMATION_FRAME_INTERVAL_MS)
  }

  @Test
  fun `recognizes both valid gif signatures`() {
    assertTrue(OverlayMediaDetector.isGifHeader("GIF87a".toByteArray()))
    assertTrue(OverlayMediaDetector.isGifHeader("GIF89a".toByteArray()))
  }

  @Test
  fun `does not treat static or incomplete headers as gif`() {
    assertFalse(OverlayMediaDetector.isGifHeader(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    assertFalse(OverlayMediaDetector.isGifHeader("GIF89".toByteArray()))
    assertFalse(OverlayMediaDetector.isGifHeader("NOTGIF".toByteArray()))
  }

  @Test
  fun `gif size guard permits unknown and twenty megabytes but rejects larger files`() {
    assertTrue(OverlayMediaDetector.isSupportedGifSize(-1L))
    assertTrue(OverlayMediaDetector.isSupportedGifSize(20L * 1024L * 1024L))
    assertFalse(OverlayMediaDetector.isSupportedGifSize(20L * 1024L * 1024L + 1L))
  }
}
