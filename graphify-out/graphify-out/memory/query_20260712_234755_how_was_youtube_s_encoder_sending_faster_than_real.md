---
type: "query"
date: "2026-07-12T23:47:55.032330+00:00"
question: "How was YouTube's encoder sending faster than realtime error fixed?"
contributor: "graphify"
source_nodes: ["RtmpSender", "RealtimePacer", "BaseSender", "H264Packet", "ScreenService"]
---

# Q: How was YouTube's encoder sending faster than realtime error fixed?

## Answer

BaseSender already ignores frames before RtmpSender starts and clears its queue at publish, so pre-publish backlog was not the cause. RtmpSender previously wrote frames immediately and trusted hardware encoder callback timing. Added RealtimePacer, which anchors the first media timestamp to Android monotonic elapsedRealtime and suspends each subsequent audio/video frame until its media-time position is reached. A 2 ms tolerance avoids jitter; reconnect/start and backward timestamps reset the epoch. Version 6 is 1.0.5-realtime-pacing. Forced bitrate remains removed. Build succeeded and 185 tests passed, including exact 1x pacing and backward-timestamp reset tests.

## Source Nodes

- RtmpSender
- RealtimePacer
- BaseSender
- H264Packet
- ScreenService