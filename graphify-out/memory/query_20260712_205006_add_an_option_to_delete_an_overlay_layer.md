---
type: "query"
date: "2026-07-12T20:50:06.736856+00:00"
question: "Add an option to delete an overlay layer"
contributor: "graphify"
source_nodes: ["OverlayStudioActivity", "OverlayStudioView", "OverlayLayerOrdering"]
---

# Q: Add an option to delete an overlay layer

## Answer

Added confirmed layer deletion in OverlayStudioActivity. The selected-layer Delete action now asks for confirmation. Arrange Layers now shows a red Delete action for every image/text row, keeps Screen undeletable, disables deletion while a layer is locked, removes the row immediately, and updates the unified portrait/landscape config through OverlayStudioView.deleteSelected. Unit tests and assembleDebug completed successfully.

## Source Nodes

- OverlayStudioActivity
- OverlayStudioView
- OverlayLayerOrdering