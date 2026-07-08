package com.stream.prime.utils

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Centralized error handling utility for consistent error management across the app
 */
object ErrorHandler {
    private const val TAG = "ErrorHandler"
    
    /**
     * Handle errors with consistent logging and user feedback
     */
    fun handleError(context: Context, error: Throwable, operation: String, showUserMessage: Boolean = true) {
        // Log the error
        Log.e(TAG, "Error during $operation: ${error.message}", error)
        
        // Show user-friendly message if requested
        if (showUserMessage) {
            val userMessage = when (error) {
                is IllegalArgumentException -> "Invalid configuration: ${error.message}"
                is IllegalStateException -> "Operation not allowed: ${error.message}"
                is SecurityException -> "Permission denied: ${error.message}"
                is OutOfMemoryError -> "Insufficient memory for operation"
                else -> "An error occurred: ${error.message}"
            }
            
            Toast.makeText(context, userMessage, Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Handle network-related errors
     */
    fun handleNetworkError(context: Context, error: Throwable) {
        Log.e(TAG, "Network error: ${error.message}", error)
        Toast.makeText(context, "Network connection failed. Please check your internet connection.", Toast.LENGTH_LONG).show()
    }
    
    /**
     * Handle streaming errors
     */
    fun handleStreamingError(context: Context, error: Throwable) {
        Log.e(TAG, "Streaming error: ${error.message}", error)
        Toast.makeText(context, "Streaming failed. Please try again.", Toast.LENGTH_LONG).show()
    }
    
    /**
     * Handle permission errors
     */
    fun handlePermissionError(context: Context, permission: String) {
        Log.e(TAG, "Permission denied: $permission")
        Toast.makeText(context, "Permission required: $permission", Toast.LENGTH_LONG).show()
    }
    
    /**
     * Safe execution with error handling
     */
    inline fun <T> safeExecute(
        context: Context,
        operation: String,
        showUserMessage: Boolean = true,
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (e: Exception) {
            handleError(context, e, operation, showUserMessage)
            null
        }
    }
} 