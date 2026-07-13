---
type: "query"
date: "2026-07-12T21:34:40.678606+00:00"
question: "Fix unsharp or dirty Screen Capture edges visible in output"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender", "CaptureOrientation", "ScreenService"]
---

# Q: Fix unsharp or dirty Screen Capture edges visible in output

## Answer

Original screenshot inspection showed a one-pixel contaminated row at the screen boundary. ScreenLayoutFilterRender now snaps unrotated frame bounds to encoder pixel boundaries, uses half-open edge tests so no extra row/column is included, passes actual source texel size to the shader, and samples 1.25 texels inside the crop to prevent neighboring letterbox/system-bar pixels from bleeding into scaled edges. Internal content sampling quality is otherwise unchanged. All 25 tests and assembleDebug pass.

## Source Nodes

- ScreenLayoutFilterRender
- CaptureOrientation
- ScreenService