---
type: "query"
date: "2026-07-13T00:31:41.147877+00:00"
question: "Optimize extra black borders around the composed vertical overlay scene shown in IMG_7537"
contributor: "graphify"
source_nodes: ["OverlayStudioView", "OverlayStudioActivity", "LayerCanvasRenderer", "ScreenLayoutFilterRender"]
---

# Q: Optimize extra black borders around the composed vertical overlay scene shown in IMG_7537

## Answer

Added an aspect-safe whole-scene optimizer to Overlay Studio. The new Fit scene action measures the actual visible bounds of the captured screen, text, and image layers, includes rotation in the bounds, accounts for image aspect-fit rather than its unused sizing box, and applies one uniform scale plus translation to center the complete scene with a 3 percent safe margin. Relative spacing, rotation, image aspect ratios, z-order, and hidden layers are preserved. Visible locked items block the operation and show an unlock message. Added SceneLayoutOptimizer pure geometry and three tests. Built version 1.0.8-fit-scene code 9; 32 app tests and 56 RTMP tests pass.

## Source Nodes

- OverlayStudioView
- OverlayStudioActivity
- LayerCanvasRenderer
- ScreenLayoutFilterRender