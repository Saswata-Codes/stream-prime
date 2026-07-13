---
type: "query"
date: "2026-07-12T14:40:52.741641+00:00"
question: "How should vertical recording use separate portrait-app and landscape-app screen overlays?"
contributor: "graphify"
source_nodes: ["ScreenService", "ScreenLayoutFilterRender"]
---

# Q: How should vertical recording use separate portrait-app and landscape-app screen overlays?

## Answer

Implemented two persisted screen presets. Portrait display rotation uses the portrait layout. Landscape display rotation uses an independently movable, zoomable, and rotatable wide layout. The live renderer switches presets automatically while image overlays stay fixed on the vertical canvas.

## Source Nodes

- ScreenService
- ScreenLayoutFilterRender