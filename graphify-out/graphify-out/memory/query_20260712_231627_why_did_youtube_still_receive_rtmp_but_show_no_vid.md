---
type: "query"
date: "2026-07-12T23:16:27.579255+00:00"
question: "Why did YouTube still receive RTMP but show no video after metadata bitrate was fixed?"
contributor: "graphify"
source_nodes: ["ScreenService,H264Packet,VideoEncoder,RtmpClient,CommandsManagerAmf0,UnifiedStreamActivity"]
---

# Q: Why did YouTube still receive RTMP but show no video after metadata bitrate was fixed?

## Answer

The 04:42 log confirms correct metadata (1080x1920, 60 fps, 10000 kbps video, 256 kbps audio), NetStream.Publish.Start, continuous AVC/AAC packets, keyframes, and server acknowledgements. The low instantaneous rate on static content is Huawei hardware encoder compression behavior, not network failure. The remaining compatibility risk was Huawei High-profile AVC producing reordered/B-frames while the FLV packetizer emits composition time zero. ScreenService now tries AVC Baseline Level 4.2 first, which forbids B-frames, with High profile only as fallback. Version 3 was built and 183 tests passed.

## Source Nodes

- ScreenService,H264Packet,VideoEncoder,RtmpClient,CommandsManagerAmf0,UnifiedStreamActivity