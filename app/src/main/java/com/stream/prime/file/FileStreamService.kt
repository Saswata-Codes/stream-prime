package com.stream.prime.file

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.generic.GenericFromFile
import com.stream.prime.R
import com.stream.prime.settings.SettingsManager

class FileStreamService : Service(), ConnectChecker, VideoDecoderInterface, AudioDecoderInterface {

    companion object {
        private const val TAG = "FileStreamService"
        private const val CHANNEL_ID = "FileStreamService"
        private const val NOTIFY_ID = 1002
        var INSTANCE: FileStreamService? = null
    }

    // Callback interface for activity communication
    interface StreamCallback {
        fun onStreamAutoStop()
    }

    private var streamCallback: StreamCallback? = null

    fun setStreamCallback(callback: StreamCallback?) {
        streamCallback = callback
    }

    private var notificationManager: NotificationManager? = null
    private lateinit var genericFromFile: GenericFromFile
    private var videoUri: Uri? = null
    private var prepared = false
    private var isLoopEnabled = false
    private var endpoint: String? = null
    private var videoDecoderFinished = false
    private var audioDecoderFinished = false
    private var manualLoopEnabled = false // Manual loop flag
    private var restartTimer: android.os.Handler? = null

    // Quality settings
    private var width = 1280
    private var height = 720
    private var fps = 30
    private var vBitrate = 2500000

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "File Stream service create")
        INSTANCE = this
        
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
            notificationManager?.createNotificationChannel(channel)
        }
        
        loadQualitySettings()
        
        Log.d(TAG, "=== ONCREATE DEBUG ===")
        Log.d(TAG, "Width: $width, Height: $height, FPS: $fps, Bitrate: $vBitrate")
        
        // Initialize GenericFromFile without OpenGL context for service use
        try {
            genericFromFile = GenericFromFile(this, this, this)
            Log.d(TAG, "GenericFromFile initialized successfully")
            
            // Set initial loop mode to false to prevent OpenGL crashes in service context
            genericFromFile.setLoopMode(false)
            Log.d(TAG, "Initial loop mode set to false in service context")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GenericFromFile: ${e.message}")
            Log.e(TAG, "Exception details: ${e.printStackTrace()}")
        }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "File Stream service started")
        return START_STICKY
    }

    fun setVideoUri(uri: Uri) {
        videoUri = uri
        Log.d(TAG, "Video URI set: $uri")
    }

    fun setLoopEnabled(enabled: Boolean) {
        isLoopEnabled = enabled
        manualLoopEnabled = enabled // Use manual loop instead of library loop
        Log.d(TAG, "Loop enabled: $enabled (using manual loop)")
        
        // In service context, we use manual loop instead of library loop to prevent OpenGL crashes
        Log.d(TAG, "Using manual loop mechanism to prevent OpenGL crashes")
        
        // Update the loop mode in the library if it's already initialized
        if (::genericFromFile.isInitialized) {
            try {
                genericFromFile.setLoopMode(false) // Always disable library loop in service
                Log.d(TAG, "Library loop mode forced to false, using manual loop")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set loop mode due to OpenGL context issue: ${e.message}")
                // In service context, loop mode might not work properly due to OpenGL limitations
                // We'll handle this gracefully
            }
        }
    }

    fun prepareStream(): Boolean {
        // Don't call stopStream() here as it might interfere with loop mechanism
        // Only stop if we're already streaming and need to restart
        
        // Force reload quality settings from centralized Stream Settings
        loadQualitySettings()
        
        // Log the exact values being used
        Log.d(TAG, "=== PREPARE STREAM DEBUG ===")
        Log.d(TAG, "Width: $width, Height: $height, FPS: $fps, Bitrate: $vBitrate")
        Log.d(TAG, "Video URI: $videoUri")
        
        if (videoUri == null) {
            Log.e(TAG, "No video URI provided")
            return false
        }
        
        // Reinitialize video preparation with new settings - following the example pattern
        prepared = try {
            Log.d(TAG, "Preparing video with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps")
            
            // Set video and audio codecs explicitly
            genericFromFile.setVideoCodec(com.pedro.common.VideoCodec.H264)
            genericFromFile.setAudioCodec(com.pedro.common.AudioCodec.AAC)
            Log.d(TAG, "Video codec set to H264, Audio codec set to AAC")
            
            // Set loop mode to false to prevent OpenGL crashes, use manual loop instead
            try {
                genericFromFile.setLoopMode(false)
                Log.d(TAG, "Library loop mode forced to false, using manual loop: $manualLoopEnabled")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set loop mode due to OpenGL context issue: ${e.message}")
                // In service context, loop mode might not work properly due to OpenGL limitations
                // We'll continue without loop mode
            }
            
            // Prepare video first
            Log.d(TAG, "Starting video preparation...")
            val videoResult = genericFromFile.prepareVideo(this, videoUri!!)
            Log.d(TAG, "Video preparation result: $videoResult")
            
            if (!videoResult) {
                Log.e(TAG, "Video preparation failed!")
                return false
            }
            
            // Prepare audio (following the example pattern)
            Log.d(TAG, "Starting audio preparation...")
            val audioResult = genericFromFile.prepareAudio(this, videoUri!!)
            Log.d(TAG, "Audio preparation result: $audioResult")
            
            if (!audioResult) {
                Log.e(TAG, "Audio preparation failed!")
                return false
            }
            
            // Both video and audio must be prepared successfully
            val result = videoResult and audioResult
            Log.d(TAG, "Final preparation result: $result")
            
            if (result) {
                Log.d(TAG, "Both video and audio prepared successfully!")
            } else {
                Log.e(TAG, "Preparation failed - video: $videoResult, audio: $audioResult")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare stream: ${e.message}")
            Log.e(TAG, "Exception details: ${e.printStackTrace()}")
            false
        }
        
        if (!prepared) {
            Log.e(TAG, "Failed to prepare stream with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps")
            return false
        }
        
        Log.d(TAG, "Stream prepared successfully with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps")
        return true
    }

    fun startStream(endpoint: String) {
        Log.d(TAG, "Starting file stream to configured RTMP endpoint")
        
        // Store endpoint for loop functionality
        this.endpoint = endpoint
        
        // Reset decoder finished flags for new stream
        videoDecoderFinished = false
        audioDecoderFinished = false
        
        // Validate endpoint format
        if (!endpoint.startsWith("rtmp://")) {
            Log.e(TAG, "Invalid RTMP endpoint format")
            return
        }
        
        // Ensure quality settings are up to date before streaming
        loadQualitySettings()
        
        // Check if already streaming
        if (!genericFromFile.isStreaming) {
            try {
                Log.d(TAG, "Starting file stream with settings: ${width}x${height}, ${fps}fps, ${vBitrate}bps")
                Log.d(TAG, "Video URI: $videoUri")
                Log.d(TAG, "Prepared state: $prepared")
                
                if (!prepared) {
                    Log.e(TAG, "Stream not prepared, attempting to prepare first")
                    if (!prepareStream()) {
                        Log.e(TAG, "Failed to prepare stream")
                        return
                    }
                }
                
                genericFromFile.startStream(endpoint)
                Log.d(TAG, "File stream start initiated")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start file stream: ${e.message}")
                Log.e(TAG, "Exception details: ${e.printStackTrace()}")
            }
        } else {
            Log.d(TAG, "File stream already running, ignoring start request")
        }
    }

    fun stopStream() {
        Log.d(TAG, "Stopping file stream")
        if (genericFromFile.isStreaming) {
            genericFromFile.stopStream()
            notificationManager?.cancel(NOTIFY_ID)
            Log.d(TAG, "File stream stopped")
        } else {
            Log.d(TAG, "File stream not running, ignoring stop request")
        }
    }

    fun isStreaming(): Boolean {
        return genericFromFile.isStreaming
    }

    fun isRecording(): Boolean {
        return genericFromFile.isRecording
    }

    fun isServiceReady(): Boolean {
        return ::genericFromFile.isInitialized && videoUri != null
    }

    fun getServiceStatus(): String {
        return "Service Ready: ${isServiceReady()}, " +
               "Video URI: ${videoUri != null}, " +
               "Prepared: $prepared, " +
               "Streaming: ${genericFromFile.isStreaming}, " +
               "Loop Enabled: $isLoopEnabled"
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FileStreamService onDestroy called")
        
        // Clean up restart timer
        restartTimer?.removeCallbacksAndMessages(null)
        restartTimer = null
        
        // Clean up resources
        try {
            if (prepared) {
                genericFromFile.stopStream()
                prepared = false
            }
            
            // Clear callback
            INSTANCE = null
            
            // Stop notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            notificationManager?.cancel(NOTIFY_ID)
            
            Log.d(TAG, "FileStreamService resources cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    // ConnectChecker implementation
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "File stream connection started")
        Log.d(TAG, "Connection attempt initiated")
        Log.d(TAG, "=== RTMP CONNECTION DEBUG ===")
        Log.d(TAG, "URL: configured RTMP endpoint")
        Log.d(TAG, "Video URI: $videoUri")
        Log.d(TAG, "Prepared: $prepared")
        Log.d(TAG, "Streaming: ${genericFromFile.isStreaming}")
        Log.d(TAG, "Video bitrate: $vBitrate")
        Log.d(TAG, "Video resolution: ${width}x${height}")
        Log.d(TAG, "Video FPS: $fps")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "File stream connection success")
        Log.d(TAG, "RTMP connection established successfully")
        Log.d(TAG, "=== RTMP SUCCESS DEBUG ===")
        Log.d(TAG, "Connection to RTMP server successful")
        Log.d(TAG, "Video stream should now be live")
        Log.d(TAG, "Check your streaming platform for the live stream")
        showNotification("File Stream Active", "Streaming video file")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "File stream connection failed: $reason")
        Log.e(TAG, "RTMP connection failed - reason: $reason")
        Log.e(TAG, "=== RTMP FAILURE DEBUG ===")
        Log.e(TAG, "Current endpoint: configured RTMP endpoint")
        Log.e(TAG, "Video URI: $videoUri")
        Log.e(TAG, "Prepared state: $prepared")
        Log.e(TAG, "Streaming state: ${genericFromFile.isStreaming}")
        Log.e(TAG, "Possible causes:")
        Log.e(TAG, "1. Invalid stream key")
        Log.e(TAG, "2. Network connectivity issues")
        Log.e(TAG, "3. RTMP server not accepting connections")
        Log.e(TAG, "4. Firewall blocking RTMP traffic")
        Log.e(TAG, "5. Invalid URL format")
        
        // Notify activity callback about connection failure
        streamCallback?.onStreamAutoStop()
        
        // Stop the file stream service when connection fails
        try {
            if (genericFromFile.isStreaming) {
                Log.d(TAG, "Stopping file stream due to connection failure")
                genericFromFile.stopStream()
            }
            
            // Stop the foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
            // Stop the service itself
            stopSelf()
            
            Log.d(TAG, "File stream service stopped due to connection failure")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service on connection failure: ${e.message}")
        }
        
        showNotification("File Stream Failed", reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "File stream new bitrate: $bitrate")
        if (bitrate > 0) {
            Log.d(TAG, "Stream is sending data - bitrate: $bitrate")
            Log.d(TAG, "Video data is being transmitted to RTMP server")
            Log.d(TAG, "Decoder states - Video finished: $videoDecoderFinished, Audio finished: $audioDecoderFinished")
            Log.d(TAG, "Streaming state: ${genericFromFile.isStreaming}")
            Log.d(TAG, "Prepared state: $prepared")
        } else {
            Log.w(TAG, "Stream bitrate is 0 - no data being sent")
            Log.w(TAG, "This indicates the video is not being transmitted")
            Log.w(TAG, "Check if the video file is valid and accessible")
            Log.w(TAG, "Decoder states - Video finished: $videoDecoderFinished, Audio finished: $audioDecoderFinished")
            Log.w(TAG, "Streaming state: ${genericFromFile.isStreaming}")
            Log.w(TAG, "Prepared state: $prepared")
        }
    }

    override fun onDisconnect() {
        Log.d(TAG, "File stream disconnected")
        Log.d(TAG, "RTMP connection lost")
        Log.d(TAG, "=== RTMP DISCONNECT DEBUG ===")
        Log.d(TAG, "Connection to RTMP server was lost")
        Log.d(TAG, "This could be due to:")
        Log.d(TAG, "1. Network interruption")
        Log.d(TAG, "2. Server-side disconnection")
        Log.d(TAG, "3. Video file ended")
        
        // Notify activity callback about disconnection
        streamCallback?.onStreamAutoStop()
        
        // Ensure proper cleanup when connection is lost
        try {
            if (genericFromFile.isStreaming) {
                Log.d(TAG, "Stopping file stream due to disconnection")
                genericFromFile.stopStream()
            }
            
            // Stop the foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
            // Stop the service itself
            stopSelf()
            
            Log.d(TAG, "File stream service stopped due to disconnection")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service on disconnection: ${e.message}")
        }
        
        showNotification("File Stream Disconnected", "Connection lost")
    }

    override fun onAuthError() {
        Log.e(TAG, "File stream auth error")
        Log.e(TAG, "RTMP authentication failed")
        Log.e(TAG, "=== RTMP AUTH ERROR DEBUG ===")
        Log.e(TAG, "Authentication with RTMP server failed")
        Log.e(TAG, "Check your stream key and URL")
        Log.e(TAG, "Current endpoint: configured RTMP endpoint")
        Log.e(TAG, "Common auth issues:")
        Log.e(TAG, "1. Invalid stream key")
        Log.e(TAG, "2. Expired stream key")
        Log.e(TAG, "3. Wrong RTMP URL format")
        Log.e(TAG, "4. Server not accepting this stream key")
        showNotification("File Stream Auth Error", "Authentication failed")
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "File stream auth success")
        Log.d(TAG, "RTMP authentication successful")
        Log.d(TAG, "=== RTMP AUTH SUCCESS DEBUG ===")
        Log.d(TAG, "Successfully authenticated with RTMP server")
        Log.d(TAG, "Stream key is valid and accepted")
        Log.d(TAG, "Ready to transmit video data")
    }

    // VideoDecoderInterface implementation
    override fun onVideoDecoderFinished() {
        Log.d(TAG, "Video decoder finished")
        videoDecoderFinished = true
        
        if (manualLoopEnabled) {
            Log.d(TAG, "Manual loop enabled - video finished, waiting for audio to finish")
            // Don't stop immediately when video finishes, wait for audio to finish too
            // This prevents the issue where only audio continues streaming
        } else {
            Log.d(TAG, "Manual loop disabled - video finished, waiting for audio to finish")
            // Don't stop immediately when video finishes, wait for audio to finish too
            // This prevents the issue where only audio continues streaming
        }
    }

    override fun onAudioDecoderFinished() {
        Log.d(TAG, "Audio decoder finished")
        audioDecoderFinished = true
        
        if (manualLoopEnabled) {
            Log.d(TAG, "Manual loop enabled - audio finished, checking if video also finished")
            
            // Only stop the stream if both video and audio have finished
            if (videoDecoderFinished && audioDecoderFinished) {
                Log.d(TAG, "Both video and audio finished - checking manual loop")
                
                Log.d(TAG, "Manual loop enabled - restarting stream in 2 seconds")
                
                // Stop current stream
                try {
                    if (genericFromFile.isStreaming) {
                        genericFromFile.stopStream()
                        prepared = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop stream for restart: ${e.message}")
                }
                
                // Restart stream after 2 seconds
                restartTimer = android.os.Handler(android.os.Looper.getMainLooper())
                restartTimer?.postDelayed({
                    Log.d(TAG, "Restarting stream for manual loop")
                    if (endpoint != null && videoUri != null) {
                        // Reset decoder flags
                        videoDecoderFinished = false
                        audioDecoderFinished = false
                        
                        // Prepare and start stream again
                        if (prepareStream()) {
                            startStream(endpoint!!)
                        }
                    }
                }, 2000) // 2 second delay
                
            } else {
                Log.d(TAG, "Audio finished but video still running - waiting for video to finish")
            }
        } else {
            Log.d(TAG, "Manual loop disabled - audio finished, checking if video also finished")
            
            // Only stop the stream if both video and audio have finished
            if (videoDecoderFinished && audioDecoderFinished) {
                Log.d(TAG, "Manual loop disabled - stopping stream")
                try {
                    if (genericFromFile.isStreaming) {
                        Log.d(TAG, "Stopping stream automatically after both video and audio completion")
                        genericFromFile.stopStream()
                        prepared = false
                        showNotification("File Stream Completed", "Video file finished streaming")
                        
                        // Notify activity about auto-stop
                        streamCallback?.onStreamAutoStop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to auto-stop stream: ${e.message}")
                }
            } else {
                Log.d(TAG, "Audio finished but video still running - waiting for video to finish")
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        startForeground(NOTIFY_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
} 
