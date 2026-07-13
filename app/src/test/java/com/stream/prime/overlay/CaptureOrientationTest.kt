package com.stream.prime.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureOrientationTest {

  @Test
  fun quarterTurnsAreNormalized() {
    assertEquals(1, CaptureOrientation.quarterTurns(450))
    assertEquals(3, CaptureOrientation.quarterTurns(-90))
  }

  @Test
  fun bothLandscapeDevicePosesSelectLandscapeScene() {
    assertEquals(true, CaptureOrientation.isLandscapeScene(90))
    assertEquals(true, CaptureOrientation.isLandscapeScene(270))
    assertEquals(true, CaptureOrientation.isLandscapeScene(-90))
  }

  @Test
  fun portraitDevicePosesSelectPortraitScene() {
    assertEquals(false, CaptureOrientation.isLandscapeScene(0))
    assertEquals(false, CaptureOrientation.isLandscapeScene(180))
    assertEquals(false, CaptureOrientation.isLandscapeScene(360))
  }

  @Test
  fun landscapeSourceCropSelectsCenteredActiveBand() {
    val canvasAspect = 720f / 1280f
    val crop = CaptureOrientation.sourceCrop(canvasAspect, ScreenPreset.LANDSCAPE)
    val expectedHeight = canvasAspect * canvasAspect

    assertEquals(0f, crop.x, 0.0001f)
    assertEquals((1f - expectedHeight) / 2f, crop.y, 0.0001f)
    assertEquals(1f, crop.width, 0.0001f)
    assertEquals(expectedHeight, crop.height, 0.0001f)
  }

  @Test
  fun portraitSourceCropUsesWholeTexture() {
    val crop = CaptureOrientation.sourceCrop(720f / 1280f, ScreenPreset.PORTRAIT)

    assertEquals(0f, crop.x, 0.0001f)
    assertEquals(0f, crop.y, 0.0001f)
    assertEquals(1f, crop.width, 0.0001f)
    assertEquals(1f, crop.height, 0.0001f)
  }

  @Test
  fun landscapeCaptureFitsInsideVerticalCanvas() {
    val canvasAspect = 1080f / 1920f
    val (width, height) = CaptureOrientation.fittedSize(canvasAspect, 90)

    assertEquals(1f, width, 0.0001f)
    assertEquals(canvasAspect * canvasAspect, height, 0.0001f)
  }

  @Test
  fun portraitCaptureKeepsFullCanvas() {
    val (width, height) = CaptureOrientation.fittedSize(1080f / 1920f, 0)

    assertEquals(1f, width, 0.0001f)
    assertEquals(1f, height, 0.0001f)
  }

  @Test
  fun landscapePresetUsesWideNaturalShape() {
    val canvasAspect = 1080f / 1920f
    val (width, height) = CaptureOrientation.contentSize(canvasAspect, ScreenPreset.LANDSCAPE)

    assertEquals(1f, width, 0.0001f)
    assertEquals(canvasAspect * canvasAspect, height, 0.0001f)
  }

  @Test
  fun landscapeDefaultIsCenteredInVerticalCanvas() {
    val canvasAspect = 1080f / 1920f
    val layout = CaptureOrientation.defaultLayout(canvasAspect, ScreenPreset.LANDSCAPE)
    val expectedHeight = canvasAspect * canvasAspect

    assertEquals(0f, layout.positionXPct, 0.0001f)
    assertEquals((1f - expectedHeight) * 50f, layout.positionYPct, 0.0001f)
    assertEquals(100f, layout.scalePct, 0.0001f)
  }

  @Test
  fun stretchModeDoesNotChangeSelectedLandscapeFrameShape() {
    val size = CaptureOrientation.contentSize(
      1080f / 1920f,
      ScreenPreset.LANDSCAPE,
      ScreenFitMode.STRETCH
    )

    assertEquals(1f, size.first, 0.0001f)
    assertEquals((1080f / 1920f) * (1080f / 1920f), size.second, 0.0001f)
  }

  @Test
  fun fullPortraitSourceFitsInsideLandscapeFrame() {
    val canvasAspect = 1080f / 1920f
    val size = CaptureOrientation.sourceFitSize(
      canvasAspect,
      ScreenPreset.LANDSCAPE,
      ScreenPreset.PORTRAIT,
      ScreenFitMode.ASPECT
    )

    assertEquals(canvasAspect * canvasAspect, size.first, 0.0001f)
    assertEquals(1f, size.second, 0.0001f)
  }

  @Test
  fun fullLandscapeSourceFitsInsidePortraitFrame() {
    val canvasAspect = 1080f / 1920f
    val size = CaptureOrientation.sourceFitSize(
      canvasAspect,
      ScreenPreset.PORTRAIT,
      ScreenPreset.LANDSCAPE,
      ScreenFitMode.ASPECT
    )

    assertEquals(1f, size.first, 0.0001f)
    assertEquals(canvasAspect * canvasAspect, size.second, 0.0001f)
  }

  @Test
  fun stretchFillsSelectedFrame() {
    val size = CaptureOrientation.sourceFitSize(
      1080f / 1920f,
      ScreenPreset.LANDSCAPE,
      ScreenPreset.PORTRAIT,
      ScreenFitMode.STRETCH
    )

    assertEquals(1f, size.first, 0.0001f)
    assertEquals(1f, size.second, 0.0001f)
  }

  @Test
  fun nativeWideLandscapeAspectRemovesMediaProjectionLetterboxFromMatchingFrame() {
    val canvasAspect = 1080f / 1920f
    val deviceLandscapeAspect = 2340f / 1080f
    val expectedHeight = canvasAspect / deviceLandscapeAspect
    val frame = CaptureOrientation.contentSize(
      canvasAspect,
      ScreenPreset.LANDSCAPE,
      ScreenFitMode.ASPECT,
      deviceLandscapeAspect
    )
    val crop = CaptureOrientation.sourceCrop(
      canvasAspect,
      ScreenPreset.LANDSCAPE,
      deviceLandscapeAspect
    )
    val fittedSource = CaptureOrientation.sourceFitSize(
      canvasAspect,
      ScreenPreset.LANDSCAPE,
      ScreenPreset.LANDSCAPE,
      ScreenFitMode.ASPECT,
      deviceLandscapeAspect
    )

    assertEquals(1f, frame.first, 0.0001f)
    assertEquals(expectedHeight, frame.second, 0.0001f)
    assertEquals((1f - expectedHeight) / 2f, crop.y, 0.0001f)
    assertEquals(expectedHeight, crop.height, 0.0001f)
    assertEquals(1f, fittedSource.first, 0.0001f)
    assertEquals(1f, fittedSource.second, 0.0001f)
  }

  @Test
  fun portraitInsideNativeLandscapeFrameKeepsIntentionalBlackArea() {
    val canvasAspect = 1080f / 1920f
    val deviceLandscapeAspect = 2340f / 1080f
    val fittedSource = CaptureOrientation.sourceFitSize(
      canvasAspect,
      ScreenPreset.LANDSCAPE,
      ScreenPreset.PORTRAIT,
      ScreenFitMode.ASPECT,
      deviceLandscapeAspect
    )

    assertEquals(1f / (deviceLandscapeAspect * deviceLandscapeAspect), fittedSource.first, 0.0001f)
    assertEquals(1f, fittedSource.second, 0.0001f)
  }
}
