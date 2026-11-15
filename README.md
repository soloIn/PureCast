# PureCast — Android TV 源码级 USB DAC 独占无损音频渲染器

PureCast 是一款专为 Android TV 与智能电视盒子打造的局域网高保真（Hi-Fi）无损音频渲染器与 USB 数字转盘应用。项目通过底层 Linux `usbfs` 文件描述符穿透与静态集成的 `libusb` 驱动直接接管外置 USB DAC（如 Sony NW-ZX300A 等解码耳放设备），彻底绕过 Android 系统音频混音器强制 48kHz SRC（采样率转换）的限制，实现从 AirPlay 1 (RAOP) 及 UPnP/DLNA 接收端到硬件声卡的 **Bit-Perfect（比特完美）源码直通输出**。同时，应用深度还原 Apple TV (tvOS) 工业美学与 10-Foot 遥控器交互体验，搭载超低开销的原生 OpenGL ES 2.0 材质网格动态流光背景。



## ✨ 核心特性

### 1. 免 Root 底层 USB DAC 硬件独占与 Bit-Perfect 直通

- **绕过 Android SRC 限制**：通过 Android `UsbDeviceConnection.getFileDescriptor()` 获取底层 Linux `usbfs`文件描述符并透传至 C++20 Native 引擎，自动卸载内核 `snd-usb-audio` 驱动并独占声明音频接口。

  

- **UAC1 / UAC2 双协议栈与原生时钟配置**：完整支持 USB Audio Class 1.0 与 2.0 描述符解析，通过 `CS_SAM_FREQ_CONTROL`（UAC2）或 `SAMPLING_FREQ_CONTROL`（UAC1）锁定 44.1kHz ~ 192kHz+ 原生硬件采样率，并将 UAC Feature Unit 硬件音量锁定在 `0.0dB` (Unity Gain)。

  

- **UAC2 Explicit Feedback 闭环异步时钟伺服**：内置 8 个并发等时输出 URB（每个包含 8 个微帧）与 4 个显式时钟反馈输入 URB 管道，实时解析 DAC 反馈的 Q16.16/10.14 瞬时采样率以进行相位累加微帧步进，在无反馈端点时自动降级至高精度 Bresenham 开环步进算法。

  

- **高精度物理子帧高位对齐 (`PcmFormatter`)**：支持 `S16_LE`（2字节）、`S24_3LE`（3字节打包）与 `S32_LE`（4字节槽位）自动转换，将 16-bit/24-bit 采样精准左移对齐至最高有效位（MSB）并低位补零，彻底杜绝位深不匹配导致的 48dB 音量衰减或爆音。

  

- **智能声卡动态让渡与自愈**：支持停止播放后延迟（可配置 5秒、10秒、30秒或常驻独占）或外部应用抢占系统音频焦点（`AUDIOFOCUS_LOSS`）时，安全取消在途 URB、释放接口并向 Linux 内核重绑 `snd-usb-audio`驱动，无缝将声卡归还给电视系统其他影音应用。

  

### 2. 双引擎无损网络音频协议栈

- **原生 AirPlay 1 (AirTunes / RAOP) 引擎**：

  - **双通道免配对广播**：基于 `JmDNS` 广播符合 RFC 规范的 `<MAC>@<Name>._raop._tcp.local.` 服务，伪装 `AirRecever3,1` / `AirPort4,107` 硬件标识并拦截 `/pair-setup` 请求，引导 iOS / macOS 走免配对的无损 AirPlay 1 音频通道。

    

  - **硬件级解密与无损解码**：内置 AirPort Express 经典 RSA-2048 私钥完成 `Apple-Challenge` 签名认证与 `rsaaeskey` 解密，在 Native 层通过 `AES-128-CBC` 实时解密 RTP 音频包，并由 C/C++ `AlacDecoder` 或 L16/L24 PCM 转换器还原无损音频。

    

  - **抗抖动重排与丢包补偿 (`RaopJitterBuffer`)**：具备 128 帧容量、25 帧（约 200ms）Pre-Roll 预充门限与 8 帧乱序容忍窗口，发现序列号空洞时主动通过控制端口发送 `0xd5` 丢包重传请求，超差时自动插入静音保真帧对齐物理晶振时钟。

    

  - **双向控制与元数据同步**：内置 `DmapParser` 解析曲目名称、艺术家、专辑、时长与内嵌封面，并结合 `_dacp._tcp.local.` 动态端口发现与 `DacpClient`，支持使用电视遥控器反向控制 iPhone / Mac 播放、暂停、切歌与精准寻道。

    

- **UPnP / DLNA MediaRenderer 2.0 引擎**：

  - **全状态 SSDP 与 GENA 架构**：内置 `UpnpRendererServer`（默认 HTTP 端口 `7952`，SSDP 组播端口 `1900`）与 `GenaEventNotifier`，支持 `AVTransport:1`、`RenderingControl:1` 与 `ConnectionManager:1` 服务描述、SOAP 动作响应及订阅状态实时推送。

    

  - **WAV / FLAC 无损流解复用 (`StreamDemuxer`)**：支持流式解析 WAV（含 `fmt `、`data` 及 `LIST INFO` 内嵌标签）与 Native `dr_flac` 16-bit/24-bit 无损流式解码，支持基于 `HTTP Range (206 Partial Content)`的高精度对齐寻道与实时流（`>= 0x7FFFFFFF`）自动识别。

    

### 3. 三态正交会话协调与无锁硬件快路径

- **三态正交状态机 (`TriStateTransitionMatrix`)**：将播放器状态解耦为**传输管道态 (`TransportPipeState`)**、**硬件管道态 (`HardwarePipelineState` 7态)** 与**业务会话态 (`BusinessSessionState`)**，由 `PlaybackSessionCoordinator` 单线程消费协程统一归约并发布全局唯一可信快照 `PlayerSessionSnapshot`。

  

- **无锁硬件快路径 (`HardwareFastPathController`)**：暂停、切歌冲刷、停止与跨协议抢占直接穿透至 C++ 原子操作 `emergency_mute_and_flush`（无需等待协程队列或加互斥锁），在 ≤20ms≤20ms 内将 URB 切换为全 0 静音保活并按需递增 62-bit `buffer_epoch_`。

  

- **SPSC 无锁环形缓冲与“写先于清”门闩**：C++20 `SpscRingBuffer` 采用 64 字节缓存行对齐（消除伪共享）与 `mlock()` 物理内存页锁定（杜绝缺页中断）；配合 `Write-Before-Clear` 世代追平门闩与 100ms 预充自动解锁机制，彻底消灭切歌残音、跨代串扰与首音吞字。

  

### 4. Apple TV 原生级视觉交互与 GLES2 动态流光背景

- **超低开销 GLES2 流体背景 (`MacBackground` / `MacRenderer`)**：深度移植 Apple Music for TV 渲染管线，基于独立 `HandlerThread` + `Choreographer` VSYNC 驱动，采用 `128~192px` 离屏 FBO、三层旋转混叠、双向分离高斯模糊及预烘焙 `41x41` 材质网格（`assets/macfluid/mesh.bin`）顶点插值变形，GPU 开销低于 1%。

  

- **多档画质调节与优雅降级**：提供极佳（60fps）、标准（60fps）、省电（30fps 隔帧节流）、静态磨砂与关闭五档画质选择；暂停播放时自动停止 VSYNC 渲染使 GPU 占用降至 0%，无硬件加速或 EGL 异常时自动回退至双线性插值静态渐变背景或 `MacStaticFrame`。

  

- **10-Foot 遥控器专属交互与 OLED 保护**：采用 42%（封面）与 58%（元数据及播控）黄金分割布局，搭载纯色彩语义音频链路指示胶囊（`SignalPathPill`）、D-Pad 10 秒快进/快退防抖进度条、自适应深/浅色主题模式（`AppPalette`），以及每 10 秒微移 2~3 像素的 **OLED 像素位移防烧屏引擎**与智能防休眠常亮联动。

  

## 🏗️ 系统架构与代码目录结构

### 架构分层概览

```
+-----------------------------------------------------------------------------------+
|                                  UI Layer (Compose TV)                            |
|  MainActivity / MainTvScreen / SettingsScreen / OnboardingWizard / MacBackground  |
+-----------------------------------------+-----------------------------------------+
                                          | StateFlow<PlayerSessionSnapshot> (只读订阅)
                                          v
+-----------------------------------------------------------------------------------+
|                         Session & Coordination Layer (Kotlin)                     |
|   PlaybackService -> PlaybackServiceSessionHost <-> PlaybackSessionCoordinator    |
|   TriStateTransitionMatrix | TrackTransactionManager | HardwareFastPathController |
+-------------------+-----------------------------------+---------------------------+
                    |                                   |
     ProtocolSessionBridge                       SessionHardwareBridge
                    |                                   |
        +-----------+-----------+                       v
        |                       |             +-------------------------------------+
        v                       v             |      USB & Audio Arbitrator         |
+---------------+       +---------------+     | UsbDeviceManager (HardwareExecutor) |
|   AirPlay 1   |       |  UPnP / DLNA  |     | AudioFocusManager / UsbReceiver     |
| AirPlayServer |       | UpnpRenderer  |     +-----------------+-------------------+
| JmDns / DACP  |       | StreamDemuxer |                       | Linux usbfs fd
+-------+-------+       +-------+-------+                       |
        |                       |                               |
        +-----------+-----------+-------------------------------+
                    | JNI (NativeAudioEngine / jni_bridge.cpp)
                    v
+-----------------------------------------------------------------------------------+
|                             Native Engine Layer (C++20)                           |
|  AudioEngine (UAC1/UAC2 Isochronous Pipeline + Explicit Feedback + PcmFormatter)  |
|  SpscRingBuffer (Lock-free + mlock) | RaopServer / RaopRtp / Alac / FlacDecoder   |
|  Static libusb-1.0 (Linux usbfs backend with LIBUSB_OPTION_NO_DEVICE_DISCOVERY)   |
+-----------------------------------------------------------------------------------+
```

- 上述分层结构确保 UI 层、业务协调层、网络协议层与底层硬件驱动严格解耦。

  

### 核心代码目录结构

Plaintext

```
PureCast/app/src/main/
├── java/com/solo/purecast/
│   ├── PureCastApp.kt                       # Application 入口，负责主题初始化与加载 libpurecast_usb.so
│   ├── airplay/                             # AirPlay 1 (RAOP) 协议栈模块
│   │   ├── AirPlayServerManager.kt          # AirPlay 服务生命周期、密钥注入与 DACP 托管
│   │   ├── AirPlayNativeEventDispatcher.kt  # C++ RAOP 回调事件翻译、寻道防抖与会话映射
│   │   ├── AirPlayKeyDecryptor.kt           # AirPort Express RSA 私钥解密与 Apple-Challenge 签名
│   │   ├── AirPlayStateManager.kt           # AirPlay 会话状态与进度过滤管理
│   │   ├── DacpClient.kt                    # Apple DACP 远端反向播控与寻道 HTTP 客户端[cite: 3]
│   │   ├── JmDnsDiscoveryService.kt         # 基于 JmDNS 的 _raop._tcp 广播与 _dacp._tcp 端口监听[cite: 3]
│   │   └── NativeAirPlayCallback.kt         # Native RAOP 回调接口契约[cite: 3]
│   ├── upnp/                                # UPnP / DLNA MediaRenderer 模块
│   │   ├── UpnpRendererServer.kt            # SSDP 组播发现、HTTP/SOAP 控制服务与 DIDL-Lite 解析
│   │   ├── StreamDemuxer.kt                 # HTTP WAV/FLAC 流式解复用、LIST INFO 提取与 Range 寻道
│   │   └── GenaEventNotifier.kt             # DLNA GENA 订阅管理与 LastChange 状态广播通知器[cite: 3]
│   ├── core/
│   │   ├── audio/AudioFocusManager.kt       # Android 11+ 系统音频焦点仲裁器
│   │   ├── nativebridge/NativeAudioEngine.kt# JNI Native 桥接单例接口
│   │   ├── usb/
│   │   │   ├── UsbDeviceManager.kt          # 单线程 HardwareExecutor、USB 枚举、授权与原位切频回滚
│   │   │   └── UsbDeviceReceiver.kt         # USB 热插拔与电视盒子冷开机/快速开机广播接收器
│   │   └── session/
│   │       ├── PlaybackSessionCoordinator.kt# 全局会话协调器、事件鉴权、定时器与 DACP 串行队列
│   │       ├── TriStateTransitionMatrix.kt  # 三态正交纯状态转换矩阵与副作用生成
│   │       ├── TrackTransactionManager.kt   # 曲目代数事务与增量元数据合并管理器
│   │       ├── HardwareFastPathController.kt# 无锁硬件快路径静音与切频控制器
│   │       ├── ProtocolSessionBridge.kt     # 协议层与协调器通信桥接契约
│   │       └── SessionEvents.kt             # 领域事件信封与载荷密封定义
│   ├── model/PlaybackState.kt               # 全局不可变快照 PlayerSessionSnapshot 与核心状态枚举[cite: 3]
│   ├── service/
│   │   ├── PlaybackService.kt               # MediaPlayback | ConnectedDevice 前台服务与系统锁管理[cite: 3]
│   │   └── PlaybackServiceSessionHost.kt    # 快照副作用观察、MediaSession 同步与 AirPlay 进度推进[cite: 3]
│   ├── data/SettingsRepository.kt           # SharedPreferences 配置持久化仓库[cite: 3]
│   ├── util/                                # 全局分级日志 Logger、网卡过滤 NetworkUtils 与 PermissionHelper
│   └── ui/                                  # Android TV Compose 界面层
│       ├── MainActivity.kt                  # 顶层页面导航、物理遥控器按键拦截与防休眠常亮控制
│       ├── background/                      # GLES2 流光背景渲染引擎 (MacRenderer/MacShaders/MacMesh 等)
│       ├── components/                      # PlaybackControls / ProgressBarComponent / SignalPathPill
│       ├── screen/                          # MainTvScreen / SettingsScreen / OnboardingWizard
│       └── theme/                           # PureCastTheme 与 AppPalette 自适应深浅色彩系统
└── cpp/                                     # C++20 Native 核心层
    ├── jni_bridge.cpp                       # JNI 方法映射、异常防护与线程级自动 Detach 封装[cite: 2]
    ├── audio_engine.h / audio_engine.cpp    # libusb 独占驱动、等时 URB 管线、Explicit Feedback 与三元组切频[cite: 3]
    ├── ring_buffer.h                        # 64字节对齐 + mlock 的 SPSC 无锁环形缓冲区[cite: 3]
    ├── pcm_formatter.h                      # S16_LE / S24_3LE / S32_LE 子帧高位对齐转换器[cite: 2]
    ├── uac_protocol.h                       # UAC1 / UAC2 标准描述符与控制选择器协议定义[cite: 2]
    ├── flac_decoder.h / flac_decoder.cpp    # 基于 dr_flac 的 JNI InputStream 流式无损解码器[cite: 2]
    ├── alac/                                # Apple Lossless (ALAC) 解码器实现[cite: 2]
    ├── crypto/aes.h                         # AES-128-CBC 解密模块[cite: 3]
    ├── raop/                                # Native RAOP RTSP 服务端、RTP 接收、JitterBuffer 与 DMAP 解析[cite: 3]
    ├── libusb/                              # 针对 Android usbfs 定制编译的 libusb-1.0 源码[cite: 2]
    └── tests/                               # C++ 宿主机无硬件模拟测试套件 (MockLibusbController 等)
```

## 🔬 关键技术机制详解

### 1. 三态正交状态机与定时器矩阵

PureCast 摒弃了传统易产生竞态的单一播放状态枚举，通过 `TriStateTransitionMatrix` 维护三个正交状态维度：



| 状态维度       | 枚举类型                | 状态值集合                                                   | 核心职责                                                     |
| -------------- | ----------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **传输管道态** | `TransportPipeState`    | `DISCONNECTED`, `PREPARING`, `STREAMING`, `INTERRUPTED`      | 反映 AirPlay / UPnP 网络协议流接收通道的连接与数据活性[cite: 3]。 |
| **硬件管道态** | `HardwarePipelineState` | `DISCONNECTED`, `ATTACHED`, `PERMISSION_PENDING`, `RELEASED`, `EXCLUSIVE_MUTED`, `EXCLUSIVE_ACTIVE`, `AUDIO_RECONFIGURING` | 刻画 USB DAC 物理插拔、权限审批、内核驱动重绑让渡及等时传输锁相阶段[cite: 3]。 |
| **业务会话态** | `BusinessSessionState`  | `IDLE`, `PLAYING`, `TRANSITIONING`, `PAUSED`, `STOPPED`      | 控制用户体验层的稳定阶段；`TRANSITIONING` 对外映射为 `PLAYING`，防止切歌时界面闪烁[cite: 3]。 |

协调器内置多项精密计时器以应对网络与硬件瞬态[cite: 1]：

- **220ms 显式暂停防抖 (`DEBOUNCE_PAUSE_MS`)**：吸收远端协议在切歌瞬间下发的短暂 Pause 抖动，而用户通过电视遥控器主动点击暂停则以 `0ms` 立即生效[cite: 1]。

- **800ms 切歌 UI 保护窗口 (`UI_PROTECTION_WINDOW_MS`)**：连续快速按键切歌期间锁定焦点与视觉连贯性[cite: 1]。

- **1200ms 封面宽限期 (`ScheduleCoverGraceTimeout`)**：切歌边界保留上一曲封面，若新曲目在 1.2 秒内未提供封面才平滑清除，杜绝切歌封面黑屏闪烁[cite: 1]。

- **5000ms 过渡态与 DACP 看门狗 (`TRANSITION_TIMEOUT_MS` / `REMOTE_COMMAND_TIMEOUT_MS`)**：防止建流无数据流入或远端未响应切歌指令导致 UI 卡死在 `TRANSITIONING`[cite: 1]。

- **10s 硬件空闲让渡 (`HARDWARE_IDLE_RELEASE_MS`) 与 10m 挂起回收 (`SUSPEND_TIMEOUT_MS`)**：停止后自动归还声卡，长时间暂停后自动清理会话、DACP 凭据与低延迟 Wi-Fi 锁。

  

### 2. SPSC 世代防串扰与原位三元组切频

- **单字打包原子世代 (`epoch_and_flush_`)**：在 `AudioEngine` 中，64 位原子变量的高 62 位存储单调递增的 `buffer_epoch_`，低 2 位分别存储 `FLAG_PREROLLED`（100ms 预充就绪标志）与 `FLAG_FLUSHING`（切歌静音标志），利用单条 CAS 指令彻底消除多变量竞态[cite: 3]。

- **原位切频与安全回滚 (`reconfigure_audio_format`)**：当音频规格发生变更（例如从 `44.1kHz/16bit` 切换至 `96kHz/24bit` 或 `192kHz/24bit`）时，优先在已打开的 `dev_handle_` 上原位重设 Alternate Setting 与硬件时钟；若硬件拒绝或发生管道错误，自动执行安全回滚并由 `UsbDeviceManager` 重新获取 `usbfs fd` 重开设备。

  

- **静音免疫与自愈唤醒**：AirPlay RTP 引擎采用 64 位宽并行扫描检测全零静音帧，设置 375 帧（约 3.0 秒）常态静音门限与最后 6 秒曲目尾部 1250 帧（约 10 秒）免疫窗口，既能在发送端暂停时自动触发隐式静音暂停，又绝不将交响乐休止符或曲目结尾自然淡出误判为暂停[cite: 3]。

## 🔒 权限声明与零录音隐私承诺

为了在无触控的 Android TV / 电视盒子上实现开箱即用的静默接管与后台保活，PureCast 提供了专属的**开机权限与自检引导向导 (`OnboardingWizard`)**[cite: 1]：

1. **USB 声卡静默接管 (`RECORD_AUDIO`)**：

   > **零录音硬件调用承诺 (Zero-AudioRecord Privacy Guarantee)**：PureCast 纯粹作为局域网高保真音频渲染器与 USB 转盘，**全工程严禁且绝无任何 `AudioRecord` 硬件录音 API 调用，绝不采集任何麦克风环境声音**[cite: 1]。申请该权限仅用于满足 Android 系统内核在无触控 TV 端对 USB Audio Class 声卡设备免弹窗静默接管的合规要求[cite: 1]。

2. **悬浮窗与后台唤醒 (`SYSTEM_ALERT_WINDOW`)**：突破 Android 10+ 后台 Activity 启动限制（BAL），在电视待机或处于桌面收到手机端 AirPlay / UPnP 推流时，立即自动唤醒大屏播放界面。

   

3. **待机电池优化白名单 (`IGNORE_BATTERY_OPTIMIZATIONS`)**：配合 `PARTIAL_WAKE_LOCK`、`MulticastLock` 与推流期动态激活的 `WIFI_MODE_FULL_LOW_LATENCY` 低延迟锁，防止电视盒子熄屏或休眠时杀死后台组播发现与高码率推流服务[cite: 1, 3]。

4. **防权限自动回收白名单 (`Auto-Revoke Whitelist`, API 30+)**：防止长期闲置后系统自动重置已授予的权限[cite: 1]。

## 🎮 遥控器交互与设置指南

### 物理遥控器快捷键

- **菜单键 (`KEYCODE_MENU`)**：在任意状态下快速呼出或关闭全屏双栏“设置与硬件”界面[cite: 1]。
- **方向键 (`DPAD_LEFT` / `DPAD_RIGHT`)**：当焦点位于可寻道点播曲目的进度条上时，按左右方向键执行 ±10±10秒防抖快退/快进；按下中心确认键（`DPAD_CENTER` / `ENTER`）立即提交寻道[cite: 1]。
- **媒体控制键 (`MEDIA_PLAY_PAUSE` / `PLAY` / `PAUSE` / `STOP` / `PREVIOUS` / `NEXT`)**：全局拦截并直通控制当前活跃会话；在不支持电视端切歌的 UPnP 流下会弹出温和横幅提示而不抢夺遥控器焦点[cite: 1]。
- **返回键 (`KEYCODE_BACK`)**：在设置页采用严格的 Apple TV 层级路由——焦点位于右侧配置项或输入框时按返回键先将焦点交还左侧分类导航栏，焦点位于左侧导航栏时按返回键才平滑退回播放主页[cite: 1]。

### 信号链路胶囊 (`SignalPathPill`) 色彩语义

主屏幕右侧顶部的信号胶囊以 **`协议 · 编码格式 · 采样率/位深 | 设备名称`** 格式实时展示物理音频链路，并通过**设备名称颜色**直观传达硬件状态[cite: 1]：

- 🟢 **亮绿色 (`#30D158`)**：USB DAC 硬件独占接管成功，处于 Bit-Perfect 源码直通与 UAC2 物理时钟锁定状态[cite: 1]。
- 🟡 **琥珀色 (`#FFD60A` / `#FF9F0A`)**：正在执行 UAC2 采样率重配（`切换采样率中...`）或等待 USB 权限授权（`等待 USB 权限...`）[cite: 1]。
- 🔴 **亮红色 (`#FF453A`)**：未独占、声卡已让渡给系统共享输出或设备异常断开[cite: 1]。
- ⚪ **中性灰 (`#8E8E93` / `#6E6E73`)**：就绪等待推流待机状态[cite: 1]。

## 🛠️ 构建与测试

### 环境要求

- **Android SDK**：建议 API 30+ (Android 11 ~ 14)，支持具备 USB Host 接口的 Android TV 电视与机顶盒[cite: 1, 3]。
- **NDK & CMake**：需支持 C++20 标准（`std::atomic`、缓存行对齐 `alignas(64)`）与 POSIX 线程库以编译 `libpurecast_usb.so`[cite: 2, 3]。

### 构建 Android 应用

在项目根目录下执行 Gradle 构建命令：

Bash

```
./gradlew assembleDebug
```

### 运行 C++ Native 核心单元测试

项目在 `app/src/main/cpp/tests/` 下内置了脱离真机硬件的宿主机 C++ 测试套件（包含模拟 Sony NW-ZX300A UAC2/UAC1 描述符与实时 URB 调度的 `MockLibusbController` 及 `MockJniInputStream`），覆盖：

1. **音频引擎测试 (`audio_engine` tests)**：“写先于清”门闩、`EXPLICIT_PAUSE` 保活（验证 ≤20ms≤20ms 静音 SLA 且不丢缓冲）、多线程 SPSC 压力测试、三元组原位切频（44.1k/16 -> 96k/24 -> 192k/24）与硬件错误回滚、并发重入零 UAF 校验以及 UAC1 控制传输验证[cite: 2, 3]。
2. **协议栈测试 (`protocol_native_test.cpp`)**：验证 TV 暂停期间免疫远端 RTP 音频包冲刷、声卡重开后 `resume_after_reopen` 刷新世代、跨协议抢占 `preempt_active_client`、FLAC 解码器在暂停期遇世代失效立即退出，以及 AirPlay 隐式静音自愈与曲目尾部 6 秒静音免疫窗口[cite: 3]。

可通过 CMake 与 CTest 在宿主机编译并运行 Native 测试：

Bash

```
cd app/src/main/cpp/tests
cmake -B build
cmake --build build
ctest --test-dir build --output-on-failure
```