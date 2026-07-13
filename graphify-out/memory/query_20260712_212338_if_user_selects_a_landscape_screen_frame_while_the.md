---
type: "query"
date: "2026-07-12T21:23:38.181122+00:00"
question: "If user selects a landscape screen frame while the captured app is portrait, show the full portrait app inside the landscape frame"
contributor: "graphify"
source_nodes: ["OverlayConfig", "CaptureOrientation", "ScreenLayoutFilterRender", "OverlayStudioView", "ScreenService"]
---

# Q: If user selects a landscape screen frame while the captured app is portrait, show the full portrait app inside the landscape frame

## Answer

Implemented manual frame orientation independent of captured source orientation. OverlayConfig now persists the user's screenPreset. Overlay Studio restores and saves the selected Portrait/Landscape frame. ScreenLayoutFilterRender always uses that selected frame and uses display rotation only to choose the source crop. CaptureOrientation.sourceFitSize computes an aspect-fit content rectangle, so a portrait source is fully pillarboxed inside a landscape frame; Stretch fills the same landscape frame without changing its shape. Fit-mode changes no longer reset frame placement. Added orientation-fit and legacy/round-trip persistence tests; 25 tests pass and assembleDebug succeeds.

## Source Nodes

- OverlayConfig
- CaptureOrientation
- ScreenLayoutFilterRender
- OverlayStudioView
- ScreenService