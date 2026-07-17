package com.stream.prime.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Lightweight media metadata used before an image layer is added to the canvas. */
object OverlayMediaDetector {
  private const val GIF_HEADER_SIZE = 6
  const val MAX_GIF_FILE_BYTES = 20L * 1024L * 1024L

  fun isGifHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
    if (length < GIF_HEADER_SIZE || bytes.size < GIF_HEADER_SIZE) return false
    val signature = String(bytes, 0, GIF_HEADER_SIZE, StandardCharsets.US_ASCII)
    return signature == "GIF87a" || signature == "GIF89a"
  }

  fun isSupportedGifSize(sizeBytes: Long): Boolean =
    sizeBytes <= 0L || sizeBytes <= MAX_GIF_FILE_BYTES

  fun isGif(context: Context, uriString: String): Boolean {
    if (uriString.isBlank()) return false
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    if (mimeType?.lowercase(Locale.US) == "image/gif") return true
    if (uri.lastPathSegment?.lowercase(Locale.US)?.endsWith(".gif") == true) return true
    return readHeader(context, uri)?.let(::isGifHeader) == true
  }

  internal fun readHeader(context: Context, uri: Uri): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
      val header = ByteArray(GIF_HEADER_SIZE)
      val count = input.read(header)
      if (count == GIF_HEADER_SIZE) header else null
    }
  }.getOrNull()
}

/**
 * Decoded image asset owned by one renderer. GIF frames are drawn by [Movie] directly into the
 * reusable composition canvas, avoiding a frame-sized Bitmap allocation on every animation tick.
 */
internal sealed class OverlayImageAsset {
  abstract val width: Int
  abstract val height: Int
  abstract val animated: Boolean

  abstract fun draw(canvas: Canvas, destination: RectF, rotationDegrees: Float, paint: Paint, frameTimeMs: Long)

  open fun recycle() = Unit

  class Static(private val bitmap: Bitmap) : OverlayImageAsset() {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height
    override val animated: Boolean = false

    override fun draw(
      canvas: Canvas,
      destination: RectF,
      rotationDegrees: Float,
      paint: Paint,
      frameTimeMs: Long
    ) {
      val save = canvas.save()
      canvas.rotate(rotationDegrees, destination.centerX(), destination.centerY())
      canvas.drawBitmap(bitmap, null, destination, paint)
      canvas.restoreToCount(save)
    }

    override fun recycle() {
      if (!bitmap.isRecycled) bitmap.recycle()
    }
  }

  @Suppress("DEPRECATION")
  class AnimatedGif(private val movie: Movie) : OverlayImageAsset() {
    override val width: Int = movie.width().coerceAtLeast(1)
    override val height: Int = movie.height().coerceAtLeast(1)
    private val rawDurationMs = movie.duration()
    private val durationMs = rawDurationMs.takeIf { it > 0 } ?: DEFAULT_GIF_DURATION_MS
    // Some valid animated GIFs omit or expose a zero total duration through Movie. They still
    // need frame ticks; the fallback duration above keeps their playback moving safely.
    override val animated: Boolean = true
    private val startedAtMs = SystemClock.uptimeMillis()

    override fun draw(
      canvas: Canvas,
      destination: RectF,
      rotationDegrees: Float,
      paint: Paint,
      frameTimeMs: Long
    ) {
      val elapsed = (frameTimeMs - startedAtMs).coerceAtLeast(0L)
      movie.setTime((elapsed % durationMs).toInt())
      val save = canvas.save()
      canvas.rotate(rotationDegrees, destination.centerX(), destination.centerY())
      canvas.translate(destination.left, destination.top)
      canvas.scale(destination.width() / width, destination.height() / height)
      movie.draw(canvas, 0f, 0f, paint)
      canvas.restoreToCount(save)
    }
  }

  companion object {
    private const val DEFAULT_GIF_DURATION_MS = 1_000
  }
}

/**
 * Per-renderer image cache. Preview and output intentionally use separate instances so Android's
 * stateful GIF decoder is never accessed from two threads.
 */
internal class OverlayAssetCache {
  private val assets = mutableMapOf<String, OverlayImageAsset?>()

  fun getOrLoad(context: Context, layer: OverlayLayer): OverlayImageAsset? {
    val key = key(layer)
    if (assets.containsKey(key)) return assets[key]
    removeStaleEntriesForLayer(layer.id, key)
    return decode(context, layer).also { assets[key] = it }
  }

  fun dimensions(context: Context, layer: OverlayLayer): Pair<Int, Int>? {
    val asset = getOrLoad(context, layer) ?: return null
    return asset.width to asset.height
  }

  fun retainLayerIds(layerIds: Set<String>) {
    val keys = assets.keys.filter { key -> layerIds.none { id -> key.startsWith("$id::") } }
    keys.forEach(::remove)
  }

  fun removeLayerIds(layerIds: Set<String>) {
    if (layerIds.isEmpty()) return
    val keys = assets.keys.filter { key -> layerIds.any { id -> key.startsWith("$id::") } }
    keys.forEach(::remove)
  }

  fun clear() {
    assets.values.forEach { it?.recycle() }
    assets.clear()
  }

  private fun removeStaleEntriesForLayer(layerId: String, currentKey: String) {
    assets.keys.filter { it.startsWith("$layerId::") && it != currentKey }.forEach(::remove)
  }

  private fun remove(key: String) {
    assets.remove(key)?.recycle()
  }

  private fun key(layer: OverlayLayer): String = "${layer.id}::${layer.imageUri}"

  @Suppress("DEPRECATION")
  private fun decode(context: Context, layer: OverlayLayer): OverlayImageAsset? {
    if (layer.imageUri.isBlank()) return null
    val uri = runCatching { Uri.parse(layer.imageUri) }.getOrNull() ?: return null
    val animated = layer.animated || OverlayMediaDetector.isGif(context, layer.imageUri)
    if (animated) {
      val movie = runCatching {
        context.contentResolver.openInputStream(uri)?.use { raw ->
          Movie.decodeStream(BufferedInputStream(raw))
        }
      }.getOrNull()
      if (movie != null && movie.width() > 0 && movie.height() > 0) {
        return OverlayImageAsset.AnimatedGif(movie)
      }
    }
    val bitmap = runCatching {
      context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    }.getOrNull() ?: return null
    return OverlayImageAsset.Static(bitmap)
  }
}

/** GIF cadence is independent of the encoder FPS; video can continue rendering at 60 FPS. */
internal const val OVERLAY_ANIMATION_MAX_FPS = 30
internal const val OVERLAY_ANIMATION_FRAME_INTERVAL_MS = 1_000L / OVERLAY_ANIMATION_MAX_FPS
