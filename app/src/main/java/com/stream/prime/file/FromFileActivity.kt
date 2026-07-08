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
package com.stream.prime.file

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericFromFile
import com.pedro.library.view.OpenGlView
import com.stream.prime.R
import com.stream.prime.utils.PathUtils
import com.stream.prime.utils.ScreenOrientation
import com.stream.prime.utils.fitAppPadding
import com.stream.prime.utils.setColorFilter
import com.stream.prime.utils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Example code to stream using a file.
 * Necessary API 18+
 *
 * More documentation see:
 * [com.pedro.library.base.FromFileBase]
 * Support RTMP, RTSP and SRT with commons features
 * [com.pedro.library.generic.GenericFromFile]
 * Support RTSP with all RTSP features
 * [com.pedro.library.rtsp.RtspFromFile]
 * Support RTMP with all RTMP features
 * [com.pedro.library.rtmp.RtmpFromFile]
 * Support SRT with all SRT features
 * [com.pedro.library.srt.SrtFromFile]
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class FromFileActivity : AppCompatActivity(), ConnectChecker,
  VideoDecoderInterface, AudioDecoderInterface, OnSeekBarChangeListener {

  private lateinit var genericFromFile: GenericFromFile
  private lateinit var bStream: FloatingActionButton
  private lateinit var bSelectFile: FloatingActionButton
  private lateinit var bReSync: FloatingActionButton
  private lateinit var bRecord: FloatingActionButton
  private lateinit var seekBar: SeekBar
  private lateinit var etUrl: TextInputEditText
  private lateinit var etStreamKey: TextInputEditText
  private lateinit var tvFileName: TextView
  private lateinit var txtStatus: TextView
  private lateinit var txtBitrate: TextView
  private lateinit var statusIndicator: View
  private lateinit var openGlView: OpenGlView
  private lateinit var spinnerStreamingService: AutoCompleteTextView
  private lateinit var tilStreamUrl: TextInputLayout

  private var filePath: Uri? = null
  private var recordPath = ""

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
    private const val KEY_WIDTH = "file_width"
    private const val KEY_HEIGHT = "file_height"
    private const val KEY_FPS = "file_fps"
    private const val KEY_BITRATE = "file_bitrate"
  }
  private var touching = false

  private val activityResult = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    filePath = uri
    tvFileName.text = (uri?.path ?: "").split("/").last()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(R.layout.activity_from_file)
    fitAppPadding()
    initializeViews()
    setupStreamingServiceDropdown()
    setupClickListeners()
    genericFromFile = GenericFromFile(openGlView, this, this, this)
    genericFromFile.setLoopMode(true)
  }

  private fun initializeViews() {
    bStream = findViewById(R.id.b_start_stop)
    bSelectFile = findViewById(R.id.select_file)
    bReSync = findViewById(R.id.b_re_sync)
    bRecord = findViewById(R.id.b_record)
    seekBar = findViewById(R.id.seek_bar)
    etUrl = findViewById(R.id.et_stream_url)
    etStreamKey = findViewById(R.id.et_stream_key)
    tvFileName = findViewById(R.id.tv_file_name)
    txtStatus = findViewById(R.id.txt_status)
    txtBitrate = findViewById(R.id.txt_bitrate)
    statusIndicator = findViewById(R.id.status_indicator)
    openGlView = findViewById(R.id.surfaceView)
    spinnerStreamingService = findViewById(R.id.spinner_streaming_service)
    tilStreamUrl = findViewById(R.id.til_stream_url)
  }

  private fun setupClickListeners() {
    bStream.setOnClickListener {
      if (!genericFromFile.isStreaming) {
        startStream()
      } else {
        stopStream()
      }
    }

    bSelectFile.setOnClickListener {
      activityResult.launch("video/*")
    }

    bReSync.setOnClickListener {
      if (filePath != null) {
        genericFromFile.reSyncFile()
        toast("File re-synced")
      } else {
        toast("Select a file first")
      }
    }

    bRecord.setOnClickListener {
      if (!genericFromFile.isRecording) {
        startRecord()
      } else {
        stopRecord()
      }
    }

    seekBar.setOnSeekBarChangeListener(this)
  }

  private fun startStream() {
    val streamUrl = etUrl.text.toString()
    val streamKey = etStreamKey.text.toString()
    val fullUrl = if (streamKey.isNotEmpty()) "$streamUrl$streamKey" else streamUrl
    
    if (fullUrl.isEmpty()) {
      toast("Please enter stream URL")
      return
    }
    
    if (filePath == null) {
      toast("Please select a file first")
      return
    }

    if (prepare()) {
      bStream.setImageResource(R.drawable.stream_stop_icon)
      genericFromFile.startStream(fullUrl)
    } else {
      toast("Error preparing stream")
    }
  }

  private fun stopStream() {
    bStream.setImageResource(R.drawable.stream_icon)
    genericFromFile.stopStream()
  }

  private fun startRecord() {
    bRecord.setImageResource(R.drawable.stop_icon)
    val folder = getExternalFilesDir(null)
    if (folder != null) {
      val fileName = "file_stream_${System.currentTimeMillis()}.mp4"
      val filePath = "${folder.absolutePath}/$fileName"
      genericFromFile.startRecord(filePath) { status ->
        when (status) {
          RecordController.Status.RECORDING -> toast("Recording started")
          RecordController.Status.STOPPED -> toast("Recording stopped")
          else -> {}
        }
      }
    } else {
      toast("Cannot access storage for recording")
    }
  }

  private fun stopRecord() {
    bRecord.setImageResource(R.drawable.record_icon)
    genericFromFile.stopRecord()
  }

  @Throws(IOException::class)
  private fun prepare(): Boolean {
    if (filePath == null) return false
    var result = genericFromFile.prepareVideo(applicationContext, filePath)
    result = result or genericFromFile.prepareAudio(applicationContext, filePath)
    return result
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
    }
  }

  override fun onConnectionStarted(url: String) {
    updateStatus("connecting")
  }

  override fun onConnectionSuccess() {
    updateStatus("connected")
  }

  override fun onNewBitrate(bitrate: Long) {
    val bitrateMbps = bitrate / 1000000.0
    txtBitrate.text = String.format("%.1f Mbps", bitrateMbps)
  }

  override fun onConnectionFailed(reason: String) {
    updateStatus("disconnected")
  }

  override fun onDisconnect() {
    updateStatus("disconnected")
  }

  override fun onAuthError() {
    updateStatus("disconnected")
    toast("Auth error")
  }

  override fun onAuthSuccess() {
    updateStatus("connected")
    toast("Auth success")
  }

  override fun onVideoDecoderFinished() {
    runOnUiThread {
      if (genericFromFile.isStreaming) {
        toast("Video decoder finished")
        stopStream()
      }
    }
  }

  override fun onAudioDecoderFinished() {
    runOnUiThread {
      if (genericFromFile.isStreaming) {
        toast("Audio decoder finished")
        stopStream()
      }
    }
  }

  override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
    if (fromUser && !touching) {
      genericFromFile.moveTo(progress.toDouble())
    }
  }

  override fun onStartTrackingTouch(seekBar: SeekBar?) {
    touching = true
  }

  override fun onStopTrackingTouch(seekBar: SeekBar?) {
    touching = false
  }

  override fun onResume() {
    super.onResume()
    genericFromFile.replaceView(openGlView)
  }

  override fun onPause() {
    super.onPause()
    genericFromFile.replaceView(applicationContext)
  }

  override fun onDestroy() {
    super.onDestroy()
    genericFromFile.stopStream()
  }

  private fun setupStreamingServiceDropdown() {
    val serviceNames = streamingServices.keys.toList()
    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, serviceNames)
    spinnerStreamingService.setAdapter(adapter)
    
    spinnerStreamingService.setOnItemClickListener { _, _, position, _ ->
      val selectedService = serviceNames[position]
      val serviceUrl = streamingServices[selectedService] ?: ""
      
      if (selectedService == "Custom RTMP") {
        // Show URL field for custom RTMP
        tilStreamUrl.visibility = View.VISIBLE
        etUrl.setText("")
        etUrl.hint = "Enter your RTMP URL"
      } else {
        // Hide URL field and set predefined URL
        tilStreamUrl.visibility = View.GONE
        etUrl.setText(serviceUrl)
      }
      
      // Save settings when service changes
      saveSettings()
    }
    
    // Set YouTube Live as default
    spinnerStreamingService.setText("YouTube Live", false)
    tilStreamUrl.visibility = View.GONE
    etUrl.setText("rtmp://a.rtmp.youtube.com/live2/")
    
    // Load saved settings
    loadSavedSettings()
    
    // Add text change listeners to save settings when user types
    etUrl.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: android.text.Editable?) {
        saveSettings()
      }
    })
    
    etStreamKey.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: android.text.Editable?) {
        saveSettings()
      }
    })
  }

  private fun saveSettings() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val editor = prefs.edit()
    
    editor.putString(KEY_STREAMING_SERVICE, spinnerStreamingService.text.toString())
    editor.putString(KEY_STREAM_URL, etUrl.text.toString())
    editor.putString(KEY_STREAM_KEY, etStreamKey.text.toString())
    editor.putInt(KEY_WIDTH, 1280) // File stream uses standard resolution
    editor.putInt(KEY_HEIGHT, 720)
    editor.putInt(KEY_FPS, 30)
    editor.putInt(KEY_BITRATE, 2500 * 1000)
    
    editor.apply()
  }

  private fun loadSavedSettings() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    
    // Load streaming service
    val savedService = prefs.getString(KEY_STREAMING_SERVICE, "YouTube Live")
    spinnerStreamingService.setText(savedService, false)
    
    // Load stream URL and key
    val savedUrl = prefs.getString(KEY_STREAM_URL, "rtmp://a.rtmp.youtube.com/live2/")
    val savedKey = prefs.getString(KEY_STREAM_KEY, "")
    etUrl.setText(savedUrl)
    etStreamKey.setText(savedKey)
    
    // Update URL field visibility based on saved service
    if (savedService == "Custom RTMP") {
      tilStreamUrl.visibility = View.VISIBLE
    } else {
      tilStreamUrl.visibility = View.GONE
    }
  }
}
