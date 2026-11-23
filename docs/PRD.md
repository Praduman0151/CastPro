# Product Requirements Document (PRD) - Cast Pro

## 1. Executive Summary & Vision
* **App Name**: **Cast Pro** (Gamer Screen Mirroring Suite)
* **Target Users**: Mobile gamers, streamers, and competitive players who demand real-time, low-latency screen casting from an Android phone to a big-screen Android TV without an internet connection.
* **Core Value Proposition**: 
  - **Zero Internet Requirement**: Fully offline peer-to-peer transmission over local Wi-Fi, Wi-Fi Direct, or mobile hotspot.
  - **Ultra-Low Latency (<35ms)**: Hardware-accelerated `MediaCodec` pipeline with zero-buffer gaming profile.
  - **Bespoke Dual-App Architecture**: 
    1. **Cast Pro Mobile (Sender)**: Touch-optimized controls, resolution/FPS presets, background capture service, and quick-toggle Gaming Mode.
    2. **Cast Pro TV (Receiver)**: Fullscreen Leanback receiver, hardware decoder surface, zero-overscan display, and real-time Gamer HUD overlay.

---

## 2. User Personas

### Persona A: Alex "The Competitive Mobile Gamer"
* **Device**: Android Flagship (Snapdragon 8 Gen 2/3, 120Hz display).
* **Goal**: Cast fast-paced FPS and rhythm games to his living room 4K TV with virtually imperceptible input lag.
* **Pain Point with standard Chromecast / Miracast**: High latency (150ms-400ms), dropped frames, and dependency on internet/cloud routing.

### Persona B: David "The Retro & Emulation Enthusiast"
* **Device**: Android Phone + Android TV Box.
* **Goal**: Play retro console games on his TV using his phone as the processing powerhouse without needing an active internet connection or router.
* **Pain Point**: Complex pairing procedures and stuttering streams.

---

## 3. Prioritized Feature Matrix

### P0 (Essential MVP - Must Have)
1. **Screen Capture Service (Phone)**: Foreground service utilizing `MediaProjection` API to capture screen video at native resolution (1080p / 720p) at 60 FPS.
2. **Hardware Encoding & Decoding**:
   - Phone: `MediaCodec` H.264 (AVC) and H.265 (HEVC) hardware encoding configured for ultra-low latency (`KEY_LATENCY = 0`, CBR, 0 B-frames, baseline/main profile).
   - TV: `MediaCodec` hardware decoder rendering directly to a hardware `SurfaceView`.
3. **Local Offline Networking**:
   - Zero internet dependency: Operates over LAN Wi-Fi, Mobile Hotspot, or Wi-Fi Direct (P2P).
   - Network Service Discovery (NSD / mDNS) for instant discovery of the TV receiver.
   - Low-latency socket protocol (UDP with packet sequencing or high-performance TCP socket with `TCP_NODELAY`).
4. **Android TV Full-Screen Receiver**:
   - Dedicated Android TV UI optimized for TV remote / D-Pad navigation.
   - Automatic immersive full-screen display upon stream connection with zero border/letterboxing.
5. **Gamer Mode**:
   - Phone & TV sync toggle: Maximizes FPS (up to device max), disables decoder jitter buffers, enforces keyframe intervals (1s), and prioritizes network packets.
6. **Real-Time Game Audio Streaming**:
   - Internal device audio capture via Android `AudioPlaybackCaptureConfiguration` (Android 10+) linked with the active `MediaProjection`.
   - Lossless 48kHz / 16-bit PCM / Low-Latency AAC transmission over the binary socket stream.
   - Low-latency TV audio rendering via `AudioTrack` with `AUDIO_OUTPUT_FLAG_FAST` and AV sync alignment.

### P1 (High Value - Immediate Polish)
1. **Real-time Performance HUD**: Floating or corner HUD toggle on both phone and TV displaying live FPS, estimated latency (ms), network bitrate (Mbps), and packet loss.
3. **Adaptive Bitrate Tuning**: Dynamic bitrate throttling (2 Mbps - 25 Mbps) based on real-time network queue backpressure.

### P2 (Future Roadmap)
1. **USB Tethering / High-Speed Cable Mode**: Direct zero-jitter tethered casting.
2. **Reverse Controller Input**: Transmit Bluetooth gamepad inputs connected to Android TV back to the phone.

---

## 4. Dual-App User Journeys & Screen Navigation

### A. Cast Pro Mobile (Phone Sender)
```
[Phone Launch]
      |
      v
[Main Dashboard]
  ├── Device Status & Network Info (Hotspot / Wi-Fi IP)
  ├── TV Receiver Discovery List (Found via NSD)
  ├── Gaming Mode Toggle (Ultra-Low Latency / 60 FPS / Minimal Buffer)
  └── [Start Casting CTA Button]
      |
      v
[System Permission Prompt] -> (MediaProjection Dialog)
      |
      v
[Active Stream Floating Bubble / Notification]
  ├── Stop Casting button
  ├── Mute Audio toggle
  └── Real-time bitrate & FPS counter
```

### B. Cast Pro TV (Android TV Receiver)
```
[TV Launch]
      |
      v
[TV Standby / Pairing Screen (Leanback)]
  ├── Receiver Server Status: "Ready to connect"
  ├── Device Name & Local IP Address / Port display
  ├── Quick Settings: Resolution, Codec Preference (H.264 / HEVC), Buffer Mode
  └── Waiting for connection animation
      |
      v
[Incoming Connection Handshake] (Auto-accepted or PIN confirm)
      |
      v
[Immersive Full-Screen Stream]
  ├── Full hardware SurfaceView rendering
  ├── Remote D-Pad Menu: Toggle Gamer HUD (Overlay), Aspect Ratio, Disconnect
  └── Loss of Signal / Reconnecting state
```

---

## 5. Technical Specifications & Low-Latency Targets

| Metric | Target Specification |
|---|---|
| **End-to-End Latency** | < 35ms on 5GHz Wi-Fi; < 50ms on 2.4GHz Wi-Fi / Hotspot |
| **Framerate** | 60 FPS sustained (120 FPS capable on supported display panels) |
| **Resolution Support** | 1080p (1920x1080) default; 720p (1280x720) for weak signals; 4K upscale ready |
| **Video Codec** | H.264 (Baseline/Main, low-latency flag) / H.265 (HEVC) |
| **Audio Codec** | Opus or AAC-ELD (Low Delay) / uncompressed PCM (local network) |
| **Transmission Protocol** | Direct socket streaming with TCP_NODELAY or custom UDP stream |
| **Target SDKs** | Phone: Min SDK 26 (Android 8.0), Target SDK 35 (Android 15)<br>TV: Min SDK 26, Target SDK 35 |

---

## 6. Non-Functional & Reliability Requirements
1. **No External Internet Access**: Must function in airplane mode with Wi-Fi / Hotspot active.
2. **Thermal & Battery Optimization**: Efficient hardware encoder usage to prevent CPU throttling on the gaming phone during intense gameplay.
3. **Graceful Reconnection**: Instant auto-resume if Wi-Fi signal experiences transient drops.
