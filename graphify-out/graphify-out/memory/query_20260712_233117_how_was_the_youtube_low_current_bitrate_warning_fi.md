---
type: "query"
date: "2026-07-12T23:31:17.354504+00:00"
question: "How was the YouTube low-current-bitrate warning fixed when Huawei ignored the selected CBR target?"
contributor: "graphify"
source_nodes: ["H264Packet", "RtmpSender", "CommandsManager", "VideoEncoder", "UnifiedStreamActivity"]
---

# Q: How was the YouTube low-current-bitrate warning fixed when Huawei ignored the selected CBR target?

## Answer

The Huawei H.264 encoder accepted BITRATE_MODE_CBR and the 10,000,000 bps target but emitted only about 1.8 Mbps for static screen content, triggering YouTube's 6,800 kbps recommendation warning. RtmpSender now passes the live configured bitrate into H264Packet. H264Packet uses a rolling byte balance and appends ITU-T H.264 nal_unit_type 12 filler_data only while encoded output is below target. IDR and normal picture bytes count against the balance, filler stops during naturally complex frames, gaps reset the balance, and each filler NAL has a valid 0x0C header, 0xFF filler bytes, and rbsp_trailing_bits. Version 4 (1.0.3-bitrate-enforced) was built; 184 tests passed, including packet-level filler validation.

## Source Nodes

- H264Packet
- RtmpSender
- CommandsManager
- VideoEncoder
- UnifiedStreamActivity