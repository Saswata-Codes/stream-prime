---
type: "query"
date: "2026-07-12T21:50:10.412752+00:00"
question: "Fix stair-stepped screen layer frame border in video output"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender,CaptureOrientation,ScreenLayout"]
---

# Q: Fix stair-stepped screen layer frame border in video output

## Answer

ScreenLayoutFilterRender used a binary inside/outside fragment test for the transformed screen rectangle, which aliases its outer frame edge when rotated. The shader now computes a rotation-aware half-pixel footprint in local frame coordinates, applies smoothstep coverage only across that one-pixel boundary, clamps sampling at the edge, and composites the covered frame against the canvas background. Pixel-snapped unrotated borders remain exact; rotated borders are antialiased without softening the screen interior or black bars. The debug APK builds and all 25 overlay/orientation tests pass.

## Source Nodes

- ScreenLayoutFilterRender,CaptureOrientation,ScreenLayout