# Cast Pro (Gamer Screen Mirroring Suite)

An ultra-low latency, offline screen casting system designed specifically for gamers to mirror their Android phone display and in-game audio directly to an Android TV with zero internet requirement.

![Cast Pro Architecture](https://raw.githubusercontent.com/OpenBMB/ChatDev/main/misc/architecture.png)

---

## Key Features

* **Zero Internet Requirement**: Operates 100% peer-to-peer over local Wi-Fi, Wi-Fi Direct, or mobile hotspot.
* **Ultra-Low Latency (<35ms)**: Direct hardware `MediaCodec` H.264/HEVC encoding pipeline with `KEY_LATENCY=0` and zero-buffer `TCP_NODELAY` streaming.
* **Real-Time Game Audio**: High-fidelity 48kHz Stereo PCM audio capture via Android 10+ `AudioPlaybackCapture` and low-latency `AudioTrack` playback on TV.
* **Gamer Mode**: One-tap toggle for 60/120 FPS target, disabled jitter buffering, and live HUD overlay.
* **Android TV Immersive Receiver**: Clean Leanback standby UI with automatic mDNS (NSD) discovery and fullscreen hardware surface rendering.

---

## Project Modules

* `:app-phone` - Android Phone Sender application (Jetpack Compose UI, `MediaProjection` capture service, Gamer Mode HUD).
* `:app-tv` - Android TV Fullscreen Receiver application (Leanback UI, hardware `SurfaceView` video sink, low-latency audio player).
* `:core-streaming` - Low-latency streaming engine (`HardwareScreenEncoder`, `HardwareVideoDecoder`, `GameAudioCapture`, `TvAudioPlayer`, sockets, and NSD discovery).
* `:core-common` - Binary packet protocol headers, shared data models, and constants.

---

## Automated CI/CD Builds

Every push to GitHub triggers an automated GitHub Actions build workflow (`.github/workflows/build-apk.yml`) that compiles both applications on Ubuntu runners with JDK 21 and generates downloadable APK artifacts:
1. `CastPro-Phone-debug.apk`
2. `CastPro-TV-debug.apk`

---

## License

Apache-2.0 License.
