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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.stream.prime.audio.PristineAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.stream.prime.R
import com.stream.prime.utils.PathUtils
import com.stream.prime.utils.toast
import com.stream.prime.settings.SettingsManager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.PendingIntent
import android.content.Context
import android.content.IntentFilter
import android.content.BroadcastReceiver
import com.pedro.encoder.input.audio.MicrophoneManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.stream.prime.accessibility.StreamAccessibilityService
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.stream.prime.overlay.OverlayManager
import com.stream.prime.overlay.LayeredOverlayRenderer
import com.stream.prime.overlay.ScreenLayoutFilterRender
import com.stream.prime.overlay.LayerCanvasRenderer
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
  }

  private var notificationManager: NotificationManager? = null
  private lateinit var genericStream: GenericStream
  
  fun getGenericStream(): GenericStream = genericStream
  private var mediaProjection: MediaProjection? = null
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
  private var deviceVolume = 100
  private var lastBitrate: Long = 0
  private var streamStartTime: Long = 0
  private val PREFS_NAME = "StreamAudioPrefs"
  private val KEY_MIC_VOLUME = "mic_volume"
  private val KEY_DEVICE_VOLUME = "device_volume"
  private val KEY_STREAM_START_TIME = "stream_start_time"

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
          micVolume = prefs.getInt(KEY_MIC_VOLUME, 100)
          deviceVolume = prefs.getInt(KEY_DEVICE_VOLUME, 100)
          Log.d(TAG, "Volume settings loaded - Mic: $micVolume%, Device: $deviceVolume%")
          
          // Apply the loaded settings to the audio sources if streaming
          if (::genericStream.isInitialized && genericStream.isStreaming) {
              applyStreamAudioLevels()
              Log.d(TAG, "Applied loaded volume settings to active stream")
          }
      } catch (e: Exception) {
          Log.e(TAG, "Error loading volume settings: ${e.message}")
          // Use defaults if loading fails
          micVolume = 100
          deviceVolume = 100
      }
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
              .putInt(KEY_DEVICE_VOLUME, deviceVolume)
              .apply()
          Log.d(TAG, "Volume settings saved - Mic: $micVolume%, Device: $deviceVolume%")
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
    
    genericStream = GenericStream(baseContext, this, NoVideoSource(), PristineAudioSource(this)).apply {
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
                    stopStream()
                }
                ACTION_TOGGLE_MIC -> {
                    Log.d(TAG, "Toggle mic action received from notification")
                    toggleMicrophone()
                    updateNotification()
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

  private fun startForegroundService() {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Stream Prime",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Stream Prime notification channel"
    }
    
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(channel)
    
    // Determine notification text based on current state
    val contentText = when {
      genericStream.isRecording -> "Recording video..."
      genericStream.isStreaming -> "Streaming live..."
      else -> "Service active"
    }
    
    // Create PendingIntents with proper flags for Android 16
    val micIntent = Intent(this, ScreenService::class.java).apply {
        action = ACTION_TOGGLE_MIC
    }
    val stopIntent = Intent(this, ScreenService::class.java).apply {
        action = ACTION_STOP_STREAM
    }
    
    val micPendingIntent = PendingIntent.getService(
        this,
        0,
        micIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    
    val stopPendingIntent = PendingIntent.getService(
        this,
        1,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    
    val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Stream Prime")
        .setContentText(contentText)
        .setSmallIcon(R.drawable.ic_notification_small)
        .setSilent(true)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .addAction(
            R.drawable.ic_launcher_foreground,
            if (isMicrophoneMuted) "Unmute Mic" else "Mute Mic",
            micPendingIntent
        )
    
    // Only add stop action if streaming or recording is active
    if (genericStream.isStreaming || genericStream.isRecording) {
        notificationBuilder.addAction(
            R.drawable.ic_launcher_foreground,
            if (genericStream.isRecording) "Stop Recording" else "Stop Stream",
            stopPendingIntent
        )
    }
    
    val notification = notificationBuilder.build()
    startForeground(NOTIFY_ID, notification)
  }

  fun updateNotification() {
    try {
      val notificationManager = getSystemService(NotificationManager::class.java)
      
      // Determine notification text based on current state
      val contentText = when {
        genericStream.isRecording -> "Recording video..."
        genericStream.isStreaming -> "Streaming live..."
        else -> "Service active"
      }
      
      // Create PendingIntents with proper flags for Android 16
      val micIntent = Intent(this, ScreenService::class.java).apply {
          action = ACTION_TOGGLE_MIC
      }
      val stopIntent = Intent(this, ScreenService::class.java).apply {
          action = ACTION_STOP_STREAM
      }
      
      val micPendingIntent = PendingIntent.getService(
          this,
          0,
          micIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      
      val stopPendingIntent = PendingIntent.getService(
          this,
          1,
          stopIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      
      val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
          .setContentTitle("Stream Prime")
          .setContentText(contentText)
          .setSmallIcon(R.drawable.ic_notification_small)
          .setSilent(true)
          .setOngoing(true)
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .addAction(
              R.drawable.ic_launcher_foreground,
              if (isMicrophoneMuted) "Unmute Mic" else "Mute Mic",
              micPendingIntent
          )
      
      // Only add stop action if streaming or recording is active
      if (genericStream.isStreaming || genericStream.isRecording) {
          notificationBuilder.addAction(
              R.drawable.ic_launcher_foreground,
              if (genericStream.isRecording) "Stop Recording" else "Stop Stream",
              stopPendingIntent
          )
      }
      
      val notification = notificationBuilder.build()
      notificationManager.notify(NOTIFY_ID, notification)
      
      Log.d(TAG, "Notification updated successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error updating notification: ${e.message}")
      // Don't let notification errors crash the service
    }
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
      
      // Ensure foreground service is maintained for background audio access
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(NOTIFY_ID, notification)
        Log.d("ScreenService", "Maintained foreground service with microphone access")
      } else {
        startForeground(NOTIFY_ID, notification)
        Log.d("ScreenService", "Maintained foreground service (legacy)")
      }
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
    return null
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "RTP Display service started")
    
    // Handle notification action intents
    when (intent?.action) {
        ACTION_STOP_STREAM -> {
            Log.d(TAG, "Stop action received from notification")
            // Stop both streaming and recording
            if (genericStream.isStreaming) {
                stopStream()
            }
            if (genericStream.isRecording) {
                stopRecording()
            }
        }
        ACTION_TOGGLE_MIC -> {
            Log.d(TAG, "Toggle mic action received from notification")
            toggleMicrophone()
            // Add a small delay to ensure state is updated before updating notification
            Handler(Looper.getMainLooper()).postDelayed({
                updateNotification()
            }, 100)
        }
        ACTION_APPLY_OVERLAY -> {
            Log.d(TAG, "Apply overlay action received by service")
            applyConfiguredOverlay()
        }
    }
    
    return START_STICKY
  }

  fun sendIntent(): Intent {
    return mediaProjectionManager.createScreenCaptureIntent()
  }

  fun isStreaming(): Boolean {
    return genericStream.isStreaming
  }

  fun isRecording(): Boolean {
    return genericStream.isRecording
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

      if (!genericStream.isRecording) {
        Log.d("ScreenService", "No recording active, stopping service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION")
          stopForeground(true)
        }
        stopSelf()
      } else {
        updateNotification()
      }
    } catch (e: Exception) {
      Log.e("ScreenService", "Error stopping stream: ${e.message}")
    }
  }

  fun stopRecording() {
    try {
      if (genericStream.isRecording) {
        genericStream.stopRecord()
        PathUtils.updateGallery(this, recordPath)
        Log.d("ScreenService", "Recording stopped")
        
        // If no streaming is happening, stop the service entirely
        if (!genericStream.isStreaming) {
          Log.d("ScreenService", "No streaming active, stopping service")
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
          } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
          }
          stopSelf()
        } else {
          // Update notification if streaming is still active
          updateNotification()
        }
      }
    } catch (e: Exception) {
      Log.e("ScreenService", "Error stopping recording: ${e.message}")
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
    try {
      // Unregister broadcast receiver
      notificationReceiver?.let {
        unregisterReceiver(it)
        notificationReceiver = null
      }
      
      // Stop foreground service
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
      
      // Release the entire stream object, including preview EGL and MediaProjection
      // resources. Leaving preview attached lets a replacement service create a second
      // publisher but fail to attach the same SurfaceView with EGL_BAD_ALLOC.
      if (::genericStream.isInitialized) {
        val wasRecording = genericStream.isRecording
        genericStream.release()
        if (wasRecording && recordPath.isNotBlank()) PathUtils.updateGallery(this, recordPath)
      }
      mediaProjection?.stop()
      mediaProjection = null
      
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

  fun prepareStream(resultCode: Int, data: Intent): Boolean {
    keepAliveTrick()
    // Re-prepare encoders without stopping this service. The public stopStream() represents
    // a user Stop and intentionally calls stopSelf().
    streamRequested = false
    resetReconnectState(clearEndpoint = true)
    if (genericStream.isStreaming) genericStream.stopStream()
    mediaProjection?.stop()
    
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
    
    val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data) ?: throw IllegalStateException("get MediaProjection failed")
    this.mediaProjection = mediaProjection
    val screenSource = ScreenSource(applicationContext, mediaProjection)
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
        genericStream.changeAudioSource(PristineAudioSource(this))
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
            genericStream.changeAudioSource(MixAudioSource(it, context = this))
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
            genericStream.changeAudioSource(MixAudioSource(projection, context = this))
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
      genericStream.changeAudioSource(PristineAudioSource(this))
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
      
      // Start foreground service when recording begins
      startForegroundService()
      
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
      
      genericStream.startRecord(recordPath) { status ->
        if (status == RecordController.Status.RECORDING) {
          state(RecordController.Status.RECORDING)
          Log.d("ScreenService", "Recording started successfully")
          
          // Debug audio source after recording starts
          Log.d(TAG, "=== AUDIO SOURCE AFTER RECORDING START ===")
          debugAudioSourceOnStart()
          
          // Re-apply volume settings after recording starts
          Log.d(TAG, "=== RE-APPLYING VOLUME SETTINGS AFTER RECORDING START ===")
          applyStreamAudioLevels()
          
          // Update notification to show recording status
          updateNotification()
        }
      }
      state(RecordController.Status.STARTED)
    } else {
      genericStream.stopRecord()
      state(RecordController.Status.STOPPED)
      PathUtils.updateGallery(this, recordPath)
      Log.d("ScreenService", "Recording stopped")
      
      // Update notification to show current status
      updateNotification()
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
    startForegroundService()
    
    // Validate endpoint format
    if (!endpoint.startsWith("rtmp://")) {
      Log.e("ScreenService", "Invalid RTMP endpoint format")
      callback?.onConnectionFailed("Invalid RTMP URL format")
      return
    }

    if (serviceDestroyed || INSTANCE !== this) {
      Log.w(TAG, "Ignoring stream start from an inactive service instance")
      callback?.onConnectionFailed("Streaming service is no longer active")
      return
    }
    
    // Remember endpoint for reconnection
    streamRequested = true
    resetReconnectState(clearEndpoint = false)
    currentEndpoint = endpoint

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
        callback?.onConnectionFailed("Failed to start stream: ${e.message}")
      }
    } else {
      Log.d("ScreenService", "Stream already running, ignoring start request")
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
    }
    
    // Update settings
    width = newWidth
    height = newHeight
    fps = newFps
    vBitrate = newBitrate
    
    // Reinitialize with new settings
    genericStream.release()
    genericStream = GenericStream(baseContext, this, NoVideoSource(), PristineAudioSource(this)).apply {
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

  private fun applyConfiguredOverlay() {
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
          if (belowScreen.isNotEmpty()) renderer.setBackgroundImage(renderOverlayBitmap(belowScreen))
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

  private fun renderOverlayBitmap(layers: List<OverlayLayer>): Bitmap? {
    val bitmap = LayerCanvasRenderer.createCanvasBitmap(width, height) ?: return null
    val cache = mutableMapOf<String, Bitmap?>()
    LayerCanvasRenderer.renderLayersOnCanvas(
      canvas = android.graphics.Canvas(bitmap),
      layers = layers,
      canvasWidth = width.toFloat(),
      canvasHeight = height.toFloat(),
      context = this,
      layerBitmapCache = cache
    )
    LayerCanvasRenderer.clearBitmapCache(cache)
    return bitmap
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
    }
    
    // Reinitialize with new settings
    genericStream.release()
    genericStream = GenericStream(baseContext, this, NoVideoSource(), PristineAudioSource(this)).apply {
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
    try {
      isMicrophoneMuted = !isMicrophoneMuted
      Log.d("ScreenService", "Toggling microphone - isMicrophoneMuted: $isMicrophoneMuted")
      
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        mediaProjection?.let { projection ->
          try {
            if (isMicrophoneMuted) {
              // Switch to InternalAudioSource (device audio only)
              genericStream.changeAudioSource(InternalAudioSource(projection))
              Log.d(TAG, "Switched to device audio only")
            } else {
              // Switch to MixAudioSource (device audio + microphone)
              genericStream.changeAudioSource(MixAudioSource(projection, context = this))
              Log.d(TAG, "Switched to device audio + microphone")
            }
            
                  // Apply current volume levels to the new audio source
      applyStreamAudioLevels()
      
      // Apply enhanced audio quality settings to reduce hissing
      applyEnhancedAudioQuality()
      
      // Comprehensive verification after toggling microphone
      verifyAudioControlsAndStates()
            
          } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle microphone: ${e.message}")
          }
        }
      }
      
      saveVolumeSettings() // Save volume settings after toggling microphone
      
      // Log the microphone state change for debugging
      Log.d(TAG, "Microphone state changed - isMicrophoneMuted: $isMicrophoneMuted")
    } catch (e: Exception) {
      Log.e(TAG, "Error toggling microphone: ${e.message}")
    }
  }

  fun setMicVolume(volume: Int) {
    try {
      Log.d(TAG, "=== SETTING MIC VOLUME ===")
      Log.d(TAG, "Previous mic volume: $micVolume%")
      Log.d(TAG, "New mic volume: $volume%")
      
      micVolume = volume.coerceIn(0, 100)
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

  /**
   * Handle window state changes from accessibility service
   */
  fun onWindowStateChanged(packageName: String?, className: String?) {
    Log.d(TAG, "Window state changed - Package: $packageName, Class: $className")
    
    // Update notification with current app info if streaming
    if (genericStream.isStreaming || genericStream.isRecording) {
      updateNotification()
    }
  }

  /**
   * Handle window content changes from accessibility service
   */
  fun onWindowContentChanged(packageName: String?) {
    Log.d(TAG, "Window content changed - Package: $packageName")
    
    // Log the current app being captured
    if (genericStream.isStreaming || genericStream.isRecording) {
      Log.d(TAG, "Currently capturing: $packageName")
    }
  }

  /**
   * Handle user interactions from accessibility service
   */
  fun onUserInteraction(packageName: String?, className: String?) {
    Log.d(TAG, "User interaction - Package: $packageName, Class: $className")
    
    // Track user interactions for analytics or enhanced capture
    if (genericStream.isStreaming || genericStream.isRecording) {
      Log.d(TAG, "User interaction detected while streaming/recording")
    }
  }

  /**
   * Check if accessibility service is enabled
   */
  fun isAccessibilityServiceEnabled(): Boolean {
    return StreamAccessibilityService.INSTANCE?.isAccessibilityServiceEnabled() == true
  }

  /**
   * Get enhanced window information for better capture
   */
  fun getEnhancedWindowInfo(): String? {
    return StreamAccessibilityService.INSTANCE?.getCurrentWindowInfo()
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
      
      // Convert percentage to float (0.0 to 1.0) for stream audio processing
      val micLevelFloat = micVolume / 100f
      val deviceAudioLevelFloat = deviceVolume / 100f
      
      Log.d(TAG, "=== APPLYING STREAM AUDIO LEVELS ===")
      Log.d(TAG, "Raw volume values - Mic: $micVolume%, Device: $deviceVolume%")
      Log.d(TAG, "Converted float values - Mic: $micLevelFloat, Device: $deviceAudioLevelFloat")
      
      // Apply volume levels to the current audio source
      val currentAudioSource = genericStream.audioSource
      Log.d(TAG, "Current audio source type: ${currentAudioSource.javaClass.simpleName}")
      
      when (currentAudioSource) {
        is PristineAudioSource -> {
          // Apply volume to PristineAudioSource
          currentAudioSource.setMicrophoneVolume(micLevelFloat)
          currentAudioSource.setDeviceAudioVolume(deviceAudioLevelFloat)
          
          // Check system volume and mute if necessary
          val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
          val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
          val systemVolumeLevel = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
          
          if (systemVolumeLevel <= 0.01f) {
            currentAudioSource.mute()
            Log.d(TAG, "🔇 System volume is 0, muting PristineAudioSource")
          } else {
            currentAudioSource.unMute()
            Log.d(TAG, "🔊 System volume is ${systemVolumeLevel * 100}%, unmuting PristineAudioSource")
          }
          
          Log.d(TAG, "✅ Applied volume to PristineAudioSource - Mic: $micLevelFloat, Device: $deviceAudioLevelFloat, System: ${systemVolumeLevel * 100}%")
        }
        is MixAudioSource -> {
          // Apply volume control to MixAudioSource
          currentAudioSource.microphoneVolume = micLevelFloat
          currentAudioSource.internalVolume = deviceAudioLevelFloat
          
          // Check system volume and mute if necessary
          val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
          val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
          val systemVolumeLevel = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
          
          if (systemVolumeLevel <= 0.01f) {
            currentAudioSource.mute()
            Log.d(TAG, "🔇 System volume is 0, muting MixAudioSource")
          } else {
            currentAudioSource.unMute()
            Log.d(TAG, "🔊 System volume is ${systemVolumeLevel * 100}%, unmuting MixAudioSource")
          }
          
          Log.d(TAG, "✅ Applied volume to MixAudioSource - Mic: $micLevelFloat, Device: $deviceAudioLevelFloat, System: ${systemVolumeLevel * 100}%")
        }
        is MicrophoneSource -> {
          // For MicrophoneSource, we can only control microphone volume
          // We need to access the MicrophoneManager through reflection or extension
          try {
            val microphoneField = currentAudioSource.javaClass.getDeclaredField("microphone")
            microphoneField.isAccessible = true
            val microphone = microphoneField.get(currentAudioSource) as? MicrophoneManager
            microphone?.setMicrophoneVolume(micLevelFloat)
            Log.d(TAG, "⚠️ Applied microphone volume to MicrophoneSource: $micLevelFloat (device audio not controlled)")
          } catch (e: Exception) {
            Log.e(TAG, "Error applying volume to MicrophoneSource: ${e.message}")
          }
        }
        is InternalAudioSource -> {
          // For InternalAudioSource, we can only control internal audio volume
          try {
            val microphoneField = currentAudioSource.javaClass.getDeclaredField("microphone")
            microphoneField.isAccessible = true
            val microphone = microphoneField.get(currentAudioSource) as? MicrophoneManager
            microphone?.setInternalVolume(deviceAudioLevelFloat)
            Log.d(TAG, "⚠️ Applied internal volume to InternalAudioSource: $deviceAudioLevelFloat (microphone not controlled)")
          } catch (e: Exception) {
            Log.e(TAG, "Error applying volume to InternalAudioSource: ${e.message}")
          }
        }
        else -> {
          Log.d(TAG, "❌ Unknown audio source type: ${currentAudioSource.javaClass.simpleName}")
        }
      }
      
    } catch (e: Exception) {
      Log.e(TAG, "Error applying stream audio levels: ${e.message}")
    }
    
    // Check and apply system volume
    checkAndApplySystemVolume()
    
    // Debug the audio state
    checkAudioState()
  }

  /**
   * Check and apply system volume to current audio source
   */
  private fun checkAndApplySystemVolume() {
    try {
      val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
      val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
      val systemVolumeLevel = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
      
      val currentAudioSource = genericStream.audioSource
      when (currentAudioSource) {
        is PristineAudioSource -> {
          if (systemVolumeLevel <= 0.01f) {
            currentAudioSource.mute()
            Log.d(TAG, "🔇 System volume is 0, muting PristineAudioSource")
          } else {
            currentAudioSource.unMute()
            Log.d(TAG, "🔊 System volume is ${systemVolumeLevel * 100}%, unmuting PristineAudioSource")
          }
        }
        is MixAudioSource -> {
          if (systemVolumeLevel <= 0.01f) {
            currentAudioSource.mute()
            Log.d(TAG, "🔇 System volume is 0, muting MixAudioSource")
          } else {
            currentAudioSource.unMute()
            Log.d(TAG, "🔊 System volume is ${systemVolumeLevel * 100}%, unmuting MixAudioSource")
          }
        }
        else -> {
          Log.d(TAG, "System volume check skipped for audio source: ${currentAudioSource.javaClass.simpleName}")
        }
      }
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
        Log.d(TAG, "  - ⚠️ System volume is 0, audio will be muted")
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
    try {
      Log.d(TAG, "Applying enhanced audio quality settings")
      
      // Apply audio quality improvements to reduce hissing
      val currentAudioSource = genericStream.audioSource
      when (currentAudioSource) {
        is MixAudioSource -> {
          // Apply anti-feedback measures to prevent hissing when mixing sources
          applyAntiFeedbackMeasures(currentAudioSource)
        }
        is MicrophoneSource -> {
          // Optimize microphone source
          try {
            val microphoneField = currentAudioSource.javaClass.getDeclaredField("microphone")
            microphoneField.isAccessible = true
            val microphone = microphoneField.get(currentAudioSource) as? MicrophoneManager
            microphone?.let { mic ->
              // Set optimal microphone settings to reduce hissing
              mic.setMicrophoneVolume((micVolume / 100f).coerceIn(0.1f, 1.0f))
              Log.d(TAG, "Applied enhanced audio quality to MicrophoneSource")
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error applying enhanced audio quality to MicrophoneSource: ${e.message}")
          }
        }
        is InternalAudioSource -> {
          // Optimize internal audio source
          try {
            val microphoneField = currentAudioSource.javaClass.getDeclaredField("microphone")
            microphoneField.isAccessible = true
            val microphone = microphoneField.get(currentAudioSource) as? MicrophoneManager
            microphone?.let { mic ->
              // Set optimal internal audio settings
              mic.setInternalVolume((deviceVolume / 100f).coerceIn(0.1f, 1.0f))
              Log.d(TAG, "Applied enhanced audio quality to InternalAudioSource")
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error applying enhanced audio quality to InternalAudioSource: ${e.message}")
          }
        }
        else -> {
          Log.d(TAG, "Unknown audio source type for enhanced quality: ${currentAudioSource.javaClass.simpleName}")
        }
      }
      
    } catch (e: Exception) {
      Log.e(TAG, "Error applying enhanced audio quality: ${e.message}")
    }
  }

  /**
   * Optimize audio settings for better quality and reduced hissing
   */
  fun optimizeAudioForQuality() {
    try {
      Log.d(TAG, "Optimizing audio settings for better quality")
      
      // Note: Volume levels are controlled by user via UI bars, not automatically changed
      
      // Apply the optimized settings
      applyStreamAudioLevels()
      applyEnhancedAudioQuality()
      applyAdvancedAudioFiltering()
      
    } catch (e: Exception) {
      Log.e(TAG, "Error optimizing audio settings: ${e.message}")
    }
  }

  /**
   * Apply smart hissing elimination measures that preserve microphone audio
   */
  private fun applyAntiFeedbackMeasures(mixAudioSource: MixAudioSource) {
    try {
      Log.d(TAG, "Applying smart hissing elimination measures")
      
      // Check if microphone is enabled (volume > 0)
      val isMicrophoneEnabled = micVolume > 0
      Log.d(TAG, "Microphone enabled: $isMicrophoneEnabled (volume: $micVolume%)")
      
      if (isMicrophoneEnabled) {
        // Apply smart hissing elimination that preserves microphone
        Log.d(TAG, "=== SMART HISSING ELIMINATION ===")
        
        // Smart approach: Reduce device audio when microphone is active
        val adjustedDeviceVolume = (deviceVolume * 0.6f).coerceIn(0.1f, 0.7f) // Reduce device audio by 40%
        val adjustedMicVolume = (micVolume / 100f).coerceIn(0.2f, 0.9f) // Keep microphone active but limit volume
        
        mixAudioSource.internalVolume = adjustedDeviceVolume
        mixAudioSource.microphoneVolume = adjustedMicVolume
        
        Log.d(TAG, "Smart anti-feedback applied - Device: ${(adjustedDeviceVolume * 100).toInt()}%, Mic: ${(adjustedMicVolume * 100).toInt()}%")
        Log.d(TAG, "Microphone audio preserved while reducing device audio to prevent hissing")
        
      } else {
        // When microphone is disabled, use full device audio
        mixAudioSource.internalVolume = (deviceVolume / 100f).coerceIn(0.1f, 1.0f)
        mixAudioSource.microphoneVolume = 0f // Disable microphone completely
        
        Log.d(TAG, "Microphone disabled - using full device audio: ${(deviceVolume / 100f * 100).toInt()}%")
      }
      
    } catch (e: Exception) {
      Log.e(TAG, "Error applying anti-feedback measures: ${e.message}")
    }
  }

  /**
   * Apply advanced audio filtering to reduce hissing and improve quality
   */
  private fun applyAdvancedAudioFiltering() {
    try {
      Log.d(TAG, "Applying advanced audio filtering to reduce hissing")
      

      
      val currentAudioSource = genericStream.audioSource
      when (currentAudioSource) {
        is MixAudioSource -> {
          // Apply more aggressive filtering for mix audio
          currentAudioSource.microphoneVolume = (micVolume / 100f).coerceIn(0.3f, 0.9f) // Tighter range
          currentAudioSource.internalVolume = (deviceVolume / 100f).coerceIn(0.3f, 0.9f) // Tighter range
          Log.d(TAG, "Applied advanced filtering to MixAudioSource")
        }
        is MicrophoneSource -> {
          // Apply filtering to microphone source
          try {
            val microphoneField = currentAudioSource.javaClass.getDeclaredField("microphone")
            microphoneField.isAccessible = true
            val microphone = microphoneField.get(currentAudioSource) as? MicrophoneManager
            microphone?.let { mic ->
              // Apply optimal microphone settings with tighter constraints
              mic.setMicrophoneVolume((micVolume / 100f).coerceIn(0.3f, 0.9f))
              Log.d(TAG, "Applied advanced filtering to MicrophoneSource")
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error applying advanced filtering to MicrophoneSource: ${e.message}")
          }
        }
        is InternalAudioSource -> {
          // Apply filtering to internal audio source
          try {
            val microphoneField = currentAudioSource.javaClass.getDeclaredField("microphone")
            microphoneField.isAccessible = true
            val microphone = microphoneField.get(currentAudioSource) as? MicrophoneManager
            microphone?.let { mic ->
              // Apply optimal internal audio settings with tighter constraints
              mic.setInternalVolume((deviceVolume / 100f).coerceIn(0.3f, 0.9f))
              Log.d(TAG, "Applied advanced filtering to InternalAudioSource")
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error applying advanced filtering to InternalAudioSource: ${e.message}")
          }
        }
        else -> {
          Log.d(TAG, "Unknown audio source type for advanced filtering: ${currentAudioSource.javaClass.simpleName}")
        }
      }
      
    } catch (e: Exception) {
      Log.e(TAG, "Error applying advanced audio filtering: ${e.message}")
    }
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
    Log.d("ScreenService", "Restoring audio source state - isMicrophoneMuted: $isMicrophoneMuted")
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      mediaProjection?.let { projection ->
        try {
          if (isMicrophoneMuted) {
            // Restore to device audio only
            genericStream.changeAudioSource(InternalAudioSource(projection))
            Log.d("ScreenService", "Audio source restored to device audio only")
          } else {
            // Restore to mix audio (device + microphone)
            genericStream.changeAudioSource(MixAudioSource(projection))
            Log.d("ScreenService", "Audio source restored to device audio + microphone")
          }
        } catch (e: Exception) {
          Log.e("ScreenService", "Failed to restore audio source state: ${e.message}")
        }
      }
    }
  }

  fun ensureAudioSourcePersistence() {
    Log.d("ScreenService", "Ensuring audio source persistence - isMicrophoneMuted: $isMicrophoneMuted")
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      mediaProjection?.let { projection ->
        try {
          val currentAudioSource = genericStream.audioSource
          Log.d("ScreenService", "Current audio source: ${currentAudioSource.javaClass.simpleName}")
          
          // Check if we need to restore the correct audio source
          val shouldBeMix = !isMicrophoneMuted
          val isCurrentlyMix = currentAudioSource is MixAudioSource
          val isCurrentlyInternal = currentAudioSource is InternalAudioSource
          
          if (shouldBeMix && !isCurrentlyMix) {
            Log.d("ScreenService", "Fixing audio source - switching to MixAudioSource")
            genericStream.changeAudioSource(MixAudioSource(projection))
          } else if (!shouldBeMix && !isCurrentlyInternal) {
            Log.d("ScreenService", "Fixing audio source - switching to InternalAudioSource")
            genericStream.changeAudioSource(InternalAudioSource(projection))
          } else {
            Log.d(TAG, "Audio source is already correct")
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to ensure audio source persistence: ${e.message}")
        }
      }
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
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
      stopSelf()
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
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
      stopSelf()
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
      micVolume = prefs.getInt(KEY_MIC_VOLUME, 100)
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
      val currentMicVolume = prefs.getInt(KEY_MIC_VOLUME, micVolume)
      val currentDeviceVolume = prefs.getInt(KEY_DEVICE_VOLUME, deviceVolume)
      
      Log.d(TAG, "Current saved values - Mic: $currentMicVolume%, Device: $currentDeviceVolume%")
      Log.d(TAG, "Service cached values - Mic: $micVolume%, Device: $deviceVolume%")
      
      // Update service variables if they differ
      if (currentMicVolume != micVolume) {
        Log.d(TAG, "🔄 Updating mic volume from $micVolume% to $currentMicVolume%")
        micVolume = currentMicVolume
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
