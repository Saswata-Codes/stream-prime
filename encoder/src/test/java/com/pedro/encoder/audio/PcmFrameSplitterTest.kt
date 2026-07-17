package com.pedro.encoder.audio

import com.pedro.encoder.Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameSplitterTest {

  @Test
  fun `oversized reusable stereo buffer becomes immutable AAC frames`() {
    val sourceBytes = ByteArray(8_192) { (it and 0xff).toByte() }
    val source = Frame(sourceBytes, 0, sourceBytes.size, 5_000_000L)

    val frames = PcmFrameSplitter.splitForAac(source, 48_000, true)

    assertEquals(2, frames.size)
    assertEquals(4_096, frames[0].size)
    assertEquals(4_096, frames[1].size)
    assertEquals(5_000_000L, frames[0].timeStamp)
    assertEquals(5_021_333L, frames[1].timeStamp)
    assertArrayEquals(sourceBytes.copyOfRange(0, 4_096), frames[0].buffer)
    assertArrayEquals(sourceBytes.copyOfRange(4_096, 8_192), frames[1].buffer)

    sourceBytes.fill(0)
    assertEquals(1.toByte(), frames[0].buffer[1])
    assertEquals(1.toByte(), frames[1].buffer[1])
  }

  @Test
  fun `mono split uses one 1024 sample channel per frame`() {
    val sourceBytes = ByteArray(4_096) { 7 }
    val frames = PcmFrameSplitter.splitForAac(
      Frame(sourceBytes, 0, sourceBytes.size, 0L),
      48_000,
      false
    )

    assertEquals(2, frames.size)
    assertEquals(2_048, frames[0].size)
    assertEquals(21_333L, frames[1].timeStamp)
  }
}
