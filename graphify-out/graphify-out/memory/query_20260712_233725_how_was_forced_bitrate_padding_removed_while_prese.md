---
type: "query"
date: "2026-07-12T23:37:25.734321+00:00"
question: "How was forced bitrate padding removed while preserving the streaming fixes?"
contributor: "graphify"
source_nodes: ["H264Packet", "RtmpSender", "CommandsManager", "VideoEncoder", "ScreenService"]
---

# Q: How was forced bitrate padding removed while preserving the streaming fixes?

## Answer

Removed the H.264 filler-data rate controller, bitrate supplier wiring in RtmpSender, the CommandsManager bitrate getter used only by padding, and the filler-specific unit test. Preserved configured MediaCodec bitrate, RTMP metadata bitrate, kbps settings label, Baseline Level 4.2 compatibility, SPS/PPS and fresh-keyframe flow, multi-NAL packetization, stop/reconnect behavior, and overlay work. Version 5 is 1.0.4-natural-bitrate. Build succeeded and 183 tests passed with zero failures.

## Source Nodes

- H264Packet
- RtmpSender
- CommandsManager
- VideoEncoder
- ScreenService