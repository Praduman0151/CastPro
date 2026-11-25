# CastPro Ultra transport experiment

This branch adds an isolated, opt-in UDP video transport prototype. The existing TCP transport remains the production default.

## Files

- `UdpVideoStreamTransport.kt`: fragments outgoing packets into datagrams capped at 1200 bytes.
- `UdpVideoReceiver.kt`: validates and reassembles fragments with bounded frame size, bounded in-flight assemblies and a short expiry.

## Important integration status

This prototype is deliberately **not wired into the active sender/receiver session**. The current app routes video, audio, heartbeat, geometry and stream configuration through the TCP transport. Switching only the sender to UDP would break compatibility. A proper hybrid integration must route video to UDP while retaining TCP for audio/control/configuration, start the UDP receiver on the TV, and coordinate ports/discovery on both apps.

## Before enabling

1. Add a transport selector/negotiation field to pairing or stream configuration.
2. Start TCP for audio/control/configuration and UDP for video on a dedicated port.
3. Route only `TYPE_VIDEO_NAL` to UDP; retain reliable TCP for all other packet types.
4. Add packet-loss counters and request a fresh keyframe after incomplete frame expiry.
5. Test loss, reordering, reconnects, hotspot and Wi-Fi router paths.
6. Compare against TCP using a high-speed camera or synchronized instrumentation.

UDP does not guarantee lower latency on every network. The benchmark must decide whether it becomes the default.
