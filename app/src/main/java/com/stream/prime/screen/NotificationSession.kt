package com.stream.prime.screen

import java.util.Locale

/** Selects the timer base shown by the foreground notification. */
internal object NotificationSession {

  fun microphoneActionLabel(isMuted: Boolean): String =
    if (isMuted) "Unmute Mic" else "Mute Mic"

  /**
   * Android 15 builds from these affected vendor families can revoke MediaProjection when its
   * foreground notification is replaced. Other devices can safely refresh action labels.
   */
  fun shouldAvoidForegroundRefresh(
    sdkInt: Int,
    manufacturer: String?,
    hardware: String?
  ): Boolean {
    if (sdkInt < 35) return false
    val vendor = manufacturer.orEmpty().lowercase(Locale.US)
    val platform = hardware.orEmpty().lowercase(Locale.US)
    return vendor.contains("motorola") ||
      platform.contains("unisoc") ||
      platform.contains("spreadtrum") ||
      platform.startsWith("ums") ||
      platform.startsWith("sprd")
  }

  fun startedAt(
    isStreaming: Boolean,
    isRecording: Boolean,
    streamStartedAt: Long,
    recordingStartedAt: Long
  ): Long {
    val activeStarts = buildList {
      if (isStreaming && streamStartedAt > 0L) add(streamStartedAt)
      if (isRecording && recordingStartedAt > 0L) add(recordingStartedAt)
    }
    return activeStarts.minOrNull() ?: 0L
  }
}
