/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stream.prime.screen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.TimestampMode
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.stream.prime.audio.MicrophoneCapturePolicy
import com.stream.prime.audio.PristineAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.stream.prime.R
import com.stream.prime.utils.PathUtils
import com.stream.prime.utils.toast
import com.stream.prime.settings.SettingsManager
import com.stream.prime.settings.VoiceChatCompatibilityPolicy
import com.stream.prime.accessibility.StreamAccessibilityService

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.PendingIntent
import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import com.pedro.encoder.input.audio.MicrophoneManager
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.stream.prime.overlay.OverlayManager
import com.stream.prime.overlay.LayeredOverlayRenderer
import com.stream.prime.overlay.ScreenLayoutFilterRender
import com.stream.prime.overlay.OverlayLayer
import com.stream.prime.overlay.OverlayLayerOrdering
import com.stream.prime.overlay.OverlayLayerType
import com.stream.prime.overlay.CaptureDisplayAspect


/**
 * Basic Screen service streaming implementation
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenService: Service(), ConnectChecker {

  companion object {
    private const val TAG = "ScreenService"
    private const val CHANNEL_ID = "DisplayStreamChannel"
    const val NOTIFY_ID = 123456
    var INSTANCE: ScreenService? = null
    private const val ACTION_STOP_STREAM = "com.stream.prime.STOP_STREAM"
    private const val ACTION_TOGGLE_MIC = "com.stream.prime.TOGGLE_MIC"
    private const val ACTION_APPLY_OVERLAY = "com.stream.prime.APPLY_OVERLAY"
    private const val ACTION_ARM_CAPTURE_FOREGROUND =
      "com.stream.prime.ARM_CAPTURE_FOREGROUND"
    const val ACTION_RECORDING_STATE_CHANGED = "com.stream.prime.RECORDING_STATE_CHANGED"
    const val EXTRA_IS_RECORDING = "is_recording"
    const val ACTION_MICROPHONE_STATE_CHANGED =
      "com.stream.prime.MICROPHONE_STATE_CHANGED"
    const val EXTRA_MICROPHONE_MUTED = "microphone_muted"
    const val EXTRA_MICROPHONE_VOLUME = "microphone_volume"
    const val EXTRA_MICROPHONE_RESTORE_VOLUME = "microphone_restore_volume"

    private data class StartupCaptureRequest(
      val resultCode: Int,
      val data: Intent,
      val completion: (Boolean) -> Unit
    )
    private var startupCaptureRequest: StartupCaptureRequest? = null

    /** Build the consent intent without creating ScreenService as a background/bound service. */
    fun createCaptureIntent(context: Context): Intent {
      val manager = context.applicationContext.getSystemService(
        Context.MEDIA_PROJECTION_SERVICE
      ) as MediaProjectionManager
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
      } else {
        manager.createScreenCaptureIntent()
      }
    }

    /**
     * Start capture only after consent. On the first run this creates ScreenService directly as
     * the typed foreground service; it never promotes an older bound/background service record.
     */
    fun startCapture(
      context: Context,
      resultCode: Int,
      data: Intent,
      completion: (Boolean) -> Unit
    ) {
      INSTANCE?.let { service ->
        service.prepareStream(resultCode, data, completion)
        return
      }

      startupCaptureRequest?.completion?.invoke(false)
      startupCaptureRequest = StartupCaptureRequest(resultCode, data, completion)
      try {
        ContextCompat.startForegroundService(
          context.applicationContext,
          Intent(context.applicationContext, ScreenService::class.java).apply {
            action = ACTION_ARM_CAPTURE_FOREGROUND
          }
        )
        Log.d(TAG, "Fresh capture foreground service requested after projection consent")
      } catch (error: RuntimeException) {
        Log.e(TAG, "Unable to create capture foreground service", error)
        startupCaptureRequest = null
        completion(false)
      }
    }

    private fun takeStartupCaptureRequest(): StartupCaptureRequest? {
      return startupCaptureRequest.also { startupCaptureRequest = null }
    }
  }

  private var notificationManager: NotificationManager? = null
  private val localBinder = Binder()
  private var captureWakeLock: PowerManager.WakeLock? = null
  private var sessionProtectionActive = false
  /**
   * A MediaProjection grant is tied to one uninterrupted mediaProjection foreground-service
   * session. Repeated startForeground() calls are unnecessary notification updates and some
   * vendor ActivityManager implementations briefly publish an empty service-type mask while
   * processing them, which makes MediaProjectionManager revoke the active grant.
   */
  private var captureForegroundActive = false
  private data class PendingCaptureRequest(
    val resultCode: Int,
    val data: Intent,
    val completion: (Boolean) -> Unit
  )
  private var pendingCaptureRequest: PendingCaptureRequest? = null
  private lateinit var genericStream: GenericStream
  
  fun getGenericStream(): GenericStream = genericStream
  private var mediaProjection: MediaProjection? = null
  private var mediaProjectionCallback: MediaProjection.Callback? = null
  private val mediaProjectionManager: MediaProjectionManager by lazy {
    applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
  }
  private var callback: ConnectChecker? = null
  private var width = 1280
  private var height = 720
  private var vBitrate = 2500 * 1000
  private var fps = 30
  private var rotation = 0 //0 for landscape or 90 for portrait
  private val sampleRate = 48000  // Further increased to 48kHz for professional quality
  private val isStereo = true
  private val aBitrate = 256 * 1000  // Further increased to 256k for maximum quality
  private var prepared = false
  private var layeredOverlayRenderer: LayeredOverlayRenderer? = null
  private var screenLayoutRenderer: ScreenLayoutFilterRender? = null
  private lateinit var displayManager: DisplayManager
  private var capturedDisplayRotationDegrees = 0
  private var capturedDisplayLandscapeAspect = 16f / 9f
  private val capturedDisplayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
      if (displayId != Display.DEFAULT_DISPLAY) return
      val newRotation = readCapturedDisplayRotation()
      val newLandscapeAspect = CaptureDisplayAspect.landscapeAspect(this@ScreenService)
      if (newRotation == capturedDisplayRotationDegrees &&
        kotlin.math.abs(newLandscapeAspect - capturedDisplayLandscapeAspect) < 0.001f
      ) return
      capturedDisplayRotationDegrees = newRotation
      capturedDisplayLandscapeAspect = newLandscapeAspect
      screenLayoutRenderer?.updateCaptureRotation(newRotation)
      screenLayoutRenderer?.updateCaptureLandscapeAspect(newLandscapeAspect)
      Log.d(
        TAG,
        "Captured display changed: rotation=$newRotation°, landscapeAspect=$newLandscapeAspect; " +
          "output canvas remains ${width}x$height"
      )
    }
  }
  private var recordPath = ""
  // Android playback capture (internal/mix audio) requires API 29.
  // Older devices must start with microphone audio or prepareStream() will fail.
  private var selectedAudioSource: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    R.id.audio_source_mix
  } else {
    R.id.audio_source_microphone
  }
  private var isMicrophoneMuted = false
  private var notificationReceiver: BroadcastReceiver? = null
  
  // Noise Gate Filter for hissing suppression

  private var micVolume = 100
  private var micVolumeBeforeMute = 100
  private var deviceVolume = 100
  private var lastBitrate: Long = 0
  private var streamStartTime: Long = 0
  private var recordingStartTime: Long = 0
  private var captureSessionStartedAt: Long = 0
  private val PREFS_NAME = "StreamAudioPrefs"
  private val KEY_MIC_VOLUME = "mic_volume"
  private val KEY_MIC_VOLUME_BEFORE_MUTE = "mic_volume_before_mute"
  private val KEY_DEVICE_VOLUME = "device_volume"
  private val KEY_MIC_MUTED = "mic_muted"
  private val KEY_STREAM_START_TIME = "stream_start_time"

  private fun compatibilityMicrophoneSource(): Int {
    return MicrophoneCapturePolicy.sourceForGameVoiceChatCompatibility(
      SettingsManager.isGameVoiceChatCompatibilityEnabled(this)
    )
  }

  private fun createPristineAudioSource(): PristineAudioSource {
    return PristineAudioSource(this, compatibilityMicrophoneSource())
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun createMixedAudioSource(projection: MediaProjection): MixAudioSource {
    return MixAudioSource(
      projection,
      microphoneAudioSource = compatibilityMicrophoneSource(),
      context = this
    )
  }

  private fun activateVoiceChatCompatibilityForCapture(): Boolean {
    val requestedEnabled = SettingsManager.isGameVoiceChatCompatibilityEnabled(this)
    val state = VoiceChatCompatibilityPolicy.resolve(
      requestedEnabled = requestedEnabled,
      accessibilityEnabled = StreamAccessibilityService.isEnabled(this),
      accessibilityConnected = StreamAccessibilityService.isConnected(this)
    )
    if (!state.enabled) {
      StreamAccessibilityService.setCaptureActive(this, false)
      if (requestedEnabled) {
        // The system permission was removed outside Stream Prime. Clear the stale switch and
        // continue with normal microphone behavior instead of repeatedly rejecting capture.
        SettingsManager.setGameVoiceChatCompatibilityEnabled(this, false)
        Log.w(TAG, "Mic Share disconnected; compatibility mode automatically disabled")
        Handler(Looper.getMainLooper()).post {
          toast(getString(R.string.game_voice_chat_compatibility_auto_disabled))
        }
      }
      return true
    }

    val activated = StreamAccessibilityService.setCaptureActive(this, true)
    if (!activated) {
      val message = getString(R.string.mic_share_not_ready)
      Log.e(TAG, message)
      Handler(Looper.getMainLooper()).post { toast(message) }
      callback?.onConnectionFailed(message)
      return false
    }

    Log.i(
      TAG,
      "Game voice-chat microphone compatibility active: accessibility capture identity + " +
        "VOICE_RECOGNITION source"
    )
    return true
  }

  /** Apply a settings change immediately when the settings screen is opened during capture. */
  fun refreshGameVoiceChatCompatibilityMode() {
    val captureActive = genericStream.isStreaming || genericStream.isRecording
    if (!captureActive) {
      StreamAccessibilityService.setCaptureActive(this, false)
      return
    }

    if (!activateVoiceChatCompatibilityForCapture()) return

    try {
      when (genericStream.audioSource) {
        is MixAudioSource -> mediaProjection?.let {
          genericStream.changeAudioSource(createMixedAudioSource(it))
        }
        is PristineAudioSource -> genericStream.changeAudioSource(createPristineAudioSource())
        // InternalAudioSource means the user explicitly muted the microphone; preserve that.
      }
      applyStreamAudioLevels()
      Log.i(TAG, "Game voice-chat compatibility audio source refreshed")
    } catch (error: RuntimeException) {
      Log.e(TAG, "Unable to refresh game voice-chat audio source", error)
    }
  }

  // --- RTMP auto-reconnect state ---
  private var currentEndpoint: String? = null
  private var isReconnecting: Boolean = false
  private var reconnectEndTimeMs: Long = 0L
  private val reconnectTimeoutMs: Long = 60_000L
  private val reconnectIntervalMs: Long = 5_000L
  private val reconnectHandler = Handler(Looper.getMainLooper())
  private var pendingReconnect: Runnable? = null
  @Volatile private var streamRequested = false
  @Volatile private var serviceDestroyed = false

  private fun cancelPendingReconnect() {
    pendingReconnect?.let(reconnectHandler::removeCallbacks)
    pendingReconnect = null
  }

  private fun resetReconnectState(clearEndpoint: Boolean) {
    cancelPendingReconnect()
    isReconnecting = false
    reconnectEndTimeMs = 0L
    if (clearEndpoint) currentEndpoint = null
  }

  private fun canReconnect(nowMs: Long = System.currentTimeMillis()): Boolean {
    return StreamReconnectPolicy.shouldReconnect(
      streamRequested = streamRequested,
      serviceDestroyed = serviceDestroyed,
      isCurrentService = INSTANCE === this,
      nowMs = nowMs,
      reconnectEndTimeMs = reconnectEndTimeMs
    )
  }

  private fun scheduleManualReconnect(delayMs: Long, reason: String) {
    cancelPendingReconnect()
    val endpoint = currentEndpoint ?: return
    val task = Runnable {
      pendingReconnect = null
      if (!canReconnect()) {
        Log.d(TAG, "Reconnect cancelled ($reason): stream no longer requested")
        return@Runnable
      }
      if (genericStream.isStreaming) {
        Log.d(TAG, "Reconnect skipped ($reason): stream client is already active")
        return@Runnable
      }
      Log.w(TAG, "Manual reconnect attempt to configured RTMP endpoint ($reason)")
      try {
        genericStream.startStream(endpoint)
      } catch (e: Exception) {
        Log.e(TAG, "Manual reconnect failed ($reason): ${e.message}")
      }
    }
    pendingReconnect = task
    reconnectHandler.postDelayed(task, delayMs)
  }

  private fun loadQualitySettings() {
      // Get current streaming mode from settings using SettingsManager
      val streamingMode = SettingsManager.getStreamingMode(this) ?: "Landscape"
      Log.d(TAG, "Loading quality settings for mode: $streamingMode")
      
      when (streamingMode) {
          "Vertical" -> {
              // Vertical mode - use vertical settings
              width = SettingsManager.getVerticalWidth(this)
              height = SettingsManager.getVerticalHeight(this)
              fps = SettingsManager.getVerticalFps(this)
              vBitrate = SettingsManager.getVerticalBitrate(this) * 1000
              // Don't set rotation here - let device orientation determine it
              
              Log.d(TAG, "Vertical settings loaded: ${width}x${height}, ${fps}fps, ${vBitrate}bps")
          }
          else -> {
              // Landscape mode - use landscape settings
              width = SettingsManager.getLandscapeWidth(this)
              height = SettingsManager.getLandscapeHeight(this)
              fps = SettingsManager.getLandscapeFps(this)
              vBitrate = SettingsManager.getLandscapeBitrate(this) * 1000
              
              Log.d(TAG, "Landscape settings loaded: ${width}x${height}, ${fps}fps, ${vBitrate}bps")
          }
      }
      
      // Load saved volume settings
      loadVolumeSettings()
  }

  private fun loadVolumeSettings() {
      try {
          val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
          applyMicrophoneState(readStoredMicrophoneState(prefs))
          deviceVolume = prefs.getInt(KEY_DEVICE_VOLUME, 100)
          persistMicrophoneState(prefs)
          Log.d(
            TAG,
            "Audio settings loaded - Mic: $micVolume%, restore: $micVolumeBeforeMute%, " +
              "Device: $deviceVolume%, Mic muted: $isMicrophoneMuted"
          )
          
          // Apply the loaded settings to the audio sources if streaming
          if (::genericStream.isInitialized && genericStream.isStreaming) {
              applyStreamAudioLevels()
              Log.d(TAG, "Applied loaded volume settings to active stream")
          }
      } catch (e: Exception) {
          Log.e(TAG, "Error loading volume settings: ${e.message}")
          // Use defaults if loading fails
          micVolume = 100
          micVolumeBeforeMute = 100
          deviceVolume = 100
          isMicrophoneMuted = false
      }
  }

  private fun readStoredMicrophoneState(
    prefs: SharedPreferences
  ): MicrophoneMuteVolumePolicy.State {
    return MicrophoneMuteVolumePolicy.fromStored(
      volumePercent = prefs.getInt(KEY_MIC_VOLUME, 100),
      restorePercent = if (prefs.contains(KEY_MIC_VOLUME_BEFORE_MUTE)) {
        prefs.getInt(KEY_MIC_VOLUME_BEFORE_MUTE, 100)
      } else {
        null
      },
      muted = prefs.getBoolean(KEY_MIC_MUTED, false)
    )
  }

  private fun currentMicrophoneState(): MicrophoneMuteVolumePolicy.State {
    return MicrophoneMuteVolumePolicy.fromStored(
      volumePercent = micVolume,
      restorePercent = micVolumeBeforeMute,
      muted = isMicrophoneMuted
    )
  }

  private fun applyMicrophoneState(state: MicrophoneMuteVolumePolicy.State) {
    micVolume = state.volumePercent
    micVolumeBeforeMute = state.restorePercent
    isMicrophoneMuted = state.muted
  }

  private fun persistMicrophoneState(prefs: SharedPreferences) {
    prefs.edit()
      .putInt(KEY_MIC_VOLUME, micVolume)
      .putInt(KEY_MIC_VOLUME_BEFORE_MUTE, micVolumeBeforeMute)
      .putBoolean(KEY_MIC_MUTED, isMicrophoneMuted)
      .apply()
  }

  /**
   * Prepare an AVC stream whose signalled level can actually carry the configured frame size and
   * frame rate. Some Huawei encoders otherwise choose Level 3 for 1080x1920@60 even though the
   * encoded frames exceed that level. RTMP then remains connected while strict endpoints discard
   * the undecodable video track.
   */
  private fun prepareEndpointCompatibleVideo(rotationDegrees: Int): Boolean {
    val profiles = intArrayOf(
      // Baseline forbids B-frames. The RTMP/FLV path uses zero composition-time offset, so this
      // profile is the deterministic choice for endpoints such as YouTube. High remains a device
      // fallback when Baseline at the required level is unavailable.
      MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
      MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    )

    fun tryPrepare(targetFps: Int, targetLevel: Int): Boolean {
      for (profile in profiles) {
        Log.i(
          TAG,
          "Preparing AVC ${width}x${height}@$targetFps profile=$profile level=$targetLevel"
        )
        try {
          if (genericStream.prepareVideo(
              width = width,
              height = height,
              bitrate = vBitrate,
              fps = targetFps,
              iFrameInterval = 2,
              rotation = rotationDegrees,
              profile = profile,
              level = targetLevel
            )) {
            fps = targetFps
            genericStream.getGlInterface().setForceRender(true, fps)
            Log.i(TAG, "Prepared endpoint-compatible AVC profile=$profile level=$targetLevel")
            return true
          }
        } catch (error: IllegalArgumentException) {
          Log.w(TAG, "AVC profile=$profile level=$targetLevel rejected: ${error.message}")
        }
      }
      return false
    }

    val requiredLevel = if (fps > 30) {
      MediaCodecInfo.CodecProfileLevel.AVCLevel42
    } else {
      MediaCodecInfo.CodecProfileLevel.AVCLevel41
    }
    if (tryPrepare(fps, requiredLevel)) return true

    if (fps > 30) {
      Log.w(TAG, "Configured 60fps AVC mode unsupported; retrying at 30fps Level 4.1")
      return tryPrepare(30, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
    }
    return false
  }

  private fun saveVolumeSettings() {
      try {
          val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
          prefs.edit()
              .putInt(KEY_MIC_VOLUME, micVolume)
              .putInt(KEY_MIC_VOLUME_BEFORE_MUTE, micVolumeBeforeMute)
              .putInt(KEY_DEVICE_VOLUME, deviceVolume)
              .putBoolean(KEY_MIC_MUTED, isMicrophoneMuted)
              .apply()
          Log.d(
            TAG,
            "Audio settings saved - Mic: $micVolume%, restore: $micVolumeBeforeMute%, " +
              "Device: $deviceVolume%, Mic muted: $isMicrophoneMuted"
          )
      } catch (e: Exception) {
          Log.e(TAG, "Error saving volume settings: ${e.message}")
      }
  }

  private fun configureStreamConnectionPolicy() {
    val streamClient = genericStream.getStreamClient()
    // Retry time is bounded by this service's reconnect deadline.
    streamClient.setReTries(100)
    // RTMP already detects real disconnects through socket reads/writes. The optional alive check
    // uses an ICMP reachability probe which many production relays block, producing a false
    // "No response from server" while media packets are still being accepted.
    streamClient.setCheckServerAlive(false)
  }

  override fun onCreate() {
    super.onCreate()
    serviceDestroyed = false
    streamRequested = false
    resetReconnectState(clearEndpoint = true)
    INSTANCE = this
    Log.d(TAG, "ScreenService created")
    Log.i(TAG, "App version ${com.stream.prime.BuildConfig.VERSION_NAME} (${com.stream.prime.BuildConfig.VERSION_CODE})")
    Log.i(TAG, "RTP Display service create")
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
    capturedDisplayRotationDegrees = readCapturedDisplayRotation()
    capturedDisplayLandscapeAspect = CaptureDisplayAspect.landscapeAspect(this)
    displayManager.registerDisplayListener(capturedDisplayListener, Handler(Looper.getMainLooper()))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
      notificationManager?.createNotificationChannel(channel)
    }
    
    // Load quality settings from centralized Stream Settings
    loadQualitySettings()
    
    Log.d("ScreenService", "=== ONCREATE DEBUG ===")
    Log.d("ScreenService", "Mode: ${getSharedPreferences("StreamSettings", 0).getString("streaming_mode", "Auto")}")
    Log.d("ScreenService", "Width: $width, Height: $height, FPS: $fps, Bitrate: $vBitrate, Rotation: $rotation")
    
    genericStream = GenericStream(baseContext, this, NoVideoSource(), createPristineAudioSource()).apply {
      // Video follows the capture clock. Audio follows the submitted PCM sample count so RTMP AAC
      // timestamps stay at a stable 1024-sample cadence even when codec callbacks arrive in bursts.
      setTimestampMode(TimestampMode.CLOCK, TimestampMode.BUFFER)
      //This is important to keep a constant fps because media projection only produce fps if the screen change
      // Use the actual FPS setting instead of hardcoded 15
      getGlInterface().setForceRender(true, fps)
      // Disable automatic orientation handling to prevent forced screen rotation
      getGlInterface().autoHandleOrientation = false
    }
    
    configureStreamConnectionPolicy()
    
    prepared = try {
      Log.d("ScreenService", "Initial prepareVideo with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps")
      // Always use rotation=0 to prevent forced screen orientation
      // The streaming mode only affects video output quality settings, not screen orientation
      prepareEndpointCompatibleVideo(rotationDegrees = 0) &&
          genericStream.prepareAudio(sampleRate, isStereo, aBitrate,
            echoCanceler = true,
            noiseSuppressor = true
          )
    } catch (_: IllegalArgumentException) {
      false
    }
    if (prepared) INSTANCE = this
    else Log.e(TAG, "Invalid audio or video parameters, prepare failed")
    
    // Don't start foreground service immediately - only start when actually streaming/recording
    // This prevents the SecurityException on Android 16 when permission is not granted
    
    // Load saved volume settings
    loadVolumeSettings()
    
    // Apply volume settings immediately after loading
    Log.d(TAG, "=== APPLYING VOLUME SETTINGS ON SERVICE START ===")
    Log.d(TAG, "Loaded volume settings - Mic: $micVolume%, Device: $deviceVolume%")
    applyStreamAudioLevels()


    // Load stream start time
    loadStreamStartTime()

    // Register broadcast receiver for notification actions
    notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_STREAM -> {
                    Log.d(TAG, "Stop stream action received from notification")
                    stopAllCapture()
                }
                ACTION_TOGGLE_MIC -> {
                    Log.d(TAG, "Toggle mic action received from notification")
                    toggleMicrophone()
                }
            ACTION_APPLY_OVERLAY -> {
              Log.d(TAG, "Apply overlay action received")
              applyConfiguredOverlay()
            }
            }
        }
    }
    
    registerReceiver(notificationReceiver, IntentFilter().apply {
        addAction(ACTION_STOP_STREAM)
        addAction(ACTION_TOGGLE_MIC)
        addAction(ACTION_APPLY_OVERLAY)
    }, Context.RECEIVER_NOT_EXPORTED)
  }

  private fun promoteCaptureForeground() {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Stream Prime",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Stream Prime notification channel"
    }
    
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(channel)

    startCaptureForeground(buildStatusNotification())
  }

  /**
   * Establish the foreground-service types once, then leave them unchanged for the lifetime of
   * the capture grant. MediaProjectionManagerService revokes an active projection as soon as
   * Android observes that its owner no longer has a mediaProjection foreground service.
   */
  private fun startCaptureForeground(notification: Notification) {
    // Keep one uninterrupted typed foreground session for each projection grant. Preparation
    // calls this helper more than once, but those calls must not replace the live FGS notification:
    // affected vendor builds interpret a plain notify() replacement as STOP_FOREGROUND.
    if (captureForegroundActive) {
      Log.d(TAG, "Capture foreground session already active; promotion skipped")
      return
    }

    publishCaptureForeground(notification)
    captureForegroundActive = true
    Log.d(TAG, "Capture foreground session started with explicit mediaProjection|microphone types")
  }

  private fun publishCaptureForeground(notification: Notification) {
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
        startForeground(
          NOTIFY_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
              ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
      }
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
        startForeground(
          NOTIFY_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
      }
      else -> startForeground(NOTIFY_ID, notification)
    }
  }

  private fun stopCaptureForeground() {
    if (!captureForegroundActive) return
    Log.d(TAG, "Stopping capture foreground session")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    captureForegroundActive = false
    captureSessionStartedAt = 0L
  }

  /**
   * Keep the encoder/network CPU awake and ask the opt-in accessibility process to hold a
   * priority binding while a user-requested stream or recording is active. The binding never
   * recreates this service: MediaProjection still requires fresh user consent after process
   * death.
   */
  @SuppressLint("WakelockTimeout")
  private fun refreshCaptureSessionProtection(forceInactive: Boolean = false) {
    val shouldProtect = !forceInactive &&
      ::genericStream.isInitialized &&
      CaptureSessionProtectionPolicy.shouldProtect(
        isStreaming = genericStream.isStreaming,
        isRecording = genericStream.isRecording,
        streamRequested = streamRequested
      )

    if (shouldProtect == sessionProtectionActive) {
      // A previous process can leave the cross-process request persisted as true. An explicit
      // shutdown must clear it even when this new service instance never acquired protection.
      if (forceInactive) {
        StreamAccessibilityService.setSessionProtectionActive(this, false)
      }
      return
    }
    sessionProtectionActive = shouldProtect

    if (shouldProtect) {
      runCatching {
        val lock = captureWakeLock ?: run {
          val powerManager = getSystemService(POWER_SERVICE) as PowerManager
          powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:ActiveCapture"
          ).apply {
            setReferenceCounted(false)
            captureWakeLock = this
          }
        }
        if (!lock.isHeld) lock.acquire()
      }.onFailure {
        Log.w(TAG, "Unable to acquire active capture wake lock", it)
      }
      StreamAccessibilityService.setSessionProtectionActive(this, true)
      Log.i(TAG, "Active capture background protection enabled")
      return
    }

    StreamAccessibilityService.setSessionProtectionActive(this, false)
    captureWakeLock?.let { lock ->
      if (lock.isHeld) {
        runCatching { lock.release() }
          .onFailure { Log.w(TAG, "Unable to release active capture wake lock", it) }
      }
    }
    Log.i(TAG, "Active capture background protection disabled")
  }

  /**
   * End an idle capture session in Android's required order. Removing the mediaProjection
   * foreground-service type first makes the system revoke the grant and invoke the projection
   * callback as though capture failed. Explicitly releasing the grant first keeps a user Stop
   * intentional and prevents a false reconnect/error notification.
   */
  private fun finishCaptureSessionIfIdle(): Boolean {
    if (genericStream.isStreaming || genericStream.isRecording) return false
    refreshCaptureSessionProtection(forceInactive = true)
    StreamAccessibilityService.setCaptureActive(this, false)
    releaseMediaProjection()
    stopCaptureForeground()
    stopSelf()
    return true
  }

  fun updateNotification() {
    if (!captureForegroundActive) return
    // Keep the narrow Android 15 vendor workaround without suppressing correct state updates on
    // unaffected devices. Reposting on the affected builds can revoke the projection itself.
    if (
      NotificationSession.shouldAvoidForegroundRefresh(
        Build.VERSION.SDK_INT,
        Build.MANUFACTURER,
        Build.HARDWARE
      )
    ) {
      Log.d(TAG, "Capture notification refresh skipped for affected Android 15 firmware")
      return
    }
    notificationManager?.notify(NOTIFY_ID, buildStatusNotification())
  }

  private fun buildStatusNotification(): Notification {
    val isRecording = genericStream.isRecording
    val isStreaming = genericStream.isStreaming
    val voiceChatCompatibility =
      SettingsManager.isGameVoiceChatCompatibilityEnabled(this) &&
        StreamAccessibilityService.isEnabled(this)
    val micPendingIntent = PendingIntent.getService(
      this,
      0,
      Intent(this, ScreenService::class.java).apply { action = ACTION_TOGGLE_MIC },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val stopPendingIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, ScreenService::class.java).apply { action = ACTION_STOP_STREAM },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Stream Prime")
      .setContentText(
        when {
          isRecording && isStreaming && voiceChatCompatibility ->
            "Recording + live • Mic Share compatibility"
          isRecording && voiceChatCompatibility -> "Recording • Mic Share compatibility"
          isStreaming && voiceChatCompatibility -> "Live • Mic Share compatibility"
          isRecording && isStreaming -> "Recording and streaming live"
          isRecording -> "Recording video"
          isStreaming -> "Streaming live"
          voiceChatCompatibility -> "Screen capture • Mic Share compatibility"
          else -> "Screen capture active"
        }
      )
      .setSmallIcon(R.drawable.ic_notification_small)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      // A capture notification must be visible immediately. Leaving the Android 12+ default
      // deferred policy lets affected Motorola/Unisoc builds detach the notification when the
      // encoder becomes active; that demotes this service and MediaProjection is then revoked.
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .addAction(
        R.drawable.ic_launcher_foreground,
        NotificationSession.microphoneActionLabel(isMicrophoneMuted),
        micPendingIntent
      )

    // Let SystemUI render the elapsed duration without reposting the notification every second.
    // Reposting can replace foreground-service state; a system chronometer does not touch it.
    builder
      .setWhen(
        if (captureSessionStartedAt > 0L) captureSessionStartedAt else System.currentTimeMillis()
      )
      .setShowWhen(true)
      .setUsesChronometer(true)

    if (isStreaming || isRecording || captureForegroundActive || pendingCaptureRequest != null) {
      builder.addAction(
        R.drawable.ic_launcher_foreground,
        "Stop Capture",
        stopPendingIntent
      )
    }
    return builder.build()
  }

  private fun keepAliveTrick() {
    try {
      val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.notification_icon)
        .setContentTitle("Stream Prime")
        .setContentText("Streaming service is active")
        .setSilent(true)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
      
      // Keep both capture and microphone service types active. Losing the mediaProjection type
      // makes Android revoke the token and immediately invalidates playback audio capture.
      startCaptureForeground(notification)
      Log.d(
        TAG,
        "Maintained foreground service with explicit mediaProjection/microphone capture types"
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error in keepAliveTrick: ${e.message}")
      // Don't let notification errors crash the service
    }
  }

  // Preview attach/detach for showing encoded output (with overlays) in a SurfaceView
  fun attachPreview(surfaceView: android.view.SurfaceView) {
    try {
      // Must be called after prepareVideo
      if (genericStream.isOnPreview) {
        Log.d(TAG, "Preview already attached; ignoring duplicate request")
        return
      }
      genericStream.startPreview(surfaceView, true)
      Log.d(TAG, "Preview attached to SurfaceView")
    } catch (e: Exception) {
      Log.e(TAG, "Error attaching preview: ${e.message}")
    }
  }

  fun detachPreview() {
    try {
      genericStream.stopPreview()
      Log.d(TAG, "Preview detached")
    } catch (e: Exception) {
      Log.e(TAG, "Error detaching preview: ${e.message}")
    }
  }

  override fun onBind(p0: Intent?): IBinder? {
    // The isolated Accessibility service uses this binder only to raise the importance of an
    // already-running capture process. All capture control remains inside ScreenService.
    return localBinder
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "RTP Display service started")
    
    // Handle notification action intents
    when (intent?.action) {
        ACTION_ARM_CAPTURE_FOREGROUND -> {
            if (pendingCaptureRequest == null) {
                takeStartupCaptureRequest()?.let { request ->
                    pendingCaptureRequest = PendingCaptureRequest(
                        request.resultCode,
                        request.data,
                        request.completion
                    )
                    captureSessionStartedAt = System.currentTimeMillis()
                }
            }
            // This call must acknowledge startForegroundService() from inside onStartCommand.
            // Promoting before this callback leaves a pending FGS start unacknowledged; affected
            // Android 15 builds then remove the service type when the activity leaves the screen,
            // which immediately revokes MediaProjection.
            if (pendingCaptureRequest == null) {
                Log.w(TAG, "Ignoring stale capture foreground start without a consent request")
                return START_NOT_STICKY
            }
            try {
                promoteCaptureForeground()
                Log.d(TAG, "Capture foreground-service start acknowledged in onStartCommand")
                Handler(Looper.getMainLooper()).post(::completePendingCaptureRequest)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to promote capture foreground service", error)
                failPendingCaptureRequest()
            }
            // A projection grant cannot be restored after process death; never recreate this
            // service without a new user consent result.
            return START_NOT_STICKY
        }
        ACTION_STOP_STREAM -> {
            Log.d(TAG, "Stop action received from notification")
            stopAllCapture()
        }
        ACTION_TOGGLE_MIC -> {
            Log.d(TAG, "Toggle mic action received from notification")
            toggleMicrophone()
        }
        ACTION_APPLY_OVERLAY -> {
            Log.d(TAG, "Apply overlay action received by service")
            applyConfiguredOverlay()
        }
    }
    
    return START_NOT_STICKY
  }

  fun isStreaming(): Boolean {
    return genericStream.isStreaming
  }

  fun isRecording(): Boolean {
    return genericStream.isRecording
  }

  private fun notifyRecordingStateChanged(isRecording: Boolean) {
    sendBroadcast(
      Intent(ACTION_RECORDING_STATE_CHANGED)
        .setPackage(packageName)
        .putExtra(EXTRA_IS_RECORDING, isRecording)
    )
  }

  private fun notifyMicrophoneStateChanged() {
    sendBroadcast(
      Intent(ACTION_MICROPHONE_STATE_CHANGED)
        .setPackage(packageName)
        .putExtra(EXTRA_MICROPHONE_MUTED, isMicrophoneMuted)
        .putExtra(EXTRA_MICROPHONE_VOLUME, micVolume)
        .putExtra(EXTRA_MICROPHONE_RESTORE_VOLUME, micVolumeBeforeMute)
    )
  }

  private fun stopAllCapture() {
    pendingCaptureRequest?.completion?.invoke(false)
    pendingCaptureRequest = null

    if (genericStream.isStreaming || streamRequested || currentEndpoint != null) {
      stopStream()
    }
    if (genericStream.isRecording) {
      stopRecording()
    }
    // The activity may still be visible when Stop is pressed from the notification. Publish the
    // final state even if the recorder already transitioned to STOPPED before this command was
    // handled, so every active screen can immediately restore its Record action.
    notifyRecordingStateChanged(false)
    finishCaptureSessionIfIdle()
  }

  fun stopStream() {
    // Set intent before disconnecting. RtmpClient reports an asynchronous onDisconnect;
    // without this guard a user Stop is mistaken for a network failure and reconnects.
    streamRequested = false
    resetReconnectState(clearEndpoint = true)
    try {
      if (genericStream.isStreaming) {
        genericStream.stopStream()
        Log.d("ScreenService", "Stream stopped")
      } else {
        Log.d(TAG, "Stop requested while RTMP was connecting/retrying")
      }

      // Clear stream state even when the RTMP client is between retry attempts.
      streamStartTime = 0
      saveStreamStartTime()
      refreshCaptureSessionProtection()

      if (!genericStream.isRecording) {
        Log.d("ScreenService", "No recording active, stopping service")
        finishCaptureSessionIfIdle()
      } else {
        updateNotification()
      }
    } catch (e: Exception) {
      Log.e("ScreenService", "Error stopping stream: ${e.message}")
      refreshCaptureSessionProtection()
    }
  }

  fun stopRecording() {
    try {
      if (genericStream.isRecording) {
        genericStream.stopRecord()
        recordingStartTime = 0L
        notifyRecordingStateChanged(false)
        refreshCaptureSessionProtection()
        PathUtils.updateGallery(this, recordPath)
        Log.d("ScreenService", "Recording stopped")
        
        // If no streaming is happening, stop the service entirely
        if (!genericStream.isStreaming) {
          Log.d("ScreenService", "No streaming active, stopping service")
          finishCaptureSessionIfIdle()
        } else {
          // Update notification if streaming is still active
          updateNotification()
        }
      }
    } catch (e: Exception) {
      Log.e("ScreenService", "Error stopping recording: ${e.message}")
      refreshCaptureSessionProtection()
    }
  }

  fun setCallback(connectChecker: ConnectChecker?) {
    callback = connectChecker
  }

  override fun onDestroy() {
    // Invalidate every delayed/network callback before releasing stream resources.
    serviceDestroyed = true
    streamRequested = false
    resetReconnectState(clearEndpoint = true)
    pendingCaptureRequest?.completion?.invoke(false)
    pendingCaptureRequest = null
    try {
      refreshCaptureSessionProtection(forceInactive = true)
      StreamAccessibilityService.setCaptureActive(this, false)
      // Unregister broadcast receiver
      notificationReceiver?.let {
        unregisterReceiver(it)
        notificationReceiver = null
      }
      
      // Unregister our callback and release the grant before tearing down the stream source.
      // This prevents GenericStream/ScreenSource cleanup from being reported as an unexpected
      // projection revocation while this service is intentionally shutting down.
      releaseMediaProjection()

      // Release the entire stream object, including preview EGL and MediaProjection
      // resources. Leaving preview attached lets a replacement service create a second
      // publisher but fail to attach the same SurfaceView with EGL_BAD_ALLOC.
      if (::genericStream.isInitialized) {
        val wasRecording = genericStream.isRecording
        genericStream.release()
        if (wasRecording) {
          notifyRecordingStateChanged(false)
          if (recordPath.isNotBlank()) PathUtils.updateGallery(this, recordPath)
        }
      }
      stopCaptureForeground()
      recordingStartTime = 0L
      
      // Clean up overlay renderer
      layeredOverlayRenderer?.release()
      layeredOverlayRenderer = null
      screenLayoutRenderer = null
      if (::displayManager.isInitialized) {
        displayManager.unregisterDisplayListener(capturedDisplayListener)
      }
      
      INSTANCE = null
      callback = null
      Log.d(TAG, "ScreenService destroyed")
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying service: ${e.message}")
    } finally {
      super.onDestroy()
    }
  }

  /**
   * Queue projection preparation until Android has delivered the foreground-service start.
   * MediaProjection consent data is single-use, so only the latest pending request is retained.
   */
  fun prepareStream(resultCode: Int, data: Intent, completion: (Boolean) -> Unit) {
    pendingCaptureRequest?.completion?.invoke(false)
    pendingCaptureRequest = PendingCaptureRequest(resultCode, data, completion)
    captureSessionStartedAt = System.currentTimeMillis()

    if (captureForegroundActive) {
      Handler(Looper.getMainLooper()).post(::completePendingCaptureRequest)
      return
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(
          applicationContext,
          Intent(applicationContext, ScreenService::class.java).apply {
            action = ACTION_ARM_CAPTURE_FOREGROUND
          }
        )
        Log.d(TAG, "Capture foreground-service start requested after projection consent")
      } else {
        promoteCaptureForeground()
        Handler(Looper.getMainLooper()).post(::completePendingCaptureRequest)
      }
    } catch (error: RuntimeException) {
      Log.e(TAG, "Unable to request capture foreground service", error)
      failPendingCaptureRequest()
    }
  }

  private fun completePendingCaptureRequest() {
    val request = pendingCaptureRequest ?: return
    pendingCaptureRequest = null
    val success = try {
      prepareStreamInternal(request.resultCode, request.data)
    } catch (error: RuntimeException) {
      Log.e(TAG, "Screen capture preparation failed", error)
      false
    }
    if (!success) {
      releaseMediaProjection()
      if (!genericStream.isStreaming && !genericStream.isRecording) {
        finishCaptureSessionIfIdle()
      }
    }
    request.completion(success)
  }

  private fun failPendingCaptureRequest() {
    val request = pendingCaptureRequest ?: return
    pendingCaptureRequest = null
    request.completion(false)
  }

  private fun prepareStreamInternal(resultCode: Int, data: Intent): Boolean {
    // Re-prepare encoders without stopping this service. The public stopStream() represents
    // a user Stop and intentionally calls stopSelf().
    streamRequested = false
    resetReconnectState(clearEndpoint = true)
    if (genericStream.isStreaming) genericStream.stopStream()
    releaseMediaProjection()
    
    // Force reload quality settings from centralized Stream Settings
    loadQualitySettings()
    
    // Log the exact values being used
    Log.d("ScreenService", "=== PREPARE STREAM DEBUG ===")
    Log.d("ScreenService", "Mode: ${getSharedPreferences("StreamSettings", 0).getString("streaming_mode", "Auto")}")
    Log.d("ScreenService", "Width: $width, Height: $height, FPS: $fps, Bitrate: $vBitrate, Rotation: $rotation")
    
    // Reinitialize video preparation with new settings
    prepared = try {
      Log.d("ScreenService", "Preparing video with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps, rotation=$rotation")
      prepareEndpointCompatibleVideo(rotationDegrees = rotation) &&
          genericStream.prepareAudio(sampleRate, isStereo, aBitrate,
            echoCanceler = true,
            noiseSuppressor = true
          )
    } catch (_: IllegalArgumentException) {
      false
    }
    
    if (!prepared) {
      Log.e("ScreenService", "Failed to prepare stream with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps, rotation=$rotation")
      return false
    }
    
    val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
      ?: throw IllegalStateException("get MediaProjection failed")
    val projectionCallback = object : MediaProjection.Callback() {
      override fun onStop() {
        Handler(Looper.getMainLooper()).post {
          handleProjectionStopped(projection)
        }
      }
    }
    projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
    mediaProjection = projection
    mediaProjectionCallback = projectionCallback
    val screenSource = ScreenSource(applicationContext, projection)
    return try {
      genericStream.changeVideoSource(screenSource)
      toggleAudioSource(selectedAudioSource)
      
      // Ensure foreground service is maintained for background audio access
      keepAliveTrick()
      
      Log.d("ScreenService", "Stream prepared successfully with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps, rotation=$rotation")
      true
    } catch (_: IllegalArgumentException) {
      false
    }
  }

  private fun releaseMediaProjection() {
    val projection = mediaProjection ?: return
    val projectionCallback = mediaProjectionCallback
    mediaProjection = null
    mediaProjectionCallback = null

    if (projectionCallback != null) {
      runCatching { projection.unregisterCallback(projectionCallback) }
    }
    runCatching { projection.stop() }
      .onFailure { Log.w(TAG, "Unable to stop MediaProjection cleanly: ${it.message}") }
  }

  private fun handleProjectionStopped(projection: MediaProjection) {
    // Ignore a delayed callback belonging to an older capture grant.
    if (mediaProjection !== projection) return

    mediaProjection = null
    mediaProjectionCallback = null
    Log.e(
      TAG,
      "MediaProjection ended; stopping capture so audio/video sources cannot retry an invalid token"
    )

    val wasStreaming = genericStream.isStreaming
    val wasRecording = genericStream.isRecording
    if (wasStreaming) stopStream()
    if (wasRecording && genericStream.isRecording) stopRecording()

    if (!wasStreaming && !wasRecording) {
      stopCaptureForeground()
      stopSelf()
    }

    callback?.onConnectionFailed("Screen capture ended. Start again to grant permission.")
  }

  fun getCurrentAudioSource(): AudioSource = genericStream.audioSource

  fun toggleAudioSource(itemId: Int) {
    Log.d(TAG, "toggleAudioSource called with itemId: $itemId")
    Log.d(TAG, "Current audio source before change: ${genericStream.audioSource.javaClass.simpleName}")
    
    when (itemId) {
      R.id.audio_source_microphone -> {
        Log.d(TAG, "Switching to PristineAudioSource (recommended for hiss-free audio)")
        selectedAudioSource = R.id.audio_source_microphone
        if (genericStream.audioSource is PristineAudioSource) {
          Log.d(TAG, "Already using PristineAudioSource, no change needed")
          return
        }
        genericStream.changeAudioSource(createPristineAudioSource())
        Log.d(TAG, "Successfully switched to PristineAudioSource")
      }
      R.id.audio_source_internal -> {
        Log.d(TAG, "Switching to InternalAudioSource")
        if (genericStream.audioSource is InternalAudioSource) {
          selectedAudioSource = R.id.audio_source_internal
          Log.d(TAG, "Already using InternalAudioSource, no change needed")
          return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          mediaProjection?.let {
            selectedAudioSource = R.id.audio_source_internal
            genericStream.changeAudioSource(InternalAudioSource(it))
            Log.d(TAG, "Successfully switched to InternalAudioSource")
          } ?: run {
            fallbackToMicrophone("MediaProjection unavailable for InternalAudioSource")
          }
        } else {
          fallbackToMicrophone("InternalAudioSource requires Android 10+")
        }
      }
      R.id.audio_source_mix -> {
        Log.d(TAG, "Switching to MixAudioSource")
        if (genericStream.audioSource is MixAudioSource) {
          selectedAudioSource = R.id.audio_source_mix
          Log.d(TAG, "Already using MixAudioSource, no change needed")
          return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          mediaProjection?.let {
            selectedAudioSource = R.id.audio_source_mix
            genericStream.changeAudioSource(createMixedAudioSource(it))
            Log.d(TAG, "Successfully switched to MixAudioSource")
          } ?: run {
            fallbackToMicrophone("MediaProjection unavailable for MixAudioSource")
          }
        } else {
          fallbackToMicrophone("MixAudioSource requires Android 10+")
        }
      }
      R.id.audio_source_dual_channel -> {
        Log.d(TAG, "Switching to Dual Channel Audio (separate tracks)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          mediaProjection?.let { projection ->
            selectedAudioSource = R.id.audio_source_dual_channel
            // Use MixAudioSource with anti-feedback measures
            genericStream.changeAudioSource(createMixedAudioSource(projection))
            Log.d(TAG, "Successfully switched to Dual Channel Audio (using enhanced MixAudioSource)")
          } ?: run {
            fallbackToMicrophone("MediaProjection unavailable for Dual Channel Audio")
          }
        } else {
          fallbackToMicrophone("Dual Channel Audio requires Android 10+")
        }
      }
    }
    
    Log.d(TAG, "Audio source after change: ${genericStream.audioSource.javaClass.simpleName}")
    Log.d(TAG, "Current volume levels - Mic: $micVolume%, Device: $deviceVolume%")
    
    // Verify audio controls after audio source change
    verifyAudioControlsAndStates()
  }

  private fun fallbackToMicrophone(reason: String) {
    Log.w(TAG, "$reason; falling back to microphone audio")
    selectedAudioSource = R.id.audio_source_microphone
    if (genericStream.audioSource !is PristineAudioSource) {
      genericStream.changeAudioSource(createPristineAudioSource())
    }
  }

  fun toggleRecord(state: (RecordController.Status) -> Unit) {
    if (!genericStream.isRecording) {
      // Check for required permissions before starting foreground service
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
          Log.e("ScreenService", "FOREGROUND_SERVICE_MEDIA_PROJECTION permission not granted, cannot start recording")
          state(RecordController.Status.STOPPED)
          return
        }
      }

      // The isolated Mic Share service must be enabled before AudioRecord starts. When this
      // optional mode is selected, fail clearly instead of silently recording an all-zero mic.
      if (!activateVoiceChatCompatibilityForCapture()) {
        state(RecordController.Status.STOPPED)
        finishCaptureSessionIfIdle()
        return
      }
      
      // Start foreground service when recording begins
      promoteCaptureForeground()
      
      // Ensure quality settings are up to date before recording
      reloadQualitySettings()
      
      // Apply overlay (image filter) if configured for recordings too
      applyConfiguredOverlay()

      // Ensure audio source is properly set before starting recording
      ensureAudioSourcePersistence()
      
      // Sync current volume settings from UI
      syncCurrentVolumeSettings()
      
      // Ensure audio source is ready
      ensureAudioSourceReady()
      
      // Force reload and apply current volume settings
      forceReloadAndApplyVolumeSettings()
      
      // Comprehensive verification of all audio controls and states
      verifyAudioControlsAndStates()
      
      // Debug audio source state before starting
      debugAudioSourceOnStart()
      
      // Explicitly apply volume settings before starting recording
      Log.d(TAG, "=== APPLYING VOLUME SETTINGS BEFORE RECORDING ===")
      Log.d(TAG, "Current volume settings - Mic: $micVolume%, Device: $deviceVolume%")
      applyStreamAudioLevels()
      
      // Optimize audio settings for better quality and reduced hissing
      optimizeAudioForQuality()
      
      // Note: Volume levels are controlled by user via UI bars, not automatically changed
      
      val folder = PathUtils.getRecordPath()
      if (!folder.exists()) folder.mkdir()
      val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
      recordPath = "${folder.absolutePath}/${sdf.format(Date())}.mp4"
      
      Log.d("ScreenService", "Starting recording with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps, rotation=$rotation")
      
      try {
        genericStream.startRecord(recordPath) { status ->
          if (status == RecordController.Status.RECORDING) {
            if (recordingStartTime <= 0L) recordingStartTime = System.currentTimeMillis()
            notifyRecordingStateChanged(true)
            state(RecordController.Status.RECORDING)
            Log.d("ScreenService", "Recording started successfully")

            // Debug audio source after recording starts
            Log.d(TAG, "=== AUDIO SOURCE AFTER RECORDING START ===")
            debugAudioSourceOnStart()

            // Re-apply volume settings after recording starts
            Log.d(TAG, "=== RE-APPLYING VOLUME SETTINGS AFTER RECORDING START ===")
            applyStreamAudioLevels()

            // Update notification to show recording status
            refreshCaptureSessionProtection()
            updateNotification()
          }
        }
        state(RecordController.Status.STARTED)
        refreshCaptureSessionProtection()
      } catch (error: Exception) {
        Log.e(TAG, "Unable to start recording sources", error)
        if (genericStream.isRecording) {
          runCatching { genericStream.stopRecord() }
            .onFailure { Log.w(TAG, "Unable to clean up failed recording start", it) }
        }
        recordingStartTime = 0L
        notifyRecordingStateChanged(false)
        refreshCaptureSessionProtection()
        state(RecordController.Status.STOPPED)
        callback?.onConnectionFailed(
          "Unable to start selected audio capture. Try Microphone audio."
        )
        finishCaptureSessionIfIdle()
        return
      }
    } else {
      genericStream.stopRecord()
      recordingStartTime = 0L
      notifyRecordingStateChanged(false)
      refreshCaptureSessionProtection()
      state(RecordController.Status.STOPPED)
      PathUtils.updateGallery(this, recordPath)
      Log.d("ScreenService", "Recording stopped")

      if (!finishCaptureSessionIfIdle()) {
        updateNotification()
      }
    }
  }

  fun startStream(endpoint: String) {
    Log.d("ScreenService", "Starting stream to configured RTMP endpoint")
    
    // Check for required permissions before starting foreground service
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
        Log.e("ScreenService", "FOREGROUND_SERVICE_MEDIA_PROJECTION permission not granted, cannot start stream")
        callback?.onConnectionFailed("Media projection permission required")
        return
      }
    }
    
    // Start foreground service when streaming begins
    promoteCaptureForeground()
    
    // Validate endpoint format
    if (!endpoint.startsWith("rtmp://")) {
      Log.e("ScreenService", "Invalid RTMP endpoint format")
      callback?.onConnectionFailed("Invalid RTMP URL format")
      finishCaptureSessionIfIdle()
      return
    }

    if (serviceDestroyed || INSTANCE !== this) {
      Log.w(TAG, "Ignoring stream start from an inactive service instance")
      callback?.onConnectionFailed("Streaming service is no longer active")
      finishCaptureSessionIfIdle()
      return
    }

    if (!activateVoiceChatCompatibilityForCapture()) {
      finishCaptureSessionIfIdle()
      return
    }
    
    // Remember endpoint for reconnection
    streamRequested = true
    resetReconnectState(clearEndpoint = false)
    currentEndpoint = endpoint
    refreshCaptureSessionProtection()

    // Ensure quality settings are up to date before streaming
    reloadQualitySettings()
    
    // Apply overlay (image filter) if configured
    applyConfiguredOverlay()

    // Ensure audio source is properly set before starting stream
    ensureAudioSourcePersistence()
    
    // Sync current volume settings from UI
    syncCurrentVolumeSettings()
    
    // Ensure audio source is ready
    ensureAudioSourceReady()
    
    // Force reload and apply current volume settings
    forceReloadAndApplyVolumeSettings()
    
    // Comprehensive verification of all audio controls and states
    verifyAudioControlsAndStates()
    
    // Debug audio source state before starting
    debugAudioSourceOnStart()
    
    // Explicitly apply volume settings before starting streaming
    Log.d(TAG, "=== APPLYING VOLUME SETTINGS BEFORE STREAMING ===")
    Log.d(TAG, "Current volume settings - Mic: $micVolume%, Device: $deviceVolume%")
    applyStreamAudioLevels()
    
    // Optimize audio settings for better quality and reduced hissing
    optimizeAudioForQuality()
    
    // Note: Volume levels are controlled by user via UI bars, not automatically changed
    
    // Check if already streaming
    if (!genericStream.isStreaming) {
      try {
        Log.d("ScreenService", "Starting stream with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps, rotation=$rotation")
        
        // Save stream start time
        streamStartTime = System.currentTimeMillis()
        saveStreamStartTime()
        
        genericStream.startStream(endpoint)
        refreshCaptureSessionProtection()
        // Keep the projection type attached after audio/video sources become active. This is
        // especially important on Android 15 vendor builds that emit an FGS-state transition when
        // REMOTE_SUBMIX and the physical microphone start together.
        startCaptureForeground(buildStatusNotification())
        Log.d("ScreenService", "Stream start initiated")
        
        // Debug audio source after streaming starts
        Log.d(TAG, "=== AUDIO SOURCE AFTER STREAMING START ===")
        debugAudioSourceOnStart()
        
        // Re-apply volume settings after streaming starts
        Log.d(TAG, "=== RE-APPLYING VOLUME SETTINGS AFTER STREAMING START ===")
        applyStreamAudioLevels()
        
        // Update notification to show streaming status
        updateNotification()
      } catch (e: Exception) {
        Log.e("ScreenService", "Failed to start stream: ${e.message}")
        streamRequested = false
        resetReconnectState(clearEndpoint = true)
        streamStartTime = 0L
        saveStreamStartTime()
        if (genericStream.isStreaming) {
          runCatching { genericStream.stopStream() }
            .onFailure { Log.w(TAG, "Unable to clean up failed stream start", it) }
        }
        refreshCaptureSessionProtection()
        finishCaptureSessionIfIdle()
        callback?.onConnectionFailed("Failed to start stream: ${e.message}")
      }
    } else {
      Log.d("ScreenService", "Stream already running, ignoring start request")
      refreshCaptureSessionProtection()
    }
  }

  fun updateQualitySettings(newWidth: Int, newHeight: Int, newFps: Int, newBitrate: Int) {
    val wasStreaming = genericStream.isStreaming
    val wasRecording = genericStream.isRecording
    
    // Stop current stream/recording if active
    if (wasStreaming) {
      genericStream.stopStream()
    }
    if (wasRecording) {
      genericStream.stopRecord()
      recordingStartTime = 0L
      notifyRecordingStateChanged(false)
    }
    
    // Update settings
    width = newWidth
    height = newHeight
    fps = newFps
    vBitrate = newBitrate
    
    // Reinitialize with new settings
    genericStream.release()
    genericStream = GenericStream(baseContext, this, NoVideoSource(), createPristineAudioSource()).apply {
      setTimestampMode(TimestampMode.CLOCK, TimestampMode.BUFFER)
      //This is important to keep a constant fps because media projection only produce fps if the screen change
      getGlInterface().setForceRender(true, fps)
      // Disable automatic orientation handling to prevent forced screen rotation
      getGlInterface().autoHandleOrientation = false
    }
    configureStreamConnectionPolicy()
    
    prepared = try {
      // Always use rotation=0 to prevent forced screen orientation
      prepareEndpointCompatibleVideo(rotationDegrees = 0) &&
          genericStream.prepareAudio(sampleRate, isStereo, aBitrate,
            echoCanceler = true,
            noiseSuppressor = true
          )
    } catch (_: IllegalArgumentException) {
      false
    }
    
    if (!prepared) {
      Log.e(TAG, "Invalid audio or video parameters, prepare failed")
      return
    }
    
    // Restore media projection if it exists
    mediaProjection?.let { projection ->
      val screenSource = ScreenSource(applicationContext, projection)
      try {
        genericStream.changeVideoSource(screenSource)
        toggleAudioSource(selectedAudioSource)
      } catch (_: IllegalArgumentException) {
        // Handle error
      }
    }
    
    // Restart stream/recording if they were active
    if (wasStreaming) {
      // Note: You'll need to call startStream again from the activity
    }
  }

  fun applyConfiguredOverlay() {
    try {
      val cfg = OverlayManager.load(this)
      val gl = genericStream.getGlInterface()
      
      // Clear existing overlay filters first
      gl.clearFilters()
      layeredOverlayRenderer = null
      screenLayoutRenderer = null
      
      val isVerticalCanvas = height > width
      val enabledLayers = if (cfg.enabled) cfg.layers.filter {
        it.enabled && (it.type == OverlayLayerType.TEXT || it.imageUri.isNotEmpty())
      } else emptyList()
      val (allBelowScreen, allAboveScreen) = OverlayLayerOrdering.splitAtScreen(cfg.layers, cfg.screenLayerPosition)
      val enabledIds = enabledLayers.map { it.id }.toSet()
      val belowScreen = allBelowScreen.filter { it.id in enabledIds }
      val aboveScreen = allAboveScreen.filter { it.id in enabledIds }
      val needsScreenLayer = isVerticalCanvas ||
        (cfg.enabled && (!cfg.screenLayout.isDefault() || !cfg.landscapeScreenLayout.isDefault() || belowScreen.isNotEmpty()))

      // First orient and place/zoom the captured screen on the fixed output canvas.
      // Vertical mode keeps this renderer even with overlays disabled so landscape apps fit.
      if (needsScreenLayer) {
        screenLayoutRenderer = ScreenLayoutFilterRender(
          cfg.screenPreset,
          cfg.screenLayout,
          cfg.landscapeScreenLayout,
          cfg.portraitScreenFitMode,
          cfg.landscapeScreenFitMode,
          cfg.canvasTheme,
          capturedDisplayLandscapeAspect
        ).also { renderer ->
          renderer.setCanvasSize(width, height)
          renderer.updateCaptureRotation(if (isVerticalCanvas) capturedDisplayRotationDegrees else 0)
          renderer.updateCaptureLandscapeAspect(capturedDisplayLandscapeAspect)
          if (belowScreen.isNotEmpty()) renderer.setBackgroundLayers(this, belowScreen)
          gl.addFilter(renderer)
        }
      }

      if (!cfg.enabled) return

      // Composite only layers placed above Screen; lower layers are in its background texture.
      if (aboveScreen.isNotEmpty()) {
        layeredOverlayRenderer = LayeredOverlayRenderer(this).also { renderer ->
          renderer.setCanvasSize(width, height)
          renderer.updateLayers(aboveScreen)
          gl.addFilter(renderer)
        }
      }

      Log.d(
        TAG,
        "Applied canvas composition: portrait=${cfg.screenLayout}, " +
          "landscape=${cfg.landscapeScreenLayout}, frame=${cfg.screenPreset}, " +
          "below=${belowScreen.size}, above=${aboveScreen.size}"
      )
    } catch (e: Exception) {
      // Avoid crashing service due to overlay errors
      Log.e(TAG, "Error applying layered overlay: ${e.message}")
    }
  }

  private fun readCapturedDisplayRotation(): Int {
    val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    return when (rotation) {
      Surface.ROTATION_90 -> 90
      Surface.ROTATION_180 -> 180
      Surface.ROTATION_270 -> 270
      else -> 0
    }
  }

  private var lastReloadTime = 0L
  private val RELOAD_DEBOUNCE_MS = 1000L // 1 second debounce
  
  fun reloadQualitySettings() {
    Log.d("ScreenService", "=== RELOAD QUALITY SETTINGS ===")
    
    // Debounce reload requests to prevent rapid successive calls
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastReloadTime < RELOAD_DEBOUNCE_MS) {
      Log.d("ScreenService", "Reload debounced - too soon since last reload")
      return
    }
    lastReloadTime = currentTime
    
    // Don't reload if currently streaming to avoid interruption
    if (genericStream.isStreaming) {
      Log.d("ScreenService", "Stream is active, skipping quality settings reload to avoid interruption")
      return
    }
    
    // Store current settings before loading new ones
    val oldWidth = width
    val oldHeight = height
    val oldFps = fps
    val oldBitrate = vBitrate
    val oldRotation = rotation
    val oldMode = SettingsManager.getStreamingMode(this)
    
    loadQualitySettings()
    
    // Check if settings actually changed
    val settingsChanged = oldWidth != width || oldHeight != height || oldFps != fps || oldBitrate != vBitrate || oldRotation != rotation || oldMode != SettingsManager.getStreamingMode(this)
    
    Log.d("ScreenService", "Quality settings check - Changed: $settingsChanged")
    Log.d("ScreenService", "Old: ${oldWidth}x${oldHeight} @ ${oldFps}fps, ${oldBitrate/1000}kbps, rotation=$oldRotation")
    Log.d("ScreenService", "New: ${width}x${height} @ ${fps}fps, ${vBitrate/1000}kbps, rotation=$rotation")
    
    if (!settingsChanged) {
      // No changes, no need to reload
      Log.d("ScreenService", "No quality settings changed, skipping reload")
      return
    }
    
    Log.d("ScreenService", "Quality settings changed, reloading stream")
    
    val wasStreaming = genericStream.isStreaming
    val wasRecording = genericStream.isRecording
    
    // Stop current stream/recording if active
    if (wasStreaming) {
      genericStream.stopStream()
    }
    if (wasRecording) {
      genericStream.stopRecord()
      recordingStartTime = 0L
      notifyRecordingStateChanged(false)
    }
    
    // Reinitialize with new settings
    genericStream.release()
    genericStream = GenericStream(baseContext, this, NoVideoSource(), createPristineAudioSource()).apply {
      setTimestampMode(TimestampMode.CLOCK, TimestampMode.BUFFER)
      //This is important to keep a constant fps because media projection only produce fps if the screen change
      // Use the actual FPS setting instead of hardcoded 15
      getGlInterface().setForceRender(true, fps)
      // Disable automatic orientation handling to prevent forced screen rotation
      getGlInterface().autoHandleOrientation = false
    }
    configureStreamConnectionPolicy()
    
    prepared = try {
      // Always use rotation=0 to prevent forced screen orientation
      prepareEndpointCompatibleVideo(rotationDegrees = 0) &&
          genericStream.prepareAudio(sampleRate, isStereo, aBitrate,
            echoCanceler = true,
            noiseSuppressor = true
          )
    } catch (_: IllegalArgumentException) {
      false
    }
    
    if (!prepared) {
      toast("Invalid audio or video parameters, prepare failed")
      return
    }
    
    // Restore media projection if it exists
    mediaProjection?.let { projection ->
      val screenSource = ScreenSource(applicationContext, projection)
      try {
        genericStream.changeVideoSource(screenSource)
        toggleAudioSource(selectedAudioSource)
        Log.d("ScreenService", "Reload completed successfully with new settings")
      } catch (_: IllegalArgumentException) {
        // Handle error
      }
    }
  }

  fun getCurrentQualitySettings(): Map<String, Int> {
    return mapOf(
      "width" to width,
      "height" to height,
      "fps" to fps,
      "bitrate" to vBitrate
    )
  }

  fun getCurrentSettingsInfo(): String {
    val prefs = getSharedPreferences("StreamSettings", 0)
    val streamingMode = prefs.getString("streaming_mode", "Auto") ?: "Auto"
    return "Mode: $streamingMode, Resolution: ${width}x${height}, FPS: $fps, Bitrate: ${vBitrate/1000}kbps, Rotation: $rotation"
  }

  fun toggleMicrophone() {
    setMicrophoneMuted(!isMicrophoneMuted)
  }

  fun setMicrophoneMuted(muted: Boolean) {
    try {
      val nextState = MicrophoneMuteVolumePolicy.setMuted(currentMicrophoneState(), muted)
      applyMicrophoneState(nextState)
      Log.d(
        TAG,
        "Setting microphone mute - muted=$isMicrophoneMuted, mic=$micVolume%, " +
          "restore=$micVolumeBeforeMute%"
      )

      // A microphone mute must never replace the live audio source. Recreating AudioRecord while
      // recording/streaming races the old REMOTE_SUBMIX teardown on vendor audio stacks and can
      // leave the replacement internal source silent. Keep MixAudioSource running and set only
      // its microphone contribution to zero; device playback remains uninterrupted.
      applyStreamAudioLevels()
      applyEnhancedAudioQuality()
      verifyAudioControlsAndStates()
      
      saveVolumeSettings() // Save volume settings after toggling microphone
      notifyMicrophoneStateChanged()
      updateNotification()
      
      // Log the microphone state change for debugging
      Log.d(TAG, "Microphone state changed - isMicrophoneMuted: $isMicrophoneMuted")
    } catch (e: Exception) {
      Log.e(TAG, "Error setting microphone mute: ${e.message}", e)
    }
  }

  fun setMicVolume(volume: Int) {
    try {
      Log.d(TAG, "=== SETTING MIC VOLUME ===")
      Log.d(TAG, "Previous mic volume: $micVolume%")
      Log.d(TAG, "New mic volume: $volume%")

      val nextState = MicrophoneMuteVolumePolicy.setVolume(currentMicrophoneState(), volume)
      if (nextState == currentMicrophoneState() && isMicrophoneMuted) {
        Log.d(TAG, "Ignoring microphone slider change while mute lock is active")
        return
      }
      applyMicrophoneState(nextState)
      Log.d(TAG, "Stream microphone level set to: $micVolume%")
      
      // Apply volume to stream audio source and verify controls
      applyStreamAudioLevels()
      verifyAudioControlsAndStates()
      saveVolumeSettings() // Save volume settings after setting mic volume
      
      Log.d(TAG, "=== MIC VOLUME SET ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error setting stream mic level: ${e.message}")
    }
  }

  fun setDeviceVolume(volume: Int) {
    try {
      Log.d(TAG, "=== SETTING DEVICE VOLUME ===")
      Log.d(TAG, "Previous device volume: $deviceVolume%")
      Log.d(TAG, "New device volume: $volume%")
      
      deviceVolume = volume.coerceIn(0, 100)
      Log.d(TAG, "Stream device audio level set to: $deviceVolume%")
      
      // Apply volume to stream audio source and verify controls
      applyStreamAudioLevels()
      verifyAudioControlsAndStates()
      saveVolumeSettings() // Save volume settings after setting device volume
      
      Log.d(TAG, "=== DEVICE VOLUME SET ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error setting stream device audio level: ${e.message}")
    }
  }

  fun getMicVolume(): Int {
    return micVolume
  }

  fun getMicVolumeBeforeMute(): Int {
    return micVolumeBeforeMute
  }

  fun getDeviceVolume(): Int {
    return deviceVolume
  }

  fun requestCurrentBitrate() {
    try {
      Log.d(TAG, "Current bitrate requested: $lastBitrate")
      
      // Send the last known bitrate through the callback
      if (lastBitrate > 0) {
        callback?.onNewBitrate(lastBitrate)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error requesting current bitrate: ${e.message}")
    }
  }

  fun getStreamStartTime(): Long {
    return streamStartTime
  }

  fun saveStreamStartTime() {
    try {
      val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      prefs.edit().putLong(KEY_STREAM_START_TIME, streamStartTime).apply()
      Log.d(TAG, "Stream start time saved: $streamStartTime")
    } catch (e: Exception) {
      Log.e(TAG, "Error saving stream start time: ${e.message}")
    }
  }

  fun loadStreamStartTime() {
    try {
      val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      streamStartTime = prefs.getLong(KEY_STREAM_START_TIME, 0)
      Log.d(TAG, "Stream start time loaded: $streamStartTime")
    } catch (e: Exception) {
      Log.e(TAG, "Error loading stream start time: ${e.message}")
    }
  }

  private fun applyStreamAudioLevels() {
    try {
      Log.d(TAG, "=== APPLYING STREAM AUDIO LEVELS ===")
      Log.d(TAG, "Current volume values - Mic: $micVolume%, Device: $deviceVolume%")
      
      // Check if audio source is ready
      val audioSourceForCheck = genericStream.audioSource
      Log.d(TAG, "Audio source type: ${audioSourceForCheck.javaClass.simpleName}")
      Log.d(TAG, "Audio source ready: ${audioSourceForCheck != null}")
      
      // Capture levels are controlled only by the two app sliders. The physical speaker volume is
      // deliberately not part of this policy: users often mute the phone to avoid feedback while
      // still expecting device playback and microphone audio in the encoded output.
      val levels = CaptureAudioLevelPolicy.resolve(
        microphonePercent = micVolume,
        devicePercent = deviceVolume,
        microphoneMuted = isMicrophoneMuted
      )
      val micLevelFloat = levels.microphone
      val deviceAudioLevelFloat = levels.device
      
      Log.d(TAG, "=== APPLYING STREAM AUDIO LEVELS ===")
      Log.d(TAG, "Raw volume values - Mic: $micVolume%, Device: $deviceVolume%")
      Log.d(TAG, "Converted float values - Mic: $micLevelFloat, Device: $deviceAudioLevelFloat")
      
      // Apply volume levels to the current audio source
      val currentAudioSource = genericStream.audioSource
      Log.d(TAG, "Current audio source type: ${currentAudioSource.javaClass.simpleName}")
      
      when (currentAudioSource) {
        is PristineAudioSource -> {
          currentAudioSource.setMicrophoneVolume(micLevelFloat)
          currentAudioSource.setDeviceAudioVolume(deviceAudioLevelFloat)
          currentAudioSource.setRespectSystemVolume(false)
          if (levels.hasMicrophoneAudio) currentAudioSource.unMute() else currentAudioSource.mute()
          Log.d(TAG, "Applied user levels to PristineAudioSource - Mic: $micLevelFloat")
        }
        is MixAudioSource -> {
          currentAudioSource.microphoneVolume = micLevelFloat
          currentAudioSource.internalVolume = deviceAudioLevelFloat
          currentAudioSource.setRespectSystemVolume(false)
          if (levels.hasMixedAudio) currentAudioSource.unMute() else currentAudioSource.mute()
          Log.d(TAG, "Applied independent MixAudioSource levels - Mic: $micLevelFloat, Device: $deviceAudioLevelFloat")
        }
        is MicrophoneSource -> {
          currentAudioSource.microphoneVolume = micLevelFloat
          Log.d(TAG, "Applied microphone level: $micLevelFloat")
        }
        is InternalAudioSource -> {
          currentAudioSource.internalVolume = deviceAudioLevelFloat
          if (levels.hasDeviceAudio) currentAudioSource.unMute() else currentAudioSource.mute()
          Log.d(TAG, "Applied device-audio level: $deviceAudioLevelFloat")
        }
        else -> {
          Log.d(TAG, "❌ Unknown audio source type: ${currentAudioSource.javaClass.simpleName}")
        }
      }
      
    } catch (e: Exception) {
      Log.e(TAG, "Error applying stream audio levels: ${e.message}")
    }
    
    // Speaker volume is diagnostic only; it must never override capture sliders.
    checkAndApplySystemVolume()
    
    // Debug the audio state
    checkAudioState()
  }

  /** Log speaker volume without mutating the capture path. */
  private fun checkAndApplySystemVolume() {
    try {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
      val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
      val systemVolumeLevel = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
      
      Log.d(
        TAG,
        "Speaker volume is ${systemVolumeLevel * 100}% (capture remains controlled by Mic/Device sliders)"
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error checking system volume: ${e.message}")
    }
  }

  /**
   * Comprehensive verification of all audio controls and states before starting recording/streaming
   */
  private fun verifyAudioControlsAndStates() {
    try {
      Log.d(TAG, "=== AUDIO CONTROLS VERIFICATION ===")
      
      // 1. Check system volume state
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
      val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
      val systemVolumeLevel = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
      
      Log.d(TAG, "System Volume State:")
      Log.d(TAG, "  - Current volume: $currentVolume/$maxVolume (${systemVolumeLevel * 100}%)")
      Log.d(TAG, "  - Is system volume zero: ${systemVolumeLevel <= 0.01f}")
      
      // 2. Check app volume controls
      Log.d(TAG, "App Volume Controls:")
      Log.d(TAG, "  - Microphone volume: $micVolume%")
      Log.d(TAG, "  - Device volume: $deviceVolume%")
      
      // 3. Check microphone mute state
      Log.d(TAG, "Microphone State:")
      Log.d(TAG, "  - isMicrophoneMuted: $isMicrophoneMuted")
      
      // 4. Check current audio source
      val currentAudioSource = genericStream.audioSource
      Log.d(TAG, "Current Audio Source:")
      Log.d(TAG, "  - Type: ${currentAudioSource.javaClass.simpleName}")
      
      when (currentAudioSource) {
        is PristineAudioSource -> {
          Log.d(TAG, "  - Microphone volume: ${currentAudioSource.getMicrophoneVolume() * 100}%")
          Log.d(TAG, "  - Device audio volume: ${currentAudioSource.getDeviceAudioVolume() * 100}%")
          Log.d(TAG, "  - System volume: ${currentAudioSource.getCurrentSystemVolume() * 100}%")
          Log.d(TAG, "  - Is muted: ${currentAudioSource.isMuted()}")
          Log.d(TAG, "  - Is system volume zero: ${currentAudioSource.isSystemVolumeZero()}")
        }
        is MixAudioSource -> {
          Log.d(TAG, "  - Microphone volume: ${currentAudioSource.microphoneVolume * 100}%")
          Log.d(TAG, "  - Internal volume: ${currentAudioSource.internalVolume * 100}%")
          Log.d(TAG, "  - Mix volume: ${currentAudioSource.mixVolume * 100}%")
          Log.d(TAG, "  - Is muted: ${currentAudioSource.isMuted()}")
          Log.d(TAG, "  - System volume: ${currentAudioSource.getCurrentSystemVolume() * 100}%")
          Log.d(TAG, "  - Is system volume zero: ${currentAudioSource.isSystemVolumeZero()}")
        }
        else -> {
          Log.d(TAG, "  - Volume control may be limited for this audio source")
        }
      }
      
      // 5. Apply proper volume controls based on current state
      Log.d(TAG, "Applying Volume Controls:")
      
      // Apply app volume levels
      applyStreamAudioLevels()
      
      // Check and apply system volume
      checkAndApplySystemVolume()
      
      // 6. Verify final state
      Log.d(TAG, "Final Audio State:")
      when (currentAudioSource) {
        is PristineAudioSource -> {
          Log.d(TAG, "  - Final mute state: ${currentAudioSource.isMuted()}")
          Log.d(TAG, "  - Final mic volume: ${currentAudioSource.getMicrophoneVolume() * 100}%")
          Log.d(TAG, "  - Final device volume: ${currentAudioSource.getDeviceAudioVolume() * 100}%")
        }
        is MixAudioSource -> {
          Log.d(TAG, "  - Final mute state: ${currentAudioSource.isMuted()}")
          Log.d(TAG, "  - Final mic volume: ${currentAudioSource.microphoneVolume * 100}%")
          Log.d(TAG, "  - Final internal volume: ${currentAudioSource.internalVolume * 100}%")
        }
      }
      
      // 7. Log recommendations
      Log.d(TAG, "Audio Control Recommendations:")
      if (systemVolumeLevel <= 0.01f) {
        Log.d(TAG, "  - Speaker volume is 0; encoded capture still follows the app sliders")
      }
      if (micVolume == 0) {
        Log.d(TAG, "  - ⚠️ Microphone volume is 0, no mic audio will be captured")
      }
      if (deviceVolume == 0) {
        Log.d(TAG, "  - ⚠️ Device volume is 0, no device audio will be captured")
      }
      if (isMicrophoneMuted) {
        Log.d(TAG, "  - ℹ️ Microphone is muted, only device audio will be captured")
      }
      
      Log.d(TAG, "=== END AUDIO CONTROLS VERIFICATION ===")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error verifying audio controls: ${e.message}")
    }
  }

  /**
   * Check and log current audio state for debugging
   */
  private fun checkAudioState() {
    try {
      val currentAudioSource = genericStream.audioSource
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
      val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
      val systemVolumeLevel = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
      
      Log.d(TAG, "=== AUDIO STATE DEBUG ===")
      Log.d(TAG, "Audio source type: ${currentAudioSource.javaClass.simpleName}")
      Log.d(TAG, "System volume: $currentVolume/$maxVolume (${systemVolumeLevel * 100}%)")
      Log.d(TAG, "App mic volume: $micVolume%")
      Log.d(TAG, "App device volume: $deviceVolume%")
      
      when (currentAudioSource) {
        is PristineAudioSource -> {
          Log.d(TAG, "PristineAudioSource state:")
          Log.d(TAG, "  - Microphone volume: ${currentAudioSource.getMicrophoneVolume() * 100}%")
          Log.d(TAG, "  - Device audio volume: ${currentAudioSource.getDeviceAudioVolume() * 100}%")
          Log.d(TAG, "  - System volume: ${currentAudioSource.getCurrentSystemVolume() * 100}%")
          Log.d(TAG, "  - Is muted: ${currentAudioSource.isMuted()}")
          Log.d(TAG, "  - Is system volume zero: ${currentAudioSource.isSystemVolumeZero()}")
        }
        is MixAudioSource -> {
          Log.d(TAG, "MixAudioSource state:")
          Log.d(TAG, "  - Microphone volume: ${currentAudioSource.microphoneVolume * 100}%")
          Log.d(TAG, "  - Internal volume: ${currentAudioSource.internalVolume * 100}%")
          Log.d(TAG, "  - Mix volume: ${currentAudioSource.mixVolume * 100}%")
          Log.d(TAG, "  - Is muted: ${currentAudioSource.isMuted()}")
          Log.d(TAG, "  - System volume: ${currentAudioSource.getCurrentSystemVolume() * 100}%")
          Log.d(TAG, "  - Is system volume zero: ${currentAudioSource.isSystemVolumeZero()}")
        }
        else -> {
          Log.d(TAG, "Other audio source - volume control may be limited")
        }
      }
      Log.d(TAG, "=== END AUDIO STATE DEBUG ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error checking audio state: ${e.message}")
    }
  }

  /**
   * Apply enhanced audio quality settings to reduce hissing and improve audio clarity
   */
  private fun applyEnhancedAudioQuality() {
    // PCM ownership, AAC frame sizing, and timestamp pacing are handled in the encoder. Do not
    // disguise gain changes as a quality filter: doing so previously reduced device audio and
    // could even turn a user-selected 0% level back on.
    Log.d(TAG, "Audio quality path active; preserving user-selected capture gains")
  }

  /**
   * Optimize audio settings for better quality and reduced hissing
   */
  fun optimizeAudioForQuality() {
    try {
      Log.d(TAG, "Applying stream audio levels without hidden gain overrides")
      applyStreamAudioLevels()
    } catch (e: Exception) {
      Log.e(TAG, "Error optimizing audio settings: ${e.message}")
    }
  }

  /**
   * Apply smart hissing elimination measures that preserve microphone audio
   */
  private fun applyAntiFeedbackMeasures(mixAudioSource: MixAudioSource) {
    Log.d(TAG, "Anti-feedback gain override skipped for ${mixAudioSource.javaClass.simpleName}")
  }

  /**
   * Apply advanced audio filtering to reduce hissing and improve quality
   */
  private fun applyAdvancedAudioFiltering() {
    Log.d(TAG, "Advanced gain override skipped; user capture levels remain unchanged")
  }

  /**
   * Try different audio configurations to find the best one for reducing hissing
   */
  fun tryAlternativeAudioConfigurations() {
    try {
      Log.d(TAG, "Trying alternative audio configurations to reduce hissing")
      
      // Store current settings
      val currentMicVolume = micVolume
      val currentDeviceVolume = deviceVolume
      val currentAudioSource = selectedAudioSource
      
      // Try different volume combinations
      val volumeConfigurations = listOf(
        Pair(50, 50),   // Balanced
        Pair(40, 60),   // More device audio
        Pair(60, 40),   // More microphone
        Pair(70, 30),   // Heavy microphone
        Pair(30, 70)    // Heavy device audio
      )
      
      // Try different audio sources if available
      val audioSources = listOf(
        R.id.audio_source_mix,
        R.id.audio_source_internal,
        R.id.audio_source_microphone
      )
      
      Log.d(TAG, "Testing different audio configurations for optimal quality")
      
      // Apply first configuration (balanced)
      micVolume = volumeConfigurations[0].first
      deviceVolume = volumeConfigurations[0].second
      
      // Keep current audio source but optimize volume levels
      // Don't switch audio sources automatically to preserve user's choice
      Log.d(TAG, "Keeping current audio source and optimizing volume levels")
      
      // Apply the new configuration
      applyStreamAudioLevels()
      applyEnhancedAudioQuality()
      applyAdvancedAudioFiltering()
      
      Log.d(TAG, "Applied alternative audio configuration - Mic: $micVolume%, Device: $deviceVolume%")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error trying alternative audio configurations: ${e.message}")
    }
    }

    /**
   * Switch to microphone-only mode to eliminate device audio hissing
   */
  fun switchToMicrophoneOnly() {
    try {
      Log.d(TAG, "Switching to microphone-only mode to eliminate device audio hissing")
      
      // Switch to microphone source only
      selectedAudioSource = R.id.audio_source_microphone
      toggleAudioSource(R.id.audio_source_microphone)
      
      // Set microphone to optimal level
      micVolume = 80 // High but not maximum to prevent distortion
      deviceVolume = 0 // Disable device audio completely
      
      // Apply the new configuration
      applyStreamAudioLevels()
      applyEnhancedAudioQuality()
      applyAdvancedAudioFiltering()
      
      // Save the settings
      saveVolumeSettings()
      
      Log.d(TAG, "Switched to microphone-only mode - Mic: $micVolume%, Device: $deviceVolume%")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error switching to microphone-only mode: ${e.message}")
    }
  }

    // Note: Volume levels are controlled by user via UI bars, not automatically changed

    // Note: Volume levels are controlled by user via UI bars, not automatically changed

  // Note: Volume levels are controlled by user via UI bars, not automatically changed

  // Note: Volume levels are controlled by user via UI bars, not automatically changed

  // Note: Volume levels are controlled by user via UI bars, not automatically changed

  // Note: Volume levels are controlled by user via UI bars, not automatically changed

  // Note: Volume levels are controlled by user via UI bars, not automatically changed

  // Note: Volume levels are controlled by user via UI bars, not automatically changed

  // Note: Volume levels are controlled by user via UI bars, not automatically changed

  fun isMicrophoneMuted(): Boolean {
    return isMicrophoneMuted
  }

  fun restoreAudioSourceState() {
    Log.d(
      TAG,
      "Restoring selected audio source without replacing it for mic mute; muted=$isMicrophoneMuted"
    )
    ensureAudioSourcePersistence()
    applyStreamAudioLevels()
  }

  fun ensureAudioSourcePersistence() {
    Log.d(
      TAG,
      "Ensuring selected source persistence - selected=$selectedAudioSource, muted=$isMicrophoneMuted"
    )

    try {
      val sourceMatchesSelection = when (selectedAudioSource) {
        R.id.audio_source_microphone -> genericStream.audioSource is PristineAudioSource
        R.id.audio_source_internal -> genericStream.audioSource is InternalAudioSource
        R.id.audio_source_mix,
        R.id.audio_source_dual_channel -> genericStream.audioSource is MixAudioSource
        else -> false
      }

      if (!sourceMatchesSelection) {
        Log.d(TAG, "Restoring selected audio source without coupling it to microphone mute")
        toggleAudioSource(selectedAudioSource)
      } else {
        Log.d(TAG, "Selected audio source is already active")
      }
      applyStreamAudioLevels()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to ensure audio source persistence: ${e.message}", e)
    }
  }


  fun setRotation(newRotation: Int) {
    // Don't force screen orientation - let user control device orientation manually
    Log.d("ScreenService", "setRotation called with $newRotation - ignoring to prevent forced screen rotation")
    
    // The streaming mode only affects video output quality settings, not screen orientation
    // Users can rotate their device manually to achieve the desired orientation
  }

  override fun onConnectionStarted(url: String) {
    if (streamRequested && !serviceDestroyed) callback?.onConnectionStarted(url)
  }

  override fun onConnectionSuccess() {
    if (!streamRequested || serviceDestroyed || INSTANCE !== this) {
      Log.w(TAG, "Ignoring late connection success after stream stop")
      try {
        if (genericStream.isStreaming) genericStream.stopStream()
      } catch (e: Exception) {
        Log.e(TAG, "Error closing late RTMP connection: ${e.message}")
      }
      return
    }
    resetReconnectState(clearEndpoint = false)
    // Re-assert the configured target after the Huawei encoder is running. Some vendor codecs
    // accept the initial MediaFormat value but only apply runtime rate control after start().
    genericStream.setVideoBitrateOnFly(vBitrate)
    Log.i(TAG, "Re-applied encoder bitrate target after publish start: ${vBitrate}bps")
    // The encoder starts before the RTMP publish handshake completes. Ask for a fresh IDR after
    // the sender is live so the endpoint never has to wait for (or recover from) a missing GOP.
    genericStream.requestKeyframe()
    Log.i(TAG, "Requested fresh keyframe after RTMP publish start")
    callback?.onConnectionSuccess()
  }

  override fun onNewBitrate(bitrate: Long) {
    lastBitrate = bitrate
    callback?.onNewBitrate(bitrate)
  }

  override fun onConnectionFailed(reason: String) {
    Log.e(TAG, "Connection failed: $reason")
    if (!streamRequested || serviceDestroyed || INSTANCE !== this) {
      Log.d(TAG, "Ignoring connection failure after intentional stop")
      return
    }
    // Attempt auto-reconnect for up to 60s while keeping encoders running
    if (!isReconnecting) {
      isReconnecting = true
      reconnectEndTimeMs = System.currentTimeMillis() + reconnectTimeoutMs
    }

    val remaining = reconnectEndTimeMs - System.currentTimeMillis()
    if (remaining > 0) {
      // Ask stream client to retry after a short delay
      val delay = reconnectIntervalMs.coerceAtMost(remaining)
      val retried = genericStream.getStreamClient().reTry(delay, reason)
      Log.w(TAG, "Scheduling reconnect in ${delay}ms. accepted=$retried remaining=${remaining}ms")
      if (!retried) {
        // If client refuses to retry, fall back to one service-owned delayed restart.
        scheduleManualReconnect(delay, "connection failure")
      }
      // Do not stop service here; we'll let retries proceed within the window
      callback?.onConnectionFailed("Retrying: $reason")
      return
    }

    // Exhausted reconnect window: cleanup and notify
    streamRequested = false
    resetReconnectState(clearEndpoint = true)
    try {
      if (genericStream.isStreaming) genericStream.stopStream()
      finishCaptureSessionIfIdle()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service after retries: ${e.message}")
    }
    callback?.onConnectionFailed(reason)
  }

  override fun onDisconnect() {
    Log.d(TAG, "Connection disconnected")

    if (!streamRequested || serviceDestroyed || INSTANCE !== this) {
      Log.d(TAG, "Intentional disconnect completed; reconnect disabled")
      resetReconnectState(clearEndpoint = true)
      callback?.onDisconnect()
      return
    }

    // Treat unexpected disconnect like failure and attempt reconnect within window
    if (!isReconnecting) {
      isReconnecting = true
      reconnectEndTimeMs = System.currentTimeMillis() + reconnectTimeoutMs
    }
    val remaining = reconnectEndTimeMs - System.currentTimeMillis()
    if (remaining > 0) {
      val delay = reconnectIntervalMs.coerceAtMost(remaining)
      val retried = genericStream.getStreamClient().reTry(delay, "disconnect")
      Log.w(TAG, "Scheduling reconnect after disconnect in ${delay}ms. accepted=$retried remaining=${remaining}ms")
      if (!retried) {
        scheduleManualReconnect(delay, "disconnect")
      }
      callback?.onDisconnect()
      return
    }

    // Exhausted reconnect window; perform cleanup
    streamRequested = false
    resetReconnectState(clearEndpoint = true)
    try {
      if (genericStream.isStreaming) genericStream.stopStream()
      finishCaptureSessionIfIdle()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service after disconnect retries: ${e.message}")
    }
    callback?.onDisconnect()
  }

  override fun onAuthError() {
    callback?.onAuthError()
  }

  override fun onAuthSuccess() {
    callback?.onAuthSuccess()
  }

  /**
   * Investigate hissing sound by testing different audio configurations
   */
  fun investigateHissingSound() {
    try {
      Log.d(TAG, "=== HISSING SOUND INVESTIGATION ===")
      Log.d(TAG, "Current audio source: ${genericStream.audioSource.javaClass.simpleName}")
      Log.d(TAG, "Current volume levels - Mic: $micVolume%, Device: $deviceVolume%")
      
      // Test different audio source configurations
      val audioSources = listOf(
        "Microphone Only" to { 
          Log.d(TAG, "Testing MicrophoneSource only")
          toggleAudioSource(R.id.audio_source_microphone)
        },
        "Internal Audio Only" to { 
          Log.d(TAG, "Testing InternalAudioSource only")
          toggleAudioSource(R.id.audio_source_internal)
        },
        "Mix Audio" to { 
          Log.d(TAG, "Testing MixAudioSource")
          toggleAudioSource(R.id.audio_source_mix)
        }
      )
      
      for ((name, testFunction) in audioSources) {
        try {
          Log.d(TAG, "--- Testing $name ---")
          testFunction()
          Log.d(TAG, "Successfully tested $name")
          // Give time for audio to stabilize
          Thread.sleep(1000)
        } catch (e: Exception) {
          Log.e(TAG, "Error testing $name: ${e.message}")
        }
      }
      
      Log.d(TAG, "=== HISSING INVESTIGATION COMPLETE ===")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error during hissing investigation: ${e.message}")
    }
  }

  /**
   * Test device audio hissing when microphone is enabled
   */
  fun testDeviceAudioHissingWithMic() {
    try {
      Log.d(TAG, "=== TESTING DEVICE AUDIO HISSING WITH MICROPHONE ===")
      
      // Test 1: Internal Audio Only (should be clean)
      Log.d(TAG, "Test 1: Internal Audio Only (baseline)")
      toggleAudioSource(R.id.audio_source_internal)
      Log.d(TAG, "Internal audio only - should be clean")
      Thread.sleep(2000)
      
      // Test 2: Mix Audio with high mic volume (potential hissing)
      Log.d(TAG, "Test 2: Mix Audio with high microphone volume")
      toggleAudioSource(R.id.audio_source_mix)
      Log.d(TAG, "Mix audio with mic enabled - check for hissing")
      Thread.sleep(2000)
      
      // Test 3: Mix Audio with low mic volume
      Log.d(TAG, "Test 3: Mix Audio with low microphone volume")
      Log.d(TAG, "Mix audio with reduced mic - check if hissing reduces")
      Thread.sleep(2000)
      
      // Test 4: Microphone Only
      Log.d(TAG, "Test 4: Microphone Only")
      toggleAudioSource(R.id.audio_source_microphone)
      Log.d(TAG, "Microphone only - check for hissing")
      Thread.sleep(2000)
      
      Log.d(TAG, "=== DEVICE AUDIO HISSING TEST COMPLETE ===")
      Log.d(TAG, "Check logs for audio source changes and potential hissing patterns")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error during device audio hissing test: ${e.message}")
    }
  }

  /**
   * Switch to dual channel audio (enhanced mixing) to eliminate hissing
   */
  fun switchToDualChannelAudio() {
    try {
      Log.d(TAG, "=== SWITCHING TO DUAL CHANNEL AUDIO ===")
      Log.d(TAG, "This will use enhanced MixAudioSource with anti-feedback measures")
      Log.d(TAG, "Prevents hissing by smart volume management")
      
      // Switch to dual channel audio source (enhanced MixAudioSource)
      toggleAudioSource(R.id.audio_source_dual_channel)
      
      // Apply current volume levels with anti-feedback measures
      applyStreamAudioLevels()
      
      Log.d(TAG, "Successfully switched to dual channel audio")
      Log.d(TAG, "Enhanced mixing with anti-feedback measures")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error switching to dual channel audio: ${e.message}")
    }
  }

  /**
   * Investigate and eliminate hissing sound completely
   */
  fun eliminateHissingCompletely() {
    try {
      Log.d(TAG, "=== COMPREHENSIVE HISSING ELIMINATION ===")
      Log.d(TAG, "Investigating root cause of hissing sound")
      
      // Test 1: Internal Audio Only (should be clean)
      Log.d(TAG, "Test 1: Switching to Internal Audio Only")
      toggleAudioSource(R.id.audio_source_internal)
      Log.d(TAG, "Internal audio only - check if hissing persists")
      Thread.sleep(2000)
      
      // Test 2: Microphone Only
      Log.d(TAG, "Test 2: Switching to Microphone Only")
      toggleAudioSource(R.id.audio_source_microphone)
      Log.d(TAG, "Microphone only - check if hissing persists")
      Thread.sleep(2000)
      
      // Test 3: Enhanced Mix with aggressive measures
      Log.d(TAG, "Test 3: Enhanced Mix with aggressive hissing elimination")
      toggleAudioSource(R.id.audio_source_dual_channel)
      applyStreamAudioLevels()
      Log.d(TAG, "Enhanced mix with aggressive measures - check if hissing persists")
      Thread.sleep(2000)
      
      // Test 4: Device Audio Only (force internal audio)
      Log.d(TAG, "Test 4: Force Device Audio Only")
      toggleAudioSource(R.id.audio_source_internal)
      Log.d(TAG, "Forced device audio only - should eliminate all hissing")
      Thread.sleep(2000)
      
      Log.d(TAG, "=== HISSING ELIMINATION TEST COMPLETE ===")
      Log.d(TAG, "Check logs for audio source changes and hissing patterns")
      Log.d(TAG, "If hissing persists in all tests, the issue may be:")
      Log.d(TAG, "1. Hardware interference")
      Log.d(TAG, "2. System audio processing")
      Log.d(TAG, "3. Device-specific audio issues")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error during hissing elimination test: ${e.message}")
    }
  }

  /**
   * Test different microphone configurations to find optimal balance
   */
  fun testMicrophoneConfigurations() {
    try {
      Log.d(TAG, "=== TESTING MICROPHONE CONFIGURATIONS ===")
      Log.d(TAG, "Finding optimal balance between microphone audio and hissing prevention")
      
      // Test 1: High microphone, low device audio
      Log.d(TAG, "Test 1: High microphone (80%), Low device audio (20%)")
      micVolume = 80
      deviceVolume = 20
      applyStreamAudioLevels()
      Log.d(TAG, "Applied high mic, low device configuration")
      Thread.sleep(2000)
      
      // Test 2: Balanced configuration
      Log.d(TAG, "Test 2: Balanced (60% mic, 40% device)")
      micVolume = 60
      deviceVolume = 40
      applyStreamAudioLevels()
      Log.d(TAG, "Applied balanced configuration")
      Thread.sleep(2000)
      
      // Test 3: Low microphone, high device audio
      Log.d(TAG, "Test 3: Low microphone (40%), High device audio (60%)")
      micVolume = 40
      deviceVolume = 60
      applyStreamAudioLevels()
      Log.d(TAG, "Applied low mic, high device configuration")
      Thread.sleep(2000)
      
      // Test 4: Microphone only
      Log.d(TAG, "Test 4: Microphone only (100% mic, 0% device)")
      micVolume = 100
      deviceVolume = 0
      applyStreamAudioLevels()
      Log.d(TAG, "Applied microphone only configuration")
      Thread.sleep(2000)
      
      Log.d(TAG, "=== MICROPHONE CONFIGURATION TEST COMPLETE ===")
      Log.d(TAG, "Check which configuration provides best microphone audio with minimal hissing")
      
    } catch (e: Exception) {
      Log.e(TAG, "Error testing microphone configurations: ${e.message}")
    }
  }

  /**
   * Configure noise gate filter for hissing suppression
   */

  /**
   * Force reload and apply volume settings to ensure current settings are active
   */
  private fun forceReloadAndApplyVolumeSettings() {
    try {
      Log.d(TAG, "=== FORCE RELOADING VOLUME SETTINGS ===")
      
      // Reload volume settings from preferences
      val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      applyMicrophoneState(readStoredMicrophoneState(prefs))
      deviceVolume = prefs.getInt(KEY_DEVICE_VOLUME, 100)
      
      Log.d(TAG, "Reloaded volume settings - Mic: $micVolume%, Device: $deviceVolume%")
      
      // Apply the settings immediately
      applyStreamAudioLevels()
      
      Log.d(TAG, "=== VOLUME SETTINGS RELOADED AND APPLIED ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error force reloading volume settings: ${e.message}")
    }
  }

  /**
   * Ensure audio source is properly initialized and ready for volume control
   */
  private fun ensureAudioSourceReady() {
    try {
      val currentAudioSource = genericStream.audioSource
      Log.d(TAG, "=== ENSURING AUDIO SOURCE READY ===")
      Log.d(TAG, "Current audio source: ${currentAudioSource.javaClass.simpleName}")
      
      // Wait a moment for audio source to be fully initialized
      Thread.sleep(100)
      
      // Verify audio source is accessible
      when (currentAudioSource) {
        is PristineAudioSource -> {
          Log.d(TAG, "✅ PristineAudioSource is ready")
        }
        is MixAudioSource -> {
          Log.d(TAG, "✅ MixAudioSource is ready")
        }
        is MicrophoneSource -> {
          Log.d(TAG, "✅ MicrophoneSource is ready")
        }
        is InternalAudioSource -> {
          Log.d(TAG, "✅ InternalAudioSource is ready")
        }
        else -> {
          Log.d(TAG, "⚠️ Unknown audio source type: ${currentAudioSource.javaClass.simpleName}")
        }
      }
      
      Log.d(TAG, "=== AUDIO SOURCE READY ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error ensuring audio source ready: ${e.message}")
    }
  }

  /**
   * Sync current volume settings from UI to ensure we have the latest values
   */
  private fun syncCurrentVolumeSettings() {
    try {
      Log.d(TAG, "=== SYNCING CURRENT VOLUME SETTINGS ===")
      
      // Get current values from SharedPreferences (which UI updates)
      val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      val storedMicrophoneState = readStoredMicrophoneState(prefs)
      val currentMicVolume = storedMicrophoneState.volumePercent
      val currentDeviceVolume = prefs.getInt(KEY_DEVICE_VOLUME, deviceVolume)
      
      Log.d(TAG, "Current saved values - Mic: $currentMicVolume%, Device: $currentDeviceVolume%")
      Log.d(TAG, "Service cached values - Mic: $micVolume%, Device: $deviceVolume%")
      
      // Update service variables if they differ
      if (storedMicrophoneState != currentMicrophoneState()) {
        Log.d(
          TAG,
          "🔄 Updating microphone state: volume $micVolume% -> $currentMicVolume%, " +
            "muted $isMicrophoneMuted -> ${storedMicrophoneState.muted}"
        )
        applyMicrophoneState(storedMicrophoneState)
      } else {
        Log.d(TAG, "✅ Mic volume already in sync: $micVolume%")
      }
      
      if (currentDeviceVolume != deviceVolume) {
        Log.d(TAG, "🔄 Updating device volume from $deviceVolume% to $currentDeviceVolume%")
        deviceVolume = currentDeviceVolume
      } else {
        Log.d(TAG, "✅ Device volume already in sync: $deviceVolume%")
      }
      
      Log.d(TAG, "=== VOLUME SETTINGS SYNCED ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error syncing volume settings: ${e.message}")
    }
  }

  /**
   * Apply current volume settings immediately (called from UI)
   */
  fun applyCurrentVolumeSettings() {
    try {
      Log.d(TAG, "=== APPLYING CURRENT VOLUME SETTINGS FROM UI ===")
      
      // Sync with current saved values
      syncCurrentVolumeSettings()
      
      // Apply the settings immediately
      applyStreamAudioLevels()
      
      Log.d(TAG, "=== CURRENT VOLUME SETTINGS APPLIED ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error applying current volume settings: ${e.message}")
    }
  }

  /**
   * Debug audio source state when streaming/recording starts
   */
  private fun debugAudioSourceOnStart() {
    try {
      Log.d(TAG, "=== DEBUGGING AUDIO SOURCE ON START ===")
      
      val currentAudioSource = genericStream.audioSource
      Log.d(TAG, "Audio source type: ${currentAudioSource.javaClass.simpleName}")
      Log.d(TAG, "Audio source null: ${currentAudioSource == null}")
      
      if (currentAudioSource != null) {
        when (currentAudioSource) {
          is PristineAudioSource -> {
            Log.d(TAG, "PristineAudioSource details:")
            Log.d(TAG, "  - Microphone volume: ${currentAudioSource.getMicrophoneVolume() * 100}%")
            Log.d(TAG, "  - Device audio volume: ${currentAudioSource.getDeviceAudioVolume() * 100}%")
            Log.d(TAG, "  - Is muted: ${currentAudioSource.isMuted()}")
          }
          is MixAudioSource -> {
            Log.d(TAG, "MixAudioSource details:")
            Log.d(TAG, "  - Microphone volume: ${currentAudioSource.microphoneVolume * 100}%")
            Log.d(TAG, "  - Internal volume: ${currentAudioSource.internalVolume * 100}%")
            Log.d(TAG, "  - Is muted: ${currentAudioSource.isMuted()}")
          }
          else -> {
            Log.d(TAG, "Other audio source type")
          }
        }
      }
      
      Log.d(TAG, "Service volume values - Mic: $micVolume%, Device: $deviceVolume%")
      Log.d(TAG, "=== END AUDIO SOURCE DEBUG ===")
    } catch (e: Exception) {
      Log.e(TAG, "Error debugging audio source: ${e.message}")
    }
  }
}
