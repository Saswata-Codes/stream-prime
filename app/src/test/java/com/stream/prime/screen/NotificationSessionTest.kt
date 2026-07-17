package com.stream.prime.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSessionTest {

  @Test
  fun microphoneActionOffersMuteWhileMicrophoneIsActive() {
    assertEquals("Mute Mic", NotificationSession.microphoneActionLabel(isMuted = false))
  }

  @Test
  fun microphoneActionOffersUnmuteWhileMicrophoneIsMuted() {
    assertEquals("Unmute Mic", NotificationSession.microphoneActionLabel(isMuted = true))
  }

  @Test
  fun normalDevicesCanRefreshForegroundNotification() {
    assertFalse(
      NotificationSession.shouldAvoidForegroundRefresh(
        sdkInt = 35,
        manufacturer = "HUAWEI",
        hardware = "kirin970"
      )
    )
  }

  @Test
  fun affectedAndroid15VendorBuildsKeepProjectionWorkaround() {
    assertTrue(
      NotificationSession.shouldAvoidForegroundRefresh(
        sdkInt = 35,
        manufacturer = "motorola",
        hardware = "ums9230"
      )
    )
    assertFalse(
      NotificationSession.shouldAvoidForegroundRefresh(
        sdkInt = 34,
        manufacturer = "motorola",
        hardware = "ums9230"
      )
    )
  }

  @Test
  fun liveStreamUsesItsOwnStartTime() {
    assertEquals(
      1_000L,
      NotificationSession.startedAt(
        isStreaming = true,
        isRecording = false,
        streamStartedAt = 1_000L,
        recordingStartedAt = 0L
      )
    )
  }

  @Test
  fun recordingUsesItsOwnStartTime() {
    assertEquals(
      2_000L,
      NotificationSession.startedAt(
        isStreaming = false,
        isRecording = true,
        streamStartedAt = 0L,
        recordingStartedAt = 2_000L
      )
    )
  }

  @Test
  fun simultaneousRecordingAndStreamingShowsTotalActiveDuration() {
    assertEquals(
      1_000L,
      NotificationSession.startedAt(
        isStreaming = true,
        isRecording = true,
        streamStartedAt = 3_000L,
        recordingStartedAt = 1_000L
      )
    )
  }

  @Test
  fun inactiveSessionHasNoTimer() {
    assertEquals(
      0L,
      NotificationSession.startedAt(
        isStreaming = false,
        isRecording = false,
        streamStartedAt = 1_000L,
        recordingStartedAt = 2_000L
      )
    )
  }
}
