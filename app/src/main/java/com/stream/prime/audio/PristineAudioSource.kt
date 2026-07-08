package com.stream.prime.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import kotlinx.coroutines.*
import android.util.Log

class PristineAudioSource(private val context: Context) : AudioSource(), GetMicrophoneData {
    companion object {
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private var microphoneRecorder: AudioRecord? = null
    private var deviceAudioRecorder: AudioRecord? = null
    private var microphoneBuffer: ByteArray? = null
    private var deviceAudioBuffer: ByteArray? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val bufferSize: Int
    private val microphoneBufferSize: Int
    private val deviceAudioBufferSize: Int
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    // Volume controls (0.0 to 1.0)
    private var microphoneVolume = 0.5f
    private var deviceAudioVolume = 0.5f
    private var respectSystemVolume = true
    private var isMuted = false

    init {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
        microphoneBufferSize = bufferSize
        deviceAudioBufferSize = bufferSize
    }

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        microphoneBuffer = ByteArray(microphoneBufferSize)
        deviceAudioBuffer = ByteArray(deviceAudioBufferSize)
        microphoneRecorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            microphoneBufferSize
        )
        // Use DEFAULT instead of VOICE_COMMUNICATION to respect system volume
        deviceAudioRecorder = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            deviceAudioBufferSize
        )
        return microphoneRecorder?.state == AudioRecord.STATE_INITIALIZED &&
                deviceAudioRecorder?.state == AudioRecord.STATE_INITIALIZED
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        microphoneRecorder?.startRecording()
        deviceAudioRecorder?.startRecording()
        isRecording = true
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRecording) {
                val micBytesRead = microphoneRecorder?.read(microphoneBuffer!!, 0, microphoneBufferSize) ?: 0
                val deviceBytesRead = deviceAudioRecorder?.read(deviceAudioBuffer!!, 0, deviceAudioBufferSize) ?: 0
                val maxBytes = maxOf(micBytesRead, deviceBytesRead)
                val mixedBuffer = ByteArray(maxBytes)
                
                // Get current system volume if respecting system volume
                val systemVolumeMultiplier = if (respectSystemVolume) {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
                } else {
                    1.0f
                }
                
                // If system volume is 0, completely mute device audio
                val effectiveDeviceVolume = if (systemVolumeMultiplier <= 0.01f) 0f else deviceAudioVolume * systemVolumeMultiplier
                
                // If muted, send silence
                if (isMuted) {
                    val silenceBuffer = ByteArray(maxBytes)
                    getMicrophoneData.inputPCMData(Frame(silenceBuffer, 0, silenceBuffer.size, System.nanoTime() / 1000))
                    delay(10)
                    continue
                }
                
                for (i in 0 until maxBytes step 2) {
                    val micSample = if (i + 1 < micBytesRead) ((microphoneBuffer!![i].toInt() and 0xFF) or (microphoneBuffer!![i + 1].toInt() shl 8)).toShort() else 0
                    val deviceSample = if (i + 1 < deviceBytesRead) ((deviceAudioBuffer!![i].toInt() and 0xFF) or (deviceAudioBuffer!![i + 1].toInt() shl 8)).toShort() else 0
                    
                    // Apply volume scaling with system volume consideration
                    val scaledMicSample = (micSample * microphoneVolume).toInt()
                    val scaledDeviceSample = (deviceSample * effectiveDeviceVolume).toInt()
                    
                    // Mix with overflow protection
                    val mixedSample = (scaledMicSample + scaledDeviceSample).coerceIn(-32768, 32767).toShort()
                    
                    mixedBuffer[i] = (mixedSample.toInt() and 0xFF).toByte()
                    if (i + 1 < mixedBuffer.size) mixedBuffer[i + 1] = (mixedSample.toInt() shr 8).toByte()
                }
                
                // Debug logging every 1000 frames (about once per second)
                if (System.currentTimeMillis() % 1000 < 10) {
                    Log.d("PristineAudioSource", "Audio mixing - Mic vol: ${microphoneVolume * 100}%, Device vol: ${deviceAudioVolume * 100}%, System vol: ${systemVolumeMultiplier * 100}%, Effective device vol: ${effectiveDeviceVolume * 100}%, Muted: $isMuted")
                }
                getMicrophoneData.inputPCMData(Frame(mixedBuffer, 0, mixedBuffer.size, System.nanoTime() / 1000))
                delay(10)
            }
        }
    }

    override fun stop() {
        recordingJob?.cancel()
        recordingJob = null
        microphoneRecorder?.stop()
        deviceAudioRecorder?.stop()
        isRecording = false
    }

    override fun isRunning(): Boolean = isRecording
    override fun release() {
        stop()
        microphoneRecorder?.release()
        deviceAudioRecorder?.release()
        microphoneRecorder = null
        deviceAudioRecorder = null
    }
    override fun inputPCMData(frame: Frame) { }
    
    // Volume control methods
    fun setMicrophoneVolume(volume: Float) {
        microphoneVolume = volume.coerceIn(0.0f, 1.0f)
        Log.d("PristineAudioSource", "Microphone volume set to: ${microphoneVolume * 100}%")
    }
    
    fun setDeviceAudioVolume(volume: Float) {
        deviceAudioVolume = volume.coerceIn(0.0f, 1.0f)
        Log.d("PristineAudioSource", "Device audio volume set to: ${deviceAudioVolume * 100}%")
    }
    
    fun setRespectSystemVolume(respect: Boolean) {
        respectSystemVolume = respect
        Log.d("PristineAudioSource", "System volume respect set to: $respect")
    }
    
    fun mute() {
        isMuted = true
        Log.d("PristineAudioSource", "Audio muted")
    }
    
    fun unMute() {
        isMuted = false
        Log.d("PristineAudioSource", "Audio unmuted")
    }
    
    fun isMuted(): Boolean = isMuted
    
    fun getMicrophoneVolume(): Float = microphoneVolume
    fun getDeviceAudioVolume(): Float = deviceAudioVolume
    fun getRespectSystemVolume(): Boolean = respectSystemVolume
    
    fun getCurrentSystemVolume(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
    }
    
    fun isSystemVolumeZero(): Boolean {
        return getCurrentSystemVolume() <= 0.01f
    }
} 