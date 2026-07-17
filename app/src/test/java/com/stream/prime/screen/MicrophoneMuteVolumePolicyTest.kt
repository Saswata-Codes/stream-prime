package com.stream.prime.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneMuteVolumePolicyTest {

    @Test
    fun `mute stores current volume and locks slider at zero`() {
        val state = MicrophoneMuteVolumePolicy.setMuted(
            MicrophoneMuteVolumePolicy.fromStored(73, null, muted = false),
            muted = true
        )

        assertEquals(0, state.volumePercent)
        assertEquals(73, state.restorePercent)
        assertTrue(state.muted)
        assertFalse(state.sliderEnabled)
    }

    @Test
    fun `unmute restores exact volume from before mute`() {
        val muted = MicrophoneMuteVolumePolicy.setMuted(
            MicrophoneMuteVolumePolicy.fromStored(42, null, muted = false),
            muted = true
        )

        val restored = MicrophoneMuteVolumePolicy.setMuted(muted, muted = false)

        assertEquals(42, restored.volumePercent)
        assertEquals(42, restored.restorePercent)
        assertFalse(restored.muted)
        assertTrue(restored.sliderEnabled)
    }

    @Test
    fun `repeated mute does not replace restore volume with zero`() {
        val once = MicrophoneMuteVolumePolicy.setMuted(
            MicrophoneMuteVolumePolicy.fromStored(85, null, muted = false),
            muted = true
        )

        val twice = MicrophoneMuteVolumePolicy.setMuted(once, muted = true)

        assertEquals(0, twice.volumePercent)
        assertEquals(85, twice.restorePercent)
    }

    @Test
    fun `legacy muted preference migrates nonzero slider into restore volume`() {
        val state = MicrophoneMuteVolumePolicy.fromStored(64, null, muted = true)

        assertEquals(0, state.volumePercent)
        assertEquals(64, state.restorePercent)
        assertTrue(state.muted)
    }

    @Test
    fun `volume changes are ignored while slider is locked`() {
        val muted = MicrophoneMuteVolumePolicy.fromStored(0, 58, muted = true)

        val changed = MicrophoneMuteVolumePolicy.setVolume(muted, 99)

        assertEquals(muted, changed)
    }
}
