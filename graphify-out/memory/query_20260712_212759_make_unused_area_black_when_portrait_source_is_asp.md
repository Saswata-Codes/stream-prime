---
type: "query"
date: "2026-07-12T21:27:59.544072+00:00"
question: "Make unused area black when portrait source is aspect-fitted inside a landscape frame"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender", "CaptureOrientation"]
---

# Q: Make unused area black when portrait source is aspect-fitted inside a landscape frame

## Answer

ScreenLayoutFilterRender now outputs opaque black for pixels inside the selected screen frame but outside the aspect-fitted source content. This creates black pillarbox bars for portrait-in-landscape and black letterbox bars for landscape-in-portrait, while pixels outside the screen frame continue using the configured canvas background and lower layers. Tests and assembleDebug pass.

## Source Nodes

- ScreenLayoutFilterRender
- CaptureOrientation