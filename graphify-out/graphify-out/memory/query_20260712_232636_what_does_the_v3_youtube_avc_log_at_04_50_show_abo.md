---
type: "query"
date: "2026-07-12T23:26:36.087086+00:00"
question: "What does the v3 YouTube AVC log at 04:50 show about the remaining no-video report?"
contributor: "graphify"
source_nodes: ["ScreenService", "H264Packet", "VideoEncoder", "RtmpClient", "CommandsManager", "UnifiedStreamActivity"]
---

# Q: What does the v3 YouTube AVC log at 04:50 show about the remaining no-video report?

## Answer

Version 3 is installed. Huawei OMX.hisi.video.encoder.avc successfully configures AVC Baseline Level 4.2 at 1080x1920 60fps. YouTube returns NetStream.Publish.Start. Metadata reports H.264 1080x1920 60fps 10000 kbps and AAC 48kHz 256 kbps. The app sends SPS/PPS, requests a fresh keyframe, sends a sequence packet, an immediate IDR-sized packet, continued video/audio packets, another IDR about two seconds later, and receives server acknowledgements. The supplied trace ends only about 3.2 seconds after publish begins and contains no RTMP, encoder, or packetizer failure. The next evidence needed is YouTube Live Control Room stream-health status after keeping the stream running at least 30 seconds. The pasted log exposes the stream key, so it must be rotated and future shared logs must redact it.

## Source Nodes

- ScreenService
- H264Packet
- VideoEncoder
- RtmpClient
- CommandsManager
- UnifiedStreamActivity