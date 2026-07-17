/*
 *
 *  * Copyright (C) 2024 pedroSG94.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.pedro.encoder.input.sources.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.CustomAudioEffect
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager

/**
 * Mix microphone and internal audio sources in one source to allow send both at the same time.
 * NOTES:
 * Recommended configure prepareAudio with:
 *             echoCanceler = true,
 *             noiseSuppressor = true
 * This is to avoid echo in microphone track.
 *
 * Recommended increase microphone volume to 2f,
 * because the internal audio normally is higher and you can't hear audio track properly.
 *
 * Tested in 2 devices (Android 12 and Android 14). This could change depend of the model or not, I'm not sure:
 * MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC -> If other app open the microphone you receive buffers with silence from the microphone until the other app release the microphone (maybe you need close the app).
 * MediaRecorder.AudioSource.CAMCORDER -> Block the access to microphone to others apps. Others apps can't instantiate the microphone.
 * MediaRecorder.AudioSource.VOICE_COMMUNICATION -> Block the access to microphone to others apps. Others apps can instantiate the microphone but receive buffers with silence from the microphone.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MixAudioSource(
    private val mediaProjection: MediaProjection,
    mediaProjectionCallback: MediaProjection.Callback? = null,
    private val microphoneAudioSource: Int = MediaRecorder.AudioSource.MIC,
    context: Context? = null
): AudioSource(), GetMicrophoneData {

    private val TAG = "MixAudioSource"
    private var handlerThread = HandlerThread(TAG)
    private val microphone = MicrophoneManager(this)
    private var preferredDevice: AudioDeviceInfo? = null
    private val mediaProjectionCallback = mediaProjectionCallback ?: object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
        }
    }
    private var respectSystemVolume = true
    private var context: Context? = context

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        // AudioSource.init already stores these parameters. Allocate both AudioRecords together in
        // start(), after the current MediaProjection is ready; allocating a validation microphone
        // here leaked that AudioRecord when start() created the real mixed pair.
        return sampleRate > 0
    }

    fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean {
        preferredDevice = deviceInfo
        return microphone.setPreferredDevice(deviceInfo)
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (!isRunning()) {
            handlerThread = HandlerThread(TAG)
            handlerThread.start()
            mediaProjection.registerCallback(mediaProjectionCallback, Handler(handlerThread.looper))
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            // Keep the physical microphone on the caller-selected raw capture path. DEFAULT plus
            // AEC/NS can select a vendor voice-processing route; on affected Unisoc stacks that
            // route change invalidates the simultaneous REMOTE_SUBMIX AudioRecord
            // (restoreRecord_l -22), which can also tear down the MediaProjection session. Device
            // playback is already-rendered PCM, so mix both raw tracks in software and leave
            // voice-call DSP disabled here.
            val result = microphone.createMixMicrophone(
                microphoneAudioSource,
                config,
                sampleRate,
                isStereo,
                false,
                false
            )
            if (!result) {
                microphone.stop()
                throw IllegalArgumentException("Failed to create microphone audio source")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                microphone.setPreferredDevice(preferredDevice)
            }
            microphone.start()
            android.util.Log.i(
                TAG,
                "Mixed capture started with microphone source=$microphoneAudioSource and " +
                    "playback capture; voice DSP disabled"
            )
        }
    }

    override fun stop() {
        if (isRunning()) {
            getMicrophoneData = null
            microphone.stop()
            handlerThread.quitSafely()
        }
    }

    override fun isRunning(): Boolean = microphone.isRunning

    override fun release() {
        runCatching { mediaProjection.unregisterCallback(mediaProjectionCallback) }
    }

    override fun inputPCMData(frame: Frame) {
        getMicrophoneData?.inputPCMData(frame)
    }

    fun mute() {
        microphone.mute()
    }

    fun unMute() {
        microphone.unMute()
    }

    fun isMuted(): Boolean = microphone.isMuted

    fun setAudioEffect(effect: CustomAudioEffect) {
        microphone.setCustomAudioEffect(effect)
    }

    var mixVolume: Float
        set(value) { microphone.setVolume(value) }
        get() = (microphone.microphoneVolume + microphone.internalVolume) / 2f

    var microphoneVolume: Float
        set(value) { microphone.microphoneVolume = value }
        get() = microphone.microphoneVolume

    var internalVolume: Float
        set(value) { microphone.internalVolume = value }
        get() = microphone.internalVolume
        
    fun setRespectSystemVolume(respect: Boolean) {
        respectSystemVolume = respect
    }
    
    fun getRespectSystemVolume(): Boolean = respectSystemVolume
    
    fun getCurrentSystemVolume(): Float {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) return 1.0f
        
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
    }
    
    fun isSystemVolumeZero(): Boolean {
        return getCurrentSystemVolume() <= 0.01f
    }
}
