package com.stream.prime.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayProjectDefaultsTest {

  @Test
  fun newOverlayUsesTheSamePortraitAndLandscapePositionsAsReset() {
    val canvasAspect = 1080f / 1920f
    val deviceLandscapeAspect = 2340f / 1080f
    val current = OverlayConfig(
      enabled = true,
      layers = listOf(OverlayLayer(id = "old")),
      screenLayout = ScreenLayout(positionXPct = 47f, positionYPct = 63f, scalePct = 180f),
      landscapeScreenLayout = ScreenLayout(positionXPct = -20f, positionYPct = 14f, scalePct = 75f),
      portraitScreenLocked = true,
      landscapeScreenLocked = true,
      screenLayerPosition = 1
    )
    val portraitReset = CaptureOrientation.defaultLayout(
      canvasAspect,
      ScreenPreset.PORTRAIT,
      current.portraitScreenFitMode,
      deviceLandscapeAspect
    )
    val landscapeReset = CaptureOrientation.defaultLayout(
      canvasAspect,
      ScreenPreset.LANDSCAPE,
      current.landscapeScreenFitMode,
      deviceLandscapeAspect
    )

    val fresh = freshOverlayConfig(current, portraitReset, landscapeReset)

    assertEquals(portraitReset, fresh.screenLayout)
    assertEquals(landscapeReset, fresh.landscapeScreenLayout)
    assertFalse(fresh.enabled)
    assertTrue(fresh.layers.isEmpty())
    assertFalse(fresh.portraitScreenLocked)
    assertFalse(fresh.landscapeScreenLocked)
    assertEquals(0, fresh.screenLayerPosition)
  }

  @Test
  fun restoredBlankOverlayCanRepairPositionsWithoutChangingItsOtherSettings() {
    val current = OverlayConfig(
      enabled = true,
      screenPreset = ScreenPreset.LANDSCAPE,
      screenLayout = ScreenLayout(positionXPct = -90f, positionYPct = 45f),
      landscapeScreenLayout = ScreenLayout(positionXPct = 80f, positionYPct = -50f),
      canvasTheme = CanvasTheme.NEON,
      showGrid = false
    )
    val portraitReset = ScreenLayout(positionXPct = 12f, positionYPct = 0f)
    val landscapeReset = ScreenLayout(positionXPct = 0f, positionYPct = 38f)

    val repaired = resetScreenLayouts(current, portraitReset, landscapeReset)

    assertEquals(portraitReset, repaired.screenLayout)
    assertEquals(landscapeReset, repaired.landscapeScreenLayout)
    assertTrue(repaired.enabled)
    assertEquals(ScreenPreset.LANDSCAPE, repaired.screenPreset)
    assertEquals(CanvasTheme.NEON, repaired.canvasTheme)
    assertFalse(repaired.showGrid)
  }

  @Test
  fun landscapeDefaultsAreRecenteredWhenVerticalCanvasBecomesActive() {
    val landscapePortraitDefault = ScreenLayout(positionXPct = 37f, positionYPct = 0f)
    val landscapeLandscapeDefault = ScreenLayout(positionXPct = 0f, positionYPct = 36f)
    val verticalPortraitDefault = ScreenLayout(positionXPct = 9f, positionYPct = 0f)
    val verticalLandscapeDefault = ScreenLayout(positionXPct = 0f, positionYPct = 38f)
    val current = OverlayConfig(
      screenLayout = landscapePortraitDefault,
      landscapeScreenLayout = landscapeLandscapeDefault
    )

    val aligned = recenterLayoutsStillAtDefaults(
      current = current,
      knownPortraitDefaults = listOf(landscapePortraitDefault, verticalPortraitDefault),
      knownLandscapeDefaults = listOf(landscapeLandscapeDefault, verticalLandscapeDefault),
      targetPortraitDefault = verticalPortraitDefault,
      targetLandscapeDefault = verticalLandscapeDefault
    )

    assertEquals(verticalPortraitDefault, aligned.screenLayout)
    assertEquals(verticalLandscapeDefault, aligned.landscapeScreenLayout)
  }

  @Test
  fun customScreenPlacementIsPreservedAcrossCanvasModeChanges() {
    val customPortrait = ScreenLayout(
      positionXPct = 18f,
      positionYPct = 12f,
      scalePct = 135f,
      rotationDegrees = 8f
    )
    val customLandscape = ScreenLayout(
      positionXPct = -4f,
      positionYPct = 31f,
      scalePct = 82f,
      rotationDegrees = -6f
    )
    val current = OverlayConfig(
      screenLayout = customPortrait,
      landscapeScreenLayout = customLandscape
    )

    val aligned = recenterLayoutsStillAtDefaults(
      current = current,
      knownPortraitDefaults = listOf(ScreenLayout(positionXPct = 37f)),
      knownLandscapeDefaults = listOf(ScreenLayout(positionYPct = 36f)),
      targetPortraitDefault = ScreenLayout(positionXPct = 9f),
      targetLandscapeDefault = ScreenLayout(positionYPct = 38f)
    )

    assertEquals(customPortrait, aligned.screenLayout)
    assertEquals(customLandscape, aligned.landscapeScreenLayout)
  }
}
