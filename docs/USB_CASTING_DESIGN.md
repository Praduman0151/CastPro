# CastPro USB-Assisted Casting — Engineering Plan

## Goal

Add a wired-network option for CastPro to reduce Wi-Fi interference and improve consistency. This is **USB network transport**, not native USB-C display output. The phone still captures the screen with MediaProjection and encodes it with MediaCodec; the TV still decodes and renders the stream.

Do not advertise this as lossless, HDMI-equivalent, blur-free, or zero-latency. USB changes the network path, not the encoder's compression quality.

## Device compatibility gate

The OnePlus Nord 5 official specification lists a USB 2.0 Type-C port and does not list DisplayPort Alt Mode video output. Therefore, do not promise USB-C-to-HDMI video output on this phone. A passive USB-C-to-HDMI cable cannot add a display feature the phone does not expose.

USB data casting is a separate possibility and must be tested on the actual phone + TV combination. It is usable only when Android exposes a working USB network interface/route on both endpoints and the TV can run the CastPro receiver over that link. Android TV USB-host/RNDIS support varies by device and firmware.

## Product behaviour

Add connection choices:
- **Wi-Fi** — current supported default.
- **USB network** — shown as Available only after a real USB network path is detected and a CastPro receiver handshake succeeds.
- **Auto** — prefer verified USB network; otherwise use the current Wi-Fi path.

Never show "USB connected" merely because a cable is plugged in. Show distinct states: Cable detected (if detectable), USB network unavailable, USB network ready, receiver reachable, streaming.

If USB networking is unavailable, explain that the phone/TV/firmware does not expose a compatible USB network connection and offer Wi-Fi fallback. Do not silently claim wired mode.

## Architecture

1. Keep MediaProjection, VirtualDisplay, HardwareScreenEncoder, decoder, audio and TV renderer unchanged.
2. Introduce a connection/network-path abstraction below StreamTransport. It should select a verified Android Network and bind the TCP socket to that Network before connect.
3. Enumerate ConnectivityManager.allNetworks and inspect NetworkCapabilities and LinkProperties. Do not infer USB solely from interface names. Prefer a capability-based check where the OS exposes one; otherwise use a carefully tested interface/route heuristic with diagnostic logging and a receiver handshake.
4. Resolve the TV endpoint on the selected network. Existing mDNS discovery may not cross a USB tether link; support explicit pairing/QR or a small receiver discovery handshake on the USB subnet.
5. Keep the existing TCP transport as the first USB implementation. Only consider UDP video after measuring the USB path and completing packet-loss/recovery handling. Keep audio, control, config and keyframe requests reliable.
6. On disconnect or network loss, stop the old socket, re-evaluate available networks, and reconnect without creating duplicate capture/encoder instances.
7. Preserve the current Wi-Fi path and make USB an opt-in/Auto route. No root, ADB, or privileged permissions should be required for normal operation.

## Quality / sharpness work (separate from USB)

USB alone will not remove blur. Add a quality pipeline that:
- Uses the requested capture resolution and never upscales a lower-resolution capture before encoding.
- Exposes actual encoder output resolution, codec, bitrate, FPS, and dropped frames in the HUD.
- Offers explicit profiles such as Balanced, High Quality, and Gaming; do not silently lower bitrate while the UI still says High.
- Uses hardware H.264 as the compatibility baseline and offers HEVC only after phone encoder + TV decoder capability checks.
- Uses bitrate adaptation based on measured delivery/decoder health, with conservative steps and hysteresis to avoid oscillation.
- Requests an IDR/keyframe after reconnect or decoder recovery.
- Separates capture timestamp, encoder output timestamp, network arrival, decode completion and render presentation metrics.
- Keeps the TV STRETCH mode independent of stream quality; stretching may distort aspect ratio but must not crop or alter the encoded source resolution.

## Diagnostics required

Log (without personal content): selected network transport, local/remote addresses, socket bind result, negotiated codec, actual width/height/FPS/bitrate, packet/frame counters, RTT, dropped frames, decode time, render time and reconnect reason.

Add a diagnostic screen with:
- Connection path: Wi-Fi / USB network
- USB-network availability and receiver reachability
- Actual stream resolution and codec
- Target vs measured FPS
- Configured vs measured bitrate
- End-to-end latency estimate and its measurement limitations
- Network/decoder dropped-frame counters

## Acceptance tests

- Wi-Fi casting remains unchanged when no USB network exists.
- USB mode is unavailable unless a real USB network path is present and the TV receiver is reachable through it.
- When USB networking is supported, stream starts, stops, reconnects and falls back cleanly.
- Repeated network changes do not create duplicate MediaProjection, encoder, decoder or server instances.
- Verify 1080p60 and higher profiles only when both endpoints support them; record actual negotiated values.
- Compare Wi-Fi and USB using the same codec, resolution, FPS and bitrate. Report measured latency, jitter and drops; do not infer improvement from the connection label.
- Confirm no quality selector orientation/position regression and no TV display-mode regression.
- Test on the actual OnePlus Nord 5 and TCL Android TV firmware before marking the feature supported.

## Release status

This document is an implementation specification, not a claim that USB casting is already implemented. Do not mark the feature complete until the code is integrated, CI passes, and the physical device tests above pass.
