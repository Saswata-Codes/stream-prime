---
type: "query"
date: "2026-07-12T16:28:16.204761+00:00"
question: "Fix landscape app changing from left-sideways to right-sideways"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender", "CaptureOrientation", "ScreenSource"]
---

# Q: Fix landscape app changing from left-sideways to right-sideways

## Answer

The two supplied recordings proved both plus and minus 90-degree UV transforms were wrong: they only moved the sideways error between directions. Refactored ScreenLayoutFilterRender so display rotation controls only portrait versus landscape scene selection. Removed the capture quarter-turn uniform and every UV rotation branch; MediaProjection pixels now use identity texture sampling. Landscape layout placement, zoom, and manual rotation remain independent. Build succeeds and all 13 tests pass.

## Source Nodes

- ScreenLayoutFilterRender
- CaptureOrientation
- ScreenSource