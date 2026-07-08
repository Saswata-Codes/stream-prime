package com.stream.prime.file

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.stream.prime.R
import com.stream.prime.databinding.ActivityFileStreamingBinding
import com.stream.prime.settings.SettingsManager
import com.stream.prime.settings.StreamSettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileStreamingActivity : AppCompatActivity(), FileStreamService.StreamCallback {

    companion object {
        private const val TAG = "FileStreamingActivity"
        const val EXTRA_VIDEO_URI = "video_uri"
    }

    private lateinit var binding: ActivityFileStreamingBinding
    private var videoUri: Uri? = null
    private var isStreaming = false
    private var isRecording = false
    private var isMicMuted = false
    private var isLoopEnabled = false
    private var streamStartTime: Long = 0
    private var streamTimer: android.os.Handler? = null
    private var streamRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get video URI from intent
        videoUri = intent.getStringExtra(EXTRA_VIDEO_URI)?.let { Uri.parse(it) }
        
        if (videoUri == null) {
            Toast.makeText(this, "No video file selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()
        setupClickListeners()
        checkPermissions()
    }

    private fun setupUI() {
        // Set initial button states
        updateStreamButtonState()
        updateRecordButtonState()
        updateMicButtonState()
        updateLoopButtonState()
        updateStatusText("Ready to Stream")
        
        // Debug button visibility and properties
        Log.d(TAG, "=== BUTTON VISIBILITY DEBUG ===")
        Log.d(TAG, "btn_stream - visibility: ${binding.btnStream.visibility}, width: ${binding.btnStream.width}, height: ${binding.btnStream.height}")
        Log.d(TAG, "btn_mic - visibility: ${binding.btnMic.visibility}, width: ${binding.btnMic.width}, height: ${binding.btnMic.height}")
        Log.d(TAG, "btn_record - visibility: ${binding.btnRecord.visibility}, width: ${binding.btnRecord.width}, height: ${binding.btnRecord.height}")
        Log.d(TAG, "btn_loop - visibility: ${binding.btnLoop.visibility}, width: ${binding.btnLoop.width}, height: ${binding.btnLoop.height}")
        
        // Force button visibility
        binding.btnStream.visibility = android.view.View.VISIBLE
        binding.btnMic.visibility = android.view.View.VISIBLE
        binding.btnRecord.visibility = android.view.View.VISIBLE
        binding.btnLoop.visibility = android.view.View.VISIBLE
        
        Log.d(TAG, "Buttons forced to VISIBLE")
        
        // Set video file info with actual file name
        videoUri?.let { uri ->
            try {
                val fileName = getFileName(uri)
                binding.tvVideoInfo.text = "Video: $fileName"
                Log.d(TAG, "Video file loaded: $fileName")
            } catch (e: Exception) {
                binding.tvVideoInfo.text = "Video file loaded"
                Log.e(TAG, "Error getting file name: ${e.message}")
            }
        } ?: run {
            binding.tvVideoInfo.text = "No video file"
        }
        
        // Initialize stream timer
        streamTimer = android.os.Handler(android.os.Looper.getMainLooper())
        streamRunnable = object : Runnable {
            override fun run() {
                updateStreamTimer()
                streamTimer?.postDelayed(this, 1000)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst()
                it.getString(nameIndex) ?: "Unknown file"
            } ?: "Unknown file"
        } catch (e: Exception) {
            "Unknown file"
        }
    }

    private fun setupClickListeners() {
        Log.d(TAG, "Setting up click listeners")
        
        // Back button
        binding.btnBack.setOnClickListener {
            Log.d(TAG, "Back button clicked")
            stopStreamIfRunning()
            finish()
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            Log.d(TAG, "Settings button clicked")
            val intent = Intent(this, StreamSettingsActivity::class.java)
            startActivity(intent)
        }

        // Stream start/stop button
        binding.btnStream.setOnClickListener {
            Log.d(TAG, "Stream button clicked, isStreaming: $isStreaming")
            if (isStreaming) {
                stopStream()
            } else {
                startStream()
            }
        }

        // Record button
        binding.btnRecord.setOnClickListener {
            Log.d(TAG, "Record button clicked, isRecording: $isRecording")
            if (isRecording) {
                stopRecord()
            } else {
                startRecord()
            }
        }

        // Mic mute/unmute button
        binding.btnMic.setOnClickListener {
            Log.d(TAG, "Mic button clicked, isMicMuted: $isMicMuted")
            toggleMic()
        }

        // Loop toggle button
        binding.btnLoop.setOnClickListener {
            Log.d(TAG, "Loop button clicked, isLoopEnabled: $isLoopEnabled")
            toggleLoop()
        }
        
        Log.d(TAG, "All click listeners set up successfully")
    }

    private fun startStream() {
        Log.d(TAG, "=== STARTING FILE STREAM ===")
        Log.d(TAG, "Video URI: $videoUri")
        Log.d(TAG, "Loop enabled: $isLoopEnabled")
        
        updateStatusText("Checking network...")
        
        if (!isNetworkAvailable()) {
            updateStatusText("No internet connection")
            Toast.makeText(this, "No internet connection available", Toast.LENGTH_LONG).show()
            return
        }

        updateStatusText("Loading settings...")

        // Get current streaming service and its specific settings
        val selectedService = SettingsManager.getStreamingService(this)
        val streamKey = SettingsManager.getStreamKeyForService(this, selectedService)
        
        Log.d(TAG, "Selected service: $selectedService")
        Log.d(TAG, "Stream key length: ${streamKey.length}")
        Log.d(TAG, "Stream key (first 10 chars): ${streamKey.take(10)}...")
        
        if (streamKey.isEmpty()) {
            updateStatusText("Stream key not configured")
            Toast.makeText(this, "Please configure your stream key in Stream Settings", Toast.LENGTH_LONG).show()
            return
        }
        
        updateStatusText("Preparing stream...")
        
        // Construct the full URL based on the selected service
        val fullUrl = when (selectedService) {
            "Custom RTMP" -> {
                val streamUrl = SettingsManager.getCustomStreamUrl(this)
                Log.d(TAG, "Custom RTMP URL: $streamUrl")
                if (streamUrl.isEmpty()) {
                    updateStatusText("Custom RTMP URL not configured")
                    Toast.makeText(this, "Please configure your Custom RTMP URL in Stream Settings", Toast.LENGTH_LONG).show()
                    return
                }
                if (streamUrl.endsWith("/")) {
                    "$streamUrl$streamKey"
                } else {
                    "$streamUrl/$streamKey"
                }
            }
            "YouTube Live" -> "rtmp://a.rtmp.youtube.com/live2/$streamKey"
            "Facebook Live" -> "rtmp://live-api-s.facebook.com/rtmp/$streamKey"
            "Twitch" -> "rtmp://live.twitch.tv/app/$streamKey"
            "Instagram Live" -> "rtmp://live-upload.instagram.com/rtmp/$streamKey"
            "TikTok Live" -> "rtmp://live-push.tiktok.com/rtmp/$streamKey"
            else -> {
                updateStatusText("Unsupported service")
                Toast.makeText(this, "Unsupported streaming service: $selectedService", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        Log.d(TAG, "Full RTMP URL: $fullUrl")
        Log.d(TAG, "URL validation: ${fullUrl.startsWith("rtmp://")}")
        
        // Validate URL format
        if (!fullUrl.startsWith("rtmp://")) {
            Log.e(TAG, "Invalid RTMP URL format: $fullUrl")
            updateStatusText("Invalid RTMP URL format")
            Toast.makeText(this, "Invalid RTMP URL format", Toast.LENGTH_LONG).show()
            return
        }
        
        Log.d(TAG, "Starting file stream with configured RTMP endpoint and video: $videoUri")
        updateStatusText("Starting service...")
        
        // Start FileStreamService for file streaming
        val fileServiceIntent = Intent(this, FileStreamService::class.java)
        startService(fileServiceIntent)
        
        // Wait a moment for service to initialize
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            updateStatusText("Preparing video...")
            
            // Set the video URI and prepare the stream
            FileStreamService.INSTANCE?.let { service ->
                Log.d(TAG, "Service instance found, setting video URI")
                Log.d(TAG, "Service status: ${service.getServiceStatus()}")
                
                // Set callback for auto-stop communication
                service.setStreamCallback(this)
                
                service.setVideoUri(videoUri!!)
                service.setLoopEnabled(isLoopEnabled)
                
                updateStatusText("Initializing stream...")
                
                if (service.prepareStream()) {
                    Log.d(TAG, "Stream prepared successfully, starting stream")
                    updateStatusText("Connecting to server...")
                    service.startStream(fullUrl)
                    isStreaming = true
                    updateStreamButtonState()
                    
                    // Start stream timer
                    streamStartTime = System.currentTimeMillis()
                    startStreamTimer()
                    
                    updateStatusText("Streaming...")
                    Toast.makeText(this, "File streaming started", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(TAG, "Failed to prepare stream")
                    Log.e(TAG, "Service status after failed prepare: ${service.getServiceStatus()}")
                    updateStatusText("Failed to prepare stream")
                    Toast.makeText(this, "Failed to prepare file stream", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                Log.e(TAG, "Service instance not found")
                updateStatusText("Service not available")
                Toast.makeText(this, "File streaming service not available", Toast.LENGTH_SHORT).show()
            }
        }, 1000) // Wait 1 second for service to initialize
    }

    private fun stopStream() {
        updateStatusText("Stopping stream...")
        
        // Clear callback before stopping
        FileStreamService.INSTANCE?.setStreamCallback(null)
        FileStreamService.INSTANCE?.stopStream()
        val fileServiceIntent = Intent(this, FileStreamService::class.java)
        stopService(fileServiceIntent)
        
        isStreaming = false
        updateStreamButtonState()
        stopStreamTimer()
        updateStatusText("Stream stopped")
        
        Toast.makeText(this, "File streaming stopped", Toast.LENGTH_SHORT).show()
    }

    private fun handleStreamAutoStop() {
        Log.d(TAG, "Handling auto-stop from service")
        
        // Update UI on main thread
        runOnUiThread {
            isStreaming = false
            updateStreamButtonState()
            stopStreamTimer()
            updateStatusText("Stream completed - video finished")
            
            Toast.makeText(this, "File streaming completed - video finished", Toast.LENGTH_LONG).show()
        }
    }

    // FileStreamService.StreamCallback implementation
    override fun onStreamAutoStop() {
        Log.d(TAG, "Received auto-stop callback from service")
        handleStreamAutoStop()
    }

    private fun startRecord() {
        if (!isStreaming) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        
        // For now, just show a toast - recording functionality can be added later
        isRecording = true
        updateRecordButtonState()
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecord() {
        isRecording = false
        updateRecordButtonState()
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    private fun toggleMic() {
        isMicMuted = !isMicMuted
        updateMicButtonState()
        
        val message = if (isMicMuted) "Microphone muted" else "Microphone unmuted"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toggleLoop() {
        isLoopEnabled = !isLoopEnabled
        updateLoopButtonState()
        
        // Update the service immediately if it's running
        FileStreamService.INSTANCE?.setLoopEnabled(isLoopEnabled)
        
        val message = if (isLoopEnabled) "Loop enabled - video will restart when finished" else "Loop disabled"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        // Update status text to show loop state
        updateLoopStatus()
        
        Log.d(TAG, "Loop toggled: $isLoopEnabled")
    }

    private fun updateLoopButtonState() {
        binding.btnLoop.setTextColor(
            if (isLoopEnabled) 
                ContextCompat.getColor(this, R.color.prism_neon_green)
            else 
                ContextCompat.getColor(this, R.color.prism_text_secondary)
        )
    }

    private fun updateStatusText(status: String) {
        binding.tvStatus.text = status
        Log.d(TAG, "Status updated: $status")
    }

    private fun updateLoopStatus() {
        val loopStatus = if (isLoopEnabled) " (Loop Enabled)" else ""
        val currentStatus = binding.tvStatus.text.toString()
        if (!currentStatus.contains("Loop")) {
            updateStatusText("$currentStatus$loopStatus")
        }
    }

    private fun updateStreamButtonState() {
        binding.btnStream.text = if (isStreaming) "⏹" else "▶"
    }

    private fun updateRecordButtonState() {
        binding.btnRecord.text = if (isRecording) "⏹" else "●"
    }

    private fun updateMicButtonState() {
        binding.btnMic.text = if (isMicMuted) "🔇" else "🎤"
    }

    private fun startStreamTimer() {
        streamRunnable?.let { runnable ->
            streamTimer?.post(runnable)
        }
    }

    private fun stopStreamTimer() {
        streamRunnable?.let { runnable ->
            streamTimer?.removeCallbacks(runnable)
        }
        binding.tvStreamTimer.text = "00:00"
    }

    private fun updateStreamTimer() {
        if (isStreaming && streamStartTime > 0) {
            val elapsedTime = System.currentTimeMillis() - streamStartTime
            val seconds = (elapsedTime / 1000).toInt()
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            val timeString = String.format("%02d:%02d", minutes, remainingSeconds)
            binding.tvStreamTimer.text = timeString
        }
    }

    private fun stopStreamIfRunning() {
        if (isStreaming) {
            stopStream()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        // Simple network check - in a real app, you'd implement proper network detection
        return true
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission required for streaming", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreamIfRunning()
        stopStreamTimer()
    }
} 
