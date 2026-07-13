---
type: "query"
date: "2026-07-12T21:43:33.900278+00:00"
question: "Fix stair-stepped thin rounded UI lines in captured video output compared with actual screen"
contributor: "graphify"
source_nodes: ["ScreenLayoutFilterRender,BaseRenderOffScreen,ScreenService"]
---

# Q: Fix stair-stepped thin rounded UI lines in captured video output compared with actual screen

## Answer

The comparison showed texture resampling artifacts, not an outer frame boundary defect. BaseRenderOffScreen creates the capture framebuffer texture with GL_NEAREST. ScreenLayoutFilterRender now overrides the bound captured-screen texture to GL_LINEAR for both minification and magnification before composition, preserving smooth rounded and diagonal UI lines whenever the screen layer is resized. Pixel-aligned outer frame edges and black letterbox bars remain hard. All 25 overlay/orientation tests pass and assembleDebug succeeds.

## Source Nodes

- ScreenLayoutFilterRender,BaseRenderOffScreen,ScreenService