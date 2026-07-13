---
type: "implementation"
date: "2026-07-13T00:44:25.771667+00:00"
question: "Fix the thick black border around the landscape screen capture shown in Screenshot_20260713_060647"
contributor: "graphify"
source_nodes: ["CaptureOrientation", "ScreenLayoutFilterRender", "ScreenService", "OverlayStudioView", "ScreenSource"]
---

# Q: Fix the thick black border around the landscape screen capture shown in Screenshot_20260713_060647

## Answer

The border was MediaProjection letterboxing caused by assuming landscape capture always matched the reciprocal canvas aspect (16:9). The device capture is approximately 19:9. Version 10 reads the physical display aspect, uses it consistently for Overlay Studio frame geometry and GL source cropping, removes the black band without stretching, and preserves intentional black padding only when portrait content is fitted inside a landscape frame. App and RTMP unit tests passed and the debug APK was assembled.

## Source Nodes

- CaptureOrientation
- ScreenLayoutFilterRender
- ScreenService
- OverlayStudioView
- ScreenSource