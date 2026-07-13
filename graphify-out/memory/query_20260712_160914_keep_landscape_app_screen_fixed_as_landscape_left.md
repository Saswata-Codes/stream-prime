---
type: "query"
date: "2026-07-12T16:09:14.699244+00:00"
question: "Keep landscape app screen fixed as landscape-left in vertical output"
contributor: "graphify"
source_nodes: ["CaptureOrientation", "ScreenLayoutFilterRender", ".applyConfiguredOverlay"]
---

# Q: Keep landscape app screen fixed as landscape-left in vertical output

## Answer

Changed capture orientation normalization from four device poses to two output scenes. Rotations 90, 270, and -90 now all map to canonical landscape-left quarter-turn 1; rotations 0, 180, and 360 map to upright portrait. ScreenLayoutFilterRender uses this canonical value, so device reverse-landscape selects the landscape layout but cannot flip its captured content. Added six assertions across two tests; full debug build and 13 unit tests pass.

## Source Nodes

- CaptureOrientation
- ScreenLayoutFilterRender
- .applyConfiguredOverlay