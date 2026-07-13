---
type: "query"
date: "2026-07-12T20:43:37.752957+00:00"
question: "Why does an image added while editing landscape not show in portrait?"
contributor: "graphify"
source_nodes: ["OverlayStudioView", "OverlayLayerOrdering", "ScreenLayoutFilterRender"]
---

# Q: Why does an image added while editing landscape not show in portrait?

## Answer

Overlay images are shared between portrait and landscape; they are not duplicated per scene. The apparent disappearance happens when the image is ordered below the Screen layer. The portrait Screen fills much more of the canvas and covers the image completely, while the shorter landscape Screen can leave it visible. In Arrange layers, drag the image above Screen, then save. Unlock the image first if needed.

## Source Nodes

- OverlayStudioView
- OverlayLayerOrdering
- ScreenLayoutFilterRender