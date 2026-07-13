---
type: "query"
date: "2026-07-13T00:05:11.261750+00:00"
question: "Fix YouTube faster-than-realtime uploads, zero-bitrate gaps, and reconnect loops shown in the v6 RTMP log"
contributor: "graphify"
source_nodes: ["RtmpSender", "StreamBlockingQueue", "RtmpClient"]
---

# Q: Fix YouTube faster-than-realtime uploads, zero-bitrate gaps, and reconnect loops shown in the v6 RTMP log

## Answer

The v6 log showed the blocking RTMP pacer waiting about 2052 ms on a future video timestamp while older audio accumulated, then releasing a burst. Later pacing stalls caused zero-bitrate intervals and server no-response reconnects. Replaced RealtimePacer with a non-blocking RealtimeTimestampNormalizer. It clamps audio and video timestamps independently to monotonic elapsed realtime, preserves valid source timing, never sleeps the shared sender, resets on each connection, and prevents media time advancing faster than wall time. Version bumped to 1.0.6-realtime-timestamps (code 7); 56 RTMP unit tests and the debug APK build pass.

## Source Nodes

- RtmpSender
- StreamBlockingQueue
- RtmpClient