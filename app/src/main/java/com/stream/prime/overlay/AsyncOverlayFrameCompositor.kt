package com.stream.prime.overlay

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Renders animated overlay compositions away from the encoder's GL thread.
 *
 * There are always two reusable bitmaps: the front bitmap currently uploaded to GL and a back
 * bitmap owned by this worker. Once a complete back frame is available, [consume] atomically
 * exchanges it for the old front bitmap. This prevents GIF decoding/software Canvas work from
 * pacing the video encoder and avoids per-frame bitmap allocation.
 */
internal class AsyncOverlayFrameCompositor(
  threadName: String,
  initialBackBuffer: Bitmap,
  private val renderFrame: (bitmap: Bitmap, frameTimeMs: Long) -> Boolean
) {
  data class CompletedFrame(
    val bitmap: Bitmap,
    val hasAnimatedLayers: Boolean
  )

  private val stateLock = Any()
  private val renderLock = Any()
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, threadName).apply { isDaemon = true }
  }

  private var backBuffer: Bitmap? = initialBackBuffer
  private var completedFrame: CompletedFrame? = null
  private var rendering = false
  private var released = false

  /** Queues at most one frame. A pending completed frame is never overwritten. */
  fun request(frameTimeMs: Long): Boolean {
    val target = synchronized(stateLock) {
      if (released || rendering || completedFrame != null) return false
      val bitmap = backBuffer ?: return false
      backBuffer = null
      rendering = true
      bitmap
    }

    return try {
      executor.execute {
        val hasAnimation = synchronized(renderLock) {
          runCatching { renderFrame(target, frameTimeMs) }.getOrDefault(false)
        }
        synchronized(stateLock) {
          rendering = false
          if (released) backBuffer = target
          else completedFrame = CompletedFrame(target, hasAnimation)
        }
      }
      true
    } catch (_: RejectedExecutionException) {
      synchronized(stateLock) {
        rendering = false
        backBuffer = target
      }
      false
    }
  }

  /**
   * Returns a completed frame without waiting. The previous GL front bitmap becomes the next
   * worker back buffer only after the caller has switched to the returned frame.
   */
  fun consume(previousFrontBuffer: Bitmap): CompletedFrame? = synchronized(stateLock) {
    val frame = completedFrame ?: return null
    completedFrame = null
    backBuffer = previousFrontBuffer
    frame
  }

  /** Stops new work, waits for the single active Canvas pass, and recycles worker-owned buffers. */
  fun close() {
    synchronized(stateLock) { released = true }
    executor.shutdownNow()
    // Canvas/Movie drawing is not interruptible. Waiting here prevents recycling its bitmap/cache
    // underneath it; release happens only when the overlay filter is being removed.
    synchronized(renderLock) { Unit }
    val buffers = synchronized(stateLock) {
      listOfNotNull(backBuffer, completedFrame?.bitmap).distinct().also {
        backBuffer = null
        completedFrame = null
        rendering = false
      }
    }
    buffers.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
  }
}
