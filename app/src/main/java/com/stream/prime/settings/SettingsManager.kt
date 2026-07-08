package com.stream.prime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.lang.ref.WeakReference

object SettingsManager {
    private const val PREFS_NAME = "StreamSettings"
    private const val TAG = "SettingsManager"
    
    // Keys for SharedPreferences
    private const val KEY_STREAMING_SERVICE = "streaming_service"
    private const val KEY_STREAM_URL = "stream_url"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_STREAMING_MODE = "streaming_mode"
    
    // Separate stream keys for each service
    private const val KEY_YOUTUBE_STREAM_KEY = "youtube_stream_key"
    private const val KEY_FACEBOOK_STREAM_KEY = "facebook_stream_key"
    private const val KEY_TWITCH_STREAM_KEY = "twitch_stream_key"
    private const val KEY_INSTAGRAM_STREAM_KEY = "instagram_stream_key"
    private const val KEY_TIKTOK_STREAM_KEY = "tiktok_stream_key"
    private const val KEY_CUSTOM_STREAM_KEY = "custom_stream_key"
    
    // Separate URL storage for Custom RTMP
    private const val KEY_CUSTOM_STREAM_URL = "custom_stream_url"
    
    // Landscape mode keys
    private const val KEY_LANDSCAPE_WIDTH = "landscape_width"
    private const val KEY_LANDSCAPE_HEIGHT = "landscape_height"
    private const val KEY_LANDSCAPE_FPS = "landscape_fps"
    private const val KEY_LANDSCAPE_BITRATE = "landscape_bitrate"
    
    // Vertical mode keys
    private const val KEY_VERTICAL_WIDTH = "vertical_width"
    private const val KEY_VERTICAL_HEIGHT = "vertical_height"
    private const val KEY_VERTICAL_FPS = "vertical_fps"
    private const val KEY_VERTICAL_BITRATE = "vertical_bitrate"
    
    // Callback interface for real-time updates
    interface SettingsChangeListener {
        fun onSettingsChanged()
    }
    
    // Use WeakReference to prevent memory leaks
    private val listeners = mutableListOf<WeakReference<SettingsChangeListener>>()
    private val listenersLock = Any() // For thread safety
    
    fun addListener(listener: SettingsChangeListener) {
        synchronized(listenersLock) {
            // Remove any existing weak references that are null
            listeners.removeAll { it.get() == null }
            
            // Check if listener already exists
            val exists = listeners.any { it.get() == listener }
            if (!exists) {
                listeners.add(WeakReference(listener))
                Log.d(TAG, "Added settings listener. Total listeners: ${listeners.size}")
            }
        }
    }
    
    fun removeListener(listener: SettingsChangeListener) {
        synchronized(listenersLock) {
            listeners.removeAll { it.get() == listener || it.get() == null }
            Log.d(TAG, "Removed settings listener. Total listeners: ${listeners.size}")
        }
    }
    
    // Clean up null references periodically
    private fun cleanupListeners() {
        synchronized(listenersLock) {
            listeners.removeAll { it.get() == null }
        }
    }
    

    
    // Get SharedPreferences instance
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // Unified SharedPreferences for all app data
    private const val APP_PREFS_NAME = "StreamPrimePrefs"
    private const val STATUS_PREFS_NAME = "StreamStatus"
    
    private fun getAppPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private fun getStatusPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // Stream Status Management
    fun setStreamingStatus(context: Context, isStreaming: Boolean, streamType: String) {
        getStatusPrefs(context).edit()
            .putBoolean("${streamType}_streaming_status", isStreaming)
            .apply()
    }
    
    fun getStreamingStatus(context: Context, streamType: String): Boolean {
        return getStatusPrefs(context).getBoolean("${streamType}_streaming_status", false)
    }
    
    // App-wide preferences
    fun setAppPreference(context: Context, key: String, value: String) {
        getAppPrefs(context).edit().putString(key, value).apply()
    }
    
    fun getAppPreference(context: Context, key: String, defaultValue: String = ""): String {
        return getAppPrefs(context).getString(key, defaultValue) ?: defaultValue
    }
    
    // RTMP Settings
    fun getStreamingService(context: Context): String {
        return getPrefs(context).getString(KEY_STREAMING_SERVICE, "YouTube Live") ?: "YouTube Live"
    }
    
    fun setStreamingService(context: Context, service: String) {
        getPrefs(context).edit().putString(KEY_STREAMING_SERVICE, service).apply()
        notifyListeners()
        Log.d(TAG, "Streaming service updated to: $service")
    }
    
    fun getStreamUrl(context: Context): String {
        return getPrefs(context).getString(KEY_STREAM_URL, "rtmp://a.rtmp.youtube.com/live2/") ?: "rtmp://a.rtmp.youtube.com/live2/"
    }
    
    fun setStreamUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_STREAM_URL, url).apply()
        notifyListeners()
        Log.d(TAG, "Stream URL updated to: $url")
    }
    
    fun getStreamKey(context: Context): String {
        return getPrefs(context).getString(KEY_STREAM_KEY, "") ?: ""
    }
    
    fun setStreamKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_STREAM_KEY, key).apply()
        notifyListeners()
        Log.d(TAG, "Stream key updated")
    }
    
    // Service-specific stream key methods
    fun getYouTubeStreamKey(context: Context): String {
        return getPrefs(context).getString(KEY_YOUTUBE_STREAM_KEY, "") ?: ""
    }
    
    fun setYouTubeStreamKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_YOUTUBE_STREAM_KEY, key).apply()
        notifyListeners()
        Log.d(TAG, "YouTube stream key updated")
    }
    
    fun getFacebookStreamKey(context: Context): String {
        return getPrefs(context).getString(KEY_FACEBOOK_STREAM_KEY, "") ?: ""
    }
    
    fun setFacebookStreamKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_FACEBOOK_STREAM_KEY, key).apply()
        notifyListeners()
        Log.d(TAG, "Facebook stream key updated")
    }
    
    fun getTwitchStreamKey(context: Context): String {
        return getPrefs(context).getString(KEY_TWITCH_STREAM_KEY, "") ?: ""
    }
    
    fun setTwitchStreamKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_TWITCH_STREAM_KEY, key).apply()
        notifyListeners()
        Log.d(TAG, "Twitch stream key updated")
    }
    
    fun getInstagramStreamKey(context: Context): String {
        return getPrefs(context).getString(KEY_INSTAGRAM_STREAM_KEY, "") ?: ""
    }
    
    fun setInstagramStreamKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_INSTAGRAM_STREAM_KEY, key).apply()
        notifyListeners()
        Log.d(TAG, "Instagram stream key updated")
    }
    
    fun getTikTokStreamKey(context: Context): String {
        return getPrefs(context).getString(KEY_TIKTOK_STREAM_KEY, "") ?: ""
    }
    
    fun setTikTokStreamKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_TIKTOK_STREAM_KEY, key).apply()
        notifyListeners()
        Log.d(TAG, "TikTok stream key updated")
    }
    
    fun getCustomStreamKey(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_STREAM_KEY, "") ?: ""
    }
    
    fun setCustomStreamKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_STREAM_KEY, key).apply()
        notifyListeners()
        Log.d(TAG, "Custom stream key updated")
    }
    
    // Custom RTMP URL methods
    fun getCustomStreamUrl(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_STREAM_URL, "") ?: ""
    }
    
    fun setCustomStreamUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_STREAM_URL, url).apply()
        notifyListeners()
        Log.d(TAG, "Custom stream URL updated")
    }
    
    // Helper method to get stream key for current service
    fun getStreamKeyForService(context: Context, service: String): String {
        return when (service) {
            "YouTube Live" -> getYouTubeStreamKey(context)
            "Facebook Live" -> getFacebookStreamKey(context)
            "Twitch" -> getTwitchStreamKey(context)
            "Instagram Live" -> getInstagramStreamKey(context)
            "TikTok Live" -> getTikTokStreamKey(context)
            "Custom RTMP" -> getCustomStreamKey(context)
            else -> getStreamKey(context) // Fallback to old method
        }
    }
    
    // Helper method to set stream key for current service
    fun setStreamKeyForService(context: Context, service: String, key: String) {
        when (service) {
            "YouTube Live" -> setYouTubeStreamKey(context, key)
            "Facebook Live" -> setFacebookStreamKey(context, key)
            "Twitch" -> setTwitchStreamKey(context, key)
            "Instagram Live" -> setInstagramStreamKey(context, key)
            "TikTok Live" -> setTikTokStreamKey(context, key)
            "Custom RTMP" -> setCustomStreamKey(context, key)
            else -> setStreamKey(context, key) // Fallback to old method
        }
    }
    
    // Streaming Mode
    fun getStreamingMode(context: Context): String {
        return getPrefs(context).getString(KEY_STREAMING_MODE, "Landscape") ?: "Landscape"
    }
    
    fun setStreamingMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_STREAMING_MODE, mode).apply()
        notifyListeners()
        Log.d(TAG, "Streaming mode updated to: $mode")
    }
    
    // Landscape Settings
    fun getLandscapeWidth(context: Context): Int {
        return getPrefs(context).getInt(KEY_LANDSCAPE_WIDTH, 1280)
    }
    
    fun setLandscapeWidth(context: Context, width: Int) {
        getPrefs(context).edit().putInt(KEY_LANDSCAPE_WIDTH, width).apply()
        notifyListeners()
        Log.d(TAG, "Landscape width updated to: $width")
    }
    
    fun getLandscapeHeight(context: Context): Int {
        return getPrefs(context).getInt(KEY_LANDSCAPE_HEIGHT, 720)
    }
    
    fun setLandscapeHeight(context: Context, height: Int) {
        getPrefs(context).edit().putInt(KEY_LANDSCAPE_HEIGHT, height).apply()
        notifyListeners()
        Log.d(TAG, "Landscape height updated to: $height")
    }
    
    fun getLandscapeFps(context: Context): Int {
        return getPrefs(context).getInt(KEY_LANDSCAPE_FPS, 30)
    }
    
    fun setLandscapeFps(context: Context, fps: Int) {
        getPrefs(context).edit().putInt(KEY_LANDSCAPE_FPS, fps).apply()
        notifyListeners()
        Log.d(TAG, "Landscape FPS updated to: $fps")
    }
    
    fun getLandscapeBitrate(context: Context): Int {
        return getPrefs(context).getInt(KEY_LANDSCAPE_BITRATE, 2500)
    }
    
    fun setLandscapeBitrate(context: Context, bitrate: Int) {
        getPrefs(context).edit().putInt(KEY_LANDSCAPE_BITRATE, bitrate).apply()
        notifyListeners()
        Log.d(TAG, "Landscape bitrate updated to: $bitrate")
    }
    
    // Vertical Settings
    fun getVerticalWidth(context: Context): Int {
        return getPrefs(context).getInt(KEY_VERTICAL_WIDTH, 720)
    }
    
    fun setVerticalWidth(context: Context, width: Int) {
        getPrefs(context).edit().putInt(KEY_VERTICAL_WIDTH, width).apply()
        notifyListeners()
        Log.d(TAG, "Vertical width updated to: $width")
    }
    
    fun getVerticalHeight(context: Context): Int {
        return getPrefs(context).getInt(KEY_VERTICAL_HEIGHT, 1280)
    }
    
    fun setVerticalHeight(context: Context, height: Int) {
        getPrefs(context).edit().putInt(KEY_VERTICAL_HEIGHT, height).apply()
        notifyListeners()
        Log.d(TAG, "Vertical height updated to: $height")
    }
    
    fun getVerticalFps(context: Context): Int {
        return getPrefs(context).getInt(KEY_VERTICAL_FPS, 30)
    }
    
    fun setVerticalFps(context: Context, fps: Int) {
        getPrefs(context).edit().putInt(KEY_VERTICAL_FPS, fps).apply()
        notifyListeners()
        Log.d(TAG, "Vertical FPS updated to: $fps")
    }
    
    fun getVerticalBitrate(context: Context): Int {
        return getPrefs(context).getInt(KEY_VERTICAL_BITRATE, 2000)
    }
    
    fun setVerticalBitrate(context: Context, bitrate: Int) {
        getPrefs(context).edit().putInt(KEY_VERTICAL_BITRATE, bitrate).apply()
        notifyListeners()
        Log.d(TAG, "Vertical bitrate updated to: $bitrate")
    }
    
    // Bulk operations
    fun saveAllSettings(context: Context, settings: Map<String, Any>) {
        val editor = getPrefs(context).edit()
        
        settings.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        
        editor.apply()
        notifyListeners()
        Log.d(TAG, "Bulk settings saved: ${settings.keys.joinToString()}")
    }
    
    // Batch operations with delayed notification
    private var batchUpdateInProgress = false
    private var pendingNotification = false
    
    fun startBatchUpdate() {
        batchUpdateInProgress = true
        pendingNotification = false
        Log.d(TAG, "Batch update started")
    }
    
    fun endBatchUpdate() {
        batchUpdateInProgress = false
        if (pendingNotification) {
            notifyListeners()
            pendingNotification = false
        }
        Log.d(TAG, "Batch update ended")
    }
    
    private fun notifyListeners() {
        if (batchUpdateInProgress) {
            pendingNotification = true
            Log.d(TAG, "Notification deferred during batch update")
            return
        }
        
        Log.d(TAG, "Notifying ${listeners.size} listeners of settings change")
        synchronized(listenersLock) {
            listeners.forEach { listenerRef ->
                val listener = listenerRef.get()
                if (listener != null) {
                    try {
                        listener.onSettingsChanged()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notifying listener: ${e.message}")
                    }
                }
            }
        }
        cleanupListeners() // Clean up any null references after notification
    }
    
    fun getAllSettings(context: Context): Map<String, Any> {
        val prefs = getPrefs(context)
        return mapOf(
            KEY_STREAMING_SERVICE to getStreamingService(context),
            KEY_STREAM_URL to getStreamUrl(context),
            KEY_STREAM_KEY to getStreamKey(context),
            KEY_STREAMING_MODE to getStreamingMode(context),
            KEY_LANDSCAPE_WIDTH to getLandscapeWidth(context),
            KEY_LANDSCAPE_HEIGHT to getLandscapeHeight(context),
            KEY_LANDSCAPE_FPS to getLandscapeFps(context),
            KEY_LANDSCAPE_BITRATE to getLandscapeBitrate(context),
            KEY_VERTICAL_WIDTH to getVerticalWidth(context),
            KEY_VERTICAL_HEIGHT to getVerticalHeight(context),
            KEY_VERTICAL_FPS to getVerticalFps(context),
            KEY_VERTICAL_BITRATE to getVerticalBitrate(context)
        )
    }
    
    fun resetToDefaults(context: Context) {
        val editor = getPrefs(context).edit()
        
        // Reset to default values
        editor.putString(KEY_STREAMING_SERVICE, "YouTube Live")
        editor.putString(KEY_STREAM_URL, "rtmp://a.rtmp.youtube.com/live2/")
        editor.putString(KEY_STREAM_KEY, "")
        editor.putString(KEY_STREAMING_MODE, "Landscape")
        
        // Landscape defaults
        editor.putInt(KEY_LANDSCAPE_WIDTH, 1280)
        editor.putInt(KEY_LANDSCAPE_HEIGHT, 720)
        editor.putInt(KEY_LANDSCAPE_FPS, 30)
        editor.putInt(KEY_LANDSCAPE_BITRATE, 2500)
        
        // Vertical defaults
        editor.putInt(KEY_VERTICAL_WIDTH, 720)
        editor.putInt(KEY_VERTICAL_HEIGHT, 1280)
        editor.putInt(KEY_VERTICAL_FPS, 30)
        editor.putInt(KEY_VERTICAL_BITRATE, 2000)
        
        editor.apply()
        notifyListeners()
        Log.d(TAG, "Settings reset to defaults")
    }
} 