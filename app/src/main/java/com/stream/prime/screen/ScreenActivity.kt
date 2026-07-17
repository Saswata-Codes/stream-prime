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

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pedro.common.ConnectChecker
import com.pedro.library.base.recording.RecordController
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.stream.prime.R
import com.stream.prime.utils.fitAppPadding
import com.stream.prime.utils.toast
import com.stream.prime.utils.updateMenuColor
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.view.SurfaceView
import java.util.Locale
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Example code to stream the device screen.
 * Necessary API 21+
 *
 * More documentation see:
 * [com.pedro.library.base.DisplayBase]
 * Support RTMP, RTSP and SRT with commons features
 * [com.pedro.library.generic.GenericDisplay]
 * Support RTSP with all RTSP features
 * [com.pedro.library.rtsp.RtspDisplay]
 * Support RTMP with all RTMP features
 * [com.pedro.library.rtmp.RtmpDisplay]
 * Support SRT with all SRT features
 * [com.pedro.library.srt.SrtDisplay]
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenActivity : AppCompatActivity(), ConnectChecker {

  enum class Action {
    STREAM, RECORD
  }

  private lateinit var button: ExtendedFloatingActionButton
  private lateinit var bRecord: ExtendedFloatingActionButton
  private lateinit var bSettings: FloatingActionButton
  private lateinit var txtStatus: TextView
  private lateinit var txtBitrate: TextView
  private lateinit var statusIndicator: ImageView
  private lateinit var surfaceView: SurfaceView

  // Quality settings
  private var currentWidth = 1280
  private var currentHeight = 720
  private var currentBitrate = 2500 * 1000
  private var currentFps = 30
  private var action = Action.STREAM
  private var currentAudioSource: MenuItem? = null
  private val serviceStateHandler = Handler(Looper.getMainLooper())
  private var serviceStateSyncAttempt = 0
  private var recordingStateReceiverRegistered = false
  private val recordingStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != ScreenService.ACTION_RECORDING_STATE_CHANGED) return
      updateRecordButtonState(intent.getBooleanExtra(ScreenService.EXTRA_IS_RECORDING, false))
    }
  }
  private val serviceStateSync = object : Runnable {
    override fun run() {
      if (isFinishing || isDestroyed) return
      val service = ScreenService.INSTANCE
      if (service == null) {
        // The recording-state receiver is not registered while this activity is backgrounded.
        // If notification Stop destroyed the service, explicitly clear the stale Record icon.
        updateRecordButtonState(false)
        updateStatus("disconnected")
        if (serviceStateSyncAttempt++ < SERVICE_STATE_SYNC_ATTEMPTS) {
          serviceStateHandler.postDelayed(this, SERVICE_STATE_SYNC_DELAY_MS)
        }
        return
      }

      service.setCallback(this@ScreenActivity)
      updateStreamButtonState()
      updateRecordButtonState()
      updateStatus(if (service.isStreaming()) "connected" else "disconnected")
    }
  }

  // Streaming services data
  private val streamingServices = mapOf(
    "Custom RTMP" to "",
    "YouTube Live" to "rtmp://a.rtmp.youtube.com/live2/",
    "Twitch" to "rtmp://live.twitch.tv/app/",
    "Facebook Live" to "rtmp://live-api-s.facebook.com/rtmp/",
    "Instagram Live" to "rtmp://live-upload.instagram.com/rtmp/",
    "TikTok Live" to "rtmp://live-push.tiktok.com/rtmp/",
    "Discord" to "rtmp://live.discord.com/live/",
    "Mixer" to "rtmp://live.mixer.com/beam/",
    "DLive" to "rtmp://stream.dlive.tv/live/",
    "Caffeine" to "rtmp://ingest.caffeine.tv/live/"
  )

  // SharedPreferences keys
  companion object {
    private const val PREFS_NAME = "StreamSettings"
    private const val KEY_STREAMING_SERVICE = "streaming_service"
    private const val KEY_STREAM_URL = "stream_url"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_WIDTH = "landscape_width"
    private const val KEY_HEIGHT = "landscape_height"
    private const val KEY_FPS = "landscape_fps"
    private const val KEY_BITRATE = "landscape_bitrate"
    private const val PERMISSION_REQUEST_CODE = 1
    private const val FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION = 2
    private const val SERVICE_STATE_SYNC_ATTEMPTS = 20
    private const val SERVICE_STATE_SYNC_DELAY_MS = 100L
  }

  private fun checkAudioPermission(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        return false
      }
    } else {
      toast("No permissions available")
      button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    }
    
    // Check for FOREGROUND_SERVICE_MEDIA_PROJECTION permission required in Android 16+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
          this,
          arrayOf(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION),
          FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION
        )
        return false
      }
    }
    
    return true
  }

  private val activityResultContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val data = result.data
    if (data != null && result.resultCode == RESULT_OK) {
      val requestedAction = action
      ScreenService.startCapture(this, result.resultCode, data) { prepared ->
        if (prepared && ScreenService.INSTANCE != null) {
          ScreenService.INSTANCE?.setCallback(this)
          when (requestedAction) {
            Action.STREAM -> startStream()
            Action.RECORD -> toggleRecord()
          }
        } else {
          toast("Prepare stream failed")
        }
      }
    } else {
      toast("No permissions available")
      button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(R.layout.activity_display)
    fitAppPadding()
    initializeViews()
    setupStreamingServiceDropdown()
    setupClickListeners()
    
    scheduleServiceStateSync()
  }

  override fun onResume() {
    super.onResume()
    ScreenService.INSTANCE?.reloadQualitySettings()
    scheduleServiceStateSync()
  }

  override fun onStart() {
    super.onStart()
    if (!recordingStateReceiverRegistered) {
      ContextCompat.registerReceiver(
        this,
        recordingStateReceiver,
        IntentFilter(ScreenService.ACTION_RECORDING_STATE_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED
      )
      recordingStateReceiverRegistered = true
    }
  }

  override fun onStop() {
    if (recordingStateReceiverRegistered) {
      unregisterReceiver(recordingStateReceiver)
      recordingStateReceiverRegistered = false
    }
    super.onStop()
  }

  override fun onPause() {
    serviceStateHandler.removeCallbacks(serviceStateSync)
    super.onPause()
  }

  private fun scheduleServiceStateSync() {
    serviceStateHandler.removeCallbacks(serviceStateSync)
    serviceStateSyncAttempt = 0
    serviceStateSync.run()
  }

  private fun initializeViews() {
    button = findViewById(R.id.button)
    bRecord = findViewById(R.id.b_record)
    bSettings = findViewById(R.id.b_settings)
    txtStatus = findViewById(R.id.txt_status)
    txtBitrate = findViewById(R.id.txt_bitrate)
    statusIndicator = findViewById(R.id.status_indicator)
    surfaceView = findViewById(R.id.surface_view)
  }

  private fun setupClickListeners() {
    button.setOnClickListener {
      if (!checkAudioPermission()) {
        return@setOnClickListener
      }
      
      val service = ScreenService.INSTANCE
      if (service != null) {
        service.setCallback(this)
        if (!service.isStreaming() && !service.isRecording()) {
          action = Action.STREAM
          activityResultContract.launch(ScreenService.createCaptureIntent(this))
        } else if (!service.isStreaming()) {
          startStream()
        } else {
          stopStream()
        }
      } else {
        action = Action.STREAM
        activityResultContract.launch(ScreenService.createCaptureIntent(this))
      }
    }

    bRecord.setOnClickListener {
      if (!checkAudioPermission()) {
        return@setOnClickListener
      }
      
      val service = ScreenService.INSTANCE
      if (service != null) {
        service.setCallback(this)
        if (!service.isStreaming() && !service.isRecording()) {
          action = Action.RECORD
          activityResultContract.launch(ScreenService.createCaptureIntent(this))
        } else toggleRecord()
      } else {
        action = Action.RECORD
        activityResultContract.launch(ScreenService.createCaptureIntent(this))
      }
    }

    bSettings.setOnClickListener {
      // Launch centralized Stream Settings
      val intent = Intent(this, com.stream.prime.settings.StreamSettingsActivity::class.java)
      startActivity(intent)
    }
  }

  private fun setupStreamingServiceDropdown() {
    val serviceNames = streamingServices.keys.toList()
    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, serviceNames)
    // spinnerStreamingService.setAdapter(adapter) // This line is removed as per the edit hint
    
    // spinnerStreamingService.setOnItemClickListener { _, _, position, _ -> // This block is removed as per the edit hint
    //   val selectedService = serviceNames[position]
    //   val serviceUrl = streamingServices[selectedService] ?: ""
      
    //   if (selectedService == "Custom RTMP") {
    //     // Show URL field for custom RTMP
    //     tilStreamUrl.visibility = View.VISIBLE
    //     etUrl.setText("")
    //     etUrl.hint = "Enter your RTMP URL"
    //   } else {
    //     // Hide URL field and set predefined URL
    //     tilStreamUrl.visibility = View.GONE
    //     etUrl.setText(serviceUrl)
    //   }
      
    //   // Save settings when service changes
    //   saveSettings()
    // }
    
    // Set YouTube Live as default
    // spinnerStreamingService.setText("YouTube Live", false) // This line is removed as per the edit hint
    // tilStreamUrl.visibility = View.GONE // This line is removed as per the edit hint
    // etUrl.setText("rtmp://a.rtmp.youtube.com/live2/") // This line is removed as per the edit hint
    
    // Load saved settings
    // loadSavedSettings() // This line is removed as per the edit hint
    
    // Add text change listeners to save settings when user types
    // etUrl.addTextChangedListener(object : android.text.TextWatcher { // This block is removed as per the edit hint
    //   override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    //   override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    //   override fun afterTextChanged(s: android.text.Editable?) {
    //     saveSettings()
    //   }
    // })
    
    // etStreamKey.addTextChangedListener(object : android.text.TextWatcher { // This block is removed as per the edit hint
    //   override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    //   override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    //   override fun afterTextChanged(s: android.text.Editable?) {
    //     saveSettings()
    //   }
    // })
  }

  private fun saveSettings() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val editor = prefs.edit()
    
    // editor.putString(KEY_STREAMING_SERVICE, spinnerStreamingService.text.toString()) // This line is removed as per the edit hint
    // editor.putString(KEY_STREAM_URL, etUrl.text.toString()) // This line is removed as per the edit hint
    // editor.putString(KEY_STREAM_KEY, etStreamKey.text.toString()) // This line is removed as per the edit hint
    editor.putInt(KEY_WIDTH, currentWidth)
    editor.putInt(KEY_HEIGHT, currentHeight)
    editor.putInt(KEY_FPS, currentFps)
    editor.putInt(KEY_BITRATE, currentBitrate)
    
    editor.apply()
    
    // Debug logging
    Log.d("Settings", "Saved settings - Width: $currentWidth, Height: $currentHeight, FPS: $currentFps, Bitrate: $currentBitrate")
  }

  private fun loadSavedSettings() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    
    // Load streaming service
    // val savedService = prefs.getString(KEY_STREAMING_SERVICE, "YouTube Live") // This line is removed as per the edit hint
    // spinnerStreamingService.setText(savedService, false) // This line is removed as per the edit hint
    
    // Load stream URL and key
    // val savedUrl = prefs.getString(KEY_STREAM_URL, "rtmp://a.rtmp.youtube.com/live2/") // This line is removed as per the edit hint
    // val savedKey = prefs.getString(KEY_STREAM_KEY, "") // This line is removed as per the edit hint
    // etUrl.setText(savedUrl) // This line is removed as per the edit hint
    // etStreamKey.setText(savedKey) // This line is removed as per the edit hint
    
    // Load quality settings
    currentWidth = prefs.getInt(KEY_WIDTH, 1280)
    currentHeight = prefs.getInt(KEY_HEIGHT, 720)
    currentFps = prefs.getInt(KEY_FPS, 30)
    currentBitrate = prefs.getInt(KEY_BITRATE, 2500 * 1000)
    
    // Debug logging
    Log.d("Settings", "Loaded settings - Width: $currentWidth, Height: $currentHeight, FPS: $currentFps, Bitrate: $currentBitrate")
    
    // Update URL field visibility based on saved service
    // if (savedService == "Custom RTMP") { // This block is removed as per the edit hint
    //   tilStreamUrl.visibility = View.VISIBLE
    // } else {
    //   tilStreamUrl.visibility = View.GONE
    // }
  }

  private fun showQualitySettingsDialog() {
    val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_quality_settings, null)

    // Initialize dialog views
    // val resolutionSpinner = dialogView.findViewById<AutoCompleteTextView>(R.id.resolution_spinner) // This line is removed as per the edit hint
    // val fpsSlider = dialogView.findViewById<Slider>(R.id.fps_slider) // This line is removed as per the edit hint
    // val fpsValueText = dialogView.findViewById<TextView>(R.id.fps_value_text) // This line is removed as per the edit hint
    // val etBitrate = dialogView.findViewById<TextInputEditText>(R.id.et_bitrate) // This line is removed as per the edit hint
    // val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btn_cancel) // This line is removed as per the edit hint
    // val btnApply = dialogView.findViewById<MaterialButton>(R.id.btn_apply) // This line is removed as per the edit hint

    // Load saved settings for the dialog
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val dialogWidth = prefs.getInt(KEY_WIDTH, currentWidth)
    val dialogHeight = prefs.getInt(KEY_HEIGHT, currentHeight)
    val dialogFps = prefs.getInt(KEY_FPS, currentFps)
    val dialogBitrate = prefs.getInt(KEY_BITRATE, currentBitrate)
    
    // Update current values with saved settings
    currentWidth = dialogWidth
    currentHeight = dialogHeight
    currentFps = dialogFps
    currentBitrate = dialogBitrate

    // Set up resolution options
    val resolutionOptions = arrayOf(
        "360p (640x360) - Low Bandwidth",
        "480p (854x480) - Standard",
        "720p (1280x720) - HD",
        "1080p (1920x1080) - Full HD"
    )
    
    // val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resolutionOptions) // This line is removed as per the edit hint
    // resolutionSpinner.setAdapter(adapter) // This line is removed as per the edit hint

    // Set current values
    // fpsSlider.value = currentFps.toFloat() // This line is removed as per the edit hint
    // fpsValueText.text = "${currentFps} FPS" // This line is removed as per the edit hint
    // etBitrate.setText((currentBitrate / 1000).toString()) // This line is removed as per the edit hint

    // Set current resolution
    val currentResolutionText = when {
        currentWidth == 640 -> resolutionOptions[0]
        currentWidth == 854 -> resolutionOptions[1]
        currentWidth == 1280 -> resolutionOptions[2]
        currentWidth == 1920 -> resolutionOptions[3]
        else -> resolutionOptions[2] // Default to 720p
    }
    // resolutionSpinner.setText(currentResolutionText, false) // This line is removed as per the edit hint

    // FPS slider listener
    // fpsSlider.addOnChangeListener { _, value, fromUser -> // This block is removed as per the edit hint
    //     if (fromUser) {
    //         fpsValueText.text = "${value.toInt()} FPS"
    //     }
    // }

    // Create dialog
    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(false)
        .create()

    // Button listeners
    // btnCancel.setOnClickListener { // This block is removed as per the edit hint
    //     dialog.dismiss()
    // }

    // btnApply.setOnClickListener { // This block is removed as per the edit hint
    //     // Get selected resolution, FPS, bitrate
    //     val selectedResolutionText = resolutionSpinner.text.toString() // This line is removed as per the edit hint
    //     val selectedResolution = when { // This block is removed as per the edit hint
    //         selectedResolutionText.contains("360p") -> Pair(640, 360) // This line is removed as per the edit hint
    //         selectedResolutionText.contains("480p") -> Pair(854, 480) // This line is removed as per the edit hint
    //         selectedResolutionText.contains("720p") -> Pair(1280, 720) // This line is removed as per the edit hint
    //         selectedResolutionText.contains("1080p") -> Pair(1920, 1080) // This line is removed as per the edit hint
    //         else -> Pair(1280, 720) // Default to 720p // This line is removed as per the edit hint
    //     }
    //     val newFps = fpsSlider.value.toInt() // This line is removed as per the edit hint
    //     val newBitrate = try { etBitrate.text.toString().toInt() * 1000 } catch (e: NumberFormatException) { 2500 * 1000 } // This line is removed as per the edit hint

    //     // Debug logging
    //     Log.d("QualitySettings", "Selected resolution: ${selectedResolution.first}x${selectedResolution.second}") // This line is removed as per the edit hint
    //     Log.d("QualitySettings", "Selected FPS: $newFps") // This line is removed as per the edit hint
    //     Log.d("QualitySettings", "Selected bitrate: $newBitrate") // This line is removed as per the edit hint

    //           // Apply settings
    //   applyQualitySettings(selectedResolution.first, selectedResolution.second, newFps, newBitrate) // This line is removed as per the edit hint
    //   saveSettings() // Save settings when quality changes // This line is removed as per the edit hint
    //   dialog.dismiss()
    //   toast("Quality settings applied: ${selectedResolution.first}x${selectedResolution.second} @ ${newFps}fps") // This line is removed as per the edit hint
    // }
    dialog.show()
  }

  private fun applyQualitySettings(width: Int, height: Int, fps: Int, bitrate: Int) {
    currentWidth = width
    currentHeight = height
    currentFps = fps
    currentBitrate = bitrate

    val service = ScreenService.INSTANCE
    service?.let {
      val wasStreaming = it.isStreaming()
      val wasRecording = it.isRecording()
      
      // Update service settings
      it.updateQualitySettings(width, height, fps, bitrate)
      
      // If stream was active, restart it
      if (wasStreaming) {
        // Get settings from centralized Stream Settings
        val prefs = getSharedPreferences("StreamSettings", 0)
        val streamUrl = prefs.getString("stream_url", "rtmp://a.rtmp.youtube.com/live2/") ?: "rtmp://a.rtmp.youtube.com/live2/"
        val streamKey = prefs.getString("stream_key", "") ?: ""
        val fullUrl = if (streamKey.isNotEmpty()) "$streamUrl$streamKey" else streamUrl
        
        if (fullUrl.isNotEmpty()) {
          it.startStream(fullUrl)
          button.setIcon(ContextCompat.getDrawable(this, R.drawable.stop_icon))
        }
      }
    }
  }

  private fun startStream() {
    // Check network connectivity first
    if (!isNetworkAvailable()) {
      toast("No internet connection available. Please check your network.")
      return
    }
    
    // Get settings from centralized Stream Settings
    val prefs = getSharedPreferences("StreamSettings", 0)
    val streamUrl = prefs.getString("stream_url", "rtmp://a.rtmp.youtube.com/live2/") ?: "rtmp://a.rtmp.youtube.com/live2/"
    val streamKey = prefs.getString("stream_key", "") ?: ""
    
    // Validate stream key
    if (streamKey.isEmpty()) {
      toast("Please configure your stream key in Stream Settings")
      return
    }
    
    // Validate stream key format (YouTube keys are typically alphanumeric)
    if (!streamKey.matches(Regex("^[a-zA-Z0-9-_]+$"))) {
      toast("Invalid stream key format. Please check your stream key.")
      return
    }
    
    // Construct full URL
    val fullUrl = if (streamUrl.endsWith("/")) {
      "$streamUrl$streamKey"
    } else {
      "$streamUrl/$streamKey"
    }
    
    // Validate URL format
    if (!fullUrl.startsWith("rtmp://")) {
      toast("Invalid RTMP URL format")
      return
    }
    
    // Debug logging
    Log.d("Streaming", "Starting stream with configured RTMP endpoint")
    
    ScreenService.INSTANCE?.startStream(fullUrl)
    // Save streaming status
    getSharedPreferences("StreamStatus", 0).edit().putBoolean("landscape_streaming_status", true).apply()
    updateStreamButtonState()
  }

  private fun stopStream() {
    ScreenService.INSTANCE?.stopStream()
    // Save streaming status
    getSharedPreferences("StreamStatus", 0).edit().putBoolean("landscape_streaming_status", false).apply()
    updateStreamButtonState()
  }

  private fun startRecord() {
    val service = ScreenService.INSTANCE
    if (service != null) {
      service.toggleRecord { status ->
        when (status) {
          com.pedro.library.base.recording.RecordController.Status.RECORDING -> {
            bRecord.setIcon(ContextCompat.getDrawable(this, R.drawable.stop_icon))
            bRecord.text = "STOP"
            toast("Recording started")
          }
          com.pedro.library.base.recording.RecordController.Status.STOPPED -> {
            bRecord.setIcon(ContextCompat.getDrawable(this, R.drawable.record_icon))
            bRecord.text = "REC"
            toast("Recording stopped")
          }
          else -> {}
        }
      }
    }
  }

  private fun toggleRecord() {
    val service = ScreenService.INSTANCE
    if (service != null) {
      service.toggleRecord { status ->
        when (status) {
          com.pedro.library.base.recording.RecordController.Status.RECORDING -> {
            bRecord.setIcon(ContextCompat.getDrawable(this, R.drawable.stop_icon))
            bRecord.text = "STOP"
            toast("Recording started")
          }
          com.pedro.library.base.recording.RecordController.Status.STOPPED -> {
            bRecord.setIcon(ContextCompat.getDrawable(this, R.drawable.record_icon))
            bRecord.text = "REC"
            toast("Recording stopped")
          }
          else -> {}
        }
      }
    }
  }

  private fun updateStatus(status: String) {
    when (status) {
      "connecting" -> {
        statusIndicator.setImageResource(R.drawable.status_connecting)
        txtStatus.text = getString(R.string.status_connecting)
      }
      "connected" -> {
        statusIndicator.setImageResource(R.drawable.status_online)
        txtStatus.text = getString(R.string.status_connected)
      }
      "disconnected" -> {
        statusIndicator.setImageResource(R.drawable.status_offline)
        txtStatus.text = getString(R.string.status_disconnected)
      }
      "error" -> {
        statusIndicator.setImageResource(R.drawable.status_offline)
        txtStatus.text = getString(R.string.status_error)
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.screen_menu, menu)
    val supportsPlaybackCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    menu.findItem(R.id.audio_source_internal).isVisible = supportsPlaybackCapture
    menu.findItem(R.id.audio_source_mix).isVisible = supportsPlaybackCapture
    menu.findItem(R.id.audio_source_dual_channel).isVisible = supportsPlaybackCapture
    val defaultAudioSource = when (ScreenService.INSTANCE?.getCurrentAudioSource()) {
      is MicrophoneSource -> menu.findItem(R.id.audio_source_microphone)
      is InternalAudioSource -> menu.findItem(R.id.audio_source_internal)
      is MixAudioSource -> menu.findItem(R.id.audio_source_mix)
      else -> menu.findItem(R.id.audio_source_microphone)
    }
    currentAudioSource = defaultAudioSource.updateMenuColor(this, currentAudioSource)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    try {
      when (item.itemId) {
        R.id.audio_source_microphone,
        R.id.audio_source_internal,
        R.id.audio_source_mix,
        R.id.audio_source_dual_channel -> {
          val service = ScreenService.INSTANCE
          if (service != null) {
            service.toggleAudioSource(item.itemId)
            currentAudioSource = item.updateMenuColor(this, currentAudioSource)
          }
        }
      }
    } catch (e: IllegalArgumentException) {
      toast("Change source error: ${e.message}")
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    when (requestCode) {
      PERMISSION_REQUEST_CODE -> {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          toast("Microphone permission granted")
        } else {
          toast("Microphone permission denied")
        }
      }
      FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION -> {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          toast("Media projection permission granted")
        } else {
          toast("Media projection permission denied")
        }
      }
    }
  }

  override fun onDestroy() {
    serviceStateHandler.removeCallbacks(serviceStateSync)
    super.onDestroy()
    val screenService = ScreenService.INSTANCE
    if (screenService != null && !screenService.isStreaming() && !screenService.isRecording()) {
      screenService.setCallback(null)
      activityResultContract.unregister()
    }
  }

  override fun onConnectionStarted(url: String) {
    Log.d("ScreenActivity", "Connection started: $url")
    updateStatus("connecting")
    // Initialize bitrate display
    txtBitrate.text = "0.0 Mbps"
  }

  override fun onConnectionSuccess() {
    Log.d("ScreenActivity", "Connection successful")
    updateStatus("connected")
  }

  override fun onConnectionFailed(reason: String) {
    Log.e("ScreenActivity", "Connection failed: $reason")
    updateStatus("error")
    // Reset button state on connection failure
    button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    button.text = "LIVE"
  }

  override fun onDisconnect() {
    Log.d("ScreenActivity", "Disconnected")
    updateStatus("disconnected")
    txtBitrate.text = "0 Mbps"
    // Reset button state on disconnect
    button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    button.text = "LIVE"
  }

  override fun onAuthError() {
    Log.e("ScreenActivity", "Authentication error")
    updateStatus("error")
    // Reset button state on auth error
    button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    button.text = "LIVE"
  }

  override fun onAuthSuccess() {
    Log.d("ScreenActivity", "Authentication successful")
  }

  override fun onNewBitrate(bitrate: Long) {
    runOnUiThread {
      val bitrateMbps = bitrate / 1000_000f
      txtBitrate.text = String.format(Locale.getDefault(), "%.1f Mbps", bitrateMbps)
      Log.d("ScreenActivity", "Bitrate updated: $bitrateMbps Mbps")
    }
  }

  private fun isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork
    val capabilities = connectivityManager.getNetworkCapabilities(network)
    return capabilities != null && (
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    )
  }

  private fun updateStreamButtonState() {
    val screenService = ScreenService.INSTANCE
    if (screenService != null) {
      if (screenService.isStreaming()) {
        button.setIcon(ContextCompat.getDrawable(this, R.drawable.stop_icon))
        button.text = "STOP"
      } else {
        button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
        button.text = "LIVE"
      }
    }
  }

  private fun updateRecordButtonState(recordingOverride: Boolean? = null) {
    if (recordingOverride ?: (ScreenService.INSTANCE?.isRecording() == true)) {
      bRecord.setIcon(ContextCompat.getDrawable(this, R.drawable.stop_icon))
      bRecord.text = "STOP"
    } else {
      bRecord.setIcon(ContextCompat.getDrawable(this, R.drawable.record_icon))
      bRecord.text = "REC"
    }
  }
}
