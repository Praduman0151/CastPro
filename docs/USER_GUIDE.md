# CastPro — User Guide

*Current release: [v1.0.4](https://github.com/Praduman0151/CastPro/releases/tag/v1.0.4)*

## About CastPro

CastPro is an offline screen and audio casting system for an Android phone and an Android TV / Google TV on the same local network. It is designed with mobile gaming in mind, but it can mirror any allowed phone screen content.

The phone captures the display with `MediaProjection`, encodes video with `MediaCodec` (H.264 by default) and sends a binary packet stream over TCP to the TV. The TV decodes the stream and renders it full-window with a selectable `TextureView` transform. No cloud relay, account or internet connection is used.

Latency depends on the phone, TV, encoder, decoder, Wi-Fi path and selected quality. CastPro does not guarantee a fixed millisecond latency or HDMI-equivalent behaviour.

## 1. Install the two apps

| App | Target device | Download |
| --- | --- | --- |
| CastPro Phone | Android 8.0+ phone | [`CastPro-Phone-debug.apk`](https://github.com/Praduman0151/CastPro/releases/download/v1.0.4/CastPro-Phone-debug.apk) |
| CastPro TV | Android TV / Google TV, API 26+ | [`CastPro-TV-debug.apk`](https://github.com/Praduman0151/CastPro/releases/download/v1.0.4/CastPro-TV-debug.apk) |

Install the phone APK normally after allowing installation from your file manager.

Sideload the TV APK using a TV file manager, "Send Files to TV", a USB drive or development ADB. ADB is only an installation/debug method; it is never the casting transport.

## 2. Network setup

Both devices must reach each other on the same local network.

### Option A: Same Wi-Fi router

1. Connect the phone and TV to the same router.
2. Prefer 5 GHz for the phone and Ethernet for the TV where possible.
3. Keep the devices close enough for a stable link.

### Option B: Phone hotspot

1. Enable the phone's portable Wi-Fi hotspot.
2. Connect the TV to that hotspot.
3. Open the CastPro TV receiver on the TV and note its IP address.

No internet connection is required for the casting path itself.

## 3. Start a session

1. Open **CastPro TV** on the TV. It shows the TV's local IP address and listening port (default `8998`).
2. Open **CastPro** on the phone. The app scans using NSD/mDNS and a UDP discovery fallback; you can also enter the TV IP/port manually or use QR pairing.
3. Choose your quality profile or manual settings.
4. Tap **Start** and approve the Android screen-capture prompt.
5. The TV switches to full-screen video when real decoded frames arrive, not merely when the TCP socket connects.

## 4. Display modes

Open **Cast settings** on the TV with the remote **OK**, **A** or **Menu** key, then choose **Display Mode**.

- **Stretch** — default. Uses independent X and Y scaling so the decoded frame fills 100% of the TV panel with no black bars and no crop. Aspect-ratio distortion is expected.
- **Fit** — shows the whole frame and keeps black bars where needed.
- **Fill** — fills the panel while preserving aspect ratio, cropping excess edges.

The mode changes the TV-side transform. It does not change the phone's capture resolution.

## 5. HUD and remote keys

The TV HUD shows real decoded resolution, codec, measured FPS, measured bitrate, target bitrate and latency indicators. Values appear from telemetry; unavailable values should not be treated as measured.

- **OK / A / Menu** — open or close Cast settings while casting.
- **Info** — show or hide the HUD overlay.
- **Mini HUD / Full HUD** — switch between compact and detailed HUD layouts.

## 6. Audio

- **Internal game/media audio** uses Android 10+ `AudioPlaybackCapture` and is sent as 48 kHz stereo PCM when the app and OS allow capture.
- **Microphone** and **Muted** modes are also available.
- Some apps or system versions block playback capture. If audio is missing, check the app's audio permissions and try another audio mode.

## 7. Quality and live changes

Presets cover 720p, 1080p, 1440p, 30/60/120 FPS and multiple bitrate targets, plus Battery Saver, Balanced, Low Latency and Max Quality profiles.

Actual support depends on the phone encoder and TV decoder. The tested TCL TV supports H.264 only; HEVC may fail and should be reported in the HUD rather than silently claimed. 1440p and 120 FPS are not guaranteed on this hardware.

Live quality changes reuse the existing screen-capture permission, swap the encoder input surface and resize the virtual display. This was introduced to stop the TV picture from jumping sideways when quality changed.

## 8. Ending and reconnecting

- Stop from the phone app notification or dashboard.
- If the TV link drops, the phone retries the TCP connection and requests a fresh keyframe when it reconnects.
- Receiver status distinguishes a connected client from an actively streaming video session.

## 9. Current verification status

- CI compiles both phone and TV APKs for v1.0.4.
- Earlier Phase 1 testing recorded 20 consecutive quality changes without root-layout or surface-bound movement.
- Full-screen Stretch verification with PUBG Mobile on the OnePlus Nord 5 and TCL Android TV is still pending.
- UDP transport and USB-assisted casting are experimental documents/prototypes, not active production features.

See [QA_REPORT.md](QA_REPORT.md) for test evidence and known limitations.
