---
type: "query"
date: "2026-07-12T23:12:15.738520+00:00"
question: "Why does a configured 10 Mbps stream measure only 0.7 to 2.8 Mbps, and why did settings still say Mbps?"
contributor: "graphify"
source_nodes: ["ScreenService", "VideoEncoder", "UnifiedStreamActivity", "StreamSettingsActivity", "GenericStream", "RtmpClient", "CommandsManagerAmf0", "CommandsManagerAmf3"]
---

# Q: Why does a configured 10 Mbps stream measure only 0.7 to 2.8 Mbps, and why did settings still say Mbps?

## Answer

The log confirms MediaCodec accepted 1080x1920 at 60fps, a 10,000,000 bps target, CBR mode, High Profile Level 4.2, and YouTube Publish.Start. A mostly static screen compresses far below the configured bitrate ceiling even on a 150 Mbps network; periodic keyframes create the measured spikes. More importantly, the log proves the device was still running the older APK because RTMP metadata still contained videodatarate=0 and audiodatarate=0 and UnifiedStreamActivity emitted the old bitrate log format. The current build advertises the configured encoder bitrate in RTMP metadata, labels settings as Bitrate (kbps), shows live versus target Mbps, re-applies the target after Huawei MediaCodec starts, and logs app version 1.0.1-bitrate-fix code 2. The uniquely named APK was verified with aapt to contain version code 2 and the kbps label. All 183 tests pass.

## Source Nodes

- ScreenService
- VideoEncoder
- UnifiedStreamActivity
- StreamSettingsActivity
- GenericStream
- RtmpClient
- CommandsManagerAmf0
- CommandsManagerAmf3