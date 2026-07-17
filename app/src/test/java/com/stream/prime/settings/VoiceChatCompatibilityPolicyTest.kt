package com.stream.prime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChatCompatibilityPolicyTest {

    @Test
    fun `connected Mic Share allows compatibility mode`() {
        val state = VoiceChatCompatibilityPolicy.resolve(
            requestedEnabled = true,
            accessibilityEnabled = false,
            accessibilityConnected = true
        )

        assertTrue(state.enabled)
        assertFalse(state.shouldOpenAccessibilitySettings)
    }

    @Test
    fun `system enabled toggle survives delayed service connection`() {
        val state = VoiceChatCompatibilityPolicy.resolve(
            requestedEnabled = true,
            accessibilityEnabled = true,
            accessibilityConnected = false
        )

        assertTrue(state.enabled)
        assertFalse(state.shouldOpenAccessibilitySettings)
    }

    @Test
    fun `missing Mic Share turns compatibility off and requests settings`() {
        val state = VoiceChatCompatibilityPolicy.resolve(
            requestedEnabled = true,
            accessibilityEnabled = false,
            accessibilityConnected = false
        )

        assertFalse(state.enabled)
        assertTrue(state.shouldOpenAccessibilitySettings)
    }

    @Test
    fun `user disabled mode does not open accessibility settings`() {
        val state = VoiceChatCompatibilityPolicy.resolve(
            requestedEnabled = false,
            accessibilityEnabled = false,
            accessibilityConnected = false
        )

        assertFalse(state.enabled)
        assertFalse(state.shouldOpenAccessibilitySettings)
    }
}
