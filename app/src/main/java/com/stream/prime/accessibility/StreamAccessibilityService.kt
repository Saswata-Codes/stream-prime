package com.stream.prime.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.stream.prime.screen.ScreenService

/**
 * Accessibility Service for enhanced screen capture and audio recording
 * This service provides additional capabilities for capturing screen content
 * and managing audio recording in background scenarios
 */
class StreamAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamAccessibilityService"
        var INSTANCE: StreamAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE = this
        
        // Configure the accessibility service
        val info = AccessibilityServiceInfo().apply {
            // Set the types of events this service wants to listen to
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            
            // Set the flags for the types of feedback this service provides
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or 
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            
            // Set the feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            // Set the notification timeout
            notificationTimeout = 100
        }
        
        serviceInfo = info
        
        Log.d(TAG, "StreamAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            // Handle accessibility events for enhanced screen capture
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(it)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    handleWindowContentChanged(it)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleViewClicked(it)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "StreamAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        INSTANCE = null
        Log.d(TAG, "StreamAccessibilityService destroyed")
    }

    /**
     * Handle window state changes for enhanced screen capture
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        
        Log.d(TAG, "Window state changed - Package: $packageName, Class: $className")
        
        // Notify the screen service about window changes
        ScreenService.INSTANCE?.let { service ->
            service.onWindowStateChanged(packageName, className)
        }
    }

    /**
     * Handle window content changes for enhanced screen capture
     */
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        
        Log.d(TAG, "Window content changed - Package: $packageName")
        
        // Notify the screen service about content changes
        ScreenService.INSTANCE?.let { service ->
            service.onWindowContentChanged(packageName)
        }
    }

    /**
     * Handle view clicks for enhanced interaction tracking
     */
    private fun handleViewClicked(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        
        Log.d(TAG, "View clicked - Package: $packageName, Class: $className")
        
        // Notify the screen service about user interactions
        ScreenService.INSTANCE?.let { service ->
            service.onUserInteraction(packageName, className)
        }
    }

    /**
     * Check if accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        return INSTANCE != null
    }

    /**
     * Get current window information for enhanced capture
     */
    fun getCurrentWindowInfo(): String? {
        return try {
            val rootNode = rootInActiveWindow
            rootNode?.let {
                "Package: ${it.packageName}, Class: ${it.className}, Text: ${it.text}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting window info: ${e.message}")
            null
        }
    }
} 