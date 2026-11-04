---
module: native-audio-driver
tags: [usb-audio, uac2, usbfs, android-tv, upnp, surge-proxy, self-healing]
problem_type: hardware-debugging
---

# EF-15 Android TV 与 Sony Walkman USB DAC 联调排查与自愈让渡记录

## 1. 问题背景
在 EF-15 电视盒子 (Android 11, armeabi-v7a 32位架构) 上部署 PureCast 进行真机联调时，接入了 Sony Walkman NW-ZX300A (PID: 0x0b8b, VID: 0x054c) USB DAC。需实现：
1. 绕过 Android 系统 48kHz SRC，通过无 Root usbfs 穿透实现 Bit-Perfect 源码独占输出。
2. 支持局域网 UPnP/DLNA 串流接收与 FLAC/WAV 解复用实时推流。
3. 暂停或空闲 10 秒后自动释放硬件独占，将 `snd-usb-audio` 归还给电视系统。

## 2. 核心排查与根因分析

### 问题一：Surge TUN 路由与 ADB / 局域网直连冲突 (Errno 65 No route to host)
- **根因**：macOS 开启 Surge 增强模式 (`utun4`) 时，直接发往局域网 `192.168.2.179:5555` 的 TCP SYN 包被截获或丢弃，引发 `Errno 65: No route to host`。
- **解决方案**：
  编写 `scripts/adb_tunnel.py`，在本地监听 `127.0.0.1:5555`，并通过 Surge 的本地 SOCKS5 代理 (`127.0.0.1:6153`) 将流量透明转发至 `192.168.2.179:5555`。ADB 通过 `adb connect 127.0.0.1:5555` 建立稳定双向调试会话。

### 问题二：Android TV 后台 Service 无法弹出 USB 授权弹窗
- **根因**：Android 10+ 对后台 Service 弹出窗口做了严格限制。直接从 `PlaybackService` 调用 `UsbManager.requestPermission()` 无法唤起系统 USB 授权弹窗，导致权限获取静默失败。
- **解决方案**：
  - 在 `AndroidManifest.xml` 中为 `MainActivity` 配置 `android.hardware.usb.action.USB_DEVICE_ATTACHED` 与 `device_filter.xml`。
  - 在 `MainActivity.onResume()` 中检查并使用前台 Activity Context 显式触发 `requestPermissionWithActivity(activity)`，成功调起 Leanback TV 原生 USB 授权对话框，并由用户勾选“默认打开”。

### 问题三：Android 9+ 明文 HTTP 流量限制阻断局域网 UPnP 推流
- **根因**：Upnp 推流 URL（如 AirConnect 的 `http://192.168.2.124:60782/stream.flac`）采用明文 HTTP，被系统网络安全策略阻断 (`Cleartext HTTP traffic not permitted`)。
- **解决方案**：
  在 `AndroidManifest.xml` 的 `<application>` 节点添加 `android:usesCleartextTraffic="true"`。

### 问题四：Native AudioEngine 互斥锁重入死锁 (Recursive Deadlock)
- **根因**：`audio_engine.cpp` 中 `open_device()` 先持有了 `op_mutex_` (`std::mutex`)，随后调用的 `init_context()` 内部同样尝试获取 `op_mutex_`，导致同一线程对不可重入的互斥锁重复加锁，造成 JNI 调用瞬间死锁卡死。
- **解决方案**：
  将 `audio_engine.h` 中的 `op_mutex_` 类型由 `std::mutex` 调整为 `std::recursive_mutex`，彻底解决初始化与销毁链路中的锁重入问题。

### 问题五：UPnP AVTransport 状态机与解码器生命周期错位
- **根因**：原先在处理 `SetAVTransportURI` 动作时即刻启动了 `demuxer.start(uri)`，导致在客户端仅下发元数据尚未发送 `Play` 指令时便发起了长连接 HTTP 请求，引发 `unexpected end of stream`。
- **解决方案**：
  严格遵循 UPnP AVTransport 标准状态机：`handleSetAvTransportUri` 仅解析并缓存 URI 与元数据；待收到客户端 `handlePlay` 指令时才正式触发 `demuxer.start(uri)` 开启下载与音频解封装。

## 3. 验证结果
1. **Bit-Perfect 硬件独占传输**：
   - Walkman 成功识别采样率并以 S16_LE / S24 等物理帧传输，绕过系统 SRC。
   - 电视屏幕流体背景与 SignalPath 药丸组件实时点亮，显示绿色：`● WALKMAN | 44.1 kHz / 16-bit BIT-PERFECT`。
2. **10 秒空闲让渡与无缝自愈**：
   - 暂停推流满 10 秒后，Native 层平滑终止等时 URB 调度线程，通过 `USBDEVFS_CONNECT` 重新挂载内核驱动。
   - `/proc/asound/cards` 重新恢复系统识别 `1 [WALKMAN]: USB-Audio - WALKMAN`。
   - UI 药丸指示灯优雅转为蓝色待机态：`● WALKMAN | 44.1 kHz / 16-bit STANDBY`。
