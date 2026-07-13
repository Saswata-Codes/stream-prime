---
type: "query"
date: "2026-07-12T22:16:05.189585+00:00"
question: "Why was there no video at the streaming endpoint despite RTMP video packet logs?"
contributor: "graphify"
source_nodes: ["GlStreamInterface,SurfaceManager,ScreenRender,ScreenService,UnifiedStreamActivity"]
---

# Q: Why was there no video at the streaming endpoint despite RTMP video packet logs?

## Answer

The attached log proves the encoder and RTMP publisher were working: continuous video packets, periodic large H.264 keyframes, SPS/PPS delivery, active bitrate, and server acknowledgements were present. The outage was caused when UnifiedStreamActivity's preview SurfaceView was destroyed while GlStreamInterface's GL executor was still drawing it. SurfaceManager released the preview EGL surface concurrently, the Mali driver returned GL error 1285 in ScreenRender.drawPreview, and the uncaught RuntimeException killed the app process and stream. GlStreamInterface now serializes preview draw/attach/detach with a preview-surface lock and catches preview-only runtime GL failures, detaching only the preview while allowing encoder and RTMP rendering to continue headless. Build and 25 overlay/orientation tests pass.

## Source Nodes

- GlStreamInterface,SurfaceManager,ScreenRender,ScreenService,UnifiedStreamActivity