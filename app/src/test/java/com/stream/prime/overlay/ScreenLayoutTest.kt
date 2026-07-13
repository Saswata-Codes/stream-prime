package com.stream.prime.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutTest {

  @Test
  fun defaultLayoutIsFullScreen() {
    assertTrue(ScreenLayout().isDefault())
  }

  @Test
  fun pictureInPictureKeepsGrabAreaVisible() {
    val layout = ScreenLayout(positionXPct = 95f, positionYPct = -10f, scalePct = 40f).normalized()

    assertEquals(90f, layout.positionXPct, 0.001f)
    assertEquals(-10f, layout.positionYPct, 0.001f)
    assertEquals(40f, layout.scalePct, 0.001f)
    assertFalse(layout.isDefault())
  }

  @Test
  fun zoomedLayoutCanPanAcrossCanvas() {
    val layout = ScreenLayout(positionXPct = -150f, positionYPct = 20f, scalePct = 200f).normalized()

    assertEquals(-150f, layout.positionXPct, 0.001f)
    assertEquals(20f, layout.positionYPct, 0.001f)
    assertEquals(200f, layout.scalePct, 0.001f)
  }

  @Test
  fun scaleIsClampedToSafeRange() {
    assertEquals(ScreenLayout.MIN_SCALE_PCT, ScreenLayout(scalePct = 0f).normalized().scalePct, 0.001f)
    assertEquals(ScreenLayout.MAX_SCALE_PCT, ScreenLayout(scalePct = 999f).normalized().scalePct, 0.001f)
  }

  @Test
  fun rotationIsNormalizedAndMakesLayoutNonDefault() {
    val layout = ScreenLayout(rotationDegrees = 450f).normalized()

    assertEquals(90f, layout.rotationDegrees, 0.001f)
    assertFalse(layout.isDefault())
  }
}
