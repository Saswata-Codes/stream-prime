package com.pedro.common

import com.pedro.common.frame.MediaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StreamBlockingQueueTest {

  @Test
  fun `queue enforces its configured frame capacity`() {
    val queue = StreamBlockingQueue(2)

    assertTrue(queue.trySend(frame(0L, MediaFrame.Type.VIDEO)).accepted)
    assertTrue(queue.trySend(frame(10_000L, MediaFrame.Type.VIDEO)).accepted)
    assertFalse(queue.trySend(frame(20_000L, MediaFrame.Type.VIDEO)).accepted)
    assertEquals(0, queue.remainingCapacity())
    assertEquals(2, queue.getSize())
  }

  @Test
  fun `audio evicts disposable video instead of waiting behind a full video queue`() {
    val queue = StreamBlockingQueue(2)
    queue.trySend(frame(0L, MediaFrame.Type.VIDEO, isKeyFrame = true))
    queue.trySend(frame(10_000L, MediaFrame.Type.VIDEO))

    val result = queue.trySend(frame(20_000L, MediaFrame.Type.AUDIO))

    assertTrue(result.accepted)
    assertEquals(1, result.evictedVideoFrames)
    assertEquals(0, result.evictedAudioFrames)
    assertEquals(2, queue.getSize())
  }

  @Test
  fun `audio removes stale inter frames before they create live latency`() {
    val queue = StreamBlockingQueue(5)
    queue.trySend(frame(0L, MediaFrame.Type.VIDEO, isKeyFrame = true))
    queue.trySend(frame(100_000L, MediaFrame.Type.VIDEO))
    queue.trySend(frame(900_000L, MediaFrame.Type.VIDEO))

    val result = queue.trySend(frame(1_000_000L, MediaFrame.Type.AUDIO))

    assertTrue(result.accepted)
    assertEquals(1, result.evictedVideoFrames)
    assertEquals(3, queue.getSize())
  }

  private fun frame(
    timestampUs: Long,
    type: MediaFrame.Type,
    isKeyFrame: Boolean = false
  ): MediaFrame {
    return MediaFrame(
      ByteBuffer.wrap(byteArrayOf(1)),
      MediaFrame.Info(offset = 0, size = 1, timestamp = timestampUs, isKeyFrame = isKeyFrame),
      type
    )
  }
}
