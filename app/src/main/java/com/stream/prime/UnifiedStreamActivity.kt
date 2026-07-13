package com.stream.prime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.base.recording.RecordController
import com.stream.prime.databinding.ActivityUnifiedStreamBinding
import com.stream.prime.screen.ScreenService
import com.stream.prime.settings.SettingsManager
import com.stream.prime.settings.StreamSettingsActivity
import com.stream.prime.file.FileStreamService
import com.stream.prime.utils.CrashPrevention
import com.stream.prime.utils.ErrorHandler
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import android.view.View
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import android.os.Handler
import android.os.Looper
import android.net.Uri
import com.stream.prime.utils.PermissionManager
import com.stream.prime.overlay.LayerManagerActivity
import java.util.Locale

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class UnifiedStreamActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback, SettingsManager.SettingsChangeListener {

    companion object {
        private const val TAG = "UnifiedStreamActivity"
        private const val PERMISSION_REQUEST_CODE = 123
        private const val REQUEST_FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION = 124
        private const val SERVICE_STATE_SYNC_ATTEMPTS = 20
        private const val SERVICE_STATE_SYNC_DELAY_MS = 100L
    }

    private lateinit var binding: ActivityUnifiedStreamBinding
    private var action = Action.STREAM
    private var currentStreamingMode = "Auto"
    private var streamStartTime: Long = 0
    private var streamTimer: Handler? = null
    private var streamDurationRunnable: Runnable? = null
    private val STREAM_START_TIME_KEY = "stream_start_time"
    private val STREAM_DURATION_PREFS = "StreamDurationPrefs"
    private var videoUri: Uri? = null
    private var isFileStreaming = false
    private var floatingOverlayService: FloatingOverlayService? = null
    private var micVolume = 100
    private var deviceVolume = 100
    private var pendingAction: Action? = null
    private val serviceStateHandler = Handler(Looper.getMainLooper())
    private var serviceStateSyncAttempt = 0
    private val serviceStateSync = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return

            val stateSourceReady = if (isFileStreaming) {
                FileStreamService.INSTANCE != null
            } else {
                ScreenService.INSTANCE != null
            }
            if (!stateSourceReady) {
                if (serviceStateSyncAttempt++ < SERVICE_STATE_SYNC_ATTEMPTS) {
                    serviceStateHandler.postDelayed(this, SERVICE_STATE_SYNC_DELAY_MS)
                }
                return
            }

            updateStreamButtonState()
            updateRecordButtonState()

            if (!isFileStreaming) {
                ScreenService.INSTANCE?.let { service ->
                    if (service.isStreaming() || service.isRecording()) {
                        service.setCallback(this@UnifiedStreamActivity)
                    }
                    if (service.isStreaming()) restoreActiveStreamUi(service)
                }
            }
        }
    }

    private enum class Action {
        STREAM, RECORD
    }

    private val activityResultContract = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val service = ScreenService.INSTANCE
            if (service != null && data != null) {
                service.setCallback(this)
                if (service.prepareStream(result.resultCode, data)) {
                    when (action) {
                        Action.STREAM -> {
                            startStream()
                        }
                        Action.RECORD -> {
                            startRecord()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle file streaming extras
        intent?.let { intent ->
            videoUri = intent.getStringExtra("VIDEO_URI")?.let { Uri.parse(it) }
            isFileStreaming = intent.getStringExtra("STREAMING_MODE") == "file"
            
            if (isFileStreaming && videoUri != null) {
                Log.d(TAG, "File streaming mode activated with video URI: $videoUri")
                Toast.makeText(this, "File streaming mode enabled", Toast.LENGTH_SHORT).show()
            }
        }

        try {
            binding = ActivityUnifiedStreamBinding.inflate(layoutInflater)
            setContentView(binding.root)

            configureFullscreen()
            setupClickListeners()
            setupChatRecyclerView()
            setupEffectsRecyclerView()
            checkPermissions()
            SettingsManager.addListener(this)

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            ErrorHandler.handleError(this, e, "Activity initialization")
            try {
                binding = ActivityUnifiedStreamBinding.inflate(layoutInflater)
                setContentView(binding.root)
                setupClickListeners()
                SettingsManager.addListener(this)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Fallback initialization also failed: ${fallbackException.message}", fallbackException)
                finish()
            }
        }

        // Restore stream duration if stream is running
        restoreStreamDuration()
        
        // Restore UI state if stream is running
        val service = ScreenService.INSTANCE
        if (service != null && service.isStreaming()) {
            updateStreamButtonState()
            updateRecordButtonState()
            service.setCallback(this)
            
            // Request current bitrate from service
            requestCurrentBitrate()
            
            // Force start timer if stream is running
            forceStartTimerIfNeeded()
            
            Log.d(TAG, "App started - restored streaming UI state")
        }

        updateMicrophoneButtonState()
        startMicrophoneSync()
    }

    private fun forceStartTimerIfNeeded() {
        try {
            val service = ScreenService.INSTANCE
            if (service != null && service.isStreaming() && streamTimer == null) {
                Log.d(TAG, "Force starting timer for running stream")
                
                // Get the actual stream start time from service
                val serviceStartTime = service.getStreamStartTime()
                if (serviceStartTime > 0) {
                    streamStartTime = serviceStartTime
                    Log.d(TAG, "Using service stream start time: $streamStartTime")
                } else {
                    streamStartTime = System.currentTimeMillis()
                    Log.d(TAG, "No service start time, using current time: $streamStartTime")
                }
                
                startStreamTimer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error force starting timer: ${e.message}")
        }
    }

    private fun configureFullscreen() {
        CrashPrevention.safeConfigureFullscreen(this)
    }

    override fun onSettingsChanged() {
        runOnUiThread {
            val previousMode = currentStreamingMode
            currentStreamingMode = SettingsManager.getStreamingMode(this)
            Log.d("UnifiedStreamActivity", "Settings updated - Previous Mode: $previousMode, New Mode: $currentStreamingMode")
            
            if (previousMode != currentStreamingMode) {
                updateStreamingModeUI()
                
                ScreenService.INSTANCE?.let { service ->
                    if (!service.isStreaming()) {
                        service.reloadQualitySettings()
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        // Check all required permissions
        val missingPermissions = PermissionManager.getMissingPermissions(this)
        
        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Missing permissions: $missingPermissions")
            showEnhancedPermissionDialog(missingPermissions)
            return
        }
        
        Log.d(TAG, "All permissions granted")
        PermissionManager.logPermissionStatus(this)
    }
    
    private fun showEnhancedPermissionDialog(missingPermissions: List<String>) {
        val message = StringBuilder()
        message.append("The following permissions are required for enhanced audio recording and screen capture:\n\n")
        
        missingPermissions.forEach { permission ->
            message.append("• $permission\n")
        }
        
        message.append("\nPlease grant these permissions to continue.")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message.toString())
            .setPositiveButton("Grant Permissions") { _, _ ->
                requestMissingPermissions(missingPermissions)
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun requestMissingPermissions(missingPermissions: List<String>) {
        val permissionsToRequest = mutableListOf<String>()
        
        if (!PermissionManager.isMicrophonePermissionGranted(this)) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (!PermissionManager.isStoragePermissionGranted(this)) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
        
        // Handle system alert window permission
        if (!PermissionManager.isSystemAlertWindowPermissionGranted(this)) {
            PermissionManager.openSystemAlertWindowSettings(this)
        }
        
        // Handle accessibility service permission
        if (!PermissionManager.isAccessibilityServiceEnabled()) {
            PermissionManager.openAccessibilitySettings(this)
        }
    }

    private fun showPermissionExplanationDialog() {
        // Permission explanation dialog implementation
        Toast.makeText(this, "Microphone permission required for streaming", Toast.LENGTH_LONG).show()
    }

    private fun updateStreamingModeUI() {
        Log.d("UnifiedStreamActivity", "updateStreamingModeUI() - Current Mode: $currentStreamingMode")
        
        runOnUiThread {
            binding.btnStream.text = "▶"
        }
    }

    override fun onResume() {
        super.onResume()
        scheduleServiceStateSync()
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

    private fun restoreActiveStreamUi(service: ScreenService) {
        service.restoreAudioSourceState()
        updateMicrophoneButtonState()
        loadVolumeSettings()

        if (streamTimer == null) {
            Log.d(TAG, "Timer not running but stream is active - restoring timer")
            restoreStreamDuration()
        }

        requestCurrentBitrate()
        Log.d(TAG, "App resumed - restored active capture UI state")
    }

    private fun setupClickListeners() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, com.stream.prime.settings.StreamSettingsActivity::class.java)
            startActivity(intent)
        }

        // Main stream button
        binding.btnStream.setOnClickListener {
            startServiceIfPermissionsGranted()
        }

        // Record button
        binding.btnRecord.setOnClickListener {
            startServiceIfPermissionsGrantedForRecord()
        }

        // Overlay editor button
        binding.btnOverlay.setOnClickListener {
            val intent = Intent(this, LayerManagerActivity::class.java)
            startActivity(intent)
        }

        // Microphone button
        binding.btnMic.setOnClickListener {
            toggleMicrophone()
        }

        // Setup volume controls
        setupVolumeControls()
        loadVolumeSettings()
        
        // Restore stream duration if stream is running
        restoreStreamDuration()

        updateMicrophoneButtonState()
    }

    private fun restoreStreamDuration() {
        try {
            Log.d(TAG, "restoreStreamDuration called")
            val prefs = getSharedPreferences(STREAM_DURATION_PREFS, MODE_PRIVATE)
            val savedStartTime = prefs.getLong(STREAM_START_TIME_KEY, 0)
            Log.d(TAG, "Saved start time: $savedStartTime")
            
            if (savedStartTime > 0) {
                // Check if stream is actually running
                val service = ScreenService.INSTANCE
                if (service != null && service.isStreaming()) {
                    // Stream is running, restore the timer
                    streamStartTime = savedStartTime
                    Log.d(TAG, "Stream is running, restoring timer with start time: $streamStartTime")
                    startStreamTimer()
                    Log.d(TAG, "Stream duration restored from saved time: $savedStartTime")
                } else {
                    // Stream is not running, clear saved time
                    prefs.edit().remove(STREAM_START_TIME_KEY).apply()
                    Log.d(TAG, "Stream not running, cleared saved start time")
                }
            } else {
                // No saved time, but check if stream is running
                val service = ScreenService.INSTANCE
                if (service != null && service.isStreaming()) {
                    // Stream is running but no saved time - use service start time
                    val serviceStartTime = service.getStreamStartTime()
                    if (serviceStartTime > 0) {
                        Log.d(TAG, "Stream is running, using service start time: $serviceStartTime")
                        streamStartTime = serviceStartTime
                        startStreamTimer()
                    } else {
                        Log.d(TAG, "Stream is running but no service start time - starting new timer")
                        streamStartTime = System.currentTimeMillis()
                        startStreamTimer()
                    }
                } else {
                    Log.d(TAG, "No saved start time found and stream not running")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring stream duration: ${e.message}")
        }
    }

    private fun loadVolumeSettings() {
        try {
            // First try to get from service if available
            val service = ScreenService.INSTANCE
            if (service != null) {
                micVolume = service.getMicVolume()
                deviceVolume = service.getDeviceVolume()
                Log.d(TAG, "Volume settings loaded from service - Mic: $micVolume%, Device: $deviceVolume%")
            } else {
                // Fallback to local SharedPreferences
                val prefs = getSharedPreferences("StreamAudioPrefs", MODE_PRIVATE)
                micVolume = prefs.getInt("mic_volume", 100)
                deviceVolume = prefs.getInt("device_volume", 100)
                Log.d(TAG, "Volume settings loaded from local prefs - Mic: $micVolume%, Device: $deviceVolume%")
            }
            
            // Update UI to reflect saved settings
            binding.seekbarMicVolume.progress = micVolume
            binding.txtMicVolume.text = "$micVolume%"
            binding.seekbarDeviceVolume.progress = deviceVolume
            binding.txtDeviceVolume.text = "$deviceVolume%"
            
            // Apply settings to service if available and streaming
            if (service != null && service.isStreaming()) {
                service.setMicVolume(micVolume)
                service.setDeviceVolume(deviceVolume)
                Log.d(TAG, "Applied volume settings to active service")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading volume settings: ${e.message}")
            // Use defaults if loading fails
            micVolume = 100
            deviceVolume = 100
            binding.seekbarMicVolume.progress = micVolume
            binding.txtMicVolume.text = "$micVolume%"
            binding.seekbarDeviceVolume.progress = deviceVolume
            binding.txtDeviceVolume.text = "$deviceVolume%"
        }
    }

    private fun setupVolumeControls() {
        // Microphone volume control
        binding.seekbarMicVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                micVolume = progress
                binding.txtMicVolume.text = "$progress%"
                updateMicVolume()
            }
            
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Device volume control
        binding.seekbarDeviceVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                deviceVolume = progress
                binding.txtDeviceVolume.text = "$progress%"
                updateDeviceVolume()
            }
            
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun updateMicVolume() {
        try {
            // Save to local SharedPreferences
            val prefs = getSharedPreferences("StreamAudioPrefs", MODE_PRIVATE)
            prefs.edit().putInt("mic_volume", micVolume).apply()
            
            // Update service if available
            val service = ScreenService.INSTANCE
            if (service != null) {
                service.setMicVolume(micVolume)
            }
            
            Log.d(TAG, "Stream microphone level set to: $micVolume%")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stream mic level: ${e.message}")
        }
    }

    private fun updateDeviceVolume() {
        try {
            // Save to local SharedPreferences
            val prefs = getSharedPreferences("StreamAudioPrefs", MODE_PRIVATE)
            prefs.edit().putInt("device_volume", deviceVolume).apply()
            
            // Update service if available
            val service = ScreenService.INSTANCE
            if (service != null) {
                service.setDeviceVolume(deviceVolume)
            }
            
            Log.d(TAG, "Stream device audio level set to: $deviceVolume%")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stream device audio level: ${e.message}")
        }
    }

    private fun setupChatRecyclerView() {
        // Removed - no longer needed
    }

    private fun setupEffectsRecyclerView() {
        // Removed - no longer needed
    }

    private fun toggleChatPanel() {
        // Removed - no longer needed
    }

    private fun toggleEffectsDrawer() {
        // Removed - no longer needed
    }

    private fun sendChatMessage() {
        // Removed - no longer needed
    }

    private fun showLikeAnimation() {
        // Removed - no longer needed
    }

    private fun showGiftAnimation() {
        // Removed - no longer needed
    }

    private fun setupStreamButton() {
        // Check RTMP configuration before requesting screen capture permission
        if (!checkRtmpConfiguration()) {
            return
        }
        
        val service = ScreenService.INSTANCE
        if (service != null) {
            service.setCallback(this)
            
            // Save current volume settings and apply them before starting stream
            saveCurrentVolumeSettings()
            service.applyCurrentVolumeSettings()
            
            val prefs = getSharedPreferences("StreamSettings", 0)
            val mode = prefs.getString("streaming_mode", "Auto") ?: "Auto"
            Toast.makeText(this, "Streaming mode: $mode", Toast.LENGTH_SHORT).show()

            if (!service.isStreaming() && !service.isRecording()) {
                action = Action.STREAM
                activityResultContract.launch(service.sendIntent())
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
            
            // Save current volume settings and apply them before starting recording
            saveCurrentVolumeSettings()
            service.applyCurrentVolumeSettings()
            
            if (!service.isStreaming() && !service.isRecording()) {
                action = Action.RECORD
                activityResultContract.launch(service.sendIntent())
            } else {
                stopRecord()
            }
        }
        updateRecordButtonState()
    }

    private fun checkAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                showPermissionExplanationDialog()
                return false
            }
        }
        
        // Check for FOREGROUND_SERVICE_MEDIA_PROJECTION permission required in Android 16+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION),
                    REQUEST_FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION
                )
                return false
            }
        }
        
        return true
    }

    private fun startServiceIfPermissionsGranted() {
        if (checkAudioPermission()) {
            if (ScreenService.INSTANCE == null) {
                startService(Intent(this, ScreenService::class.java))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    setupStreamButton()
                }, 100)
            } else {
                setupStreamButton()
            }
        } else {
            pendingAction = Action.STREAM
        }
    }

    private fun startServiceIfPermissionsGrantedForRecord() {
        if (checkAudioPermission()) {
            if (ScreenService.INSTANCE == null) {
                startService(Intent(this, ScreenService::class.java))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    setupRecordButton()
                }, 100)
            } else {
                setupRecordButton()
            }
        } else {
            pendingAction = Action.RECORD
        }
    }

    private fun checkRtmpConfiguration(): Boolean {
        // Handle file streaming mode - no RTMP config needed
        if (isFileStreaming && videoUri != null) {
            Log.d(TAG, "File streaming mode - skipping RTMP configuration check")
            return true
        }
        
        // Get current streaming service and its specific settings
        val selectedService = SettingsManager.getStreamingService(this)
        val streamKey = SettingsManager.getStreamKeyForService(this, selectedService)
        
        if (streamKey.isEmpty()) {
            Toast.makeText(this, "Please configure your stream key in Stream Settings", Toast.LENGTH_LONG).show()
            return false
        }
        
        if (!streamKey.matches(Regex("^[a-zA-Z0-9-_]+$"))) {
            Toast.makeText(this, "Invalid stream key format. Please check your stream key.", Toast.LENGTH_LONG).show()
            return false
        }
        
        // Check service-specific configuration
        when (selectedService) {
            "Custom RTMP" -> {
                val streamUrl = SettingsManager.getCustomStreamUrl(this)
                if (streamUrl.isEmpty()) {
                    Toast.makeText(this, "Please configure your Custom RTMP URL in Stream Settings", Toast.LENGTH_LONG).show()
                    return false
                }
                Log.d(TAG, "RTMP configuration validated for Custom RTMP endpoint")
            }
            "YouTube Live", "Facebook Live", "Twitch", "Instagram Live", "TikTok Live" -> {
                Log.d(TAG, "RTMP configuration validated for $selectedService")
            }
            else -> {
                Toast.makeText(this, "Unsupported streaming service: $selectedService", Toast.LENGTH_LONG).show()
                return false
            }
        }
        
        return true
    }

    private fun startStream() {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "No internet connection available. Please check your network.", Toast.LENGTH_LONG).show()
            return
        }
        
        // Handle file streaming mode
        if (isFileStreaming && videoUri != null) {
            Log.d(TAG, "Starting file stream with video URI: $videoUri")
            Toast.makeText(this, "Starting file stream...", Toast.LENGTH_SHORT).show()
            
            // Use FileStreamService for file streaming
            startFileStream()
            return
        }
        
        // Get current streaming service and its specific settings
        val selectedService = SettingsManager.getStreamingService(this)
        val streamKey = SettingsManager.getStreamKeyForService(this, selectedService)
        
        if (streamKey.isEmpty()) {
            Toast.makeText(this, "Please configure your stream key in Stream Settings", Toast.LENGTH_LONG).show()
            return
        }
        
        if (!streamKey.matches(Regex("^[a-zA-Z0-9-_]+$"))) {
            Toast.makeText(this, "Invalid stream key format. Please check your stream key.", Toast.LENGTH_LONG).show()
            return
        }
        
        // Construct the full URL based on the selected service
        val fullUrl = when (selectedService) {
            "Custom RTMP" -> {
                val streamUrl = SettingsManager.getCustomStreamUrl(this)
                if (streamUrl.isEmpty()) {
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
                Toast.makeText(this, "Unsupported streaming service: $selectedService", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        if (!fullUrl.startsWith("rtmp://")) {
            Toast.makeText(this, "Invalid RTMP URL format", Toast.LENGTH_LONG).show()
            return
        }
        
        Log.d("Streaming", "Starting stream with configured RTMP endpoint")
        
        ScreenService.INSTANCE?.startStream(fullUrl)
        getSharedPreferences("StreamStatus", 0).edit().putBoolean("unified_streaming_status", true).apply()
        updateStreamButtonState()
        
        // Start stream timer
        streamStartTime = System.currentTimeMillis()
        startStreamTimer()
    }
    
    private fun startFileStream() {
        // Get current streaming service and its specific settings
        val selectedService = SettingsManager.getStreamingService(this)
        val streamKey = SettingsManager.getStreamKeyForService(this, selectedService)
        
        if (streamKey.isEmpty()) {
            Toast.makeText(this, "Please configure your stream key in Stream Settings", Toast.LENGTH_LONG).show()
            return
        }
        
        // Construct the full URL based on the selected service
        val fullUrl = when (selectedService) {
            "Custom RTMP" -> {
                val streamUrl = SettingsManager.getCustomStreamUrl(this)
                if (streamUrl.isEmpty()) {
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
                Toast.makeText(this, "Unsupported streaming service: $selectedService", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        Log.d("FileStreaming", "Starting file stream with configured RTMP endpoint and video: $videoUri")
        
        // Start FileStreamService for file streaming
        val fileServiceIntent = Intent(this, FileStreamService::class.java)
        startService(fileServiceIntent)
        
        // Set the video URI and prepare the stream
        FileStreamService.INSTANCE?.let { service ->
            service.setVideoUri(videoUri!!)
            if (service.prepareStream()) {
                service.startStream(fullUrl)
                getSharedPreferences("StreamStatus", 0).edit().putBoolean("unified_streaming_status", true).apply()
                updateStreamButtonState()
                
                // Start stream timer
                streamStartTime = System.currentTimeMillis()
                startStreamTimer()
                
                Toast.makeText(this, "File streaming started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to prepare file stream", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "File streaming service not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStream() {
        if (isFileStreaming) {
            // Stop file streaming service
            FileStreamService.INSTANCE?.stopStream()
            val fileServiceIntent = Intent(this, FileStreamService::class.java)
            stopService(fileServiceIntent)
        } else {
            // Stop screen streaming service
            ScreenService.INSTANCE?.stopStream()
        }
        
        getSharedPreferences("StreamStatus", 0).edit().putBoolean("unified_streaming_status", false).apply()
        updateStreamButtonState()
        stopStreamTimer()
    }

    private fun startRecord() {
        val service = ScreenService.INSTANCE
        if (service != null) {
            service.toggleRecord { status ->
                runOnUiThread {
                    when (status) {
                        RecordController.Status.RECORDING -> {
                            binding.btnRecord.text = "⏹"
                            Toast.makeText(this, "Recording started with current stream settings", Toast.LENGTH_SHORT).show()
                        }
                        RecordController.Status.STOPPED -> {
                            binding.btnRecord.text = "●"
                            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
                        }
                        RecordController.Status.STARTED -> {
                            binding.btnRecord.text = "⏹"
                            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            // Handle other statuses if needed
                        }
                    }
                }
            }
        }
    }

    private fun stopRecord() {
        val service = ScreenService.INSTANCE
        if (service != null) {
            service.toggleRecord { status ->
                runOnUiThread {
                    when (status) {
                        RecordController.Status.RECORDING -> {
                            binding.btnRecord.text = "⏹"
                            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                        }
                        RecordController.Status.STOPPED -> {
                            binding.btnRecord.text = "●"
                            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
                        }
                        RecordController.Status.STARTED -> {
                            binding.btnRecord.text = "⏹"
                            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            // Handle other statuses if needed
                        }
                    }
                }
            }
        }
    }

    private fun updateStreamButtonState() {
        val isStreaming = if (isFileStreaming) {
            FileStreamService.INSTANCE?.isStreaming() ?: false
        } else {
            ScreenService.INSTANCE?.isStreaming() ?: false
        }
        
        if (isStreaming) {
            binding.btnStream.text = "⏹"
        } else {
            binding.btnStream.text = "▶"
        }
    }

    private fun updateRecordButtonState() {
        val isRecording = if (isFileStreaming) {
            FileStreamService.INSTANCE?.isRecording() ?: false
        } else {
            ScreenService.INSTANCE?.isRecording() ?: false
        }
        
        if (isRecording) {
            binding.btnRecord.text = "⏹"
        } else {
            binding.btnRecord.text = "●"
        }
    }

    private fun isNetworkAvailable(): Boolean {
        // Network availability check implementation
        return true
    }

    override fun onConnectionStarted(url: String) {
        runOnUiThread {
            binding.btnStream.text = "⏹"
        }
        Log.d(TAG, "Connection started: $url")
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            binding.btnStream.text = "⏹"
            startPreview()
        }
        
        // Get the actual stream start time from service
        val service = ScreenService.INSTANCE
        if (service != null) {
            val serviceStartTime = service.getStreamStartTime()
            if (serviceStartTime > 0) {
                streamStartTime = serviceStartTime
                Log.d(TAG, "Using service stream start time: $streamStartTime")
            } else {
                streamStartTime = System.currentTimeMillis()
                Log.d(TAG, "No service start time, using current time: $streamStartTime")
            }
        } else {
            streamStartTime = System.currentTimeMillis()
            Log.d(TAG, "No service available, using current time: $streamStartTime")
        }
        
        startStreamTimer()
        Log.d(TAG, "Connection success - timer started with start time: $streamStartTime")
    }

    override fun onNewBitrate(bitrate: Long) {
        runOnUiThread {
            val liveBitrateMbps = bitrate / 1_000_000.0
            val targetBitrateKbps = if (SettingsManager.getStreamingMode(this) == "Vertical") {
                SettingsManager.getVerticalBitrate(this)
            } else {
                SettingsManager.getLandscapeBitrate(this)
            }
            val targetBitrateMbps = targetBitrateKbps / 1000.0
            binding.txtBitrate.text = String.format(
                Locale.US,
                "Live %.1f • Target %.1f Mbps",
                liveBitrateMbps,
                targetBitrateMbps
            )
            Log.d(
                "UnifiedStreamActivity",
                "Bitrate updated: live=$liveBitrateMbps Mbps, target=$targetBitrateMbps Mbps"
            )
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            binding.btnStream.text = "▶"
            
            // Stop the stream timer when connection fails
            stopStreamTimer()
            Log.d(TAG, "Connection failed - stream timer stopped")
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            binding.txtBitrate.text = "0 Mbps"
            binding.btnStream.text = "▶"
            
            // Stop the stream timer when disconnected
            stopStreamTimer()
            Log.d(TAG, "Disconnected - stream timer stopped")
        }
    }

    override fun onAuthError() {
        runOnUiThread {
            binding.btnStream.text = "▶"
        }
    }

    override fun onAuthSuccess() {
        runOnUiThread {
        }
    }

    override fun onDestroy() {
        serviceStateHandler.removeCallbacks(serviceStateSync)
        super.onDestroy()
        SettingsManager.removeListener(this)
        ScreenService.INSTANCE?.setCallback(null)
        stopStreamTimer()
        stopMicrophoneSync()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        try {
            when (requestCode) {
                PERMISSION_REQUEST_CODE -> {
                    if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this, "Permissions granted! App will work with full functionality.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this, "Some permissions were denied. Some features may be limited.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                REQUEST_FOREGROUND_SERVICE_MEDIA_PROJECTION_PERMISSION -> {
                    if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "FOREGROUND_SERVICE_MEDIA_PROJECTION permission granted")
                        // Permission granted, now start the service based on pending action
                        when (pendingAction) {
                            Action.STREAM -> startServiceIfPermissionsGranted()
                            Action.RECORD -> startServiceIfPermissionsGrantedForRecord()
                            null -> startServiceIfPermissionsGranted() // Default to stream
                        }
                        pendingAction = null
                    } else {
                        Log.d(TAG, "FOREGROUND_SERVICE_MEDIA_PROJECTION permission denied")
                        Toast.makeText(this, "Media projection permission is required for screen recording", Toast.LENGTH_LONG).show()
                        pendingAction = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling permission result: ${e.message}")
        }
    }

    private fun startPreview() {
        ScreenService.INSTANCE?.attachPreview(binding.surfaceView)
    }

    private fun toggleMicrophone() {
        val service = ScreenService.INSTANCE
        if (service != null) {
            service.toggleMicrophone()
            updateMicrophoneButtonState()
            // Update notification to reflect the microphone state change
            service.updateNotification()
        }
    }

    private fun updateMicrophoneButtonState() {
        val service = ScreenService.INSTANCE
        if (service != null) {
            runOnUiThread {
                if (service.isMicrophoneMuted()) {
                    binding.btnMic.text = "🔇"
                    Log.d(TAG, "Microphone button updated to muted state")
                } else {
                    binding.btnMic.text = "🎤"
                    Log.d(TAG, "Microphone button updated to unmuted state")
                }
            }
        }
    }
    
    private fun startMicrophoneSync() {
        // Periodically sync microphone state every 2 seconds
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                updateMicrophoneButtonState()
                Handler(Looper.getMainLooper()).postDelayed(this, 2000)
            }
        }, 2000)
    }
    
    private fun stopMicrophoneSync() {
        // Stop the periodic sync by removing all callbacks
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
    }

    private fun startStreamTimer() {
        try {
            // Only set streamStartTime if it's not already set (0)
            if (streamStartTime == 0L) {
                streamStartTime = System.currentTimeMillis()
                Log.d(TAG, "Setting new stream start time: $streamStartTime")
            } else {
                Log.d(TAG, "Using existing stream start time: $streamStartTime")
            }
            
            // Save stream start time to SharedPreferences
            val prefs = getSharedPreferences(STREAM_DURATION_PREFS, MODE_PRIVATE)
            prefs.edit().putLong(STREAM_START_TIME_KEY, streamStartTime).apply()
            
            streamTimer = Handler(Looper.getMainLooper())
            
            streamDurationRunnable = object : Runnable {
                override fun run() {
                    Log.d(TAG, "Timer tick - updating stream duration")
                    updateStreamDuration()
                    streamTimer?.postDelayed(this, 1000) // Update every second
                }
            }
            
            streamTimer?.post(streamDurationRunnable!!)
            Log.d(TAG, "Stream timer started with start time: $streamStartTime")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream timer: ${e.message}")
        }
    }

    private fun stopStreamTimer() {
        try {
            Log.d(TAG, "Stopping stream timer")
            streamTimer?.removeCallbacks(streamDurationRunnable ?: return)
            streamTimer = null
            streamDurationRunnable = null
            streamStartTime = 0
            
            // Clear saved stream start time
            val prefs = getSharedPreferences(STREAM_DURATION_PREFS, MODE_PRIVATE)
            prefs.edit().remove(STREAM_START_TIME_KEY).apply()
            
            // Reset duration display
            binding.txtStreamDuration.text = "00:00"
            Log.d(TAG, "Stream timer stopped and cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream timer: ${e.message}")
        }
    }

    private fun updateStreamDuration() {
        try {
            Log.d(TAG, "updateStreamDuration called - streamStartTime: $streamStartTime")
            if (streamStartTime > 0) {
                val elapsedTime = System.currentTimeMillis() - streamStartTime
                val seconds = (elapsedTime / 1000).toInt()
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                
                val timeString = String.format("%02d:%02d", minutes, remainingSeconds)
                
                runOnUiThread {
                    binding.txtStreamDuration.text = timeString
                    Log.d(TAG, "Updated stream duration: $timeString (elapsed: ${elapsedTime}ms)")
                }
            } else {
                Log.d(TAG, "updateStreamDuration called but streamStartTime is 0")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stream duration: ${e.message}")
        }
    }

    private fun showFloatingOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Please grant 'Draw over other apps' permission to use floating controls", Toast.LENGTH_LONG).show()
                    return
                }
            }
            
            val intent = Intent(this, FloatingOverlayService::class.java)
            startService(intent)
            floatingOverlayService = FloatingOverlayService.INSTANCE
            
            Toast.makeText(this, "Floating controls enabled", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating overlay: ${e.message}")
            Toast.makeText(this, "Error showing floating controls", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideFloatingOverlay() {
        try {
            val intent = Intent(this, FloatingOverlayService::class.java)
            stopService(intent)
            floatingOverlayService = null
            
            Toast.makeText(this, "Floating controls disabled", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding floating overlay: ${e.message}")
        }
    }

    // SurfaceHolder.Callback implementation
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("UnifiedStreamActivity", "Surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("UnifiedStreamActivity", "Surface changed: ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("UnifiedStreamActivity", "Surface destroyed")
    }

    private fun requestCurrentBitrate() {
        val service = ScreenService.INSTANCE
        if (service != null) {
            service.requestCurrentBitrate()
        }
    }

    /**
     * Save current volume settings to ensure they are persisted
     */
    private fun saveCurrentVolumeSettings() {
        try {
            Log.d(TAG, "=== SAVING CURRENT VOLUME SETTINGS ===")
            Log.d(TAG, "Current UI values - Mic: $micVolume%, Device: $deviceVolume%")
            
            // Save to local SharedPreferences
            val prefs = getSharedPreferences("StreamAudioPrefs", MODE_PRIVATE)
            prefs.edit()
                .putInt("mic_volume", micVolume)
                .putInt("device_volume", deviceVolume)
                .apply()
            
            Log.d(TAG, "✅ Saved to SharedPreferences - Mic: $micVolume%, Device: $deviceVolume%")
            
            // Update service if available
            val service = ScreenService.INSTANCE
            if (service != null) {
                service.setMicVolume(micVolume)
                service.setDeviceVolume(deviceVolume)
                Log.d(TAG, "✅ Updated service directly - Mic: $micVolume%, Device: $deviceVolume%")
            } else {
                Log.d(TAG, "⚠️ Service not available for direct update")
            }
            
            Log.d(TAG, "=== VOLUME SETTINGS SAVED ===")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving current volume settings: ${e.message}")
        }
    }
}
