---
type: "query"
date: "2026-07-12T17:15:17.999242+00:00"
question: "Add canvas layer rearranging and layer locks"
contributor: "graphify"
source_nodes: ["OverlayLayer", "OverlayManager", "OverlayStudioView"]
---

# Q: Add canvas layer rearranging and layer locks

## Answer

Implemented persistent locked state for image/text layers and independent portrait/landscape screen locks. Overlay Studio now exposes Lock/Unlock, blocks canvas move/zoom/rotation/reset/delete and z-order crossing for locked layers, and draws a LOCKED selection state. Replaced the old layer chooser with a top-first draggable RecyclerView panel; long-press drag reorders unlocked rows and normalizes z-indices while the screen remains the fixed base. Added pure ordering tests and validated all new XML/resources statically. Final Gradle verification could not run because Codex execution credits were exhausted, not because of a reported build error.

## Source Nodes

- OverlayLayer
- OverlayManager
- OverlayStudioView