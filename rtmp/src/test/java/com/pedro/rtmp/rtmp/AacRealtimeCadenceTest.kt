package com.pedro.rtmp.rtmp

import com.pedro.common.frame.MediaFrame
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.audio.packet.AacPacket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class AacRealtimeCadenceTest {

  @Test
  fun `RTMP emits first AAC samples and preserves 48 kHz access unit cadence`() = runTest {
    var nowUs = 10_000_000L
    val normalizer = RealtimeTimestampNormalizer { nowUs }
    val packetizer = AacPacket().apply { sendAudioInfo(48_000, true) }
    val packets = mutableListOf<FlvPacket>()
    val sourceStartUs = 5_000_000L
    val sourceTimestampsUs = listOf(
      sourceStartUs,
      sourceStartUs + 21_333L,
      sourceStartUs + 42_666L,
      sourceStartUs + 64_000L
    )

    sourceTimestampsUs.forEach { sourceTimestampUs ->
      val normalizedTimestampUs = normalizer.normalize(sourceTimestampUs, MediaFrame.Type.AUDIO)
      val bytes = ByteArray(683) { 1 }
      val frame = MediaFrame(
        ByteBuffer.wrap(bytes),
        MediaFrame.Info(0, bytes.size, normalizedTimestampUs, false),
        MediaFrame.Type.AUDIO
      )
      packetizer.createFlvPacket(frame) { packets += it }
      nowUs += 200L // Simulate multiple codec callbacks delivered in one burst.
    }

    // Sequence header + all four raw access units. No first-frame loss.
    assertEquals(5, packets.size)
    assertEquals(AacPacket.Type.SEQUENCE.mark, packets[0].buffer[1])
    packets.drop(1).forEach { assertEquals(AacPacket.Type.RAW.mark, it.buffer[1]) }
    assertEquals(listOf(0L, 0L, 21L, 42L, 64L), packets.map { it.timeStamp })
  }
}
