# CastPro Native Engine 2.0 — Gaming Track

## Status

Experimental NDK implementation on `feature/native-engine-2.0-gaming`. Not enabled as production default and not verified on the OnePlus Nord 5 or target Android TV.

## Architecture

- NDK `AMediaCodec` surface-input encoder in `:core-streaming`.
- Intended capture route: `MediaProjection -> VirtualDisplay -> encoder input Surface`.
- Existing CastPro packet header and `StreamTransport` contract are retained.
- Kotlin `HardwareScreenEncoder` and TCP remain the compatibility path.
- Codec is selected by MIME through Android's codec registry; hard-coded Qualcomm codec component names are deliberately avoided.
- Low-latency vendor keys are best-effort and vary by codec/firmware.
- Encoded output crosses JNI in a byte array. Capture pixels avoid CPU readback, but this is not end-to-end zero-copy.

## Current limitations

- Service integration is present: Android 10+ gaming sessions try the NDK backend, and initialization failures fall back to the existing Kotlin encoder. Live quality-change and lifecycle behaviour still require device validation.
- UDP is not enabled: the current production TV receiver speaks the existing TCP protocol. Switching only the sender to UDP would break compatibility.
- DSCP/WMM priority is advisory and cannot guarantee router scheduling.
- No retransmission, FEC or receiver jitter-buffer changes are included.
- Native output-format/CSD forwarding must be validated on both AVC and HEVC before the backend can be considered complete.

## Nord 5 gaming validation

Start with 1920x1080 at 60 FPS. Use HEVC only when the TV decoder advertises support; otherwise use AVC. Begin around 12–16 Mbps. Compare native and Kotlin paths under identical conditions.

Record median and p95 capture-to-display latency, decoded FPS, bitrate, packet loss, dropped frames, reconnect recovery, and thermal behaviour during a sustained 20–30 minute session. A successful build is not proof of streaming compatibility or performance.

## Acceptance checklist

1. Build both phone and TV APKs with the NDK toolchain.
2. Verify existing TCP/Kotlin path is unchanged.
3. Verify codec config, IDR recovery, reconnect, quality changes, rotation and stop/restart.
4. Test on the actual OnePlus Nord 5 and target TV.
5. Enable native backend by default only after device acceptance tests pass.
