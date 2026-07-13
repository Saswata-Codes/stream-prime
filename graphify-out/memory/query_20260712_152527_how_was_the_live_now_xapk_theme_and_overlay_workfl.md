---
type: "query"
date: "2026-07-12T15:25:27.711529+00:00"
question: "How was the Live Now XAPK theme and overlay workflow adapted into Stream Prime?"
contributor: "graphify"
source_nodes: ["OverlayEditorActivity", "OverlayPreviewView", "OverlayManager", "ScreenService", "ScreenLayoutFilterRender"]
---

# Q: How was the Live Now XAPK theme and overlay workflow adapted into Stream Prime?

## Answer

Analyzed Live Now 2.12.0 as a clean-room behavior reference. Stream Prime now persists CanvasTheme, portrait and landscape ScreenFitMode, grid and center snapping, two independent captured-app screen transforms, and image-layer rotation. OverlayPreviewView matches the live ScreenLayoutFilterRender theme, frame, fit/stretch, movement, zoom, rotation, and orientation switching. LayerManagerActivity exposes theme, grid, snap, orientation, fit/fill, rotate and reset controls. Eleven overlay tests pass and the debug APK assembles.

## Source Nodes

- OverlayEditorActivity
- OverlayPreviewView
- OverlayManager
- ScreenService
- ScreenLayoutFilterRender