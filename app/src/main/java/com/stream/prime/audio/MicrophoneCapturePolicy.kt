package com.stream.prime.audio

import android.media.MediaRecorder

/** Selects the physical microphone route without coupling the decision to Android settings I/O. */
object MicrophoneCapturePolicy {
    fun sourceForGameVoiceChatCompatibility(enabled: Boolean): Int =
        if (enabled) MediaRecorder.AudioSource.VOICE_RECOGNITION
        else MediaRecorder.AudioSource.MIC
}
