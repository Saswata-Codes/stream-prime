---
type: "implementation"
date: "2026-07-13T00:58:51.581459+00:00"
question: "Fix recording button resetting to Start when reopening the app while screen recording is still running"
contributor: "graphify"
source_nodes: ["ScreenService", "UnifiedStreamActivity", "ScreenActivity", "VerticalStreamActivity", "toggleRecord", "isRecording"]
---

# Q: Fix recording button resetting to Start when reopening the app while screen recording is still running

## Answer

The capture activities restored only streaming state on resume and missed recording-only state; they could also render before ScreenService.INSTANCE finished initializing. Version 11 synchronizes both streaming and recording controls from the live service in UnifiedStreamActivity, ScreenActivity, and VerticalStreamActivity. Each activity retries service lookup for up to two seconds after resume, displays STOP whenever ScreenService reports an active recording, and cancels pending synchronization callbacks when paused or destroyed. App and RTMP tests pass and the APK assembles successfully.

## Source Nodes

- ScreenService
- UnifiedStreamActivity
- ScreenActivity
- VerticalStreamActivity
- toggleRecord
- isRecording