package com.stream.prime

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.stream.prime.screen.ScreenService
import com.stream.prime.settings.StreamSettingsActivity

class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlayService"
        var INSTANCE: FloatingOverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        Log.d(TAG, "Floating overlay service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Floating overlay service started")
        createFloatingWindow()
        return START_STICKY
    }

    private fun createFloatingWindow() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            // Inflate the floating view layout
            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_overlay, null)
            
            // Set up window parameters
            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            }
            
            // Add touch listener for dragging
            floatingView.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        true
                    }
                    else -> false
                }
            }
            
            // Set up button click listeners
            setupButtonListeners()
            
            // Add the view to window manager
            windowManager.addView(floatingView, params)
            
            Log.d(TAG, "Floating window created successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating floating window: ${e.message}")
            Toast.makeText(this, "Error creating floating controls", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtonListeners() {
        // Stream start/stop button
        floatingView.findViewById<TextView>(R.id.btn_floating_stream).setOnClickListener {
            toggleStream()
        }
        
        // Settings button
        floatingView.findViewById<TextView>(R.id.btn_floating_settings).setOnClickListener {
            openSettings()
        }
        
        // Close button
        floatingView.findViewById<TextView>(R.id.btn_floating_close).setOnClickListener {
            stopSelf()
        }
        
        // Update button states
        updateFloatingButtonStates()
    }

    private fun toggleStream() {
        try {
            val service = ScreenService.INSTANCE
            if (service != null) {
                if (service.isStreaming()) {
                    service.stopStream()
                    updateFloatingButtonStates()
                    Toast.makeText(this, "Stream stopped", Toast.LENGTH_SHORT).show()
                } else {
                    // Start stream - this will trigger the normal stream flow
                    Toast.makeText(this, "Please start stream from main app", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Streaming service not available", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling stream: ${e.message}")
            Toast.makeText(this, "Error controlling stream", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() {
        try {
            val intent = Intent(this, StreamSettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening settings: ${e.message}")
            Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFloatingButtonStates() {
        try {
            val service = ScreenService.INSTANCE
            val streamButton = floatingView.findViewById<TextView>(R.id.btn_floating_stream)
            
            if (service != null && service.isStreaming()) {
                streamButton.text = "⏹"
                streamButton.setBackgroundResource(R.drawable.prism_circular_button_red)
            } else {
                streamButton.text = "▶"
                streamButton.setBackgroundResource(R.drawable.prism_circular_button)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating floating button states: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::windowManager.isInitialized && ::floatingView.isInitialized) {
                windowManager.removeView(floatingView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing floating window: ${e.message}")
        }
        INSTANCE = null
        Log.d(TAG, "Floating overlay service destroyed")
    }
} 