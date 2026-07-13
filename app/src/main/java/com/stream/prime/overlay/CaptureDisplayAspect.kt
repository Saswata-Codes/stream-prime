package com.stream.prime.overlay

import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/** Reads the physical display shape used by MediaProjection, independent of current rotation. */
object CaptureDisplayAspect {
  fun landscapeAspect(context: Context): Float {
    val point = Point()
    @Suppress("DEPRECATION")
    val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    @Suppress("DEPRECATION")
    display?.getRealSize(point)
    val shortSide = min(point.x, point.y)
    val longSide = max(point.x, point.y)
    return if (shortSide > 0 && longSide > 0) {
      longSide.toFloat() / shortSide.toFloat()
    } else {
      DEFAULT_LANDSCAPE_ASPECT
    }
  }

  private const val DEFAULT_LANDSCAPE_ASPECT = 16f / 9f
}
