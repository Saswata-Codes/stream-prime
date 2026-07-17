package com.stream.prime.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionProtectionPolicyTest {

  @Test
  fun `idle capture does not need session protection`() {
    assertFalse(
      CaptureSessionProtectionPolicy.shouldProtect(
        isStreaming = false,
        isRecording = false,
        streamRequested = false
      )
    )
  }

  @Test
  fun `live stream needs session protection`() {
    assertTrue(
      CaptureSessionProtectionPolicy.shouldProtect(
        isStreaming = true,
        isRecording = false,
        streamRequested = false
      )
    )
  }

  @Test
  fun `recording needs session protection`() {
    assertTrue(
      CaptureSessionProtectionPolicy.shouldProtect(
        isStreaming = false,
        isRecording = true,
        streamRequested = false
      )
    )
  }

  @Test
  fun `connecting or reconnecting needs session protection`() {
    assertTrue(
      CaptureSessionProtectionPolicy.shouldProtect(
        isStreaming = false,
        isRecording = false,
        streamRequested = true
      )
    )
  }
}
