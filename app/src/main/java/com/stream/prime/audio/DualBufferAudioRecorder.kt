package com.stream.prime.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dual Buffer Audio Recorder - Records microphone and device audio into separate buffers
 * Uses VOICE_RECOGNITION for microphone with built-in noise suppression
 * Mixes the streams in the app for cleaner audio
 */
class DualBufferAudioRecorder {
    
    companion object {
        private const val TAG = "DualBufferAudioRecorder"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }
    
    // Audio recording instances
    private var microphoneRecorder: AudioRecord? = null
    private var deviceAudioRecorder: AudioRecord? = null
    
    // Buffers for separate audio streams
    private var microphoneBuffer: ByteArray? = null
    private var deviceAudioBuffer: ByteArray? = null
    private var mixedBuffer: ByteArray? = null
    
    // Recording state
    private var isRecording = false
    private var recordingJob: Job? = null
    
    // Volume controls
    private var microphoneVolume = 0.5f
    private var deviceAudioVolume = 0.5f
    
    // Buffer sizes
    private val bufferSize: Int
    private val microphoneBufferSize: Int
    private val deviceAudioBufferSize: Int
    
    init {
        // Calculate buffer sizes
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
        microphoneBufferSize = bufferSize
        deviceAudioBufferSize = bufferSize
        
        Log.d(TAG, "Initialized DualBufferAudioRecorder")
        Log.d(TAG, "Sample Rate: $SAMPLE_RATE Hz")
        Log.d(TAG, "Buffer Size: $bufferSize bytes")
        Log.d(TAG, "Microphone Buffer: $microphoneBufferSize bytes")
        Log.d(TAG, "Device Audio Buffer: $deviceAudioBufferSize bytes")
    }
    
    /**
     * Start recording microphone and device audio into separate buffers
     */
    fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }
        
        try {
            Log.d(TAG, "=== STARTING DUAL BUFFER RECORDING ===")
            
            // Initialize buffers
            microphoneBuffer = ByteArray(microphoneBufferSize)
            deviceAudioBuffer = ByteArray(deviceAudioBufferSize)
            mixedBuffer = ByteArray(bufferSize)
            
            // Initialize microphone recorder with VOICE_RECOGNITION for better noise suppression
            microphoneRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // Better noise suppression
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                microphoneBufferSize
            )
            
            // Initialize device audio recorder with VOICE_COMMUNICATION for internal audio
            deviceAudioRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // For internal audio capture
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                deviceAudioBufferSize
            )
            
            // Check if recorders are initialized properly
            if (microphoneRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Microphone recorder failed to initialize")
                return
            }
            
            if (deviceAudioRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Device audio recorder failed to initialize")
                return
            }
            
            // Start recording
            microphoneRecorder?.startRecording()
            deviceAudioRecorder?.startRecording()
            
            isRecording = true
            
            // Start recording job
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordAudioBuffers()
            }
            
            Log.d(TAG, "Dual buffer recording started successfully")
            Log.d(TAG, "Microphone: VOICE_RECOGNITION (noise suppressed)")
            Log.d(TAG, "Device Audio: VOICE_COMMUNICATION (internal audio)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting dual buffer recording: ${e.message}")
            stopRecording()
        }
    }
    
    /**
     * Stop recording and release resources
     */
    fun stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not recording")
            return
        }
        
        try {
            Log.d(TAG, "=== STOPPING DUAL BUFFER RECORDING ===")
            
            // Stop recording job
            recordingJob?.cancel()
            recordingJob = null
            
            // Stop recorders
            microphoneRecorder?.stop()
            deviceAudioRecorder?.stop()
            
            // Release recorders
            microphoneRecorder?.release()
            deviceAudioRecorder?.release()
            
            microphoneRecorder = null
            deviceAudioRecorder = null
            
            isRecording = false
            
            Log.d(TAG, "Dual buffer recording stopped successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping dual buffer recording: ${e.message}")
        }
    }
    
    /**
     * Record audio into separate buffers
     */
    private suspend fun recordAudioBuffers() {
        try {
            Log.d(TAG, "Starting audio buffer recording loop")
            
            while (isRecording) {
                // Record microphone audio
                val micBytesRead = microphoneRecorder?.read(microphoneBuffer!!, 0, microphoneBufferSize) ?: 0
                
                // Record device audio
                val deviceBytesRead = deviceAudioRecorder?.read(deviceAudioBuffer!!, 0, deviceAudioBufferSize) ?: 0
                
                if (micBytesRead > 0 || deviceBytesRead > 0) {
                    // Mix the audio streams
                    val mixedAudio = mixAudioStreams(microphoneBuffer!!, deviceAudioBuffer!!, micBytesRead, deviceBytesRead)
                    
                    // Process mixed audio (can apply noise gate filter here)
                    val processedAudio = processMixedAudio(mixedAudio)
                    
                    // Use the processed audio (this would be sent to the streaming/recording system)
                    Log.d(TAG, "Mixed audio: ${processedAudio.size} bytes")
                }
                
                // Small delay to prevent excessive CPU usage
                delay(10)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio buffer recording loop: ${e.message}")
        }
    }
    
    /**
     * Mix microphone and device audio streams
     */
    private fun mixAudioStreams(micBuffer: ByteArray, deviceBuffer: ByteArray, micBytesRead: Int, deviceBytesRead: Int): ByteArray {
        try {
            // Use the larger buffer size for mixing
            val maxBytes = maxOf(micBytesRead, deviceBytesRead)
            val mixedBuffer = ByteArray(maxBytes)
            
            // Convert to 16-bit samples for mixing
            val micSamples = ShortArray(maxBytes / 2)
            val deviceSamples = ShortArray(maxBytes / 2)
            val mixedSamples = ShortArray(maxBytes / 2)
            
            // Convert microphone buffer to samples
            for (i in 0 until minOf(micBytesRead / 2, micSamples.size)) {
                micSamples[i] = (micBuffer[i * 2].toInt() and 0xFF or (micBuffer[i * 2 + 1].toInt() shl 8)).toShort()
            }
            
            // Convert device buffer to samples
            for (i in 0 until minOf(deviceBytesRead / 2, deviceSamples.size)) {
                deviceSamples[i] = (deviceBuffer[i * 2].toInt() and 0xFF or (deviceBuffer[i * 2 + 1].toInt() shl 8)).toShort()
            }
            
            // Mix samples with volume control
            for (i in mixedSamples.indices) {
                val micSample = if (i < micSamples.size) micSamples[i] else 0
                val deviceSample = if (i < deviceSamples.size) deviceSamples[i] else 0
                
                // Apply volume and mix
                val mixedSample = (micSample * microphoneVolume + deviceSample * deviceAudioVolume).toInt()
                
                // Clamp to 16-bit range
                mixedSamples[i] = mixedSample.coerceIn(-32768, 32767).toShort()
            }
            
            // Convert back to bytes
            for (i in mixedSamples.indices) {
                mixedBuffer[i * 2] = (mixedSamples[i].toInt() and 0xFF).toByte()
                mixedBuffer[i * 2 + 1] = (mixedSamples[i].toInt() shr 8).toByte()
            }
            
            Log.d(TAG, "Mixed audio streams: Mic=${micBytesRead}bytes, Device=${deviceBytesRead}bytes, Mixed=${mixedBuffer.size}bytes")
            
            return mixedBuffer
            
        } catch (e: Exception) {
            Log.e(TAG, "Error mixing audio streams: ${e.message}")
            return ByteArray(0)
        }
    }
    
    /**
     * Process mixed audio (can apply noise gate filter here)
     */
    private fun processMixedAudio(audioData: ByteArray): ByteArray {
        try {
            // Here you can apply the noise gate filter or other processing
            // For now, return the mixed audio as-is
            return audioData
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing mixed audio: ${e.message}")
            return audioData
        }
    }
    
    /**
     * Set microphone volume (0.0 to 1.0)
     */
    fun setMicrophoneVolume(volume: Float) {
        microphoneVolume = volume.coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Microphone volume set to: ${microphoneVolume * 100}%")
    }
    
    /**
     * Set device audio volume (0.0 to 1.0)
     */
    fun setDeviceAudioVolume(volume: Float) {
        deviceAudioVolume = volume.coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Device audio volume set to: ${deviceAudioVolume * 100}%")
    }
    
    /**
     * Get current recording state
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Get microphone volume
     */
    fun getMicrophoneVolume(): Float = microphoneVolume
    
    /**
     * Get device audio volume
     */
    fun getDeviceAudioVolume(): Float = deviceAudioVolume
} 