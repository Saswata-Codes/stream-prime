package com.stream.prime.screen

/**
 * Resolves the two in-app capture sliders without coupling recording/streaming audio to the
 * physical speaker volume. Playback capture is a recording path; muting the speaker must not
 * silently mute the encoded mix or the microphone.
 */
internal object CaptureAudioLevelPolicy {

    data class Levels(
        val microphone: Float,
        val device: Float
    ) {
        val hasMicrophoneAudio: Boolean get() = microphone > 0f
        val hasDeviceAudio: Boolean get() = device > 0f
        val hasMixedAudio: Boolean get() = hasMicrophoneAudio || hasDeviceAudio
    }

    fun resolve(
        microphonePercent: Int,
        devicePercent: Int,
        microphoneMuted: Boolean = false
    ): Levels = Levels(
        // Muting the microphone is a channel operation. The device channel must stay at its
        // configured level so playback capture continues without restarting AudioRecord.
        microphone = if (microphoneMuted) 0f else microphonePercent.coerceIn(0, 100) / 100f,
        device = devicePercent.coerceIn(0, 100) / 100f
    )
}
