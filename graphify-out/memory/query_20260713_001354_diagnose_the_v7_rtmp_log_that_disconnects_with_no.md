---
type: "query"
date: "2026-07-13T00:13:54.179926+00:00"
question: "Diagnose the v7 RTMP log that disconnects with No response from server while video packets are still being sent"
contributor: "graphify"
source_nodes: ["ScreenService", "RtmpClient", "RtmpSender", "GenericStreamClient"]
---

# Q: Diagnose the v7 RTMP log that disconnects with No response from server while video packets are still being sent

## Answer

The v7 timestamp normalization worked: audio and video packets were continuously written and live throughput reached about 9.5 Mbps. The disconnect was a false liveness failure. ScreenService enabled setCheckServerAlive(true), which makes the RTMP client use InetAddress ICMP reachability; the relay blocks ICMP even though its TCP RTMP connection is healthy. Added configureStreamConnectionPolicy, disabled the ICMP probe, retained socket read/write error detection and bounded retries, and applied the policy after all three GenericStream creation paths. Built version 1.0.7-rtmp-liveness code 8; 56 RTMP tests pass and the Android debug build succeeds.

## Source Nodes

- ScreenService
- RtmpClient
- RtmpSender
- GenericStreamClient