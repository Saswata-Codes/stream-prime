---
type: "query"
date: "2026-07-12T16:17:56.741696+00:00"
question: "Fix sideways landscape app pixels shown in output screenshot"
contributor: "graphify"
source_nodes: ["CaptureOrientation", "ScreenLayoutFilterRender", "ScreenSource"]
---

# Q: Fix sideways landscape app pixels shown in output screenshot

## Answer

The supplied output screenshot showed the captured browser clockwise-sideways while static overlays stayed upright. The fixed portrait MediaProjection texture already contains Android landscape pixels clockwise, and canonical quarter-turn 1 sampled them in the same direction. Changed canonical landscape-left sampling to quarter-turn 3, the inverse counter-clockwise transform. Both 90 and 270 device poses still choose one stable landscape scene. Build succeeds and all 13 tests pass.

## Source Nodes

- CaptureOrientation
- ScreenLayoutFilterRender
- ScreenSource