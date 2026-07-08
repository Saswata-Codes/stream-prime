package com.stream.prime.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.os.Build

/**
 * Utility class to prevent common crashes and provide safe operations
 */
object CrashPrevention {
    private const val TAG = "CrashPrevention"
    
    /**
     * Safely configure fullscreen for an activity
     */
    fun safeConfigureFullscreen(activity: Activity) {
        try {
            val window = activity.window
            if (window?.decorView == null) {
                Log.w(TAG, "Window or decor view not available")
                return
            }
            
            window.decorView.post {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.insetsController?.let { controller ->
                            controller.hide(WindowInsets.Type.statusBars())
                            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            WindowManager.LayoutParams.FLAG_FULLSCREEN
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error configuring fullscreen: ${e.message}")
                    // Fallback
                    try {
                        @Suppress("DEPRECATION")
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            WindowManager.LayoutParams.FLAG_FULLSCREEN
                        )
                    } catch (fallbackException: Exception) {
                        Log.e(TAG, "Fallback fullscreen also failed: ${fallbackException.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in safeConfigureFullscreen: ${e.message}")
        }
    }
    
    /**
     * Safely execute a block of code with error handling
     */
    fun <T> safeExecute(
        context: Context,
        operation: String,
        defaultValue: T? = null,
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Error in $operation: ${e.message}")
            ErrorHandler.handleError(context, e, operation, false)
            defaultValue
        }
    }
    
    /**
     * Check if activity is still valid
     */
    fun isActivityValid(activity: Activity?): Boolean {
        return activity != null && !activity.isFinishing && !activity.isDestroyed
    }
} 