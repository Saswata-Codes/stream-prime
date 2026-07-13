---
type: "query"
date: "2026-07-13T00:19:24.868444+00:00"
question: "Verify the v8 RTMP trace after disabling the false ICMP liveness check"
contributor: "graphify"
source_nodes: ["ScreenService", "RtmpClient", "RtmpSender", "CommandsManager"]
---

# Q: Verify the v8 RTMP trace after disabling the false ICMP liveness check

## Answer

The v8 trace is healthy. During roughly nine seconds it wrote 548 video packets and 112 audio packets continuously, advanced stream duration from 1:49 to 1:58, received regular RTMP server acknowledgements, and reported natural live bitrate samples between about 1.48 and 8.19 Mbps for a 10 Mbps target. There were zero connection failures, no No response message, no reconnect, no zero-bitrate stall, and no sender error. No code change is justified by this log. If YouTube still shows offline, the remaining issue is after the app's accepted RTMP connection—most likely relay destination/routing or YouTube stream-key configuration, especially because prior server negotiation identified a non-YouTube-oriented relay destination.

## Source Nodes

- ScreenService
- RtmpClient
- RtmpSender
- CommandsManager