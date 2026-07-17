package com.stream.prime.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.encoder.input.sources.audio.AudioSource

/**
 * Low-latency microphone-only source used when internal playback capture is not selected.
 *
 * AudioSource.create() can be called more than once while ScreenService refreshes its encoder
 * settings. It must therefore only validate/store the requested format. Opening AudioRecord in
 * create() leaked the previous record every time prepareAudio() ran and left four microphone
 * inputs alive just before mixed playback capture started on affected devices.
 *
 * Internal/device audio is intentionally not opened here. MediaRecorder.AudioSource.DEFAULT is
 * another physical input, not Android playback capture. Internal-only and mixed capture are
 * handled by InternalAudioSource and MixAudioSource using AudioPlaybackCaptureConfiguration.
 */
class PristineAudioSource(
    private val context: Context,
    private val microphoneAudioSource: Int = MediaRecorder.AudioSource.MIC
) : AudioSource(), GetMicrophoneData {

    companion object {
        private const val TAG = "PristineAudioSource"
    }

    private val microphone = MicrophoneManager(this)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var deviceAudioVolume = 0.5f
    private var respectSystemVolume = true

    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        // AudioSource.init() stores these values. Allocate the real AudioRecord only in start().
        return sampleRate > 0
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (microphone.isRunning) return

        val created = microphone.createMicrophone(
            microphoneAudioSource,
            sampleRate,
            isStereo,
            echoCanceler,
            noiseSuppressor
        )
        if (!created) {
            this.getMicrophoneData = null
            microphone.stop()
            throw IllegalArgumentException("Failed to create pristine microphone audio source")
        }
        microphone.start()
        Log.i(
            TAG,
            "Microphone started lazily: ${sampleRate}Hz, stereo=$isStereo, source=$microphoneAudioSource"
        )
    }

    override fun stop() {
        getMicrophoneData = null
        if (microphone.isRunning || microphone.isCreated) {
            microphone.stop()
        }
    }

    override fun isRunning(): Boolean = microphone.isRunning

    override fun release() {
        stop()
    }

    override fun inputPCMData(frame: Frame) {
        getMicrophoneData?.inputPCMData(frame)
    }

    fun setMicrophoneVolume(volume: Float) {
        microphone.microphoneVolume = volume.coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Microphone volume set to: ${microphone.microphoneVolume * 100}%")
    }

    /**
     * Retained for the shared settings UI. It is applied by MixAudioSource when device capture is
     * selected; this microphone-only source never opens a fake second physical input.
     */
    fun setDeviceAudioVolume(volume: Float) {
        deviceAudioVolume = volume.coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Device audio volume stored for mixed capture: ${deviceAudioVolume * 100}%")
    }

    fun setRespectSystemVolume(respect: Boolean) {
        respectSystemVolume = respect
        Log.d(TAG, "System volume respect set to: $respect")
    }

    fun mute() {
        microphone.mute()
        Log.d(TAG, "Audio muted")
    }

    fun unMute() {
        microphone.unMute()
        Log.d(TAG, "Audio unmuted")
    }

    fun isMuted(): Boolean = microphone.isMuted

    fun getMicrophoneVolume(): Float = microphone.microphoneVolume

    fun getDeviceAudioVolume(): Float = deviceAudioVolume

    fun getRespectSystemVolume(): Boolean = respectSystemVolume

    fun getCurrentSystemVolume(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
    }

    fun isSystemVolumeZero(): Boolean = getCurrentSystemVolume() <= 0.01f
}
