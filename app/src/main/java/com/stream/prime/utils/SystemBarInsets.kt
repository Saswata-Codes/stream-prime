package com.stream.prime.utils

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/** Padding on the four physical edges of a window. */
internal data class EdgePadding(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int
)

internal fun EdgePadding.plus(other: EdgePadding): EdgePadding = EdgePadding(
  left = left + other.left,
  top = top + other.top,
  right = right + other.right,
  bottom = bottom + other.bottom
)

/**
 * Applies one consistent safe area to every activity.
 *
 * Android 15+ enforces edge-to-edge for apps targeting recent SDKs. Padding the
 * activity content by the current system-bar and display-cutout insets keeps
 * headers, bottom actions, and landscape side controls out of those regions.
 */
object SystemBarInsets {
  private val originalPadding = WeakHashMap<View, EdgePadding>()

  fun apply(activity: Activity) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)

    val content = activity.findViewById<View>(android.R.id.content) ?: return
    val baseline = originalPadding.getOrPut(content) {
      EdgePadding(
        left = content.paddingLeft,
        top = content.paddingTop,
        right = content.paddingRight,
        bottom = content.paddingBottom
      )
    }

    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      )
      val resolved = baseline.plus(
        EdgePadding(bars.left, bars.top, bars.right, bars.bottom)
      )
      view.setPadding(resolved.left, resolved.top, resolved.right, resolved.bottom)

      // Keep dispatching insets for components that handle the IME or gestures.
      insets
    }

    content.post { ViewCompat.requestApplyInsets(content) }
  }
}
