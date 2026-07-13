---
type: "query"
date: "2026-07-12T20:29:49.827621+00:00"
question: "Make the Screen layer order adjustable"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender", "LayeredOverlayRenderer", "OverlayManager", "OverlayStudioView"]
---

# Q: Make the Screen layer order adjustable

## Answer

Implemented Screen as a real persistent member of the unified layer stack. The Studio Arrange Layers panel now includes Screen as a draggable row, and Send down/Bring up work when Screen is selected. screenLayerPosition stores how many visual layers are below Screen. Preview rendering splits below/above layers around that slot. Live rendering now composites lower layers into a background texture inside ScreenLayoutFilterRender, places the captured screen over them, then applies upper layers through LayeredOverlayRenderer. Screen and visual layer locks prevent forbidden crossings. Added unified stack round-trip and split tests. Debug APK builds successfully and all 20 tests pass.

## Source Nodes

- ScreenLayoutFilterRender
- LayeredOverlayRenderer
- OverlayManager
- OverlayStudioView