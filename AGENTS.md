# AGENTS.md — PureCast

## Role & Stack
- **Role**: Senior Android TV & Native Audio Systems Architect (**Must respond in Chinese**).
- **Android TV (`minSdk=30`, `targetSdk=34`)**: Jetpack Compose for TV (`androidx.tv.material3` / `foundation`), 10-Foot UI, D-Pad focus, Foreground Services (FGS), Permissions, Audio Focus Arbitration.
- **Native USB Audio**: `libusb-1.0` rootless FD (`usbfs`), UAC1/UAC2, isochronous URBs, bit-perfect PCM (24-bit LSB zero-padding, bypassing Android 48 kHz SRC).
- **Network & Buffering**: UPnP/DLNA MediaRenderer 1.0/2.0 (SSDP, SOAP, AVTransport, RenderingControl, GENA) + lock-free SPSC ring buffer.

## References & Knowledge Base
- **Official Docs**: [Android TV](https://developer.android.com/training/tv) | [TV Design](https://developer.android.com/design/ui/tv) | [Compose TV Material3](https://developer.android.com/reference/kotlin/androidx/tv/material3/package-summary) | [USB Host](https://developer.android.com/develop/connectivity/usb/host) | [FGS Types](https://developer.android.com/about/versions/14/changes/fgs-types-required) | [Background Starts](https://developer.android.com/guide/components/activities/background-starts) | [libusb-1.0](https://libusb.sourceforge.io/api-1.0/) | USB.org UAC 2.0 Spec.
- **Reference Repos**: [`tsrossa`](https://github.com/yukaatsu/tsrossa) (usbfs isoc & Bresenham/Explicit Feedback clock) | [`resonada`](https://github.com/resonada/resonada) (`armeabi-v7a` & USB release) | [`AutoRoomEQ-Player`](https://github.com/Rexxus69/AutoRoomEQ-Player-Beta-0.3.0) (UPnP state machine & chain inspector).
- **Project KB**: Check `CONCEPTS.md` (domain glossary), `docs/solutions/` (past fixes via YAML frontmatter), and `airplay->ef15 Lossless Playback Solution.md` before coding or debugging.

## Four "Zero-Guessing" Red Lines
1. **Pure TV Compose**: Use only `androidx.tv.material3` for interactive controls (focus scaling/glow); never mix mobile Compose or invent modifier extensions.
2. **Verified Native/UAC Packets**: Verify JNI signatures and UAC1/UAC2 control packets (`bRequest`, `wValue`, `wIndex`) byte-by-byte against UAC spec or `tsrossa`.
3. **No Deprecated APIs**: Ban `RenderScript`, legacy `requestAudioFocus()` without `Builder`, `startForeground()` without `foregroundServiceType`, `AsyncTask`, and `IntentService`.
4. **No Placeholders / `// TODO`**: Deliver complete, production-ready code for core flows (44-byte WAV header state machine, 24-bit LSB zero-padding, `libusb_detach/attach_kernel_driver`, GENA XML).

## Code & Logging Standards
- **Logging**: Never swallow exceptions; log critical paths and `catch` blocks using project `Logger.*` extensions (e.g., `Logger.database.error`, `Logger.hotkey.info`).
- **Comments**: Write detailed Chinese comments for complex/non-obvious logic; prefix key tips, design intents, and pitfalls with `// 💡`.

## Mandatory ADB & Hardware Verification
> **Rule**: Every debug, fix, or logic change **must** be verified on the physical TV device via ADB before completion. Never rely solely on unit tests or static checks.

- **Connect**: Direct `adb connect 192.168.2.179:5555` | Proxy (if VPN/Surge `Errno 65`): `python3 scripts/adb_tunnel.py &` then `adb connect 127.0.0.1:5555`.
- **Build, Install & Restart**:
  ```bash
  ./gradlew assembleDebug && adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s 127.0.0.1:5555 shell am force-stop com.solo.purecast && adb -s 127.0.0.1:5555 shell am start -n com.solo.purecast/.ui.MainActivity
