---
type: "query"
date: "2026-07-12T00:27:06.539795+00:00"
question: "Understand complete Stream Prime project workflow"
contributor: "graphify"
source_nodes: ["UnifiedStreamActivity", "SettingsManager", "ScreenService", "GenericStream", "FileStreamService", "RtmpClient", "SrtClient", "RtspClient"]
---

# Q: Understand complete Stream Prime project workflow

## Answer

Runtime: SplashActivity to MainActivity to UnifiedStreamActivity. SettingsManager supplies service keys and quality profiles. Screen flow obtains MediaProjection, configures ScreenService and GenericStream, prepares encoders and audio/video sources, applies overlays, then dispatches encoded frames by endpoint scheme to RTMP, RTSP, SRT, or UDP clients. File flow uses FileStreamService and GenericFromFile. ConnectChecker callbacks update UI and drive retry/reconnect. Build graph: app depends on library and extra-sources; library exposes encoder and protocol modules.

## Source Nodes

- UnifiedStreamActivity
- SettingsManager
- ScreenService
- GenericStream
- FileStreamService
- RtmpClient
- SrtClient
- RtspClient