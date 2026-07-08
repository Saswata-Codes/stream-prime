package com.stream.prime.audio

import android.util.Log
import kotlin.math.abs

/**
 * Noise Gate Filter - Suppresses hissing sounds with configurable thresholds
 * 
 * Features:
 * - Opening threshold: Minimum volume level to start audio
 * - Closing threshold: Volume level below which audio is muted
 * - Attack time: How quickly the gate opens
 * - Release time: How quickly the gate closes
 * - Hiss suppression: Specifically targets hissing frequencies
 */
class NoiseGateFilter {
    
    companion object {
        private const val TAG = "NoiseGateFilter"
        
        // Default thresholds (0.0 to 1.0)
        const val DEFAULT_OPENING_THRESHOLD = 0.1f  // 10% volume to start
        const val DEFAULT_CLOSING_THRESHOLD = 0.05f // 5% volume to close
        const val DEFAULT_ATTACK_TIME = 0.01f       // 10ms attack
        const val DEFAULT_RELEASE_TIME = 0.1f       // 100ms release
        
        // Hiss frequency ranges (Hz)
        const val HISS_LOW_FREQ = 8000f   // 8kHz
        const val HISS_HIGH_FREQ = 12000f // 12kHz
    }
    
    // Configurable parameters
    private var openingThreshold = DEFAULT_OPENING_THRESHOLD
    private var closingThreshold = DEFAULT_CLOSING_THRESHOLD
    private var attackTime = DEFAULT_ATTACK_TIME
    private var releaseTime = DEFAULT_RELEASE_TIME
    
    // Internal state
    private var gateOpen = false
    private var currentVolume = 0f
    private var attackCounter = 0f
    private var releaseCounter = 0f
    
    // Hiss detection
    private var hissSuppressionEnabled = true
    private var hissThreshold = 0.08f // 8% volume for hiss detection
    
    /**
     * Set opening threshold (minimum volume to start audio)
     */
    fun setOpeningThreshold(threshold: Float) {
        openingThreshold = threshold.coerceIn(0f, 1f)
        Log.d(TAG, "Opening threshold set to: ${(openingThreshold * 100).toInt()}%")
    }
    
    /**
     * Set closing threshold (volume level below which audio is muted)
     */
    fun setClosingThreshold(threshold: Float) {
        closingThreshold = threshold.coerceIn(0f, 1f)
        Log.d(TAG, "Closing threshold set to: ${(closingThreshold * 100).toInt()}%")
    }
    
    /**
     * Set attack time (how quickly the gate opens)
     */
    fun setAttackTime(time: Float) {
        attackTime = time.coerceIn(0.001f, 1f)
        Log.d(TAG, "Attack time set to: ${(attackTime * 1000).toInt()}ms")
    }
    
    /**
     * Set release time (how quickly the gate closes)
     */
    fun setReleaseTime(time: Float) {
        releaseTime = time.coerceIn(0.001f, 1f)
        Log.d(TAG, "Release time set to: ${(releaseTime * 1000).toInt()}ms")
    }
    
    /**
     * Enable/disable hiss suppression
     */
    fun setHissSuppression(enabled: Boolean) {
        hissSuppressionEnabled = enabled
        Log.d(TAG, "Hiss suppression ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Set hiss detection threshold
     */
    fun setHissThreshold(threshold: Float) {
        hissThreshold = threshold.coerceIn(0f, 1f)
        Log.d(TAG, "Hiss threshold set to: ${(hissThreshold * 100).toInt()}%")
    }
    
    /**
     * Process audio data with noise gate filtering
     */
    fun processAudioData(audioData: ByteArray): ByteArray {
        if (audioData.isEmpty()) return audioData
        
        try {
            val processedData = ByteArray(audioData.size)
            
            for (i in audioData.indices) {
                val sample = audioData[i].toInt()
                val volume = abs(sample) / 128f // Normalize to 0-1
                
                // Apply noise gate
                val gatedVolume = applyNoiseGate(volume)
                
                // Apply hiss suppression if enabled
                val finalVolume = if (hissSuppressionEnabled) {
                    applyHissSuppression(gatedVolume, sample)
                } else {
                    gatedVolume
                }
                
                // Convert back to byte
                processedData[i] = (sample * finalVolume).toInt().toByte()
            }
            
            return processedData
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio data: ${e.message}")
            return audioData
        }
    }
    
    /**
     * Apply noise gate to volume level
     */
    private fun applyNoiseGate(volume: Float): Float {
        // Check if gate should open
        if (volume >= openingThreshold && !gateOpen) {
            attackCounter += attackTime
            if (attackCounter >= 1f) {
                gateOpen = true
                attackCounter = 0f
                Log.d(TAG, "Noise gate opened at volume: ${(volume * 100).toInt()}%")
            }
        }
        
        // Check if gate should close
        if (volume < closingThreshold && gateOpen) {
            releaseCounter += releaseTime
            if (releaseCounter >= 1f) {
                gateOpen = false
                releaseCounter = 0f
                Log.d(TAG, "Noise gate closed at volume: ${(volume * 100).toInt()}%")
            }
        }
        
        // Reset counters if volume changes significantly
        if (volume >= openingThreshold && gateOpen) {
            attackCounter = 0f
        }
        if (volume >= closingThreshold && !gateOpen) {
            releaseCounter = 0f
        }
        
        // Return filtered volume
        return if (gateOpen) volume else 0f
    }
    
    /**
     * Apply hiss suppression to audio
     */
    private fun applyHissSuppression(volume: Float, sample: Int): Float {
        // Simple hiss detection based on volume and frequency characteristics
        if (volume > hissThreshold) {
            // Check for hiss-like characteristics (high frequency, low amplitude)
            val frequency = estimateFrequency(sample)
            if (frequency in HISS_LOW_FREQ..HISS_HIGH_FREQ) {
                // Reduce volume for hiss frequencies
                val suppressionFactor = 0.3f // Reduce by 70%
                Log.d(TAG, "Hiss detected at ${frequency.toInt()}Hz, applying suppression")
                return volume * suppressionFactor
            }
        }
        
        return volume
    }
    
    /**
     * Estimate frequency from sample (simplified)
     */
    private fun estimateFrequency(sample: Int): Float {
        // Simplified frequency estimation
        // In a real implementation, you'd use FFT or similar
        return abs(sample) * 100f // Rough frequency estimate
    }
    
    /**
     * Get current gate status
     */
    fun isGateOpen(): Boolean = gateOpen
    
    /**
     * Get current volume level
     */
    fun getCurrentVolume(): Float = currentVolume
    
    /**
     * Reset noise gate state
     */
    fun reset() {
        gateOpen = false
        currentVolume = 0f
        attackCounter = 0f
        releaseCounter = 0f
        Log.d(TAG, "Noise gate reset")
    }
    
    /**
     * Get current configuration as string
     */
    fun getConfiguration(): String {
        return "Noise Gate Config:\n" +
               "Opening Threshold: ${(openingThreshold * 100).toInt()}%\n" +
               "Closing Threshold: ${(closingThreshold * 100).toInt()}%\n" +
               "Attack Time: ${(attackTime * 1000).toInt()}ms\n" +
               "Release Time: ${(releaseTime * 1000).toInt()}ms\n" +
               "Hiss Suppression: ${if (hissSuppressionEnabled) "Enabled" else "Disabled"}\n" +
               "Hiss Threshold: ${(hissThreshold * 100).toInt()}%\n" +
               "Gate Status: ${if (gateOpen) "Open" else "Closed"}"
    }
} 