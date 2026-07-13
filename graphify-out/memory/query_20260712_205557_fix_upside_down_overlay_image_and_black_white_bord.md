---
type: "query"
date: "2026-07-12T20:55:57.496920+00:00"
question: "Fix upside-down overlay image and black/white border in screen output"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender", "OverlayStudioView", "LayerCanvasRenderer"]
---

# Q: Fix upside-down overlay image and black/white border in screen output

## Answer

The live GL compositor was flipping Android Canvas bitmap Y coordinates twice for layers below Screen. ScreenLayoutFilterRender now samples the uploaded bitmap with top-left canvas coordinates directly. The shader's explicit theme-colored screen frame was removed, source crop coordinates are clamped inside the active image to prevent letterbox edge bleed, and premultiplied alpha is composited correctly to prevent dark fringes. OverlayStudioView no longer paints a decorative screen frame, keeping preview and output consistent. Tests and assembleDebug succeeded.

## Source Nodes

- ScreenLayoutFilterRender
- OverlayStudioView
- LayerCanvasRenderer