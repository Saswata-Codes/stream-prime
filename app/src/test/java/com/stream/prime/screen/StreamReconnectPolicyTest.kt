package com.stream.prime.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamReconnectPolicyTest {

  @Test
  fun `active current service can reconnect inside retry window`() {
    assertTrue(StreamReconnectPolicy.shouldReconnect(true, false, true, 1_000L, 2_000L))
  }

  @Test
  fun `manual stop disables reconnect`() {
    assertFalse(StreamReconnectPolicy.shouldReconnect(false, false, true, 1_000L, 2_000L))
  }

  @Test
  fun `destroyed or replaced service cannot reconnect`() {
    assertFalse(StreamReconnectPolicy.shouldReconnect(true, true, true, 1_000L, 2_000L))
    assertFalse(StreamReconnectPolicy.shouldReconnect(true, false, false, 1_000L, 2_000L))
  }

  @Test
  fun `expired retry window disables reconnect`() {
    assertFalse(StreamReconnectPolicy.shouldReconnect(true, false, true, 2_000L, 2_000L))
  }
}
