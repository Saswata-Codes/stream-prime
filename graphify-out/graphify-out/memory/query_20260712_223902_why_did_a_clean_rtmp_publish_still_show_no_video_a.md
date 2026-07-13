---
type: "query"
date: "2026-07-12T22:39:02.953771+00:00"
question: "Why did a clean RTMP publish still show no video at the endpoint?"
contributor: "graphify"
source_nodes: ["ScreenService", "StreamBase", "VideoEncoder", "RtmpClient", "RtmpSender"]
---

# Q: Why did a clean RTMP publish still show no video at the endpoint?

## Answer

The RTMP session was healthy: NetStream.Publish.Start completed, the AVC sequence header and keyframes were sent, continuous video packets flowed, and the server acknowledged them. The remaining defect was codec signalling: the Huawei encoder advertised H.264 Baseline Level 3 for 1080x1920 at 60 fps, a mode beyond Level 3 limits, so a strict endpoint could accept RTMP while rejecting the video track. ScreenService now centralizes all video preparation paths and requests High Level 4.2, falls back to Baseline Level 4.2, then to 30 fps Level 4.1 when necessary. It also guards volume loading until GenericStream is initialized. The debug APK builds and all 182 unit tests pass.

## Source Nodes

- ScreenService
- StreamBase
- VideoEncoder
- RtmpClient
- RtmpSender