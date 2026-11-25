# CastPro

**Offline screen and audio casting from an Android phone to an Android TV / Google TV, built with mobile gaming in mind.**

CastPro captures the phone display with `MediaProjection`, encodes it with `MediaCodec`, sends a binary packet stream over a local TCP connection, decodes it on the TV and renders it through a full-window `TextureView`. It works on a local network without a cloud relay, account or internet connection.

> A successful build only proves the APKs compile. Casting, latency, stability and full-screen behaviour must be verified on the actual phone and TV.

## Current release

Latest release: [v1.0.4](https://github.com/Praduman0151/CastPro/releases/tag/v1.0.4)

Attached debug APKs:

- `CastPro-Phone-debug.apk` — phone sender
- `CastPro-TV-debug.apk` — TV receiver

## What CastPro does today

- **Local discovery and pairing**
  - NSD/mDNS discovery plus a UDP discovery fallback.
  - Manual receiver IP/port entry and QR pairing flows.
  - Default streaming port: `8998`.
- **Phone sender**
  - `MediaProjection` screen capture in a foreground service.
  - Hardware H.264 encoding by default; HEVC can be selected but only works if both endpoint codecs support it. The tested TCL TV supports H.264 only.
  - Resolution, FPS and bitrate presets, including 720p, 1080p, 1440p, 30/60/120 FPS and several bitrate targets. Unsupported combinations must be reported by telemetry rather than silently claimed.
  - Live quality changes reuse the existing projection, swap the encoder input surface and resize the virtual display instead of creating a second capture session.
  - Internal game/media audio capture on Android 10+ using `AudioPlaybackCapture`, plus microphone and muted modes.
- **TV receiver**
  - Hardware decoding with monotonic presentation timing derived from the TV clock.
  - Full-window `TextureView` with three display modes:
    - **Stretch** (default): independent X/Y scaling, full panel, no bars and no crop.
    - **Fit**: whole frame with black bars when needed.
    - **Fill**: uniform scale with edge crop.
  - Live HUD for resolution, codec, measured FPS, measured bitrate, target bitrate and latency indicators.
  - Compact HUD, colour filter presets and remote-key settings panel.
- **Connection handling**
  - TCP transport with `tcpNoDelay`, automatic reconnect and failed-frame drop handling.
  - Heartbeat clock-offset sync and telemetry based on real decoded frames, not just socket connection.
  - Receiver state separates "client connected" from "video actually streaming".

## Architecture

| Module | Purpose |
| --- | --- |
| `:app-phone` | Jetpack Compose sender UI, settings, permission flow, foreground capture service and phone HUD. |
| `:app-tv` | Android TV receiver UI, `TextureView` renderer, colour filters and TV remote controls. |
| `:core-common` | Shared models, binary packet headers, protocol constants and telemetry data. |
| `:core-streaming` | Encoder, decoder, audio capture/playback, discovery, TCP transport and telemetry collectors. |

Video, audio, heartbeat, geometry and stream configuration use one compact binary protocol over TCP. The TV renderer applies the selected display transform to the decoded frame; it does not change the capture resolution to fake full screen.

## Experimental work, not active in v1.0.4

- `UdpVideoStreamTransport` and `UdpVideoReceiver` are isolated prototypes for fragmenting/reassembling video datagrams. TCP remains the production path; UDP is **not** wired into the live session.
- `docs/USB_CASTING_DESIGN.md` describes USB **network** transport only. It is not a claim of USB-C DisplayPort / HDMI video output, and no wired mode is enabled.
- Google Cast / standard Miracast are not raw-frame transports and are not used as the CastPro video path.

## Requirements

- Phone: Android 8.0+ (API 26).
- TV / Google TV: Android TV API 26+.
- Both devices on the same local network. A 5 GHz Wi-Fi band or wired TV backhaul is recommended.
- Internal game audio capture requires Android 10+ and `RECORD_AUDIO` permission. Some apps block playback capture.
- Screen capture permission must be granted for each casting session.

## Build and CI

GitHub Actions builds both debug APKs on pushes and pull requests:

`.github/workflows/build-apk.yml`

Local builds require a valid Android SDK configuration:

```bash
./gradlew :app-phone:assembleDebug :app-tv:assembleDebug
```

Artifacts are produced as:

- `app-phone/build/outputs/apk/debug/app-phone-debug.apk`
- `app-tv/build/outputs/apk/debug/app-tv-debug.apk`

## Install and test

1. Install `CastPro-Phone-debug.apk` on the phone.
2. Sideload `CastPro-TV-debug.apk` on the Android TV.
3. Open the TV receiver and note its IP address and port.
4. Open the phone app, select the receiver and tap **Start**.
5. Approve the Android screen-capture prompt.
6. Keep **Stretch** selected to force the decoded frame across the full TV panel.

ADB may be used to install or debug development builds. It is never used as the casting/transport path.

## Verification status

- CI compilation: passing for v1.0.4.
- Earlier Phase 1 layout work recorded 20 consecutive quality changes with no root-layout or surface-bound movement.
- The current full-screen Stretch acceptance test with PUBG Mobile on a OnePlus Nord 5 and TCL Android TV is still pending because Wi-Fi was unavailable during the release session.
- HEVC, 1440p and 120 FPS depend on device capability and are not claimed as verified on the current TV.

## Documentation

- [User guide](docs/USER_GUIDE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [QA report](docs/QA_REPORT.md)
- [Product requirements](docs/PRD.md)
- [Ultra transport experiment](docs/CASTPRO_ULTRA_TRANSPORT.md)
- [USB casting design](docs/USB_CASTING_DESIGN.md)
- [Neon UI upgrade notes](docs/NEON_FULL_UPGRADE.md)

## License

Apache-2.0 License.
