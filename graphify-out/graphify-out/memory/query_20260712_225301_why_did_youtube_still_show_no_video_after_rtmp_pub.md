---
type: "query"
date: "2026-07-12T22:53:01.063399+00:00"
question: "Why did YouTube still show no video after RTMP publish and AVC profile-level were corrected?"
contributor: "graphify"
source_nodes: ["H264Packet", "RtmpSender", "RtmpClient", "VideoSpecificConfigAVC", "ScreenService"]
---

# Q: Why did YouTube still show no video after RTMP publish and AVC profile-level were corrected?

## Answer

The new log confirms the previous codec fix is active: AVC High Level 4.1 at 1080x1920 30fps. YouTube returns NetStream.Publish.Start, receives the AVC sequence header, audio, periodic large keyframes, and acknowledges several megabytes. The remaining device-specific failure is FLV AVC packetization: Huawei MediaCodec can return an Annex-B access unit containing AUD, SEI, and multiple slice NALs, while H264Packet previously wrapped the whole access unit behind one NAL length. RTMP transport therefore succeeded but the endpoint could not decode the malformed access unit. H264Packet now splits all Annex-B NAL units and writes an independent UInt32 length for each, filters repeated SPS/PPS, identifies IDR across all NALs, and logs the first multi-NAL unit. ByteBuffer offset-plus-size slicing was corrected, and ScreenService requests a new keyframe immediately after publish start. A multi-NAL regression test was added; the APK builds with 183 tests and zero failures.

## Source Nodes

- H264Packet
- RtmpSender
- RtmpClient
- VideoSpecificConfigAVC
- ScreenService