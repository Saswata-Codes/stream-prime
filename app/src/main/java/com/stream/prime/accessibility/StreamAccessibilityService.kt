package com.stream.prime.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.stream.prime.screen.ScreenService

/**
 * Opt-in, sideload-focused microphone sharing compatibility service.
 *
 * The service contributes its accessibility capture identity and, only during an active user
 * recording/stream, holds a priority binding to the already-running ScreenService process. It
 * never inspects windows, handles accessibility events, starts ScreenService, or owns
 * MediaProjection. In particular it must not add TYPE_ACCESSIBILITY_OVERLAY windows: Android 15
 * builds from some vendors demote an active mediaProjection foreground service when such a window
 * is attached, immediately revoking screen capture. ScreenService supplies the visible foreground
 * notification and opens the microphone with VOICE_RECOGNITION while this service is connected.
 */
class StreamAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamAccessibilityService"
        private const val ACTION_SESSION_PROTECTION_CHANGED =
            "com.stream.prime.accessibility.SESSION_PROTECTION_CHANGED"
        private const val EXTRA_SESSION_PROTECTION_ACTIVE = "session_protection_active"
        private const val SESSION_PROTECTION_PREFS = "CaptureSessionProtection"
        private const val KEY_SESSION_PROTECTION_REQUESTED = "session_protection_requested"

        @Volatile
        private var captureActive = false

        /**
         * Query Android rather than a process-local singleton. The service deliberately runs in
         * :mic_share so its system binding cannot change ScreenService's foreground state.
         */
        fun isConnected(context: Context): Boolean {
            val expected = ComponentName(context, StreamAccessibilityService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo.serviceInfo
                    serviceInfo.packageName == expected.packageName &&
                        serviceInfo.name == expected.className
                }
        }

        /**
         * Android's secure setting is the authoritative user-toggle state. Some vendor builds
         * update it before the AccessibilityManager list is rebound, and some omit an enabled
         * zero-event service from that list entirely. Accept either signal so returning from
         * Accessibility Settings cannot immediately undo a switch the user just enabled.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, StreamAccessibilityService::class.java)
            val enabledBySystem = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                    .orEmpty()
                    .split(':')
                    .mapNotNull(ComponentName::unflattenFromString)
                    .any { it == expected }
            }.onFailure {
                Log.w(TAG, "Unable to read enabled Accessibility services", it)
            }.getOrDefault(false)

            return enabledBySystem || isConnected(context)
        }

        /** Kept as the public status API used by the settings screen. */
        fun isCompatibilityOverlayVisible(context: Context): Boolean =
            captureActive && isEnabled(context)

        /**
         * Mark capture active without creating another window. Returning false while activating
         * means the user still needs to enable Stream Prime Mic Share in Accessibility settings.
         */
        fun setCaptureActive(context: Context, active: Boolean): Boolean {
            captureActive = active
            if (!active) return true
            return isEnabled(context)
        }

        /**
         * Tell the real accessibility-service process to protect an already-running capture.
         * The state is committed before broadcasting so a newly connected accessibility process
         * can recover the request. This never starts ScreenService because a MediaProjection grant
         * cannot be recreated safely after process death.
         */
        fun setSessionProtectionActive(context: Context, active: Boolean) {
            val appContext = context.applicationContext
            appContext.getSharedPreferences(SESSION_PROTECTION_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SESSION_PROTECTION_REQUESTED, active)
                .commit()
            appContext.sendBroadcast(
                Intent(ACTION_SESSION_PROTECTION_CHANGED)
                    .setPackage(appContext.packageName)
                    .putExtra(EXTRA_SESSION_PROTECTION_ACTIVE, active)
            )
        }

        private fun isSessionProtectionRequested(context: Context): Boolean =
            context.getSharedPreferences(SESSION_PROTECTION_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SESSION_PROTECTION_REQUESTED, false)
    }

    private var guardReceiverRegistered = false
    private var captureServiceBound = false
    private var sessionProtectionRequested = false

    private val captureServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            captureServiceBound = true
            Log.i(TAG, "Active capture process protected by Mic Share priority binding")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureServiceBound = false
            Log.w(TAG, "Capture process priority binding disconnected")
        }
    }

    private val sessionProtectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SESSION_PROTECTION_CHANGED) return
            updateSessionProtection(
                intent.getBooleanExtra(EXTRA_SESSION_PROTECTION_ACTIVE, false)
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // No window content and no event processing are needed for audio compatibility.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 0
        }

        if (!guardReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                sessionProtectionReceiver,
                IntentFilter(ACTION_SESSION_PROTECTION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            guardReceiverRegistered = true
        }
        updateSessionProtection(isSessionProtectionRequested(this))

        Log.i(TAG, "Game voice-chat compatibility service connected")
    }

    private fun updateSessionProtection(active: Boolean) {
        sessionProtectionRequested = active
        if (active) bindToRunningCaptureService() else unbindCaptureService()
    }

    /**
     * Bind without BIND_AUTO_CREATE. A killed MediaProjection service cannot resume without fresh
     * user consent, so the accessibility process must never create a fake/stale capture service.
     */
    private fun bindToRunningCaptureService() {
        if (captureServiceBound) return
        captureServiceBound = runCatching {
            bindService(
                Intent(this, ScreenService::class.java),
                captureServiceConnection,
                Context.BIND_IMPORTANT or Context.BIND_ABOVE_CLIENT
            )
        }.onFailure {
            Log.w(TAG, "Unable to attach capture process priority binding", it)
        }.getOrDefault(false)

        if (!captureServiceBound && sessionProtectionRequested) {
            Log.w(TAG, "No running capture service available for session protection")
        }
    }

    private fun unbindCaptureService() {
        if (!captureServiceBound) return
        runCatching { unbindService(captureServiceConnection) }
            .onFailure { Log.w(TAG, "Unable to release capture process priority binding", it) }
        captureServiceBound = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: Stream Prime never reads or reacts to another app's UI.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Game voice-chat compatibility service interrupted")
    }

    override fun onDestroy() {
        unbindCaptureService()
        if (guardReceiverRegistered) {
            runCatching { unregisterReceiver(sessionProtectionReceiver) }
                .onFailure { Log.w(TAG, "Unable to unregister session protection receiver", it) }
            guardReceiverRegistered = false
        }
        super.onDestroy()
        Log.i(TAG, "Game voice-chat compatibility service destroyed")
    }
}
