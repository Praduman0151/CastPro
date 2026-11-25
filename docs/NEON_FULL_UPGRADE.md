# CastPro Neon Full Upgrade

## Branch
`feature/castpro-neon-full-upgrade`

## Scope of this first implementation pass

### Phone UI
- Adds `CastProNeonOverview`, a branded neon header and TV connection summary.
- Shows the selected receiver name and address from the existing ViewModel state.
- Shows FPS, measured bitrate, latency and resolution only when a stream is active.
- Displays an em dash for unavailable telemetry rather than sample or fabricated values.
- Inserts the overview into the existing dashboard without replacing its receiver discovery, QR pairing, manual connection, web receiver, gaming mode or start/stop controls.

### TV UI
- Aligns the standby title and subtitle with the CastPro brand.
- Preserves the existing TextureView renderer, pairing QR, remote-friendly display mode and colour filter controls.

### Streaming engine protection
This UI pass does not modify MediaProjection, MediaCodec encoder/decoder, TCP transport, audio capture, receiver discovery, stream packet formats or TV rendering. Existing transport behavior remains unchanged.

## Build and validation
The repository's `.github/workflows/build-apk.yml` builds both debug APKs on pushes and pull requests. The branch push should trigger that workflow. Check its Actions run before treating the APKs as validated.

No physical OnePlus Nord 5 or TCL Android TV test is performed by GitHub Actions. Device validation is still required for:
- Pairing and discovery
- Start/stop casting
- Quality switching during a session
- 1080p / 60 FPS stability
- Audio playback
- TV stretch / fit / fill behavior
- Reconnect and crash recovery

## Next full-upgrade work
1. Run and fix the CI build before adding more changes.
2. Add accurate capture, encode, send, decode and presentation timestamp instrumentation.
3. Add dropped-frame and network queue telemetry using measured data.
4. Add adaptive bitrate only after defining safe bounds and testing the receiver's reconfiguration path.
5. Add USB OTG / USB Ethernet detection as a separate connectivity capability, not as a claim that every USB cable supports direct casting.
6. Add TV-side connection diagnostics while preserving D-pad focus behavior.

## Important
The neon overview is a UI improvement, not a claim of improved stream quality or reduced latency. Performance improvements must be measured on real hardware.
