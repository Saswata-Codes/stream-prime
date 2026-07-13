---
type: "query"
date: "2026-07-12T16:52:18.262336+00:00"
question: "Fix stretched landscape app screen after removing pixel rotation"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender", "CaptureOrientation", "ScreenSource"]
---

# Q: Fix stretched landscape app screen after removing pixel rotation

## Answer

The output screenshot showed an upright but vertically crushed browser band. MediaProjection keeps the landscape app as a centered active band inside the fixed portrait texture; sampling the whole portrait texture into a landscape destination stretched the content. Added a normalized centered source crop based on the stream canvas aspect, sent crop position and size to ScreenLayoutFilterRender, and remapped only active landscape pixels into the saved screen rectangle. Portrait still samples the full texture; landscape position and zoom are unchanged. Build succeeds and all 15 tests pass.

## Source Nodes

- ScreenLayoutFilterRender
- CaptureOrientation
- ScreenSource