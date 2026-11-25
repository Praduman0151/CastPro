# CastPro Material 3 + Screen-Dim Mode Design

## Goal

Refresh the phone app into a coherent Material 3 interface while preserving the existing MediaProjection, MediaCodec, TCP transport, TV renderer, pairing, and telemetry pipeline. Add a safe screen-dimming mode for long casting sessions.

## Important Android platform boundary

A regular third-party Android app cannot reliably keep MediaProjection screen capture running after the physical display is truly powered off. Locking the device or turning the panel off may stop, suspend, or change captured frames depending on Android/OEM behavior. Do not advertise this as guaranteed true screen-off casting.

The supported first implementation is **Screen-Dim Mode**:
- User explicitly grants the special "Modify system settings" access.
- While casting, CastPro lowers global display brightness to the minimum supported value.
- MediaProjection and the existing foreground capture service remain running.
- The original brightness value and brightness mode are saved and restored when the user disables the mode or the cast session stops.
- A persistent notification action provides a way to restore brightness without reopening the dashboard.
- If permission is missing, show a clear explanation and open the Android special-access settings page.
- Never acquire a screen-off / proximity / device-admin lock as a workaround.
- Do not place a black overlay over the display: MediaProjection can capture that overlay and send a black frame to the TV.

## Material 3 visual direction

Use Material 3 components and semantic color roles rather than mixing unrelated custom card styles. Keep the dark gaming identity, but make it calmer and more legible.

### Color and shape

- Background: deep blue-black (#0B1020).
- Surface: layered charcoal-blue (#151D2E).
- Primary: vivid mint green (#8BE9B5) for active casting and primary actions.
- Secondary: electric cyan (#6DD6F5) for network/telemetry.
- Error: Material error role, not neon red borders everywhere.
- Cards: 20dp corner radius; buttons: 16dp; chips: fully rounded.
- Use Material 3 typography hierarchy; reserve all-caps for small status labels only.

### Dashboard hierarchy

1. Top app bar: CastPro brand, connection indicator, refresh action.
2. Hero connection card:
   - TV name and online/offline state.
   - Large Start Casting / Stop Casting primary action.
   - Pair TV and manual IP actions as secondary controls.
3. Live session card (only while streaming):
   - FPS, bitrate, latency and dropped frames.
   - Current resolution and codec.
   - Screen-Dim Mode switch with a short platform-limit explanation.
4. Quick settings:
   - Gaming Mode, audio, quality preset and web receiver.
5. Bottom navigation: Cast, Pair, Settings, Stats.

### Screen-Dim Mode interaction

- Off by default.
- Disabled when no cast session is active.
- First use explains that the display remains technically on at minimum brightness and asks for Modify System Settings access.
- On success, show a persistent "Screen dimmed for casting" status and a notification action named "Restore brightness".
- Restore the exact prior brightness and brightness mode on manual disable, session stop, service failure, or service destruction.
- If restoration fails, keep a visible recovery action and never silently discard the saved original value.

## Implementation sequence

1. Keep the existing streaming service and protocol untouched.
2. Add a dedicated ScreenDimController that owns brightness save/apply/restore and is idempotent.
3. Add service commands for toggling and restoring screen dim, plus a notification action.
4. Add a dashboard control shown only during an active cast; request special access from the Activity, never from the Service.
5. Restore brightness on every teardown path.
6. Add unit tests for idempotent toggles, original brightness restoration, auto/manual brightness mode restoration, and missing permission.
7. Validate on a physical OnePlus device and Android 13+ before describing the feature as stable.

## Acceptance checks

- Existing casting starts, connects, streams, changes quality and stops exactly as before.
- Screen-Dim Mode cannot be enabled without explicit user permission.
- TV output remains normal while the phone panel is at minimum brightness.
- Restoring brightness returns the exact previous value and brightness mode.
- Stopping casting while dimmed restores brightness.
- Notification action restores brightness while CastPro is in the background.
- Locking the phone is not presented as a supported substitute for dimming.
- No claim of true display-off capture is made unless separately verified on the target device and Android build.
