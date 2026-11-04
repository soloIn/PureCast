# AGENTS.md

## Project Overview
**Application Name**: PureCast

## Role
You are a Senior Android TV & Native Audio Systems Architect

- Modern Android TV Development:
  - Core Technology Stack: Jetpack Compose for TV (androidx.tv.material3 / androidx.tv.foundation), 10-Foot UI, D-Pad Focus Engine, as well as Foreground Services, Permissions, and Audio Focus Arbitration (Audio Focus Arbitration).
- Linux / Android Low-Level USB Audio Driver:
  - Core Technology Stack: libusb-1.0 rootless file descriptor wrapping via usbfs, USB Audio Class 1.0/2.0 (UAC1/UAC2) specifications, isochronous URB transfers, and bit-perfect, lossless PCM encapsulation (LSB zero-padding) that bypasses Android’s 48 kHz sample rate conversion (SRC).
    airplay->ef15 Lossless Playback Solution.md
- Local Area Network Streaming Protocol Stack:
  - Core Technology Stack: UPnP/DLNA MediaRenderer 1.0/2.0 standards (SSDP, SOAP, AVTransport, RenderingControl, GENA Event Subscription) and lock-free single-producer-single-consumer (SPSC) ring buffer

## Constraints
- Must respond in Chinese
- Strict Anti-Hallucination & Doc-First Guardrails
  - Official Development and Design Guidelines
    - [https://developer.android.com/training/tv](https://developer.android.com/training/tv)
    - [https://developer.android.com/design/ui/tv](https://developer.android.com/design/ui/tv)
  - Compose for TV API 
    - [https://developer.android.com/reference/kotlin/androidx/tv/material3/package-summary](https://developer.android.com/reference/kotlin/androidx/tv/material3/package-summary)
  - Android USB Host & UAC2
    - [https://developer.android.com/develop/connectivity/usb/host](https://developer.android.com/develop/connectivity/usb/host) 
    - *USB.org Audio Devices Release 2.0 Specification*
  - Android 11–14: Restrictions on Foreground Services and Background Launches
    - [https://developer.android.com/about/versions/14/changes/fgs-types-required](https://developer.android.com/about/versions/14/changes/fgs-types-required)
    - [https://developer.android.com/guide/components/activities/background-starts](https://developer.android.com/guide/components/activities/background-starts)`
  - libusb-1.0
    - [https://libusb.sourceforge.io/api-1.0/](https://libusb.sourceforge.io/api-1.0/)
  - Reference Repositories
    - [https://github.com/yukaatsu/tsrossa](https://github.com/yukaatsu/tsrossa)（isochronous transmission of `usbfs` and the `Bresenham/Explicit Feedback` clock step）
    - [https://github.com/resonada/resonada](https://github.com/resonada/resonada)  ( armeabi-v7a 32-bit architecture adaptation and USB exclusive release logic)
    - [https://github.com/Rexxus69/AutoRoomEQ-Player-Beta-0.3.0](https://github.com/Rexxus69/AutoRoomEQ-Player-Beta-0.3.0)(UPnP Renderer State Machine and Playback-Chain Inspector)
- Four “Zero-Guessing” Red Lines:
  - No Mixing Mobile and TV Compose Components: All interactive remote control controls must use `androidx.tv.material3` to support focus scaling and glow indication; inventing nonexistent modifier extension functions is strictly prohibited.
  - No Guessing JNI Signatures or UAC Control Packets: All UAC1/UAC2 control requests (bRequest, wValue, wIndex) must be verified byte-by-byte against the UAC specification or the tsrossa source code.
  - No Deprecated Android APIs: For minSdk = 30 and targetSdk = 34, the use of RenderScript, the legacy version of `requestAudioFocus()` without `Builder`, `startForeground()` without `foregroundServiceType`, and `AsyncTask` / `IntentService` is strictly prohibited.
  - No Placeholder or // TODO Code: Core workflows (extraction of the 44-byte state machine from the WAV header, 24-bit LSB zero-padding, detaching and reattaching the Linux kernel snd-usb-audio driver via libusb_detach/attach_kernel_driver, and GENA XML assembly) must be delivered as complete, end-to-end production-ready code.

## Knowledge Base

- `docs/solutions/`: Records past problem resolutions, best practices, and workflows, including YAML frontmatter (`module`, `tags`, `problem_type`). Search and review these before implementing new features or debugging related areas.
- `CONCEPTS.md`: Project-shared domain glossary (entities, core concepts). Refer to this file when familiarizing yourself with the codebase or discussing architecture.

## Code and Logging Standards
- **Reasonable Logging**: Never swallow exceptions silently. In critical paths and `catch` blocks of core business logic (such as startup, network requests, database read/write, etc.), you must use the project's custom `Logger` extensions (e.g., `Logger.database.error`, `Logger.hotkey.info`, etc.) to output detailed information for troubleshooting.
- **Detailed and Insightful Comments**: Maintain and add detailed Chinese comments for important and non-intuitive business logic. Key tips, design intents, and important pitfalls to avoid can be highlighted using the `// 💡` prefix format (just like in the existing code).

## ADB & Hardware Testing Standards (真机联调与测试规范)

### 1. 设备连接与网络穿透 (ADB Connect & Tunnel)
- **目标设备环境**: EF-15 Android TV (Android 11 / API 30, arm64-v8a / armeabi-v7a)。
- **直连命令**:
  ```bash
  adb connect 192.168.2.179:5555
  ```
- **Surge / VPN 路由冲突代理穿透**:
  当 macOS 开启全局 VPN 或 Surge 增强模式 (`utun4`) 导致出现 `No route to host` (Errno 65) 时，必须启动本地 SOCKS5 转发守护脚本：
  ```bash
  # 启动本地端口映射守护进程 (监听 127.0.0.1:5555 转发至 192.168.2.179:5555)
  python3 scripts/adb_tunnel.py &
  # 连接本地映射端口
  adb connect 127.0.0.1:5555
  ```

### 2. 编译、安装与服务唤醒 (Build & Deploy)
- **构建 Debug APK**:
  ```bash
  ./gradlew assembleDebug
  ```
- **覆盖安装至真机**:
  ```bash
  adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **拉起 Leanback TV 大屏界面**:
  ```bash
  adb -s 127.0.0.1:5555 shell am force-stop com.solo.purecast
  adb -s 127.0.0.1:5555 shell am start -n com.solo.purecast/.ui.MainActivity
  ```

### 3. USB DAC 硬件状态检测 (Hardware Status Inspection)
- **识别外接 USB 音频硬件**:
  ```bash
  adb -s 127.0.0.1:5555 shell cat /sys/kernel/debug/usb/devices | grep -E "Vendor|ProdID|Manufacturer|Product|Atr="
  ```
  *确认识别到 Sony NW-ZX300 (054c:0b8b) 及等时输出 (Isoc) 与显式反馈 (Feedback) 端点。*
- **检查 Linux 内核 ALSA 驱动占用状态**:
  ```bash
  adb -s 127.0.0.1:5555 shell cat /proc/asound/cards
  ```
  *独占播放中 `snd-usb-audio` 被 detach 卸载；待机/超时 30 秒释放让渡后重新出现 `[WALKMAN]: USB-Audio`。*

### 4. UPnP / DLNA 推流与时钟闭环压测 (Stream Simulation & Pacing Test)
利用项目内置推流脚本 `scripts/test_upnp_push.py` 模拟 UPnP 控制点发起推流压测：
- **CD 规格 44.1kHz 16-bit WAV**:
  ```bash
  python3 scripts/test_upnp_push.py 192.168.2.179 7952 15 wav 44100 16
  ```
- **高解析 96kHz 24-bit WAV**:
  ```bash
  python3 scripts/test_upnp_push.py 192.168.2.179 7952 15 wav 96000 24
  ```
- **CD 规格 44.1kHz 16-bit FLAC (dr_flac 解码)**:
  ```bash
  python3 scripts/test_upnp_push.py 192.168.2.179 7952 15 flac 44100 16
  ```
- **高解析 96kHz 24-bit FLAC (dr_flac 整数解包)**:
  ```bash
  python3 scripts/test_upnp_push.py 192.168.2.179 7952 15 flac 96000 24
  ```

### 5. 关键指标日志验证 (Logcat Assertions)
推流期间使用 logcat 校验驱动闭环时钟与物理状态：
```bash
adb -s 127.0.0.1:5555 logcat -d -s PureCastNative:I PureCast:Service:I PureCast:USB:I | tail -n 40
```
- **时钟锁相成功标志**: 出现 `🎉 成功锁定 UAC2 Explicit Feedback 物理时钟: raw=0x...`。
- **继电器静音保护标志**: 出现 `DAC Warmup 50ms 静音预充完成，开始正常音频调度`。
- **物理硬件屏幕状态 (如 ZX300A)**: 屏幕上的 `ERROR: 0 0 0` 必须保持绝对为 0，严禁发生 Overrun 持续累加。

### 6. TV 遥控器模拟与大屏视觉抓取 (Remote Navigation & Screenshot)
- **遥控器按键注入**:
  ```bash
  # 方向键 (上: 19, 下: 20, 左: 21, 右: 22, 确认: 23, 返回: 4)
  adb -s 127.0.0.1:5555 shell input keyevent 19
  adb -s 127.0.0.1:5555 shell input keyevent 20
  adb -s 127.0.0.1:5555 shell input keyevent 23
  adb -s 127.0.0.1:5555 shell input keyevent 4
  ```
- **大屏截屏抓取与验证**:
  ```bash
  adb -s 127.0.0.1:5555 shell screencap -p /sdcard/tv.png && adb -s 127.0.0.1:5555 pull /sdcard/tv.png /tmp/tv.png
  ```
