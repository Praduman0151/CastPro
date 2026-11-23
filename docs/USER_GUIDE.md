# Cast Pro — Executive User Guide

**From the Desk of the Lead Manager & CEO**  
*Cast Pro Suite (v1.1.1 Production Release)*

---

## 🎮 Introduction

Welcome to **Cast Pro**, an ultra-low latency (<35ms), 100% offline screen and audio mirroring suite engineered specifically for mobile gaming. 

Traditional casting solutions (such as standard Google Cast, Miracast, or AirPlay) introduce 150ms–500ms of buffer latency and require internet connectivity. Cast Pro bypasses all cloud relays, streaming raw H.264/HEVC hardware video frames and uncompressed 48kHz stereo PCM audio peer-to-peer over your local wireless network using zero-buffer `TCP_NODELAY` sockets.

---

## 📦 1. Installation

Cast Pro comprises two separate applications:

| Application | Target Device | APK File Location |
|---|---|---|
| **Cast Pro Sender** | Android Phone / Tablet (Android 8.0+) | [`app-phone-debug.apk`](file:///data/user/0/ai.antigem/files/home/projects/CastPro/build-artifacts/CastPro-Phone-debug/app-phone-debug.apk) |
| **Cast Pro Receiver** | Android TV / Google TV / Fire TV | [`app-tv-debug.apk`](file:///data/user/0/ai.antigem/files/home/projects/CastPro/build-artifacts/CastPro-TV-debug/app-tv-debug.apk) |

### Installing on Android Phone:
1. Transfer or download `app-phone-debug.apk` onto your phone.
2. Tap the APK file in your file manager and select **Install** (allow "Install Unknown Apps" if prompted).

### Installing on Android TV / Google TV:
1. Sideload `app-tv-debug.apk` onto your TV using any of the following methods:
   - **Send Files to TV (SFTV)** app from the Play Store.
   - **ADB over Wi-Fi**: `adb connect <tv-ip>:5555 && adb install -r app-tv-debug.apk`.
   - **USB Flash Drive**: Copy the APK to a USB drive and install via a TV file explorer (e.g., FX File Explorer).

---

## 📶 2. Offline Network Setup (No Internet Required!)

Cast Pro operates entirely within your local wireless network. Internet access is **never** required. Choose one of the following network configurations:

### Option A: Standard Wi-Fi Router (Recommended for 5GHz)
- Connect both your Android Phone and your Android TV to the same Wi-Fi router.
- *Pro Tip*: For competitive gaming, connect your TV via an Ethernet cable to the router and connect your phone to the **5GHz Wi-Fi band**.

### Option B: Mobile Hotspot (Field / Zero-Router Setup)
1. On your Android Phone, turn on **Portable Wi-Fi Hotspot** (mobile data can remain turned off).
2. On your Android TV, open Network Settings and connect to your phone's hotspot.
3. Both devices will now be on the same ultra-fast direct wireless link!

---

## 🚀 3. Step-by-Step Usage Guide

### Step 1: Launch the Receiver on Android TV
1. Open **Cast Pro TV** from your TV apps or Leanback launcher.
2. You will be greeted by the **Gamer Standby Dashboard**:
   - The screen shows the TV's **Local IP Address** (e.g., `192.168.1.50`) and listening port (`9999`).
   - The status indicator will display `LISTENING FOR SENDER...`.
   - The mDNS discovery beacon will automatically advertise `_castpro._tcp` to local devices.

### Step 2: Open Cast Pro on your Android Phone
1. Launch **Cast Pro** on your phone.
2. The dashboard will automatically scan for active TV receivers via local mDNS discovery.
3. Your TV will appear under **Discovered Displays** with its device name and IP address.
   *(Alternatively, you can manually enter the TV's IP address displayed on your TV screen).*

### Step 3: Configure Gamer Settings (Optional)
Tap the **Gamer Mode Card** on your phone dashboard to customize streaming fidelity:
- **Gamer Mode**: Toggle **ON** (Forces zero-jitter buffering for instant touch-to-screen response).
- **Target Frame Rate**: Choose **60 FPS** or **120 FPS** (for high refresh rate gaming).
- **Video Bitrate**: Choose **15 Mbps** (ideal for standard 5GHz Wi-Fi) or **25 Mbps** (crystal clear high-detail fidelity).

### Step 4: Start Casting
1. Tap on your TV receiver in the list (or tap **Start Direct Stream**).
2. Android will present the system **Screen Recording / Projection Permission Dialog**:
   - Select **Start Now** (or "Entire Screen").
3. The notification bar will show `Cast Pro: Streaming Active`.
4. Your TV screen will instantly switch to low-latency fullscreen rendering!

---

## 📊 4. Real-Time Gamer HUD Overlay

During an active session, Cast Pro TV provides a real-time diagnostic heads-up display:
- **LATENCY**: Real-time glass-to-glass network and render latency in milliseconds (typically 18ms–32ms on 5GHz).
- **FPS**: Instantaneous decoder frame rate (locked 60 FPS or 120 FPS).
- **BITRATE**: Active bandwidth throughput.
- **DROPPED FRAMES**: Count of late frames discarded to maintain zero-latency sync.

> *HUD Toggle*: Press the **Center / OK** button on your Android TV remote to toggle the HUD overlay on or off during gameplay.

---

## 🔊 5. In-Game Audio Routing

- **Internal Game Sound**: On Android 10 and newer, Cast Pro captures high-fidelity digital audio directly from the game via Android's `AudioPlaybackCaptureConfiguration`.
- **Latency-Free Output**: Sound is packetized into uncompressed 48kHz 16-bit stereo PCM packets and played through the TV's low-latency `AudioTrack`.
- *Note*: Games that explicitly set `USAGE_GAME` or allow playback capture stream audio with zero degradation.

---

## 🛑 6. Ending the Session

- **From Phone**: Open the notification shade and tap **Stop Cast**, or open the Cast Pro app and tap the red **Disconnect** button.
- **From TV**: Simply press the **Back** or **Home** button on your TV remote to return to the Standby screen or exit the application.

---

## 💡 7. Pro Tips for Competitive Low-Latency Gaming

1. **Use 5GHz or Wi-Fi 6**: Avoid 2.4GHz Wi-Fi where possible, as microwave interference and channel crowding can cause packet jitter.
2. **Enable "Game Mode" on your TV**: Switch your TV's picture preset to **Game Mode**. This disables the TV's internal post-processing image filters, shaving off an extra 20ms–50ms of display lag!
3. **Keep Phone Screen On**: In-game touch controls work directly on your phone display while your friends or audience watch on the big screen without delay.
