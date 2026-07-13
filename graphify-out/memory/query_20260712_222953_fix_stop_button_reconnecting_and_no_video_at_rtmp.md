---
type: "query"
date: "2026-07-12T22:29:53.641240+00:00"
question: "Fix Stop button reconnecting and no video at RTMP endpoint"
contributor: "graphify"
source_nodes: ["ScreenService,StreamReconnectPolicy,GenericStream,RtmpClient,GlStreamInterface,SurfaceManager"]
---

# Q: Fix Stop button reconnecting and no video at RTMP endpoint

## Answer

The new log showed manual Stop at 03:52:52 followed by ScreenService.onDisconnect scheduling a five-second reconnect. ScreenService was destroyed, but the uncancelled delayed callback ran at 03:52:57 and restarted encoders/RTMP from the dead service. The log also showed overlapping RtmpSender threads and EGL_BAD_ALLOC because the destroyed service never released its preview EGL surface, explaining endpoint no-video behavior. ScreenService now tracks explicit stream intent and service ownership, cancels its single pending reconnect task on Stop/destruction, rejects late callbacks from dead/replaced services, clears the endpoint, stops itself even between connection attempts, prevents duplicate preview attachment, and fully releases GenericStream, preview EGL, MediaProjection, encoders, and RTMP resources. Unexpected network failures still retry only while the live service owns an active requested session. Added StreamReconnectPolicy with four regression tests; all 29 app tests pass and assembleDebug succeeds.

## Source Nodes

- ScreenService,StreamReconnectPolicy,GenericStream,RtmpClient,GlStreamInterface,SurfaceManager