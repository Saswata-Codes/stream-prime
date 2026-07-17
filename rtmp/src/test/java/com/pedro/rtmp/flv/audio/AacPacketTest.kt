/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.rtmp.flv.audio

import com.pedro.common.frame.MediaFrame
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.flv.audio.packet.AacPacket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Created by pedro on 9/9/23.
 */
class AacPacketTest {

  @Test
  fun `first aac buffer emits config followed by raw audio without dropping samples`() = runTest {
    val timestamp = 123456789L
    val buffer = ByteArray(256) { 0x00 }
    val info = MediaFrame.Info(0, buffer.size, timestamp, false)
    val mediaFrame = MediaFrame(ByteBuffer.wrap(buffer), info, MediaFrame.Type.AUDIO)
    val aacPacket = AacPacket()
    aacPacket.sendAudioInfo(32000, true)
    val packets = mutableListOf<com.pedro.rtmp.flv.FlvPacket>()
    aacPacket.createFlvPacket(mediaFrame) { packets += it }

    assertEquals(2, packets.size)
    packets[0].let { flvPacket ->
      assertEquals(FlvType.AUDIO, flvPacket.type)
      assertEquals((-81).toByte(), flvPacket.buffer[0])
      assertEquals(AacPacket.Type.SEQUENCE.mark, flvPacket.buffer[1])
    }
    packets[1].let { flvPacket ->
      assertEquals(FlvType.AUDIO, flvPacket.type)
      assertEquals((-81).toByte(), flvPacket.buffer[0])
      assertEquals(AacPacket.Type.RAW.mark, flvPacket.buffer[1])
      assertEquals(buffer.size + 2, flvPacket.length)
    }
  }
}
