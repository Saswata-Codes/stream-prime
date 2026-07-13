package com.stream.prime.overlay

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class SceneBounds(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float
) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top
  val centerX: Float get() = (left + right) / 2f
  val centerY: Float get() = (top + bottom) / 2f
}

data class SceneTransform(
  val scale: Float,
  val sourceCenterX: Float,
  val sourceCenterY: Float
) {
  fun mapX(value: Float): Float = 50f + (value - sourceCenterX) * scale
  fun mapY(value: Float): Float = 50f + (value - sourceCenterY) * scale
}

/** Pure geometry used by the editor's aspect-safe whole-scene fit action. */
object SceneLayoutOptimizer {
  fun rotatedBounds(bounds: SceneBounds, rotationDegrees: Float): SceneBounds {
    if (bounds.width <= 0f || bounds.height <= 0f) return bounds
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val rotatedWidth = abs(cos(radians)).toFloat() * bounds.width +
      abs(sin(radians)).toFloat() * bounds.height
    val rotatedHeight = abs(sin(radians)).toFloat() * bounds.width +
      abs(cos(radians)).toFloat() * bounds.height
    return SceneBounds(
      bounds.centerX - rotatedWidth / 2f,
      bounds.centerY - rotatedHeight / 2f,
      bounds.centerX + rotatedWidth / 2f,
      bounds.centerY + rotatedHeight / 2f
    )
  }

  fun union(bounds: List<SceneBounds>): SceneBounds? {
    val valid = bounds.filter { it.width > 0.0001f && it.height > 0.0001f }
    if (valid.isEmpty()) return null
    return SceneBounds(
      valid.minOf { it.left },
      valid.minOf { it.top },
      valid.maxOf { it.right },
      valid.maxOf { it.bottom }
    )
  }

  fun fit(
    bounds: SceneBounds,
    safeMarginPct: Float = 3f,
    minScale: Float = 0f,
    maxScale: Float = Float.MAX_VALUE
  ): SceneTransform? {
    if (bounds.width <= 0.0001f || bounds.height <= 0.0001f) return null
    val margin = safeMarginPct.coerceIn(0f, 45f)
    val available = 100f - margin * 2f
    val desiredScale = min(available / bounds.width, available / bounds.height)
    val safeMin = minScale.coerceAtLeast(0.0001f)
    val safeMax = maxScale.coerceAtLeast(safeMin)
    return SceneTransform(
      scale = desiredScale.coerceIn(safeMin, safeMax),
      sourceCenterX = bounds.centerX,
      sourceCenterY = bounds.centerY
    )
  }
}
