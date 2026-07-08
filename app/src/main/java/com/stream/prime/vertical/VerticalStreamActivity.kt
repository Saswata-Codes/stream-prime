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

package com.stream.prime.vertical

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.pedro.common.ConnectChecker
import com.stream.prime.R
import com.stream.prime.screen.ScreenService
import com.stream.prime.utils.toast
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

class VerticalStreamActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

  private lateinit var button: ExtendedFloatingActionButton
  private lateinit var bRecord: ExtendedFloatingActionButton
  private lateinit var bSettings: FloatingActionButton
  private lateinit var txtStatus: TextView
  private lateinit var txtBitrate: TextView
  private lateinit var statusIndicator: ImageView
  private lateinit var surfaceView: SurfaceView

  private var surfaceReady = false
  private var serviceStarted = false
  private var action = Action.STREAM
  private val activityResultContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
          val data = result.data
          if (data != null) {
              val service = ScreenService.INSTANCE
              if (service != null) {
                  // Set rotation to 90 degrees for vertical streaming BEFORE preparing stream
                  service.setRotation(90)
                  service.setCallback(this)
                  
                  // Prepare the stream with the permission result
                  if (service.prepareStream(result.resultCode, data)) {
                      serviceStarted = true
                      when (action) {
                          Action.STREAM -> startStream()
                          Action.RECORD -> startRecord()
                      }
                  } else {
                      toast("Failed to prepare screen capture")
                  }
              } else {
                  toast("Screen service not available")
              }
          } else {
              toast("Screen capture permission denied")
          }
      } else {
          toast("Screen capture permission denied")
      }
  }

  enum class Action {
      STREAM, RECORD
  }

  companion object {
    private const val PERMISSION_REQUEST_CODE = 1
    private const val SCREEN_CAPTURE_REQUEST_CODE = 2
    private const val FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION = 3
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_vertical_stream)
    initializeViews()
    setupClickListeners()
    
    // Start ScreenService if not already started and permissions are granted
    if (checkAudioPermission()) {
      startService(Intent(this, ScreenService::class.java))
    }
    
    // Only check audio permission on startup, not screen capture
    checkAudioPermission()
  }

  override fun onResume() {
      super.onResume()
      // Reload quality settings when returning from Stream Settings
      ScreenService.INSTANCE?.reloadQualitySettings()
      
      // Restore streaming status from SharedPreferences
      val prefs = getSharedPreferences("StreamStatus", 0)
      val isStreaming = prefs.getBoolean("vertical_streaming_status", false)
      
      // Check actual streaming state and update UI accordingly
      val service = ScreenService.INSTANCE
      if (service != null && service.isStreaming()) {
        // Stream is actually running, update UI to reflect this
        updateStatus("connected")
        updateStreamButtonState()
        // Request current bitrate from service
        service.setCallback(this)
        // Note: Bitrate will be updated by onNewBitrate callback when it comes
      } else if (isStreaming && service?.isStreaming() != true) {
        // If status says streaming but service isn't, fix status
        prefs.edit().putBoolean("vertical_streaming_status", false).apply()
        updateStatus("disconnected")
        updateStreamButtonState()
      } else {
        // Not streaming, ensure UI reflects this
        updateStatus("disconnected")
        updateStreamButtonState()
      }
  }

  private fun initializeViews() {
    button = findViewById(R.id.button)
    bRecord = findViewById(R.id.b_record)
    bSettings = findViewById(R.id.b_settings)
    txtStatus = findViewById(R.id.txt_status)
    txtBitrate = findViewById(R.id.txt_bitrate)
    statusIndicator = findViewById(R.id.status_indicator)
    surfaceView = findViewById(R.id.surface_view)
    
    // Set up SurfaceHolder callback
    surfaceView.holder.addCallback(this)
  }

  private fun setupClickListeners() {
    button.setOnClickListener {
      startServiceIfPermissionsGrantedForStream()
    }

    bRecord.setOnClickListener {
      startServiceIfPermissionsGrantedForRecord()
    }

    bSettings.setOnClickListener {
      // Launch centralized Stream Settings
      val intent = Intent(this, com.stream.prime.settings.StreamSettingsActivity::class.java)
      startActivity(intent)
    }
  }

  private fun startServiceIfPermissionsGrantedForStream() {
    if (checkAudioPermission()) {
      // Ensure ScreenService is initialized
      if (ScreenService.INSTANCE == null) {
        startService(Intent(this, ScreenService::class.java))
        // Small delay to ensure service is initialized
        Handler(Looper.getMainLooper()).postDelayed({
          setupStreamButton()
        }, 500)
      } else {
        setupStreamButton()
      }
    }
  }

  private fun startServiceIfPermissionsGrantedForRecord() {
    if (checkAudioPermission()) {
      // Ensure ScreenService is initialized
      if (ScreenService.INSTANCE == null) {
        startService(Intent(this, ScreenService::class.java))
        // Small delay to ensure service is initialized
        Handler(Looper.getMainLooper()).postDelayed({
          setupRecordButton()
        }, 500)
      } else {
        setupRecordButton()
      }
    }
  }

  private fun updateStreamButtonState() {
    val isStreaming = ScreenService.INSTANCE?.isStreaming() == true
    if (isStreaming) {
      button.setIcon(ContextCompat.getDrawable(this, R.drawable.stop_icon))
      button.text = "STOP"
      button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_online)
    } else {
      button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
      button.text = "LIVE"
      button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_offline)
    }
  }

  private fun setupStreamButton() {
    val service = ScreenService.INSTANCE
    if (service != null) {
      service.setCallback(this)
      if (!service.isStreaming() && !service.isRecording()) {
        action = Action.STREAM
        activityResultContract.launch(service.sendIntent())
      } else if (!service.isStreaming()) {
        startStream()
      } else {
        stopStream()
      }
    }
    updateStreamButtonState()
  }

  private fun setupRecordButton() {
    val service = ScreenService.INSTANCE
    if (service != null) {
      service.setCallback(this)
      if (!service.isStreaming() && !service.isRecording()) {
        action = Action.RECORD
        activityResultContract.launch(service.sendIntent())
      } else toggleRecord()
    }
  }

  private fun checkAudioPermission(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        return false
      }
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
    getSharedPreferences("StreamStatus", 0).edit().putBoolean("vertical_streaming_status", true).apply()
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

  private fun stopStream() {
    ScreenService.INSTANCE?.stopStream()
    // Save streaming status
    getSharedPreferences("StreamStatus", 0).edit().putBoolean("vertical_streaming_status", false).apply()
    updateStreamButtonState()
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

  override fun onConnectionStarted(url: String) {
    updateStatus("connecting")
    // Initialize bitrate display
    txtBitrate.text = "0.0 Mbps"
  }

  override fun onConnectionSuccess() {
    updateStatus("connected")
  }

  override fun onNewBitrate(bitrate: Long) {
    runOnUiThread {
      val bitrateMbps = bitrate / 1000000.0
      txtBitrate.text = String.format("%.1f Mbps", bitrateMbps)
      Log.d("VerticalStreamActivity", "Bitrate updated: $bitrateMbps Mbps")
    }
  }

  override fun onConnectionFailed(reason: String) {
    Log.e("VerticalStreamActivity", "Connection failed: $reason")
    updateStatus("error")
    // Reset button state on connection failure
    button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    button.text = "LIVE"
    button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_offline)
  }

  override fun onDisconnect() {
    Log.d("VerticalStreamActivity", "Disconnected")
    updateStatus("disconnected")
    txtBitrate.text = "0 Mbps"
    // Reset button state on disconnect
    button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    button.text = "LIVE"
    button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_offline)
  }

  override fun onAuthError() {
    Log.e("VerticalStreamActivity", "Authentication error")
    updateStatus("error")
    // Reset button state on auth error
    button.setIcon(ContextCompat.getDrawable(this, R.drawable.stream_icon))
    button.text = "LIVE"
    button.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_offline)
  }

  override fun onAuthSuccess() {
    updateStatus("connected")
  }

  override fun onDestroy() {
    super.onDestroy()
    ScreenService.INSTANCE?.setCallback(null)
  }

  // SurfaceHolder.Callback methods
  override fun surfaceCreated(holder: SurfaceHolder) {
    surfaceReady = true
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    // Surface changed, but we don't need to do anything special
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    surfaceReady = false
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

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    
    when (requestCode) {
      FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION -> {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          toast("Media projection permission granted")
          // Permission granted, now start the service
          startServiceIfPermissionsGrantedForStream()
        } else {
          toast("Media projection permission denied")
        }
      }
    }
  }
} 
