---
type: "query"
date: "2026-07-12T23:04:35.677601+00:00"
question: "Why does the stream screen show about 2 Mbps when the user configured bitrate 1000, and how does that affect YouTube?"
contributor: "graphify"
source_nodes: ["SettingsManager", "StreamSettingsActivity", "VideoEncoder", "GenericStream", "RtmpClient", "CommandsManagerAmf0", "CommandsManagerAmf3", "UnifiedStreamActivity"]
---

# Q: Why does the stream screen show about 2 Mbps when the user configured bitrate 1000, and how does that affect YouTube?

## Answer

The settings UI incorrectly labelled the input as Mbps while the application stores it in kbps and multiplies by 1000 before MediaCodec. Therefore 1000 means a 1 Mbps encoder target. The stream screen displayed a one-second measured network bitrate, which can exceed the target during large two-second keyframe bursts. The RTMP metadata also hardcoded video and audio data rates to zero instead of advertising the configured targets. The fix relabels input as kbps with a conversion example, displays both live measured and target Mbps, propagates prepared encoder bitrates through GenericStream and RtmpClient, and writes real video/audio data rates into AMF0 and AMF3 metadata. The APK builds and all 183 tests pass.

## Source Nodes

- SettingsManager
- StreamSettingsActivity
- VideoEncoder
- GenericStream
- RtmpClient
- CommandsManagerAmf0
- CommandsManagerAmf3
- UnifiedStreamActivity