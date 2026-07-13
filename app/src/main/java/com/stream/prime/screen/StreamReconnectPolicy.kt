package com.stream.prime.screen

/** Pure reconnect gate shared by callbacks and delayed retry tasks. */
internal object StreamReconnectPolicy {
  fun shouldReconnect(
    streamRequested: Boolean,
    serviceDestroyed: Boolean,
    isCurrentService: Boolean,
    nowMs: Long,
    reconnectEndTimeMs: Long
  ): Boolean {
    return streamRequested &&
      !serviceDestroyed &&
      isCurrentService &&
      nowMs < reconnectEndTimeMs
  }
}
