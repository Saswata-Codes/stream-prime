package com.pedro.encoder.input.audio

class AudioUtils {

    fun applyVolumeAndMix(
        buffer: ByteArray, volume: Float,
        buffer2: ByteArray, volume2: Float,
        dst: ByteArray
    ) {
        if (buffer.size != buffer2.size) return
        if (volume == 1f && volume2 == 1f) {
            for (i in buffer.indices step 2) {
                val sample1 = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                val sample2 = (buffer2[i + 1].toInt() shl 8) or (buffer2[i].toInt() and 0xFF)
                
                // Convert from unsigned to signed 16-bit
                val signedSample1 = if (sample1 > 32767) sample1 - 65536 else sample1
                val signedSample2 = if (sample2 > 32767) sample2 - 65536 else sample2
                
                // Mix and clamp to prevent overflow
                var mixedSample = signedSample1 + signedSample2
                mixedSample = mixedSample.coerceIn(-32768, 32767)
                
                // Convert back to unsigned bytes
                val unsignedSample = if (mixedSample < 0) mixedSample + 65536 else mixedSample
                dst[i] = (unsignedSample and 0xFF).toByte()
                dst[i + 1] = ((unsignedSample shr 8) and 0xFF).toByte()
            }
            return
        }
        for (i in buffer.indices step 2) {
            val sample1 = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val sample2 = (buffer2[i + 1].toInt() shl 8) or (buffer2[i].toInt() and 0xFF)
            
            // Convert from unsigned to signed 16-bit
            val signedSample1 = if (sample1 > 32767) sample1 - 65536 else sample1
            val signedSample2 = if (sample2 > 32767) sample2 - 65536 else sample2
            
            // Apply volume and mix
            val adjustedSample1 = (signedSample1 * volume).toInt()
            val adjustedSample2 = (signedSample2 * volume2).toInt()
            var mixedSample = adjustedSample1 + adjustedSample2
            
            // Clamp to prevent overflow/distortion
            mixedSample = mixedSample.coerceIn(-32768, 32767)
            
            // Convert back to unsigned bytes
            val unsignedSample = if (mixedSample < 0) mixedSample + 65536 else mixedSample
            dst[i] = (unsignedSample and 0xFF).toByte()
            dst[i + 1] = ((unsignedSample shr 8) and 0xFF).toByte()
        }
    }

    fun applyVolume(buffer: ByteArray, volume: Float) {
        if (volume == 1f) return

        for (i in buffer.indices step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            
            // Convert from unsigned to signed 16-bit
            val signedSample = if (sample > 32767) sample - 65536 else sample
            
            // Apply volume and clamp
            var adjustedSample = (signedSample * volume).toInt()
            adjustedSample = adjustedSample.coerceIn(-32768, 32767)
            
            // Convert back to unsigned bytes
            val unsignedSample = if (adjustedSample < 0) adjustedSample + 65536 else adjustedSample
            buffer[i] = (unsignedSample and 0xFF).toByte()
            buffer[i + 1] = ((unsignedSample shr 8) and 0xFF).toByte()
        }
    }

    /**
     * assume always pcm 16bit
     * @return value from 0f to 100f
     */
    fun calculateAmplitude(buffer: ByteArray): Float {
        if (buffer.size % 2 != 0) return 0f
        var amplitude = 0
        for (i in buffer.indices step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)

            // Convert from unsigned to signed 16-bit (SAME AS YOUR OTHER FUNCTIONS)
            val signedSample = if (sample > 32767) sample - 65536 else sample

            // Use absolute value for amplitude
            val sampleAmplitude = kotlin.math.abs(signedSample)
            if (sampleAmplitude > amplitude) amplitude = sampleAmplitude
        }
        return (amplitude / Short.MAX_VALUE.toFloat()) * 100
    }
}