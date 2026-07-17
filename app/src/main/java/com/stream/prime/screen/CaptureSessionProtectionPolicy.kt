package com.stream.prime.screen

/**
 * Defines when the capture process needs extra background protection.
 *
 * A pending RTMP connection is protected too because the encoder and MediaProjection are already
 * active while the network client is connecting or reconnecting.
 */
object CaptureSessionProtectionPolicy {
  fun shouldProtect(
    isStreaming: Boolean,
    isRecording: Boolean,
    streamRequested: Boolean
  ): Boolean = isStreaming || isRecording || streamRequested
}
