---
module: network-streaming
date: 2026-11-08
problem_type: integration_issue
component: infrastructure
severity: high
symptoms:
  - "电视冷开机后局域网无法搜到 PureCast 的 AirPlay (_raop._tcp) 与 UPnP 广播，但同机 AirReceiver 秒级在线"
  - "冷开机 Direct Boot 阶段 PureCastApp 抛出 IllegalStateException: SharedPreferences in credential encrypted storage are not available"
  - "UPnP 快速切歌时旧协程 finally 块重置状态误杀新流，导致切歌失败中断"
  - "UPnP 与 AirPlay 跨协议同时投送时双流混音，声卡时钟冲突"
root_cause: system_environment
resolution_type: code_fix
tags: [android-tv, direct-boot, jmdns, ssdp-multicast, multi-nic, airplay, upnp, race-condition]
last_updated: 2026-11-08
---

# Android TV 开机静默自启广播不可达与多网卡/Direct Boot 陷阱排查

## Problem
在爱普生 EF-15 电视（Android 11, TV 投影系统）上，要求 PureCast 作为底层后台音频服务静默开机自启动，绝不开机主动拉起主界面打扰电视系统 Launcher。但在用户重启电视且未主动点击打开 PureCast 时，局域网内的 iOS / macOS 隔空播放设备列表与 DLNA 控制端完全搜索不到 PureCast 的广播，而同台电视上的第三方应用 AirReceiver 却能秒级被局域网发现。此外，冷开机日志中存在 Direct Boot 崩溃，并在流媒体投送中存在切歌被旧协程打断与双协议混音冲突。

## Symptoms
1. **网络广播彻底隐形**：开机后在局域网 Mac 终端执行 `dns-sd -B _raop._tcp local`，能看到同机的 `62A6041348C5@TV`（AirReceiver），但搜不到 PureCast 的 `1485540BCBC4@TV [AirPlay]`；UPnP/DLNA 亦无法收到 M-SEARCH 探测响应。
2. **Direct Boot 瞬时闪退**：开机首要阶段（用户凭据尚未解密）触发 USB 或 Direct Boot 广播拉起进程时，系统抛出致命崩溃：
   ```text
   java.lang.IllegalStateException: SharedPreferences in credential encrypted storage are not available until after user is unlocked
   at com.solo.purecast.PureCastApp.onCreate(PureCastApp.kt:15)
   ```
3. **快速切歌偶发断播**：UPnP 播放过程中连续快速点击“下一首”时，音频流拉取启动后瞬间戛然而止，状态机回退至 `STOPPED`。
4. **双协议混音杂音**：先发起 AirPlay 串流再使用 DLNA 推流，两路音频同时向 Native SPSC RingBuffer 写入数据，造成立体声波形交替错位与严重杂音。

## What Didn't Work
1. **怀疑后台服务被剥夺 `RECORD_AUDIO` / `OVERLAY` 权限导致静默终止**：经验证，Android 11+ 对后台服务麦克风访问有限制，但 PureCast 的推流与 UAC2 源码直通并不依赖麦克风采集；广播搜不到的根本原因不是进程被系统杀掉，`dumpsys activity services` 证实 `PlaybackService` 始终以前台服务运行中。
2. **在开机广播中主动 `startActivity(MainActivity)` 尝试唤醒**：虽然拉起 Activity 能强行让主界面进入前台，但这不仅严重违背“作为系统后台服务静默自启动、不得抢占电视桌面”的 UX 准则，而且无法解决局域网广播依然不可达的根本问题。
3. **仅依赖 `ConnectivityManager.NetworkCallback` 监听网络变化**：在 TV 定制固件冷开机瞬间，Wi-Fi 芯片在内核空间初始化较慢（需 5~15 秒），某些 Android TV 定制板卡在开机后并未向已有后台服务正常分派 `onAvailable` 回调，导致服务启动时错失网络就绪通知。

## Solution

### 1. 严格过滤硬件专网，显式绑定有效局域网网卡
爱普生 EF-15 内建两个活跃网络接口：`usb0`（`192.168.48.2`，爱普生投影仪芯片与光机主板内部通信专网）与 `wlan0`（`192.168.2.179`，家庭物理 Wi-Fi）。`usb0` 随内核秒级就绪，导致旧代码遍历网卡时顺序命中 `usb0`，将 JmDNS 与 UPnP 绑定在外部不可达的专网上。

在 `JmDnsDiscoveryService.kt` 与 `UpnpRendererServer.kt` 中引入接口过滤与优先级调度：
```kotlin
// 💡 严格优先选择物理 Wi-Fi (wlan*) 或以太网 (eth*)，坚决排除投影仪内部主板专网 usb0 与虚拟网卡
val sorted = interfaces.sortedWith(compareByDescending { iface ->
    when {
        iface.name.startsWith("wlan") -> 3
        iface.name.startsWith("eth") -> 2
        else -> 0
    }
})
for (iface in sorted) {
    if (iface.isLoopback || !iface.isUp) continue
    val name = iface.name.lowercase()
    if (name.startsWith("usb") || name.startsWith("dummy") || name.startsWith("p2p") || name.startsWith("tun")) {
        continue
    }
    // 过滤 192.168.48.* 等内部私有专网 IP
}
```

### 2. SSDP 组播多网卡路由显式绑定
Linux 内核在多网卡环境下默认将组播路由发往默认网卡（爱普生机上为 `usb0`）。UPnP 的 `MulticastSocket` 必须显式绑定局域网物理接口：
```kotlin
val group = InetAddress.getByName("239.255.255.250")
val activeInterface = getActiveNetworkInterface()
multicastSocket = MulticastSocket(1900).apply {
    timeToLive = 4
    reuseAddress = true
    if (activeInterface != null) {
        networkInterface = activeInterface
        joinGroup(InetSocketAddress(group, 1900), activeInterface)
    } else {
        joinGroup(group)
    }
}
```

### 3. JmDNS 动态 IP 变更深度自愈与开机 60 秒巡检守护
消除“旧 JmDNS 在运行则跳过重启”的逻辑死锁，并在 `PlaybackService` 启动后 60 秒内运行协程 Watchdog，动态纠偏 IP 绑定：
```kotlin
// AirPlayServerManager.kt
val currentBoundIp = jmdnsDiscoveryService.getCurrentBoundIp()
val newIp = jmdnsDiscoveryService.getLocalIpv4Address()
if (newIp != null && (newIp != currentBoundIp || !jmdnsDiscoveryService.isRunning())) {
    scope.launch(Dispatchers.IO) {
        jmdnsDiscoveryService.stopBroadcast()
        delay(500)
        jmdnsDiscoveryService.startBroadcast(deviceName, 7000, currentPort)
    }
}
```

### 4. Direct Boot 凭据加密安全兼容
在 `PureCastApp.kt` 与 `AppPalette.kt` 中增加设备保护存储兼容，防止未解锁时访问 Credential Encrypted Storage 引发 crash：
```kotlin
val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
    if (userManager?.isUserUnlocked == false) {
        context.createDeviceProtectedStorageContext()
    } else {
        context
    }
} else {
    context
}
```

### 5. 单调会话令牌（`sessionId`）与跨协议后发抢占
在 `StreamDemuxer.kt` 中使用 `AtomicLong` 为每次推流分配自增令牌，仅允许当前活跃会话在 `finally` 块中清理全局状态：
```kotlin
// StreamDemuxer.kt
val thisSession = sessionToken.incrementAndGet()
// 循环与 finally 块校验
if (thisSession == sessionToken.get()) {
    isDemuxing = false
    onStreamEnded()
}
```
并在 `PlaybackService.startStreaming` 中实现后发抢占：若当前在播 AirPlay 时收到 UPnP 推流，主动触发 `airPlayServer?.stop()`，彻底杜绝混音冲突。

## Why This Works
1. **规避硬件专用网卡黑洞**：过滤 `usb0` (192.168.48.2) 后，SSDP 组播和 mDNS 报文被强制通过物理 Wi-Fi 网卡 `wlan0` (192.168.2.179) 进出，局域网电脑与手机的 M-SEARCH 和 Bonjour 查询方可直接抵达 PureCast。
2. **时钟与状态幂等解耦**：自增 `sessionToken` 构筑了异步协程间的强隔离屏障，即使上一首歌的旧 HTTP 连接与循环在取消过程中发生延迟阻塞，其清理逻辑也无法穿透到新会话中。
3. **突破开机异步时序延迟**：开机前 60 秒的自愈 Watchdog 协程弥补了 Android TV 固件 `NetworkCallback` 偶发漏发或延迟的问题，确保一旦 Wi-Fi 获取到真实局域网 IP，服务广播能立刻被校正并发布。

## Prevention
1. **多网卡环境必选首选物理接口**：在所有涉及网络组播（MulticastSocket）、mDNS（JmDNS）、本地 IP 提取的模块中，严禁仅按 `NetworkInterface.getNetworkInterfaces()` 顺序取第一个，必须显式对接口名称 (`wlan* > eth*`) 排序并排除 `usb*`, `dummy*`, `p2p*`, `tun*` 以及硬件专网网段。
2. **Direct Boot 组件安全守则**：凡声明了 `android:directBootAware="true"` 的组件与全局 `Application`，在 `onCreate` 期间严禁未经保护直接访问普通 `SharedPreferences` 或未解密数据库，必须使用 `createDeviceProtectedStorageContext()`。
3. **热插拔与切歌单测契约**：所有涉及流媒体拆包的组件必须编写竞态单测（如 `StreamDemuxerSessionTest`），验证取消流与新流交替时状态机的一致性。
