package com.stream.prime

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class PersistentOverlayService : Service() {

    companion object {
        private const val TAG = "PersistentOverlayService"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var persistentView: TextView

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Persistent overlay service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Persistent overlay service started")
        createPersistentOverlay()
        return START_STICKY
    }

    private fun createPersistentOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                    
                    persistentView = TextView(this).apply {
                        text = ""
                        setTextColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    
                    val params = WindowManager.LayoutParams().apply {
                        width = 1
                        height = 1
                        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        format = PixelFormat.TRANSLUCENT
                        gravity = Gravity.TOP or Gravity.START
                        x = -1000
                        y = -1000
                    }
                    
                    windowManager.addView(persistentView, params)
                    Log.d(TAG, "Persistent overlay created successfully")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating persistent overlay: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::windowManager.isInitialized && ::persistentView.isInitialized) {
                windowManager.removeView(persistentView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing persistent overlay: ${e.message}")
        }
        Log.d(TAG, "Persistent overlay service destroyed")
    }
} 