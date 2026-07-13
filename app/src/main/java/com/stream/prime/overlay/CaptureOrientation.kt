package com.stream.prime.overlay

data class NormalizedCrop(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float
)

/** Pure orientation math shared by screen-capture composition and unit tests. */
object CaptureOrientation {
  fun quarterTurns(rotationDegrees: Int): Int {
    return (((rotationDegrees / 90) % 4) + 4) % 4
  }

  /**
   * Display rotation selects a composition scene only. MediaProjection already supplies
   * pixels in the correct texture orientation, so the renderer must not rotate them again.
   */
  fun isLandscapeScene(rotationDegrees: Int): Boolean = quarterTurns(rotationDegrees) % 2 == 1

  fun fittedSize(
    canvasAspect: Float,
    rotationDegrees: Int,
    landscapeAspect: Float = defaultLandscapeAspect(canvasAspect)
  ): Pair<Float, Float> {
    if (canvasAspect <= 0f || quarterTurns(rotationDegrees) % 2 == 0) return 1f to 1f
    return fittedAspectSize(canvasAspect, landscapeAspect)
  }

  private fun fittedAspectSize(canvasAspect: Float, contentAspect: Float): Pair<Float, Float> {
    if (canvasAspect <= 0f || contentAspect <= 0f) return 1f to 1f
    return if (contentAspect > canvasAspect) {
      1f to (canvasAspect / contentAspect)
    } else {
      (contentAspect / canvasAspect) to 1f
    }
  }

  fun contentSize(
    canvasAspect: Float,
    preset: ScreenPreset,
    @Suppress("UNUSED_PARAMETER") fitMode: ScreenFitMode = ScreenFitMode.ASPECT,
    landscapeAspect: Float = defaultLandscapeAspect(canvasAspect)
  ): Pair<Float, Float> {
    val physicalAspect = landscapeAspect.coerceAtLeast(0.0001f)
    val contentAspect = if (preset == ScreenPreset.LANDSCAPE) physicalAspect else 1f / physicalAspect
    return fittedAspectSize(canvasAspect, contentAspect)
  }

  /** Size occupied by the full source inside the selected frame. */
  fun sourceFitSize(
    canvasAspect: Float,
    framePreset: ScreenPreset,
    sourcePreset: ScreenPreset,
    fitMode: ScreenFitMode,
    landscapeAspect: Float = defaultLandscapeAspect(canvasAspect)
  ): Pair<Float, Float> {
    if (fitMode == ScreenFitMode.STRETCH || canvasAspect <= 0f) return 1f to 1f
    val frameSize = contentSize(canvasAspect, framePreset, fitMode, landscapeAspect)
    val sourceCrop = sourceCrop(canvasAspect, sourcePreset, landscapeAspect)
    val frameAspect = canvasAspect * frameSize.first / frameSize.second.coerceAtLeast(0.0001f)
    val sourceAspect = canvasAspect * sourceCrop.width / sourceCrop.height.coerceAtLeast(0.0001f)
    return if (sourceAspect > frameAspect) {
      1f to (frameAspect / sourceAspect)
    } else {
      (sourceAspect / frameAspect) to 1f
    }
  }

  /** Active app pixels inside the fixed portrait MediaProjection texture. */
  fun sourceCrop(
    canvasAspect: Float,
    preset: ScreenPreset,
    landscapeAspect: Float = defaultLandscapeAspect(canvasAspect)
  ): NormalizedCrop {
    val (width, height) = contentSize(
      canvasAspect,
      preset,
      ScreenFitMode.ASPECT,
      landscapeAspect
    )
    return NormalizedCrop(
      x = (1f - width) / 2f,
      y = (1f - height) / 2f,
      width = width,
      height = height
    )
  }

  fun defaultLayout(
    canvasAspect: Float,
    preset: ScreenPreset,
    fitMode: ScreenFitMode = ScreenFitMode.ASPECT,
    landscapeAspect: Float = defaultLandscapeAspect(canvasAspect)
  ): ScreenLayout {
    val (width, height) = contentSize(canvasAspect, preset, fitMode, landscapeAspect)
    return ScreenLayout(
      positionXPct = (1f - width) * 50f,
      positionYPct = (1f - height) * 50f,
      scalePct = 100f,
      rotationDegrees = 0f
    ).normalized(width, height)
  }

  private fun defaultLandscapeAspect(canvasAspect: Float): Float {
    return if (canvasAspect > 0f) 1f / canvasAspect else 1f
  }
}
