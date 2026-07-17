package com.stream.prime.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAudioLevelPolicyTest {

    @Test
    fun `preserves independent user levels`() {
        val levels = CaptureAudioLevelPolicy.resolve(35, 80)

        assertEquals(0.35f, levels.microphone, 0.0001f)
        assertEquals(0.80f, levels.device, 0.0001f)
        assertTrue(levels.hasMixedAudio)
    }

    @Test
    fun `device audio keeps mixed output active when microphone is zero`() {
        val levels = CaptureAudioLevelPolicy.resolve(0, 100)

        assertFalse(levels.hasMicrophoneAudio)
        assertTrue(levels.hasDeviceAudio)
        assertTrue(levels.hasMixedAudio)
    }

    @Test
    fun `muting microphone preserves device audio channel`() {
        val levels = CaptureAudioLevelPolicy.resolve(
            microphonePercent = 100,
            devicePercent = 65,
            microphoneMuted = true
        )

        assertEquals(0f, levels.microphone, 0f)
        assertEquals(0.65f, levels.device, 0.0001f)
        assertFalse(levels.hasMicrophoneAudio)
        assertTrue(levels.hasDeviceAudio)
        assertTrue(levels.hasMixedAudio)
    }

    @Test
    fun `clamps invalid percentages`() {
        val levels = CaptureAudioLevelPolicy.resolve(-10, 140)

        assertEquals(0f, levels.microphone, 0f)
        assertEquals(1f, levels.device, 0f)
    }
}
