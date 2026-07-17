package com.stream.prime.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Permission Manager for handling all required permissions
 * for enhanced audio recording and screen capture functionality
 */
object PermissionManager {
    
    private const val TAG = "PermissionManager"
    
    // Permission request codes
    const val REQUEST_RECORD_AUDIO = 1001
    const val REQUEST_WRITE_EXTERNAL_STORAGE = 1002
    const val REQUEST_SYSTEM_ALERT_WINDOW = 1003
    
    /**
     * Check if microphone permission is granted
     */
    fun isMicrophonePermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if storage permission is granted
     */
    fun isStoragePermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+, storage permission is not required for app-specific storage
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if system alert window permission is granted
     */
    fun isSystemAlertWindowPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun areAllPermissionsGranted(context: Context): Boolean {
        return isMicrophonePermissionGranted(context) &&
                isStoragePermissionGranted(context)
    }
    
    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(context: Context): List<String> {
        val missingPermissions = mutableListOf<String>()
        
        if (!isMicrophonePermissionGranted(context)) {
            missingPermissions.add("Microphone")
        }
        
        if (!isStoragePermissionGranted(context)) {
            missingPermissions.add("Storage")
        }
        
        return missingPermissions
    }
    
    /**
     * Open system alert window settings
     */
    fun openSystemAlertWindowSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening system alert window settings: ${e.message}")
        }
    }
    
    /**
     * Get permission status summary
     */
    fun getPermissionStatusSummary(context: Context): String {
        val status = StringBuilder()
        
        status.append("Microphone: ${if (isMicrophonePermissionGranted(context)) "✅" else "❌"}\n")
        status.append("Storage: ${if (isStoragePermissionGranted(context)) "✅" else "❌"}")
        
        return status.toString()
    }
    
    /**
     * Log current permission status
     */
    fun logPermissionStatus(context: Context) {
        Log.d(TAG, "Permission Status:")
        Log.d(TAG, "Microphone: ${isMicrophonePermissionGranted(context)}")
        Log.d(TAG, "Storage: ${isStoragePermissionGranted(context)}")
    }
}
