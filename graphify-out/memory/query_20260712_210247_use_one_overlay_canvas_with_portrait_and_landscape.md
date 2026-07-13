---
type: "query"
date: "2026-07-12T21:02:47.082492+00:00"
question: "Use one overlay canvas with Portrait and Landscape options that reshape the screen capture layer"
contributor: "graphify"
source_nodes: ["OverlayStudioActivity", "OverlayStudioView", "ScreenPreset", "ScreenLayoutFilterRender"]
---

# Q: Use one overlay canvas with Portrait and Landscape options that reshape the screen capture layer

## Answer

Redesigned Overlay Studio as one shared canvas. Removed the separate top Portrait app/Landscape app section and added Portrait and Landscape shape controls to the bottom tool strip, matching the supplied reference. Switching a control immediately reshapes the same green Screen Capture layer and updates active styling; image/text layers remain shared and stationary. The preview label now shows Screen Capture, orientation, and fit mode. Existing independent screen transforms remain persisted so live capture can automatically use the correct placement when an app rotates. Tests and assembleDebug passed.

## Source Nodes

- OverlayStudioActivity
- OverlayStudioView
- ScreenPreset
- ScreenLayoutFilterRender