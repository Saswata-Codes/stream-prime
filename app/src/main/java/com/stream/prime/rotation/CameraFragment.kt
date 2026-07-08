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

package com.stream.prime.rotation

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.pedro.common.ConnectChecker
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.BitrateAdapter
import com.stream.prime.R
import com.stream.prime.utils.PathUtils
import com.stream.prime.utils.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.ImageView
import androidx.core.content.ContextCompat
import android.util.Log

/**
 * Example code to stream using StreamBase. This is the recommend way to use the library.
 * Necessary API 21+
 * This mode allow you stream using custom Video/Audio sources, attach a preview or not dynamically, support device rotation, etc.
 *
 * Check Menu to use filters, video and audio sources, and orientation
 *
 * Orientation horizontal (by default) means that you want stream with vertical resolution
 * (with = 640, height = 480 and rotation = 0) The stream/record result will be 640x480 resolution
 *
 * Orientation vertical means that you want stream with vertical resolution
 * (with = 640, height = 480 and rotation = 90) The stream/record result will be 480x640 resolution
 *
 * More documentation see:
 * [com.pedro.library.base.StreamBase]
 * Support RTMP, RTSP and SRT with commons features
 * [com.pedro.library.generic.GenericStream]
 * Support RTSP with all RTSP features
 * [com.pedro.library.rtsp.RtspStream]
 * Support RTMP with all RTMP features
 * [com.pedro.library.rtmp.RtmpStream]
 * Support SRT with all SRT features
 * [com.pedro.library.srt.SrtStream]
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class CameraFragment: Fragment(), ConnectChecker {

  companion object {
    fun getInstance(): CameraFragment = CameraFragment()
  }

  val genericStream: GenericStream by lazy {
    GenericStream(requireContext(), this).apply {
      getGlInterface().autoHandleOrientation = false
      getStreamClient().setBitrateExponentialFactor(0.5f)
    }
  }
  private lateinit var surfaceView: SurfaceView
  private lateinit var button: ExtendedFloatingActionButton
  private lateinit var bRecord: ExtendedFloatingActionButton
  private lateinit var bSettings: FloatingActionButton
  private lateinit var txtBitrate: TextView
  private lateinit var txtStatus: TextView
  private lateinit var statusIndicator: ImageView
  
  // Basic settings
  private var rotation = 0
  private val sampleRate = 48000  // Enhanced to 48kHz for professional quality
  private val isStereo = true
  private val aBitrate = 256 * 1000  // Enhanced to 256k for maximum quality
  private var recordPath = ""
  
  //Bitrate adapter used to change the bitrate on fly depend of the bandwidth.
  private val bitrateAdapter = BitrateAdapter {
    genericStream.setVideoBitrateOnFly(it)
  }.apply {
    setMaxBitrate(2500 * 1000 + aBitrate) // Default max bitrate
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
  ): View? {
    val view = inflater.inflate(R.layout.fragment_camera, container, false)
    
    // Initialize views
    button = view.findViewById(R.id.b_start_stop)
    bRecord = view.findViewById(R.id.b_record)
    bSettings = view.findViewById(R.id.b_settings)
    txtBitrate = view.findViewById(R.id.txt_bitrate)
    txtStatus = view.findViewById(R.id.txt_status)
    statusIndicator = view.findViewById(R.id.status_indicator)
    
    surfaceView = view.findViewById(R.id.surfaceView)
    
    (activity as? RotationActivity)?.let {
      surfaceView.setOnTouchListener(it)
    }
    
    surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        if (!genericStream.isOnPreview) genericStream.startPreview(surfaceView)
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        genericStream.getGlInterface().setPreviewResolution(width, height)
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (genericStream.isOnPreview) genericStream.stopPreview()
      }
    })

    button.setOnClickListener {
      if (!genericStream.isStreaming) {
        // Get settings from centralized Stream Settings
        val prefs = requireContext().getSharedPreferences("StreamSettings", 0)
        val streamUrl = prefs.getString("stream_url", "rtmp://a.rtmp.youtube.com/live2/") ?: "rtmp://a.rtmp.youtube.com/live2/"
        val streamKey = prefs.getString("stream_key", "") ?: ""
        val fullUrl = if (streamKey.isNotEmpty()) "$streamUrl$streamKey" else streamUrl
        
        if (fullUrl.isNotEmpty()) {
          startStream()
          updateStatus("connecting")
        } else {
          stopStream()
          updateStatus("disconnected")
        }
      } else {
        stopStream()
        updateStatus("disconnected")
      }
    }
    
    bRecord.setOnClickListener {
      if (!genericStream.isRecording) {
        startRecord()
      } else {
        stopRecord()
      }
    }

    bSettings.setOnClickListener {
      // Launch centralized Stream Settings
      val intent = android.content.Intent(requireContext(), com.stream.prime.settings.StreamSettingsActivity::class.java)
      startActivity(intent)
    }
    
    return view
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    
    // Restore streaming status from SharedPreferences
    val prefs = requireContext().getSharedPreferences("StreamStatus", 0)
    val isStreaming = prefs.getBoolean("camera_streaming_status", false)
    
    // Check actual streaming state and update UI accordingly
    if (genericStream.isStreaming) {
      // Stream is actually running, update UI to reflect this
      updateStatus("connected")
      updateStreamButtonState()
      // Note: Bitrate will be updated by onNewBitrate callback when it comes
    } else if (isStreaming && !genericStream.isStreaming) {
      // If status says streaming but service isn't, fix status
      prefs.edit().putBoolean("camera_streaming_status", false).apply()
      updateStatus("disconnected")
      updateStreamButtonState()
    } else {
      // Not streaming, ensure UI reflects this
      updateStatus("disconnected")
      updateStreamButtonState()
    }
  }

  private fun startStream() {
    // Get settings from centralized Stream Settings
    val prefs = requireContext().getSharedPreferences("StreamSettings", 0)
    val streamUrl = prefs.getString("stream_url", "rtmp://a.rtmp.youtube.com/live2/") ?: "rtmp://a.rtmp.youtube.com/live2/"
    val streamKey = prefs.getString("stream_key", "") ?: ""
    val fullUrl = if (streamKey.isNotEmpty()) "$streamUrl$streamKey" else streamUrl
    
    if (fullUrl.isNotEmpty()) {
      genericStream.startStream(fullUrl)
      // Save streaming status
      requireContext().getSharedPreferences("StreamStatus", 0).edit().putBoolean("camera_streaming_status", true).apply()
      updateStreamButtonState()
    }
  }

  private fun stopStream() {
    genericStream.stopStream()
    // Save streaming status
    requireContext().getSharedPreferences("StreamStatus", 0).edit().putBoolean("camera_streaming_status", false).apply()
    updateStreamButtonState()
  }

  private fun updateStreamButtonState() {
    if (genericStream.isStreaming) {
      button.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.stop_icon))
      button.text = "STOP"
    } else {
      button.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.stream_icon))
      button.text = "LIVE"
    }
  }

  private fun updateStatus(status: String) {
    when (status) {
      "connecting" -> {
        statusIndicator.setBackgroundResource(R.drawable.status_connecting)
        txtStatus.text = getString(R.string.status_connecting)
      }
      "connected" -> {
        statusIndicator.setBackgroundResource(R.drawable.status_online)
        txtStatus.text = getString(R.string.status_connected)
      }
      "disconnected" -> {
        statusIndicator.setBackgroundResource(R.drawable.status_offline)
        txtStatus.text = getString(R.string.status_disconnected)
      }
      "error" -> {
        statusIndicator.setBackgroundResource(R.drawable.status_offline)
        txtStatus.text = getString(R.string.status_error)
      }
    }
  }

  fun setOrientationMode(isVertical: Boolean) {
    val wasOnPreview = genericStream.isOnPreview
    genericStream.release()
    rotation = if (isVertical) 90 else 0
    prepare()
    if (wasOnPreview) genericStream.startPreview(surfaceView)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prepare()
    genericStream.getStreamClient().setReTries(10)
  }

  private fun prepare() {
    // Get settings from centralized Stream Settings
    val prefs = requireContext().getSharedPreferences("StreamSettings", 0)
    val width = prefs.getInt("landscape_width", 1280)
    val height = prefs.getInt("landscape_height", 720)
    val fps = prefs.getInt("landscape_fps", 30)
    val bitrate = prefs.getInt("landscape_bitrate", 2500) * 1000
    
    val prepared = try {
      genericStream.prepareVideo(width, height, bitrate, rotation = rotation, fps = fps)
          && genericStream.prepareAudio(sampleRate, isStereo, aBitrate,
              echoCanceler = true,  // Enable echo cancellation
              noiseSuppressor = true  // Enable noise suppression
          )
    } catch (_: IllegalArgumentException) {
      false
    }
    if (!prepared) {
      toast("Audio or Video configuration failed")
      activity?.finish()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    genericStream.release()
  }

  override fun onConnectionStarted(url: String) {
    updateStatus("connecting")
    // Initialize bitrate display
    txtBitrate.text = "0.0 Mbps"
  }

  override fun onConnectionSuccess() {
    updateStatus("connected")
  }

  override fun onConnectionFailed(reason: String) {
    updateStatus("error")
    genericStream.stopStream()
    button.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.stream_icon))
    button.text = "LIVE"
  }

  override fun onNewBitrate(bitrate: Long) {
    requireActivity().runOnUiThread {
      val bitrateMbps = bitrate / 1000_000f
      txtBitrate.text = String.format(Locale.getDefault(), "%.1f Mbps", bitrateMbps)
      Log.d("CameraFragment", "Bitrate updated: $bitrateMbps Mbps")
    }
  }

  override fun onDisconnect() {
    updateStatus("disconnected")
    txtBitrate.text = "0.0 Mbps"
  }

  override fun onAuthError() {
    updateStatus("error")
    genericStream.stopStream()
    button.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.stream_icon))
    button.text = "LIVE"
  }

  override fun onAuthSuccess() {
  }

  private fun startRecord() {
    // Apply enhanced audio quality settings before recording
    applyEnhancedAudioQuality()
    
    val folder = PathUtils.getRecordPath()
    if (!folder.exists()) folder.mkdir()
    val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    recordPath = "${folder.absolutePath}/${sdf.format(Date())}.mp4"
    genericStream.startRecord(recordPath) { status ->
      if (status == RecordController.Status.RECORDING) {
        bRecord.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.stop_icon))
        bRecord.text = "STOP"
        toast("Recording started")
      }
    }
  }

  /**
   * Apply enhanced audio quality settings to reduce hissing
   */
  private fun applyEnhancedAudioQuality() {
    try {
      Log.d("CameraFragment", "Applying enhanced audio quality settings")
      // The enhanced audio settings are already applied through the updated sample rate and bitrate
      // Echo cancellation and noise suppression are enabled in the prepareAudio call
    } catch (e: Exception) {
      Log.e("CameraFragment", "Error applying enhanced audio quality: ${e.message}")
    }
  }

  private fun stopRecord() {
    genericStream.stopRecord()
    bRecord.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.record_icon))
    bRecord.text = "REC"
    toast("Recording stopped")
    PathUtils.updateGallery(requireContext(), recordPath)
  }
}