package com.stream.prime.settings

/**
 * Voice-chat compatibility is usable only while Android has the Mic Share accessibility service
 * enabled. A stale saved switch must never be presented as active without that system grant.
 */
internal object VoiceChatCompatibilityPolicy {

    data class State(
        val enabled: Boolean,
        val shouldOpenAccessibilitySettings: Boolean
    )

    fun resolve(
        requestedEnabled: Boolean,
        accessibilityEnabled: Boolean,
        accessibilityConnected: Boolean
    ): State {
        // The system toggle can become authoritative before the service appears in
        // AccessibilityManager's bound list. Either signal is enough to preserve the user's
        // choice; both being false means Mic Share is genuinely off.
        val accessibilityAvailable = accessibilityEnabled || accessibilityConnected
        return State(
            enabled = requestedEnabled && accessibilityAvailable,
            shouldOpenAccessibilitySettings = requestedEnabled && !accessibilityAvailable
        )
    }
}
