---
title: PureCast 阶段三：TV 权限向导、前台通知与 UPnP 协议规范深度完善 - Plan
type: feat
date: 2026-11-04
origin: 项目背景与核心目标.md
artifact_contract: ce-unified-plan/v1
product_contract_source: legacy-requirements
execution: code
---

# PureCast 阶段三：TV 权限向导、前台通知与 UPnP 协议规范深度完善实现计划

---

## Goal Capsule

- **Objective**: 依照《项目背景与核心目标.md》第 4.1 与 4.4 节规范，构筑完备的 Android TV 首次开机权限向导体系与全功能 UPnP/DLNA 渲染器。实现开机首次运行权限引导向导（自检录音权限、悬浮窗、电池白名单及防权限自动重置）；补齐 Manifest 权限与电视快速开机广播；突破 BAL 限制实现后台推流自动拉起大屏及全屏通知保底；深度集成 Android `MediaSessionCompat`；补全 UPnP AVTransport 的 `Seek`、`Next`、`Previous` SOAP 动作与真实播放时间戳计算；实现流内嵌元数据提取与设备广播名自定义持久化。
- **Means**: 编写 Compose TV 权限引导向导组件；更新 `AndroidManifest.xml`；在 `PlaybackService` 中构建 `MediaSessionCompat` 并管理动态 `WifiLock`；升级 `UpnpRendererServer.kt` 响应 `Seek`/`Next`/`Previous`；在 `StreamDemuxer.kt` 中引入采样计数器与 `LIST INFO` 解析器；创建 `SettingsRepository` 持久化 UPnP 设备名。
- **Authority Hierarchy**:
  1. 产品行为以 `Product Contract` 中明确的 R-IDs 为唯一权威；
  2. 底层驱动机制与技术实现以 `Planning Contract` 中明确的 KTD-IDs 为唯一权威；
  3. 实现单元（U-IDs）严格遵循上述规范，不任意扩展业务边界。
- **Stop Conditions**: 新安装 APK 或冷启动后，TV 界面优雅引导用户完成授权检查；后台待机时 iPhone 发送 AirPlay 投送，TV 自动弹出大屏播放界面；遥控器媒体按键与 MediaSession 完美同步；UPnP 控制点准确获取播放进度时间；设备重启或热插拔 100% 免弹窗静默恢复。
- **Execution Profile**: 适用于 `ce-work` 执行，结合真机无线 ADB 与 Python 模拟测试。

---

## Product Contract

### Summary
本实施计划为 PureCast 平台合规、权限自检守护与 UPnP 协议规范的深度落地计划。彻底解决用户初次安装缺少授权引导、后台无法自动弹出大屏、缺少系统级 MediaSession 联动以及 UPnP 协议动作不全的问题，打造生产级健壮性。

### Problem Frame
1. 当前应用完全依赖开发人员在电脑上敲 ADB 命令授权，没有针对普通电视用户的首次启动引导向导，电视用户直接安装将导致 USB 无法持久授权；
2. 缺少 Android 13+ `POST_NOTIFICATIONS` 声明，且未监听 `QUICKBOOT_POWERON`，导致部分品牌电视快速开机无法自启；
3. 后台收到推流时无法突破 BAL 限制唤醒界面，缺少全屏通知保底；
4. 缺少 Android `MediaSessionCompat`，无法响应系统媒体按键；
5. UPnP 服务端缺少 `Seek`、`Next`、`Previous` 处理，且 `GetPositionInfo` 返回全零时间；
6. UPnP 广播名称写死无法自定义。

### Key Decisions
- **KD1. TV 专属零录音隐私约定与一键权限向导**: 打造针对 10-Foot 遥控器的全屏引导向导。自检 `RECORD_AUDIO`、悬浮窗 `SYSTEM_ALERT_WINDOW`、电池优化白名单与权限自动回收状态；明确向用户展示“零录音硬件调用承诺”，引导遥控器一键跳转系统授权 (session-settled: user-directed — chosen over 依赖命令行注入: 实现消费级电视开箱即用). *Governs R2.*
- **KD2. 突破 BAL 限制的双重保底唤醒机制**: 收到 UPnP `Play` 指令时，优先通过 `FLAG_ACTIVITY_NEW_TASK` 拉起大屏；针对无法悬浮窗弹出的电视，前台服务立即无头直通出声，并通过带 `PendingIntent` 和 `fullScreenIntent` 的系统媒体通知作为无头保底 (session-settled: user-directed — chosen over 强依赖界面弹出: 确保音频播放 100% 不受阻碍). *Governs R2.*
- **KD3. MediaSessionCompat 深度绑定与系统级状态机同步**: 在前台服务中建立 `MediaSessionCompat`，发布播放/暂停状态及当前曲目元数据，将遥控器媒体按键与 UPnP GENA 事件通知彻底打通 (session-settled: user-directed — chosen over 仅靠 Activity 监听按键: 保证全局媒体键响应). *Governs R5.*
- **KD4. 精确采样率时间戳计算与 LIST INFO 元数据解析**: 结合已消费 PCM 字节数与采样率，动态计算 `RelTime` 并通过 UPnP 返回；流式解析 WAV 头部 `LIST INFO` 块（`INAM`, `IART`），在 DIDL-Lite 为空时自动兜底提取歌名与歌手 (session-settled: user-directed — chosen over 硬编码全零: 完善标准 UPnP 兼容性). *Governs R5.*
- **KD5. UPnP 自定义广播名称持久化**: 支持在设置中修改并在 `SharedPreferences` 保存友好名称（如 `PureCast (ZX300A)`），自动触发 SSDP 重新广播 (session-settled: user-directed — chosen over 硬编码名称: 方便多房间多设备识别). *Governs R5.*

### Requirements
- **R2-P1. 开机权限向导与零录音承诺**: 提供开机自检界面，检查并引导 `RECORD_AUDIO`、悬浮窗、电池白名单及防权限重置；承诺全工程零 `AudioRecord` 调用。
- **R2-P2. Manifest 声明与广播适配**: 声明 `POST_NOTIFICATIONS`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，注册 `QUICKBOOT_POWERON` 广播。
- **R2-P3. 后台唤醒与低延迟锁按需调度**: 收到推流时拉起界面或通知保底；播放时激活 `WIFI_MODE_FULL_LOW_LATENCY`，空闲时释放。
- **R5-P1. MediaSessionCompat 绑定**: 建立系统级媒体会话，同步播放状态与元数据，绑定前台通知 `MediaStyle`。
- **R5-P2. UPnP AVTransport 动作补全**: 支持 `Seek`、`Next`、`Previous` SOAP 动作解析与状态流转；在 SCPD XML 中声明。
- **R5-P3. 真实时间戳与内嵌元数据**: 精确计算已播放时间 `RelTime`；流式提取 WAV `LIST INFO` 文本标签。
- **R5-P4. 局域网广播名自定义**: 提供名称设置，重启 SSDP 广播。

### Scope Boundaries
- **In Scope**:
  - `app/src/main/AndroidManifest.xml` 更新。
  - `app/src/main/java/com/solo/purecast/ui/screen/OnboardingWizard.kt` 新建。
  - `app/src/main/java/com/solo/purecast/service/PlaybackService.kt` 绑定 MediaSession 与 WifiLock。
  - `app/src/main/java/com/solo/purecast/upnp/*` 补充 SOAP 动作与时间计算。
  - `app/src/main/java/com/solo/purecast/data/SettingsRepository.kt` 新建。
- **Deferred to Follow-Up Work**:
  - DSD DoP 直通。
  - 本地媒体库扫描。

### Success Criteria
- **SC1. 电视端免 ADB 首次开机向导完成**: 在未通过 ADB 授权的全新安装设备上打开 App，向导能够清晰引导用户完成授权，后续热插拔 DAC 零弹窗。
- **SC2. 后台推流自动唤醒**: App 在后台待机时，从 iPhone 发起 AirPlay 投送，电视大屏自动点亮并展示播放界面。
- **SC3. MediaSession 联动**: 电视遥控器媒体键（Play/Pause/Stop）能够全局控制播放，并同步更新手机端 AirPlay 状态。
- **SC4. UPnP 时间轴与元数据准确**: 控制点能通过 `GetPositionInfo` 获取实时更新的已播放时长。

---

## Planning Contract

### Key Technical Decisions
- **KTD1. 权限向导弹窗触发策略**:
  - 启动时通过 `PermissionHelper` 检查权限；
  - 若四项（录音权限、悬浮窗、电池白名单、防自动回收）有任一未就绪，且用户未标记“不再提示”，在 `MainTvScreen` 上覆盖展示 `OnboardingWizard`。
- **KTD2. 悬浮窗跳转容错机制**:
  - 调用 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 时使用 `intent.resolveActivity(packageManager)` 与 `try-catch` 保护，在缺少该界面的电视盒子上不崩溃并提示跳过。
- **KTD3. MediaSessionCompat 架构**:
  - 构建 `MediaSessionCompat(context, "PureCastMediaSession")`；
  - 注册 `MediaSessionCompat.Callback` 处理 `onPlay`, `onPause`, `onStop`, `onSkipToNext`, `onSkipToPrevious`, `onSeekTo`；
  - 联动 `PlaybackService` 与 `GenaEventNotifier`。
- **KTD4. 真实播放时间跟踪**:
  - 在 `NativeAudioEngine` 或 `StreamDemuxer` 中维护 `total_samples_consumed` 原子计数器；
  - `current_seconds = total_samples_consumed / sample_rate`；
  - 格式化为 `String.format("%02d:%02d:%02d", h, m, s)` 返回给 UPnP。

---

## Implementation Units

### U1. TV 专属开机权限与自检引导向导 (TV Onboarding & Permission Wizard)
- **Goal**: 构建专门适配电视遥控器的首次开机权限向导组件，向用户明确说明零录音约定，引导授予录音、悬浮窗、电池优化与防权限自动重置。
- **Requirements**: R2-P1
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/util/PermissionHelper.kt`
  - `app/src/main/java/com/solo/purecast/ui/screen/OnboardingWizard.kt`
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt`
- **Approach**:
  1. 编写 `PermissionHelper.kt`：
     - 检查 `RECORD_AUDIO` 权限。
     - 检查 `Settings.canDrawOverlays()`。
     - 检查 `PowerManager.isIgnoringBatteryOptimizations()`。
     - 检查 API 30+ `PackageManager.isAutoRevokeWhitelisted()`。
  2. 编写 `OnboardingWizard.kt`：
     - Apple TV 风格半透明卡片，列出 4 项权限状态。
     - 明确阐明：“本应用绝不初始化 AudioRecord 硬件录音，录音权限仅用于满足系统对 USB 声卡静默接管的合规要求”。
     - 提供遥控器高亮按钮引导一键跳转系统设置页（对电视缺失的 Activity 增加 try-catch 保护）。
  3. 当检测到全部完成或用户点击跳过时平滑退出。
- **Test scenarios**:
  - 全新安装环境打开 App，验证自动弹出向导卡片，焦点默认停留在第一项待授权按钮上。
  - 在未提供悬浮窗界面的电视盒子上点击跳转，不发生崩溃。
- **Verification**: 真机测试向导流程顺畅，完成引导后设备重启或重新插拔 DAC 全程零弹窗。

---

### U2. Manifest 声明补齐与电视快速开机广播适配 (Manifest Permissions & QuickBoot)
- **Goal**: 补充 Android 13+ `POST_NOTIFICATIONS` 权限声明、忽略电池优化权限声明，并在广播接收器中注册 `android.intent.action.QUICKBOOT_POWERON`。
- **Requirements**: R2-P2
- **Dependencies**: None
- **Files**:
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/solo/purecast/core/usb/UsbDeviceReceiver.kt`
- **Approach**:
  1. 在 `AndroidManifest.xml` 中添加：
     - `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
     - `<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />`
  2. 在 `UsbDeviceReceiver` 的 `<intent-filter>` 中增加：
     - `<action android:name="android.intent.action.QUICKBOOT_POWERON" />`
     - `<action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />`
  3. 在 `UsbDeviceReceiver.onReceive` 中处理快速开机广播，拉起 `PlaybackService`。
- **Test scenarios**:
  - 通过 ADB 模拟发送快速开机广播：`adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON`，验证 Service 自动拉起并枚举 USB 设备。
- **Verification**: 广播接收器在各类电视芯片冷开机/快开机时均能稳定触发服务初始化。

---

### U3. 突破 BAL 后台唤醒与全屏通知保底 (BAL Wakeup & Low Latency Lock Cycling)
- **Goal**: 收到 UPnP `Play` 指令时自动从后台唤醒大屏播放页，无法唤醒时通过高优先级全屏通知保底；动态按需获取与释放 Wi-Fi 低延迟锁。
- **Requirements**: R2-P3
- **Dependencies**: U2
- **Files**:
  - `app/src/main/java/com/solo/purecast/service/PlaybackService.kt`
  - `app/src/main/java/com/solo/purecast/upnp/UpnpRendererServer.kt`
- **Approach**:
  1. 在 `PlaybackService.startStreaming` 触发时，若 App 处于后台，构建 `Intent(this, MainActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK)` 尝试拉起 Activity。
  2. 在 `buildNotification()` 中加入 `setFullScreenIntent(pendingIntent, true)`，确保在无法前台弹出时状态栏常驻高优先级通知。
  3. 改造 `WifiLock` 调度：
     - `acquireWifiLock()` 仅在 `startStreaming`（`PLAYING`）时持有。
     - `releaseWifiLock()` 在 `stopPlayback` / `scheduleIdleRelease`（`STOPPED` / `IDLE`）时主动释放，降低待机功耗。
- **Test scenarios**:
  - 按电视 Home 键将 App 退至后台待机，从 iPhone 推流，验证电视自动拉起播放页。
  - 停止推流后查看日志，确认 `WifiLock` 已安全释放。
- **Verification**: 后台唤醒成功率 100%，待机功耗大幅降低。

---

### U4. Android MediaSessionCompat 深度集成 (MediaSession Integration)
- **Goal**: 在 `PlaybackService` 中集成 `MediaSessionCompat`，实现系统级媒体会话同步、遥控器物理按键响应与通知联动。
- **Requirements**: R5-P1
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/service/PlaybackService.kt`
- **Approach**:
  1. 初始化 `MediaSessionCompat(this, "PureCastMediaSession")`。
  2. 注册 `Callback`：
     - `onPlay()` -> 调用 `resumePlayback()`。
     - `onPause()` -> 调用 `pausePlayback()`。
     - `onStop()` -> 调用 `stopPlayback()`。
     - `onSkipToNext()` / `onSkipToPrevious()` -> 若为实时流则提示不支持，否则通知上游。
  3. 当播放状态改变或元数据更新时，更新 `setPlaybackState` 与 `setMetadata`。
  4. 将通知样式设置为 `androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken)`。
- **Test scenarios**:
  - 通过 ADB 模拟系统媒体广播：`adb shell input keyevent 85`，验证 `MediaSession` 正确响应暂停与恢复。
- **Verification**: 系统媒体状态机与 UPnP 播放完全同步，支持各类蓝牙遥控器媒体专用按键。

---

### U5. UPnP AVTransport SOAP 指令集补齐 (Seek, Next, Previous SOAP Actions)
- **Goal**: 在 `UpnpRendererServer` 中实现 `Seek`、`Next`、`Previous` 的处理逻辑，并在 SCPD XML 中合规声明。
- **Requirements**: R5-P2
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/upnp/UpnpRendererServer.kt`
- **Approach**:
  1. 在 `buildAvTransportScpdXml()` 的 `<actionList>` 中加入 `Seek`, `Next`, `Previous` 定义。
  2. 在 `handleHttpPost` 中解析 SOAP Action：
     - `Seek`：解析 `Target` 寻道时间，若当前为实时流则返回 SOAP 故障码 701（Transition not available），普通流则触发寻道。
     - `Next` / `Previous`：若为实时流返回 701，普通流通知控制端切歌。
  3. 返回合规的 `<u:SeekResponse>` 等 XML 响应。
- **Test scenarios**:
  - 使用 Python 脚本向 AVTransport 发送 `Seek` 与 `Next` SOAP 动作，验证返回 200 OK 与合规 SOAP 报文。
- **Verification**: UPnP 控制点（如 BubbleUPnP / Audirvana）能完整识别所有播放控制动作。

---

### U6. 真实时间戳上报与流内嵌元数据提取 (Accurate RelTime & LIST INFO Demuxing)
- **Goal**: 实时统计已播放 PCM 采样数，在 `GetPositionInfo` 中返回真实 `RelTime`；在 `StreamDemuxer` 中流式解析 WAV `LIST INFO` 块提取标题与艺术家。
- **Requirements**: R5-P3
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/upnp/StreamDemuxer.kt`
  - `app/src/main/java/com/solo/purecast/upnp/UpnpRendererServer.kt`
- **Approach**:
  1. 在 `StreamDemuxer` 中增加 `totalBytesStreamed` 计数，并提供 `getCurrentPlayPositionSeconds(): Long` 方法。
  2. `UpnpRendererServer.handleGetPositionInfo()` 调用该方法计算时分秒，组装为 `<RelTime>HH:mm:ss</RelTime>` 返回。
  3. 在 `StreamDemuxer.parseHeader` 遇到 `LIST` Chunk 时，进一步解析其内部包含的 `INAM`（Title）、`IART`（Artist）子块，若上游 DIDL-Lite 为空则使用解析出的元数据更新大屏展示。
- **Test scenarios**:
  - 播放 10 秒后发送 `GetPositionInfo` 查询，验证返回 `<RelTime>00:00:10</RelTime>`。
  - 推送带 `LIST INFO` 的测试 WAV 文件，验证大屏能自动显示歌曲名。
- **Verification**: 播放时间指示真实动态增长，内嵌元数据成功显示。

---

### U7. 局域网广播名自定义与持久化 (Custom UPnP Friendly Name)
- **Goal**: 提供 `SettingsRepository` 持久化用户自定义的广播名称，并在修改后重启 UPnP 广播，方便多房间识别。
- **Requirements**: R5-P4
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/data/SettingsRepository.kt`
  - `app/src/main/java/com/solo/purecast/ui/screen/SettingsDrawer.kt`
  - `app/src/main/java/com/solo/purecast/upnp/UpnpRendererServer.kt`
- **Approach**:
  1. 创建 `SettingsRepository.kt`，利用 `SharedPreferences` 保存 `friendly_name`（默认 `"PureCast TV"`）。
  2. 在 `SettingsDrawer` 中增加名称显示与预设快速切换（如 `"PureCast TV"`, `"PureCast (ZX300A)"`, `"客厅 PureCast"`）。
  3. 用户切换后调用 `upnpServer.updateFriendlyName(newName)`：先发送 `ssdp:byebye`，更新 UUID 与名称后重新启动 SSDP 广播。
- **Test scenarios**:
  - 在侧边栏修改名称为 `PureCast (ZX300A)`，查看局域网 iPhone AirPlay 列表是否刷新为新名称。
- **Verification**: 设备改名无需重启 App，局域网发现即刻生效。

---

## Verification Contract

### 质量门禁与验证命令
1. **构建与单元测试**:
   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew assembleDebug
   ```
2. **真机无线部署**:
   ```bash
   ./scripts/adb_remote_install.sh 127.0.0.1 5555
   ```
3. **UPnP 扩展动作与时间戳自动化脚本验证**:
   ```bash
   python3 -c "
   import urllib.request
   req = urllib.request.Request('http://127.0.0.1:7952/AVTransport/control', data=b'...', headers={'SOAPACTION': '\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"'})
   "
   ```
4. **后台唤醒与广播自启断言**:
   ```bash
   adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.solo.purecast
   ```

---

## Definition of Done

- [ ] 电视端首次开机权限向导组件完整可用，零录音硬件调用承诺清晰。
- [ ] Manifest 补充 `POST_NOTIFICATIONS`，快速开机广播 `QUICKBOOT_POWERON` 正常监听。
- [ ] 突破 BAL 限制，后台收到推流能够自动拉起播放大屏，全屏通知保底有效。
- [ ] `MediaSessionCompat` 深度集成，响应遥控器全局媒体按键。
- [ ] UPnP `Seek`、`Next`、`Previous` SOAP 动作合规响应，播放时长动态精确上报。
- [ ] UPnP 设备广播名称支持自定义与即时生效。
