# Technical Architecture & System Specification - Cast Pro

## 1. Multi-Module Project Structure

```
CastPro/
├── gradle/
│   └── libs.versions.toml             # Consolidated Version Catalog
├── build.gradle.kts                   # Root build script
├── settings.gradle.kts                # Multi-module settings
│
├── core-common/                       # Shared models, packet protocol, constants
│   ├── src/main/java/com/castpro/core/common/
│   │   ├── protocol/                  # Packet headers, magic numbers, frame formats
│   │   ├── model/                     # StreamConfig, DeviceInfo, LatencyMetric
│   │   └── discovery/                 # NSD service registration & discovery contracts
│   └── build.gradle.kts
│
├── core-streaming/                    # Low-latency streaming engine
│   ├── src/main/java/com/castpro/core/streaming/
│   │   ├── encoder/                   # MediaCodec H.264/HEVC hardware encoder
│   │   ├── decoder/                   # MediaCodec hardware decoder to Surface
│   │   ├── capture/                   # MediaProjection capture & AudioRecord
│   │   └── transport/                 # High-throughput, zero-buffer socket client & server
│   └── build.gradle.kts
│
├── app-phone/                         # Android Phone Sender App
│   ├── src/main/java/com/castpro/phone/
│   │   ├── service/                   # Foreground screen capture service
│   │   ├── ui/                        # Jetpack Compose UI (Dashboard, Gaming HUD, Settings)
│   │   └── viewmodel/                 # PhoneStreamViewModel
│   └── build.gradle.kts
│
└── app-tv/                            # Android TV Receiver App
    ├── src/main/java/com/castpro/tv/
    │   ├── ui/                        # Android TV Leanback / Compose for TV fullscreen view
    │   ├── render/                    # Hardware SurfaceView video sink & HUD overlay
    │   └── viewmodel/                 # TvReceiverViewModel
    └── build.gradle.kts
```

---

## 2. Low-Latency Binary Streaming Protocol

To avoid HTTP or WebRTC signaling overhead on local networks, Cast Pro uses a lightweight, zero-copy binary streaming protocol:

### Packet Header (16 Bytes)
```
+--------------------+--------------------+--------------------+--------------------+
|  Magic (2 Bytes)   |  Type (1 Byte)     | Flags (1 Byte)     |  Seq Num (4 Bytes) |
|      0x43 0x50     |  0x01: Video NAL   | 0x01: Keyframe/IDR |    32-bit counter  |
|      ("CP")        |  0x02: Audio PCM   | 0x02: Gaming Mode  |                    |
|                    |  0x03: Heartbeat   | 0x04: Last Chunk   |                    |
+--------------------+--------------------+--------------------+--------------------+
|               Timestamp (8 Bytes) - System.nanoTime()                             |
+--------------------+--------------------+--------------------+--------------------+
|                        Payload Length (4 Bytes)                                   |
+-----------------------------------------------------------------------------------+
|                        Payload Data (H.264 NAL Unit / AAC / PCM)                  |
+-----------------------------------------------------------------------------------+
```

### Low-Latency Transport
* **TCP Socket (`TCP_NODELAY = true`)**: Eliminates Nagle's algorithm delay. Direct stream pipe with send/receive buffer sizes optimized for frame burst (64KB - 128KB).
* **Optional UDP Socket**: For ultra-aggressive packet delivery where dropped packet retransmission is completely disabled for sub-20ms rendering.
* **Latency Calculation**: Receiver compares packet `Timestamp` with current `System.nanoTime()` (clock-synced during initial handshake) to display sub-millisecond precision HUD metrics.

---

## 3. MediaCodec Pipeline & Gaming Mode Parameters

### Phone Sender (Hardware Encoder)
```kotlin
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_BIT_RATE, if (gamingMode) 12_000_000 else 6_000_000) // 12 Mbps in gaming mode
    setInteger(MediaFormat.KEY_FRAME_RATE, 60)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 keyframe per second
    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    
    // Low latency & gaming optimizations (API 26+)
    setInteger(MediaFormat.KEY_LATENCY, 0) // Zero frame latency
    setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // Strictly disable B-frames
    }
}
```

### TV Receiver (Hardware Decoder)
```kotlin
val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
    setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
    setInteger(MediaFormat.KEY_LOW_LATENCY, 1) // Android 11+ low-latency decoder hint
}
decoder.configure(format, surfaceView.holder.surface, null, 0)
```

---

## 4. Network Discovery (NSD) Strategy

1. **TV Receiver**:
   - Registers service via Android `NsdManager`:
     - Service Name: `CastPro-TV-[Model]`
     - Service Type: `_castpro._tcp.`
     - Port: `8998` (Control & Video Data)
2. **Phone Sender**:
   - Discovers active services on local Wi-Fi or Hotspot subnet using `NsdManager.discoverServices()`.
   - Resolves IP address and displays instant one-tap connect tile.
   - Fallback: Direct IP address entry for manual hotspot connections.
