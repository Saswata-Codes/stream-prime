package com.stream.prime.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneCapturePolicyTest {

    @Test
    fun `normal mode uses standard microphone source`() {
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            MicrophoneCapturePolicy.sourceForGameVoiceChatCompatibility(false)
        )
    }

    @Test
    fun `compatibility mode uses shareable voice recognition source`() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MicrophoneCapturePolicy.sourceForGameVoiceChatCompatibility(true)
        )
    }
}
