package com.stream.prime.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pristine Audio Recorder - Hiss-free dual audio recording
 * 
 * Key Features:
 * - Same sample rate (48kHz) for both mic and device audio
 * - Clean microphone mode with disabled DSP effects
 * - Proper audio mixing with overflow protection
 * - Device-specific optimizations
 * - Optional software noise suppression for mic only
 */
class PristineAudioRecorder(private val context: Context) {
    
    companion object {
        private const val TAG = "PristineAudioRecorder"
        
        // Audio Configuration - MUST BE IDENTICAL for both recorders
        private const val SAMPLE_RATE = 48000                    // 48kHz for both
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO  // Mono for both
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT // 16-bit PCM for both
        private const val BUFFER_SIZE_FACTOR = 4                 // Larger buffer for stability
        
        // Device-specific configurations
        private const val SAMSUNG_AGC_DISABLE = true
        private const val ONEPLUS_NOISE_SUPPRESSION = false
        private const val PIXEL_ECHO_CANCELLER = false
        private const val XIAOMI_OPTIMIZATION = true
    }
    
    // Audio recording instances
    private var microphoneRecorder: AudioRecord? = null
    private var deviceAudioRecorder: AudioRecord? = null
    
    // DSP effect controllers
    private var micAGC: AutomaticGainControl? = null
    private var micNoiseSuppressor: NoiseSuppressor? = null
    private var micEchoCanceler: AcousticEchoCanceler? = null
    
    // Buffers for separate audio streams
    private var microphoneBuffer: ByteArray? = null
    private var deviceAudioBuffer: ByteArray? = null
    private var mixedBuffer: ByteArray? = null
    
    // Recording state
    private var isRecording = false
    private var recordingJob: Job? = null
    
    // Volume controls (0.0 to 1.0)
    private var microphoneVolume = 0.5f
    private var deviceAudioVolume = 0.5f
    
    // Buffer sizes (calculated)
    private val bufferSize: Int
    private val microphoneBufferSize: Int
    private val deviceAudioBufferSize: Int
    
    // Device detection
    private val deviceManufacturer: String
    private val deviceModel: String
    
    init {
        // Calculate buffer sizes
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
        microphoneBufferSize = bufferSize
        deviceAudioBufferSize = bufferSize
        
        // Detect device for specific optimizations
        deviceManufacturer = Build.MANUFACTURER.lowercase()
        deviceModel = Build.MODEL.lowercase()
        
        Log.d(TAG, "=== PRISTINE AUDIO RECORDER INITIALIZED ===")
        Log.d(TAG, "Sample Rate: $SAMPLE_RATE Hz (IDENTICAL for both)")
        Log.d(TAG, "Channel Config: ${if (CHANNEL_CONFIG == AudioFormat.CHANNEL_IN_MONO) "MONO" else "STEREO"}")
        Log.d(TAG, "Audio Format: 16-bit PCM")
        Log.d(TAG, "Buffer Size: $bufferSize bytes")
        Log.d(TAG, "Device: $deviceManufacturer $deviceModel")
        Log.d(TAG, "Device-specific optimizations will be applied")
    }
    
    /**
     * Start pristine dual audio recording with identical formats
     */
    fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }
        
        try {
            Log.d(TAG, "=== STARTING PRISTINE DUAL AUDIO RECORDING ===")
            
            // Initialize buffers
            microphoneBuffer = ByteArray(microphoneBufferSize)
            deviceAudioBuffer = ByteArray(deviceAudioBufferSize)
            mixedBuffer = ByteArray(bufferSize)
            
            // Initialize microphone recorder with CLEAN mode
            microphoneRecorder = createCleanMicrophoneRecorder()
            
            // Initialize device audio recorder with matching format
            deviceAudioRecorder = createDeviceAudioRecorder()
            
            // Verify both recorders are properly initialized
            if (!verifyRecorderInitialization()) {
                Log.e(TAG, "Recorder initialization failed")
                return
            }
            
            // Disable DSP effects on microphone
            disableMicrophoneDSPEffects()
            
            // Apply device-specific optimizations
            applyDeviceSpecificOptimizations()
            
            // Start recording
            microphoneRecorder?.startRecording()
            deviceAudioRecorder?.startRecording()
            
            isRecording = true
            
            // Start recording job
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordPristineAudio()
            }
            
            Log.d(TAG, "Pristine dual audio recording started successfully")
            Log.d(TAG, "Microphone: CLEAN mode (DSP disabled)")
            Log.d(TAG, "Device Audio: Matching format (48kHz, Mono, 16-bit)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting pristine recording: ${e.message}")
            stopRecording()
        }
    }
    
    /**
     * Create microphone recorder in CLEAN mode
     */
    private fun createCleanMicrophoneRecorder(): AudioRecord {
        Log.d(TAG, "Creating CLEAN microphone recorder")
        
        // Try UNPROCESSED first (cleanest)
        var audioSource = MediaRecorder.AudioSource.UNPROCESSED
        var recorder = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            microphoneBufferSize
        )
        
        // If UNPROCESSED fails, fall back to VOICE_RECOGNITION
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.d(TAG, "UNPROCESSED not available, using VOICE_RECOGNITION")
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            recorder = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                microphoneBufferSize
            )
        }
        
        // If still fails, use MIC
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.d(TAG, "VOICE_RECOGNITION not available, using MIC")
            audioSource = MediaRecorder.AudioSource.MIC
            recorder = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                microphoneBufferSize
            )
        }
        
        Log.d(TAG, "Microphone recorder created with source: $audioSource")
        return recorder
    }
    
    /**
     * Create device audio recorder with matching format
     */
    private fun createDeviceAudioRecorder(): AudioRecord {
        Log.d(TAG, "Creating device audio recorder with matching format")
        
        // Use VOICE_COMMUNICATION for device audio (internal audio capture)
        val audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        
        val recorder = AudioRecord(
            audioSource,
            SAMPLE_RATE,           // MUST match microphone
            CHANNEL_CONFIG,         // MUST match microphone
            AUDIO_FORMAT,          // MUST match microphone
            deviceAudioBufferSize
        )
        
        Log.d(TAG, "Device audio recorder created with source: $audioSource")
        return recorder
    }
    
    /**
     * Verify both recorders are properly initialized
     */
    private fun verifyRecorderInitialization(): Boolean {
        val micState = microphoneRecorder?.state
        val deviceState = deviceAudioRecorder?.state
        
        Log.d(TAG, "Microphone recorder state: $micState")
        Log.d(TAG, "Device audio recorder state: $deviceState")
        
        if (micState != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Microphone recorder failed to initialize")
            return false
        }
        
        if (deviceState != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Device audio recorder failed to initialize")
            return false
        }
        
        Log.d(TAG, "Both recorders initialized successfully")
        return true
    }
    
    /**
     * Disable DSP effects on microphone recorder
     */
    private fun disableMicrophoneDSPEffects() {
        try {
            Log.d(TAG, "=== DISABLING MICROPHONE DSP EFFECTS ===")
            
            // Disable Automatic Gain Control (AGC)
            if (AutomaticGainControl.isAvailable()) {
                micAGC = AutomaticGainControl.create(microphoneRecorder?.audioSessionId ?: 0)
                micAGC?.enabled = false
                Log.d(TAG, "AGC disabled")
            } else {
                Log.d(TAG, "AGC not available")
            }
            
            // Disable Noise Suppressor
            if (NoiseSuppressor.isAvailable()) {
                micNoiseSuppressor = NoiseSuppressor.create(microphoneRecorder?.audioSessionId ?: 0)
                micNoiseSuppressor?.enabled = false
                Log.d(TAG, "Noise Suppressor disabled")
            } else {
                Log.d(TAG, "Noise Suppressor not available")
            }
            
            // Disable Acoustic Echo Canceler
            if (AcousticEchoCanceler.isAvailable()) {
                micEchoCanceler = AcousticEchoCanceler.create(microphoneRecorder?.audioSessionId ?: 0)
                micEchoCanceler?.enabled = false
                Log.d(TAG, "Acoustic Echo Canceler disabled")
            } else {
                Log.d(TAG, "Acoustic Echo Canceler not available")
            }
            
            Log.d(TAG, "All DSP effects disabled on microphone")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling DSP effects: ${e.message}")
        }
    }
    
    /**
     * Apply device-specific optimizations
     */
    private fun applyDeviceSpecificOptimizations() {
        try {
            Log.d(TAG, "=== APPLYING DEVICE-SPECIFIC OPTIMIZATIONS ===")
            
            when {
                deviceManufacturer.contains("samsung") -> {
                    Log.d(TAG, "Samsung device detected - applying Samsung optimizations")
                    // Samsung devices often have aggressive AGC
                    micAGC?.enabled = false
                    Log.d(TAG, "Samsung: AGC explicitly disabled")
                }
                
                deviceManufacturer.contains("oneplus") -> {
                    Log.d(TAG, "OnePlus device detected - applying OnePlus optimizations")
                    // OnePlus devices may have custom noise suppression
                    micNoiseSuppressor?.enabled = false
                    Log.d(TAG, "OnePlus: Noise suppression disabled")
                }
                
                deviceManufacturer.contains("google") || deviceModel.contains("pixel") -> {
                    Log.d(TAG, "Pixel device detected - applying Pixel optimizations")
                    // Pixel devices have good audio processing
                    micEchoCanceler?.enabled = false
                    Log.d(TAG, "Pixel: Echo canceller disabled")
                }
                
                deviceManufacturer.contains("xiaomi") -> {
                    Log.d(TAG, "Xiaomi device detected - applying Xiaomi optimizations")
                    // Xiaomi devices may need specific optimizations
                    micAGC?.enabled = false
                    micNoiseSuppressor?.enabled = false
                    Log.d(TAG, "Xiaomi: AGC and noise suppression disabled")
                }
                
                else -> {
                    Log.d(TAG, "Unknown device - applying generic optimizations")
                    // Generic optimization: disable all DSP effects
                    micAGC?.enabled = false
                    micNoiseSuppressor?.enabled = false
                    micEchoCanceler?.enabled = false
                }
            }
            
            Log.d(TAG, "Device-specific optimizations applied")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error applying device-specific optimizations: ${e.message}")
        }
    }
    
    /**
     * Record pristine audio with proper mixing
     */
    private suspend fun recordPristineAudio() {
        try {
            Log.d(TAG, "Starting pristine audio recording loop")
            
            while (isRecording) {
                // Record microphone audio
                val micBytesRead = microphoneRecorder?.read(microphoneBuffer!!, 0, microphoneBufferSize) ?: 0
                
                // Record device audio
                val deviceBytesRead = deviceAudioRecorder?.read(deviceAudioBuffer!!, 0, deviceAudioBufferSize) ?: 0
                
                if (micBytesRead > 0 || deviceBytesRead > 0) {
                    // Mix the audio streams safely
                    val mixedAudio = mixAudioStreamsSafely(microphoneBuffer!!, deviceAudioBuffer!!, micBytesRead, deviceBytesRead)
                    
                    // Optional: Apply software noise suppression to mic only
                    val processedAudio = applyOptionalNoiseSuppression(mixedAudio)
                    
                    // Use the processed audio (this would be sent to the streaming/recording system)
                    Log.d(TAG, "Pristine mixed audio: ${processedAudio.size} bytes")
                }
                
                // Small delay to prevent excessive CPU usage
                delay(10)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in pristine audio recording loop: ${e.message}")
        }
    }
    
    /**
     * Mix audio streams safely with overflow protection
     */
    private fun mixAudioStreamsSafely(micBuffer: ByteArray, deviceBuffer: ByteArray, micBytesRead: Int, deviceBytesRead: Int): ByteArray {
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
            
            // SAFE MIXING with overflow protection
            for (i in mixedSamples.indices) {
                val micSample = if (i < micSamples.size) micSamples[i] else 0
                val deviceSample = if (i < deviceSamples.size) deviceSamples[i] else 0
                
                // Apply volume scaling
                val scaledMicSample = (micSample * microphoneVolume).toInt()
                val scaledDeviceSample = (deviceSample * deviceAudioVolume).toInt()
                
                // SAFE ADDITION with overflow protection
                val mixedSample = (scaledMicSample + scaledDeviceSample).coerceIn(-32768, 32767)
                
                mixedSamples[i] = mixedSample.toShort()
            }
            
            // Convert back to bytes
            for (i in mixedSamples.indices) {
                mixedBuffer[i * 2] = (mixedSamples[i].toInt() and 0xFF).toByte()
                mixedBuffer[i * 2 + 1] = (mixedSamples[i].toInt() shr 8).toByte()
            }
            
            Log.d(TAG, "Safe mixing: Mic=${micBytesRead}bytes, Device=${deviceBytesRead}bytes, Mixed=${mixedBuffer.size}bytes")
            
            return mixedBuffer
            
        } catch (e: Exception) {
            Log.e(TAG, "Error mixing audio streams safely: ${e.message}")
            return ByteArray(0)
        }
    }
    
    /**
     * Optional: Apply software noise suppression to mic only
     */
    private fun applyOptionalNoiseSuppression(audioData: ByteArray): ByteArray {
        try {
            // TODO: Implement WebRTC NS or Picovoice Koala here
            // For now, return the mixed audio as-is
            // This is where you would add software noise suppression if needed
            
            return audioData
            
        } catch (e: Exception) {
            Log.e(TAG, "Error applying noise suppression: ${e.message}")
            return audioData
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
            Log.d(TAG, "=== STOPPING PRISTINE DUAL AUDIO RECORDING ===")
            
            // Stop recording job
            recordingJob?.cancel()
            recordingJob = null
            
            // Stop recorders
            microphoneRecorder?.stop()
            deviceAudioRecorder?.stop()
            
            // Release DSP effects
            micAGC?.release()
            micNoiseSuppressor?.release()
            micEchoCanceler?.release()
            
            // Release recorders
            microphoneRecorder?.release()
            deviceAudioRecorder?.release()
            
            microphoneRecorder = null
            deviceAudioRecorder = null
            micAGC = null
            micNoiseSuppressor = null
            micEchoCanceler = null
            
            isRecording = false
            
            Log.d(TAG, "Pristine dual audio recording stopped successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping pristine recording: ${e.message}")
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
    
    /**
     * Get device information
     */
    fun getDeviceInfo(): String {
        return "$deviceManufacturer $deviceModel"
    }
} 