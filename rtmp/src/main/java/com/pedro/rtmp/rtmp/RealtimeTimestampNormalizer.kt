package com.pedro.rtmp.rtmp

import android.os.SystemClock
import com.pedro.common.frame.MediaFrame

/**
 * Keeps RTMP media timestamps on a real-time clock without damaging AAC cadence.
 *
 * Some hardware video encoders return frames whose timestamps jump ahead of wall-clock time.
 * Sending those timestamps unchanged makes live endpoints interpret the upload as faster than
 * real time, so video is clamped to the elapsed sender clock.
 *
 * AAC is different: encoders commonly emit two or more 1024-sample access units in one callback
 * burst. Clamping each audio unit to the few microseconds spent processing that burst collapses
 * their 21.3 ms spacing at 48 kHz. Receivers then play the burst too quickly, which is heard as
 * crackling and gradually damages A/V sync. Audio therefore keeps the same zero-based timestamp
 * cadence used by the recording muxer. Monotonic guards remain independent for each track.
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
    val normalizedTimestampUs = when (type) {
      MediaFrame.Type.AUDIO -> sourceElapsedUs
      MediaFrame.Type.VIDEO -> sourceElapsedUs.coerceAtMost(realtimeElapsedUs)
    }

    return when (type) {
      MediaFrame.Type.AUDIO -> normalizedTimestampUs.coerceAtLeast(lastAudioTimestampUs).also {
        lastAudioTimestampUs = it
      }
      MediaFrame.Type.VIDEO -> normalizedTimestampUs.coerceAtLeast(lastVideoTimestampUs).also {
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
