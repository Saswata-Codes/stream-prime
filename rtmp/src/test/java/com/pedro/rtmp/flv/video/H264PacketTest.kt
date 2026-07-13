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

package com.pedro.rtmp.flv.video

import com.pedro.common.frame.MediaFrame
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.flv.video.packet.H264Packet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Created by pedro on 9/9/23.
 */
class H264PacketTest {

  @Test
  fun `GIVEN a h264 buffer WHEN call create a h264 packet 1 time THEN return config and expected buffer`() = runTest {
    val timestamp = 123456789L
    val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x05)
    val fakeH264 = header.plus(ByteArray(300) { 0x00 })
    val expectedConfig = byteArrayOf(23, 0, 0, 0, 0, 1, 100, 0, 30, -1, -31, 0, 17, 103, 100, 0, 30, -84, -76, 15, 2, -115, 53, 2, 2, 2, 7, -117, 23, 8, 1, 0, 4, 104, -18, 13, -117)
    val expectedFlvPacket = byteArrayOf(23, 1, 0, 0, 0, 0, 0, 1, 45, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    val info = MediaFrame.Info(0, fakeH264.size, timestamp, true)
    val h264Packet = H264Packet()
    val sps = byteArrayOf(103, 100, 0, 30, -84, -76, 15, 2, -115, 53, 2, 2, 2, 7, -117, 23, 8)
    val pps = byteArrayOf(104, -18, 13, -117)
    val mediaFrame = MediaFrame(ByteBuffer.wrap(fakeH264), info, MediaFrame.Type.VIDEO)
    h264Packet.sendVideoInfo(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))
    val frames = mutableListOf<FlvPacket>()
    h264Packet.createFlvPacket(mediaFrame) { flvPacket ->
      assertEquals(FlvType.VIDEO, flvPacket.type)
      frames.add(flvPacket)
    }

    assertEquals(2, frames.size)
    assertArrayEquals(expectedConfig, frames[0].buffer)
    assertArrayEquals(expectedFlvPacket, frames[1].buffer)
  }

  @Test
  fun `GIVEN multiple annex b nal units WHEN create packet THEN length prefix every nal`() = runTest {
    val aud = byteArrayOf(0x09, 0x10)
    val sei = byteArrayOf(0x06, 0x05, 0x01)
    val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33)
    val accessUnit = byteArrayOf(0, 0, 0, 1).plus(aud)
      .plus(byteArrayOf(0, 0, 1)).plus(sei)
      .plus(byteArrayOf(0, 0, 0, 1)).plus(idr)
    val mediaFrame = MediaFrame(
      ByteBuffer.wrap(accessUnit),
      MediaFrame.Info(0, accessUnit.size, 2_000_000L, false),
      MediaFrame.Type.VIDEO
    )
    val h264Packet = H264Packet().apply {
      sendVideoInfo(
        ByteBuffer.wrap(byteArrayOf(0x67, 0x64, 0x00, 0x29)),
        ByteBuffer.wrap(byteArrayOf(0x68, 0x00))
      )
    }
    val packets = mutableListOf<FlvPacket>()

    h264Packet.createFlvPacket(mediaFrame) { packets.add(it) }

    assertEquals(2, packets.size)
    assertArrayEquals(
      byteArrayOf(
        0x17, 0x01, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x02, 0x09, 0x10,
        0x00, 0x00, 0x00, 0x03, 0x06, 0x05, 0x01,
        0x00, 0x00, 0x00, 0x04, 0x65, 0x11, 0x22, 0x33
      ),
      packets[1].buffer
    )
    assertEquals(2_000, packets[1].timeStamp)
  }

}
