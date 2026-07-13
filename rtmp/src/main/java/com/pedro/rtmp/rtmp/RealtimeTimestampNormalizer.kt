package com.pedro.rtmp.rtmp

import android.os.SystemClock
import com.pedro.common.frame.MediaFrame

/**
 * Keeps RTMP media timestamps on a real-time clock without blocking the sender thread.
 *
 * Some hardware encoders return frames whose timestamps jump ahead of wall-clock time. Sending
 * those timestamps unchanged makes live endpoints interpret the upload as faster than real time.
 * Sleeping here is unsafe because audio and video share the same sender: a future video frame can
 * otherwise stall every audio packet behind it. Instead, timestamps are clamped independently for
 * audio and video while preserving their original timing whenever it is already real-time safe.
 */
internal class RealtimeTimestampNormalizer(
  private val nowUs: () -> Long = { SystemClock.elapsedRealtimeNanos() / 1_000L }
) {

  private var baseMediaTimestampUs: Long? = null
  private var baseRealtimeUs = 0L
  private var lastAudioTimestampUs = 0L
  private var lastVideoTimestampUs = 0L

  fun normalize(mediaTimestampUs: Long, type: MediaFrame.Type): Long {
    val now = nowUs()
    val baseMedia = baseMediaTimestampUs
    if (baseMedia == null || now < baseRealtimeUs) {
      baseMediaTimestampUs = mediaTimestampUs
      baseRealtimeUs = now
      lastAudioTimestampUs = 0L
      lastVideoTimestampUs = 0L
      return 0L
    }

    val sourceElapsedUs = (mediaTimestampUs - baseMedia).coerceAtLeast(0L)
    val realtimeElapsedUs = (now - baseRealtimeUs).coerceAtLeast(0L)
    val realtimeSafeTimestampUs = sourceElapsedUs.coerceAtMost(realtimeElapsedUs)

    return when (type) {
      MediaFrame.Type.AUDIO -> realtimeSafeTimestampUs.coerceAtLeast(lastAudioTimestampUs).also {
        lastAudioTimestampUs = it
      }
      MediaFrame.Type.VIDEO -> realtimeSafeTimestampUs.coerceAtLeast(lastVideoTimestampUs).also {
        lastVideoTimestampUs = it
      }
    }
  }

  fun reset() {
    baseMediaTimestampUs = null
    baseRealtimeUs = 0L
    lastAudioTimestampUs = 0L
    lastVideoTimestampUs = 0L
  }
}
