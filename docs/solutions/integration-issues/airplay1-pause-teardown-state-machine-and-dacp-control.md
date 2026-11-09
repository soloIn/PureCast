---
module: airplay-protocol-engine
date: 2026-11-09
problem_type: integration_issue
component: infrastructure
severity: high
symptoms:
  - "iOS端点击暂停导致AirPlay推流元数据被意外冲刷清空，TV端错误重置为等待推流"
  - "TV端在AirPlay暂停后无法通过遥控器或界面发起恢复播放，或直接陷入无流播放的僵尸状态"
  - "iOS暂停与切歌缓冲阶段发送的 progress: 0/0/0 报文导致播放进度和总时长被归零冲刷"
  - "USB声卡常驻独占模式在暂停时被自定义空闲超时倒计时覆盖导致过早释放"
root_cause: logic_error
resolution_type: code_fix
tags:
  - airplay
  - raop
  - dacp
  - state-machine
  - rtsp
  - teardown
  - progress-filtering
  - usb-audio
---

# AirPlay 1 (RAOP) 协议状态混淆与会话生命周期治理

## Problem

在原生 AirPlay 1 (RAOP) 协议实现中，iOS 发送端在用户触发暂停时会通过 RTSP 发送 `TEARDOWN` 指令以断开 UDP RTP 音频端口。旧逻辑将 `TEARDOWN` 传输通道拆除无条件等同于整机会话终止 (Full Session Stop)，导致服务过早清理 DACP 遥控凭据与音轨元数据、TV 端大屏无法使用遥控器反向恢复播放，并在接收到暂停时的 `progress: 0/0/0` 时间戳报文时发生进度与总时长被归零冲刷的级联异常。

## Symptoms

1. **协议日志层**：当 iOS 客户端点击暂停，抓包显示接收端收到 `TEARDOWN rtsp://.../stream RTSP/1.0`。旧版本 `AirPlayServerManager.kt` 立即触发 `jmdnsDiscoveryService.clearDacpTarget()` 与 `dacpClient.clearSession()`，并回调 `onStreamStopped()`，导致 `PlaybackService` 彻底复位 `PlaybackStatus.STOPPED` 与 `StreamProtocol.NONE`。
2. **DACP 反控失效**：TV 端用户在界面上按下遥控器确认键（Play/Pause），由于 DACP 会话凭据已被清除，反向控制 `airPlayServer?.dacpClient?.play()` 无法发出合法的 HTTP 请求，推流无法恢复唤醒。
3. **无效时间戳冲刷**：iOS 暂停与切歌缓冲阶段，RTSP 控制通道会推送 `SET_PARAMETER`，其 Payload 包含 `progress: 0/0/0`。旧逻辑盲目解析并直接赋值 `_playbackPositionMs.value = 0L`，且将 `TrackMetadata.durationMs` 覆盖为 0。
4. **大屏元数据闪退丢失与僵尸播放**：手机端轻点暂停，TV 屏幕上的歌曲名、艺术家和封面瞬间消失，大屏回退至“未在播放”待机页；若强行让 TV 进入 `PLAYING`，大屏进度条空转，但由于手机端未建流、RTP 音频端口已关闭，音响毫无声音输出。
5. **DAC 锁相环破坏与频繁继电器打火**：旧逻辑在收到 `TEARDOWN` 后触发短时空闲让渡（如 10 秒倒计时），导致 USB 声卡频繁在用户短暂停顿（如接听电话、挑选歌曲）时被 `detach` 并交还 Linux 内核驱动，恢复时引发硬件爆音与继电器频繁吸合。

## What Didn't Work

- **尝试 1：将 RTSP TEARDOWN 无条件作为彻底停止处理 (Full Session Stop)**
  - *做法*：在 `raop_server.cpp` 触发 `onStreamStopped` 回调时，直接在 Java 层执行 `stopPlayback(StreamSource.AIRPLAY)`，清理 DACP 会话凭据、重置 `TrackMetadata` 并交还 USB 声卡独占。
  - *失败原因*：违反了 Apple AirPlay 1 (RAOP) 的规范设计。iOS 系统的节电策略会在暂停播放数秒后主动关闭 UDP 音频传输流（发送 `TEARDOWN`），但 AirPlay 投屏会话依然存在于控制中心，且期待接收端通过 DACP（TCP/HTTP 端口）发送 `play` 指令以唤醒并重新发送 `SETUP` / `RECORD` 重建 RTP 通道。直接停止彻底切断了唤醒链路。
- **尝试 2：TV 端点击恢复播放时直接将状态机置为 PLAYING**
  - *做法*：当大屏用户点击播放按钮时，直接设置 `_playbackStatus.value = PlaybackStatus.PLAYING`，并启动本地计时器推算进度。
  - *失败原因*：此时传输层 RTP UDP 端口处于拆除状态（`isRtpActive == false`），iOS 端尚未收到 DACP `play` 或正在重新建立 RTSP 会话。接收端本地无任何音频数据包流入，Native Audio Engine 的 SPSC 环形缓冲区处于空转状态，大屏显示“正在播放”但音响无声，造成严重的 UI/音画脱节（僵尸播放态）。
- **尝试 3：无条件信任 RTSP SET_PARAMETER progress 进度报文**
  - *做法*：只要收到 `SET_PARAMETER` 中带有 `progress: start/curr/end`，便直接更新 `_playbackPositionMs` 和 `TrackMetadata.durationMs`。
  - *失败原因*：iOS 在音频流断开、暂停或切歌缓冲期间，会发送占位报文 `progress: 0/0/0`（RTP 时间戳基准尚未确定）。直接消费该报文导致播放器现有的有效进度和总时长瞬间被洗成 0，大屏进度条剧烈抽搐。
- **尝试 4：在 `UsbDeviceManager.scheduleIdleRelease` 中仅校验全局超时配置**
  - *做法*：为了解决暂停后声卡长时间占用的问题，在暂停时传入 `scheduleIdleRelease(customTimeoutMillis = 10 * 60 * 1000L)`，但原内部实现仅校验了 `if (customTimeoutMillis <= 0L) return`。
  - *失败原因*：当调用方传入自定义超时参数时，若用户在设置中配置了“常驻独占策略 (0秒)”（`idleTimeoutMillis <= 0L`），该保护被完全绕过，破坏了“常驻独占”不自动让渡的核心契约。

## Solution

针对上述问题，PureCast 重构了 AirPlay 状态管理模型，引入分层状态机、反控看门狗、时间戳清洗守卫以及并发安全调度。

### 1. `AirPlayStateManager` 架构：传输层与应用层生命周期解耦

将底层 RTP UDP 传输通道的连通状态（`isRtpActive`）与上层业务会话状态（`PlaybackStatus`、`isDacpActive`、`TrackMetadata`）彻底分离。
- 收到 `SETUP`/`RECORD`：`isRtpActive = true`, `isDacpActive = true`, 状态为 `PLAYING`；
- 收到 `TEARDOWN` 或暂停：仅标记 `isRtpActive = false`，状态进入 `PAUSED`，**完整保留 `TrackMetadata` 与 DACP 会话凭据**，并启动 10 分钟软挂起看门狗（`airPlaySuspendJob`）；
- 只有在 10 分钟软挂起超时或用户明确退出投屏时，才调用 `onFullSessionStop()` 彻底清理会话与声卡独占。

```kotlin
// AirPlayStateManager.kt
fun onStreamTeardown(currentPos: Long = positionMs) {
    status = PlaybackStatus.PAUSED
    isRtpActive = false
    positionMs = currentPos // 保留元数据与冻结进度
}
```

### 2. TV 遥控端 DACP 反向控制与 5 秒 RTP 重连看门狗

TV 端恢复播放时，若检测到底层 RTP 已拆除（`isRtpActive == false`），严禁直接切换为 `PLAYING`。状态机维持 `PAUSED`，通过保留的 DACP 客户端向 iOS 设备下发 `play` 请求，并启动 5 秒重连看门狗任务（`airPlayResumeWatchdogJob`）。

```kotlin
// PlaybackService.kt
fun resumePlayback() {
    Logger.service.i(TAG, "恢复音频播放 (流源: $currentStreamSource)...")
    airPlayResumeWatchdogJob?.cancel()
    airPlayResumeWatchdogJob = null
    airPlaySuspendJob?.cancel()
    airPlaySuspendJob = null
    acquireWifiLowLatencyLock()
    usbDeviceManager.cancelIdleRelease()

    if (currentStreamSource == StreamSource.AIRPLAY) {
        val rtpActive = airPlayServer?.isRtpActive == true
        airPlayServer?.dacpClient?.play()
        if (!rtpActive) {
            Logger.service.i(TAG, "AirPlay RTP 通道已挂起断开 (处于 TEARDOWN 状态)，已通过 DACP 发送 play，等待客户端重连建流...")
            // 💡 启动 5 秒重连看门狗，若超时未收到 SETUP/RECORD 建流则维持 PAUSED 并释放锁
            airPlayResumeWatchdogJob = lifecycleScope.launch(Dispatchers.Default) {
                delay(5000L)
                if (currentStreamSource == StreamSource.AIRPLAY && _playbackStatus.value == PlaybackStatus.PAUSED) {
                    Logger.service.w(TAG, "AirPlay 恢复播放超时 (5秒内未收到客户端重连 RTP)，维持 PAUSED 状态")
                    releaseWifiLowLatencyLock()
                    scheduleAirPlaySuspendTimeout()
                }
            }
            return // 维持 PAUSED，等待推流建立后再切 PLAYING
        }
        airPlayAnchorRealtimeMs = android.os.SystemClock.elapsedRealtime()
        launchAirPlayProgressTicker()
    }

    NativeAudioEngine.resumeStream()
    _playbackStatus.value = PlaybackStatus.PLAYING
}
```

### 3. SET_PARAMETER 全零进度时间戳严格过滤 (`handleProgressUpdate`)

在 `AirPlayStateManager` 中设立时间戳卫语句，凡是满足 `posMs <= 0L && durMs <= 0L` 的非法或占位报文，一律予以拒绝并返回 `false`，保护当前缓存的播放进度与音轨时长不被冲刷。

```kotlin
// AirPlayStateManager.kt
fun handleProgressUpdate(posMs: Long, durMs: Long): Boolean {
    if (posMs <= 0L && durMs <= 0L) {
        return false // 💡 严格过滤 0/0 全零无效时间戳
    }
    if (durMs > 0L) {
        metadata = metadata.copy(durationMs = durMs)
    }
    positionMs = posMs
    return true
}

// PlaybackService.kt
private fun onAirPlayProgressUpdated(positionMs: Long, durationMs: Long) {
    if (!airPlayStateManager.handleProgressUpdate(positionMs, durationMs)) {
        return // 💡 过滤掉全零时间戳
    }
    val current = _currentMetadata.value
    if (durationMs > 0 && current.durationMs != durationMs) {
        val updated = current.copy(durationMs = durationMs, isLiveStream = false)
        updateMetadata(updated)
    }
    startOrUpdateAirPlayProgress(positionMs, durationMs)
    updateMediaSessionState()
}
```

### 4. USB 声卡独占让渡双重看门卫条件 (`scheduleIdleRelease`)

在 `UsbDeviceManager.scheduleIdleRelease` 中加入双重校验：既检查全局配置的 `idleTimeoutMillis <= 0L`，也检查传入的 `customTimeoutMillis <= 0L`。只要满足任一条件，即视作配置了常驻独占策略，立即注销回调并退出，绝不投递延迟归还任务。

```kotlin
// UsbDeviceManager.kt
fun scheduleIdleRelease(customTimeoutMillis: Long = idleTimeoutMillis) {
    mainHandler.removeCallbacks(releaseRunnable)
    if (idleTimeoutMillis <= 0L || customTimeoutMillis <= 0L) {
        Logger.usb.i(TAG, "已配置常驻独占策略 (0秒)，保持声卡独占不自动让渡")
        return
    }
    Logger.usb.i(TAG, "启动 ${customTimeoutMillis / 1000} 秒空闲声卡让渡倒计时...")
    mainHandler.postDelayed(releaseRunnable, customTimeoutMillis)
}
```

### 5. `StreamDemuxer` 切歌并发注销与有序重置

在切换流时，不仅调用异步任务关闭旧的 Socket 连接和输入流（强迫阻塞在 JNI 层的底层解码器抛出异常中断），而且在新的 `streamingJob` 内部执行 `previousJob?.cancelAndJoin()`，确保前序解码协程彻底退出后，才重置 Native 采样计数器并建立新连接。

```kotlin
// StreamDemuxer.kt
val previousJob = streamingJob
val oldConn = activeConnection
val oldStream = activeInputStream
activeConnection = null
activeInputStream = null

// 💡 快速断开旧连接与输入流，促使阻塞在 JNI 的 dr_flac 或 read 立即抛异常退出
scope.launch(Dispatchers.IO) {
    try {
        oldStream?.close()
        oldConn?.disconnect()
    } catch (e: Exception) {
        // ignore
    }
}

streamingJob = scope.launch(Dispatchers.IO) {
    // 💡 确保前序 native 解码任务彻底退出，消除快速切歌时计数器与 JNI 资源的并发竞争
    try {
        previousJob?.cancelAndJoin()
    } catch (e: Exception) {
        // ignore
    }
    if (thisSession != sessionToken.get()) return@launch

    NativeAudioEngine.resetFlacDecodedFrames()
    ...
```

### 6. 基于真实生产组件的单元测试重构

移除原先在单元测试内编写的虚假 Mock 闭包与测试内部 dummy 类，改由生产级 `AirPlayStateManager` 直接承接单元测试校验，确保测试覆盖真正的生产状态流转逻辑。

## Why This Works

1. **Apple AirPlay 1 (RAOP) 协议非对称性与节能机制**：Apple 协议在控制平面（RTSP TCP 5000 + DACP HTTP）与数据平面（UDP RTP 音频流）之间采取非对称管理。iOS 端暂停时拆除 UDP 端口属于节能设计，控制平面始终存活。将 `TEARDOWN` 等同于会话终止混淆了传输层与应用层。
2. **状态机解耦：物理信道挂起 vs 逻辑会话存活**：通过 `AirPlayStateManager`，接收端在 `TEARDOWN` 时维持 `(PlaybackStatus.PAUSED, isRtpActive=false, isDacpActive=true)`。TV 端大屏保持音轨元数据与进度展示，硬件层保持 USB DAC 锁相，彻底杜绝了频繁重置声卡导致的时钟漂移与硬件爆音。
3. **DACP 反控闭环与状态同步看门狗**：TV 遥控器的主动唤醒依赖 DACP 反向控制。设置 5 秒重连看门狗（`airPlayResumeWatchdogJob`）确保了容错闭环：在超时未建流时优雅保持 `PAUSED` 并重新挂载 10 分钟闲置让渡任务，防止系统进入僵尸播放状态。
4. **硬件独占与常驻策略契约一致性**：`scheduleIdleRelease` 修复后的双重卫语句，彻底捍卫了用户配置“0 秒常驻独占”时的硬件契约，阻断了任何上层业务暂停误触让渡的通道。

## Prevention

1. **状态机分层设计准则**：严禁跨层状态等价，传输层（Socket/RTP/UDP）断开绝不等价于应用层（PlaybackStatus/Metadata）结束。任何反向控制依赖上行凭据的协议在网络传输通道断开时必须设立挂起中间态，并由独立看门狗定时器托管生命周期。
2. **报文容错与防抖净化规范**：网络推流端上报的时间戳、采样率、声道数进入状态机前必须经过领域校验器（如 `handleProgressUpdate`），拦截全零、负数或越界时间戳。
3. **硬件生命周期守则**：涉及底层硬件独占让渡（USB DAC、WakeLock、WifiLock）的方法，必须在入口处强制核验常驻策略配置（如 `<= 0L`），杜绝参数重载导致的契约穿透。
4. **测试与验证范式**：拒绝测试内 Mock 闭包，单元测试必须直接实例化真实生产状态管理类（如 `AirPlayStateManager`），确保测试验证的逻辑与生产运行的代码完全一致。
