# PureCast — Android TV 源码级 USB DAC 独占无损音频渲染器

PureCast 是一款专为 Android TV 与智能电视盒子打造的局域网高保真（Hi-Fi）无损音频渲染器与 USB 数字转盘应用。项目通过底层 Linux `usbfs` 文件描述符穿透与静态集成的 `libusb-1.0` 驱动直接接管外置 USB DAC（如 Sony NW-ZX300A 等解码耳放设备），彻底绕过 Android 系统音频混音器强制 48kHz SRC（采样率转换）的限制，实现从 AirPlay 1 (RAOP) 及 UPnP/DLNA 接收端到硬件声卡的 **Bit-Perfect（比特完美）源码直通输出**。同时，应用深度还原 Apple TV (tvOS) 工业美学与 10-Foot 遥控器交互体验，搭载超低开销的原生 OpenGL ES 2.0 `SurfaceView` 硬件叠加动态流光背景与 60fps 逐字歌词系统。

---

## 📸 界面预览

| 播放主屏（42% / 58% 布局 · Bit-Perfect 独占胶囊） | 沉浸式全屏歌词（40% / 60% 布局 · 逐字扫光） |
| :---: | :---: |
| <img width="1920" height="1080" alt="screenshot1" src="https://github.com/user-attachments/assets/9f499964-3bd7-4a3c-bf75-a83e7f042b6e" /> | <img width="1920" height="1080" alt="screenshot2" src="https://github.com/user-attachments/assets/f544a40f-8c0f-491d-8f57-0209df7f9444" /> |

> **实机截图说明**：左图为标准播放主屏，展示 GLES2 动态材质流光背景、顶部亮绿色硬件独占信号胶囊（`UPnP · WAV · 44.1kHz/24bit | WALKMAN`）、进度条及焦点播控栏；右图为展开后的沉浸式歌词视图，展示 38% 黄金视线锚定与逐字羽化卡拉 OK 扫光效果。

---

## ✨ 核心特性

### 1. 免 Root 底层 USB DAC 硬件独占与 Bit-Perfect 直通

- **绕过 Android SRC 限制**：通过 Android `UsbDeviceConnection.getFileDescriptor()` 获取底层 Linux `usbfs` 文件描述符并透传至 C++20 Native 引擎，自动卸载内核 `snd-usb-audio` 驱动并独占声明音频接口（`LIBUSB_OPTION_NO_DEVICE_DISCOVERY`）。
- **UAC1 / UAC2 双协议栈与原生时钟配置**：完整支持 USB Audio Class 1.0 与 2.0 描述符解析，通过 `CS_SAM_FREQ_CONTROL`（UAC2）或 `SAMPLING_FREQ_CONTROL`（UAC1）锁定 44.1kHz ~ 192kHz+ 原生硬件采样率，并将 UAC Feature Unit 硬件音量锁定在 `0.0dB`（Unity Gain）。
- **UAC2 Explicit Feedback 闭环异步时钟伺服**：内置 8 个并发等时输出 URB（每个包含 8 个微帧）与 4 个显式时钟反馈输入 URB 管道，实时解析 DAC 反馈的 Q16.16 瞬时微帧采样率以进行相位累加微帧步进；在无反馈端点时自动降级至高精度 Bresenham 开环步进算法。
- **高精度物理子帧高位对齐 (`PcmFormatter`)**：支持 `S16_LE`（2 字节）、`S24_3LE`（3 字节打包）与 `S32_LE`（4 字节槽位）自动转换，将 16-bit / 24-bit 采样精准左移对齐至最高有效位（MSB）并低位补零，彻底杜绝位深不匹配导致的 48dB 音量衰减或爆音。
- **智能声卡动态让渡与自愈**：支持停止播放后延迟（可配置 `5 秒`、`10 秒`、`30 秒`或`常驻独占`）或外部应用抢占系统音频焦点（`AUDIOFOCUS_LOSS`）时，安全等待在途 URB 回调结束后释放接口并向 Linux 内核重绑 `snd-usb-audio` 驱动，无缝将声卡归还给电视系统其他影音应用。

### 2. 双引擎无损网络音频协议栈

- **原生 AirPlay 1 (AirTunes / RAOP) 引擎**：
  - **双通道免配对广播**：基于 `JmDNS` 广播符合 RFC 规范的 `<MAC>@<Name>._raop._tcp.local.` 服务（无真实网卡 MAC 时自动生成并持久化合法 IEEE 802 LAA 兜底 MAC），在 mDNS TXT 记录中声明 `am=AirRecever3,1`、在 `/info` 响应中伪装 `AirPort4,107` 纯音频硬件标识并拦截 `/pair-setup` 与 `/pair-verify` 请求，引导 iOS / macOS 走免配对的无损 AirPlay 1 通道。
  - **硬件指令集加速解密与无损解码**：内置 AirPort Express 经典 RSA-2048 私钥完成 `Apple-Challenge` 签名认证与 `rsaaeskey` 解密，C++ 层针对 `armeabi-v7a` / `arm64-v8a` 开启 ARMv8-A Crypto + NEON 编译优化完成 `AES-128-CBC` 实时解密，并由 Native `AlacDecoder` 或 L16/L24 PCM 转换器还原无损音频。
  - **抗抖动重排、主动 NTP 对时与 Sync 精准出声门控 (`RaopJitterBuffer` / `RaopRtp`)**：具备 512 帧（约 4.08 秒）容量、25 帧（约 200ms）Pre-Roll 预充门限与 8 帧（~64ms）乱序容忍窗口，丢包时向客户端控制端口发送 RAOP 标准 8 字节 `0xd5` 重传请求；通过主动 NTP Timing 授时（PT=82/83）8 样本最小 RTT 滤波与 RAOP Sync 控制包（PT=84）精准对标扬声器真实出声时钟。
  - **双向控制与元数据同步**：内置 `DmapParser` 解析曲目名称、艺术家、专辑、封面与进度，结合 `_dacp._tcp.local.` 动态端口发现、`DacpClient` 串行遥控队列及 RTSP `TEARDOWN` 后的 DACP 存活探活裁决，支持电视遥控器反向控制 iPhone / Mac 播放、暂停、切歌与寻道。

- **UPnP / DLNA MediaRenderer 2.0 引擎**：
  - **全状态 SSDP 与 GENA 架构**：内置 `UpnpRendererServer`（默认 HTTP 端口 `7952`，SSDP 组播端口 `1900`）与 `GenaEventNotifier`，支持 `AVTransport:1`、`RenderingControl:1` 与 `ConnectionManager:1` 服务描述、SOAP 动作响应、DIDL-Lite 解析及订阅状态 `LastChange` 实时推送。
  - **WAV / FLAC 无损流解复用 (`StreamDemuxer`)**：支持流式解析 WAV（含 `fmt `、`data` 及 `LIST INFO` 内嵌标签）与 Native `dr_flac` 16-bit / 24-bit 无损流式解码，支持基于 `HTTP Range (206 Partial Content)` 的高精度对齐寻道、实时直播流自动识别及单调会话令牌防护。

### 3. 三态正交会话协调与无锁硬件快路径

- **三态正交状态机 (`TriStateTransitionMatrix`)**：将播放器状态解耦为**传输管道态 (`TransportPipeState`)**、**硬件管道态 (`HardwarePipelineState` 7 态)** 与**业务会话态 (`BusinessSessionState`)**，由 `PlaybackSessionCoordinator` 单线程事件循环消费带 `sessionId`、`deviceGeneration` 与单调 `sequence` 的领域事件，统一归约并发布全局唯一可信快照 `PlayerSessionSnapshot`。
- **无锁硬件快路径 (`HardwareFastPathController`)**：暂停、切歌冲刷、停止与跨协议抢占直接穿透至 C++ 原子操作 `emergency_mute_and_flush`（严禁获取互斥锁或取消 URB），在 ≤20ms 内将输出切换为全 0 静音保活并按需递增 62-bit `buffer_epoch_`。
- **SPSC 无锁环形缓冲与“写先于清”门闩**：C++20 `SpscRingBuffer` 采用 64 字节缓存行对齐（消除伪共享）与 `mlock()` 物理内存页锁定（杜绝缺页中断）；配合 `Write-Before-Clear` 世代追平门闩与 100ms 预充自动解锁机制，彻底消灭切歌残音、跨代串扰与首音吞字。

### 4. 沉浸式多源歌词与 60fps 逐字扫光系统

- **严格阶梯瀑布流检索 (`LyricsWaterfallFetcher`)**：结合 In-flight 单飞去重、`LyricCandidateScorer` 多维防误配评分与简繁自动转换 (`ChineseConverter`)，严格按优先级顺序检索引擎：
  1. **L1 内存 / L2 磁盘缓存** (`LyricsCacheRepository`)
  2. **AMLL TTML 逐字高精库** (`AmllDataSource`，1.5s 硬超时)
  3. **LRCLIB 精确匹配四元组** (`LrclibDataSource /api/get`，1.5s 硬超时)
  4. **LRCLIB 模糊搜索** (`LrclibDataSource /api/search`，2.0s 硬超时)
  5. **网易云音乐中文曲库兜底** (`NeteaseDataSource`，2.0s 硬超时，同时自动为缺失翻译的 AMLL / LRCLIB 歌词补充简体中文逐句翻译)
- **单调时钟帧间插值与零重组卡拉 OK 扫光 (`LyricsSyncClock` / `WordSyncKaraokeText` / `LyricsStreamView`)**：
  - 将播放器低频进度脉冲线性外推至 60fps/120fps 连续时间轴，结合 16ms 帧级延迟缓存与指数移动平均（EMA, `alpha = 0.15`）低通滤波平滑物理缓冲抖动，并在切歌、Seek 时零延迟硬对齐。
  - 逐字扫光完全隔离在 Compose `drawWithContent` 阶段执行，预分配 `LinearGradient` 与 `Matrix` 平移复用，支持多行折行片段连续扫光与退场冻结保护，实现动画期间 **0 次 Compose 重组与 0 堆分配**。
  - 遵循 10-Foot 观影人体工学，采用 **38% 黄金视线垂直锚定**、`graphicsLayer` 物理弹簧波浪错峰上涌，以及长前奏（≥4.5s）/ 长间奏（≥5.0s）三点呼吸脉动指示器。

### 5. Apple TV 原生级视觉交互与 GLES2 硬件叠加流光背景

- **超低开销 GLES2 流体背景 (`MacBackground` / `MacTextureView` / `MacRenderer`)**：基于 `SurfaceView` + `SurfaceHolder.setFixedSize` 直接接入电视芯片 HWC / VPU 硬件显示叠加管线（彻底消除 `TextureView` 的 1080p `GlLayer` 分配与 Skia 重采样开销）。由独立 `HandlerThread` + `Choreographer` VSYNC 驱动，经 `128px / 192px` 离屏 FBO、三层旋转混叠、分离高斯模糊及预烘焙 `41×41` 材质网格（`assets/macfluid/mesh.bin`）顶点插值变形生成动态流光。
- **五档画质调节与优雅降级 (`FluidQuality`)**：提供**极佳（60fps，上限 1280px）**、**标准（20fps，上限 640px，默认）**、**省电（20fps · 低清，上限 480px）**、**静态磨砂**与**关闭**五档可选；暂停播放或切入后台/系统设置叠加层时自动暂停 VSYNC 渲染使 GPU 占用归零；首帧就绪前及 EGL 异常降级时采用 `96×96` 三次迭代盒式模糊生成的 `384×384` 温润渐变底图平滑衔接。
- **双模 10-Foot 布局与大屏防护**：主界面采用 **42%（封面）/ 58%（元数据与播控）** 通透布局，展开全屏歌词屏 (`LyricsScreen`) 后平滑过渡为 **40% / 60%** 沉浸式布局；搭载纯色彩语义链路指示胶囊（`SignalPathPill`）、纯 Draw 阶段防闪烁进度条（`ProgressBarComponent`）、自适应深浅色彩系统（`AppPalette`）以及播放态前台自动激活 `FLAG_KEEP_SCREEN_ON` 的防休眠联动。

---

## 🏗️ 系统架构与代码目录结构

### 架构分层概览

```plaintext
+-----------------------------------------------------------------------------------+
|                                  UI Layer (Compose TV)                            |
|  MainActivity / MainTvScreen / LyricsScreen / SettingsScreen / OnboardingWizard   |
|  MacBackground (GLES2 SurfaceView HWC Overlay) | LyricsStreamView (Zero-Recomp)   |
+-----------------------------------------+-----------------------------------------+
                                          | StateFlow<PlayerSessionSnapshot> / LyricUiState
                                          v
+-----------------------------------------------------------------------------------+
|                    Session, Lyrics & Service Host Layer (Kotlin)                  |
|   PlaybackService -> PlaybackServiceSessionHost <-> PlaybackSessionCoordinator    |
|   TriStateTransitionMatrix | TrackTransactionManager | HardwareFastPathController |
|   LyricsTriggerManager | LyricsWaterfallFetcher | LyricsCacheRepository           |
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
|  SpscRingBuffer (Lock-free + mlock) | RaopServer / RaopRtp / Alac / dr_flac       |
|  Static libusb-1.0 (Linux usbfs backend with LIBUSB_OPTION_NO_DEVICE_DISCOVERY)   |
+-----------------------------------------------------------------------------------+
```

### 核心代码目录结构

```plaintext
PureCast/
├── app/CMakeLists.txt                       # C++20 + ARMv8 Crypto/NEON 编译配置与静态 libusb 链接
├── app/build.gradle.kts                     # ABI 分包、JaCoCo 覆盖率门禁与 Native Sanitizer 测试任务
├── scripts/                                 # 无线部署、ADB 代理隧道、端到端推流与时序诊断脚本
└── app/src/
    ├── main/
    │   ├── assets/macfluid/mesh.bin         # 预烘焙 41×41 动态材质流光网格资源
    │   ├── java/com/solo/purecast/
    │   │   ├── PureCastApp.kt               # Application 入口，初始化 AppPalette 与加载 libpurecast_usb.so
    │   │   ├── airplay/                     # AirPlay 1 (RAOP) 协议栈模块
    │   │   │   ├── AirPlayServerManager.kt  # AirPlay 服务生命周期、DACP 探活裁决与密钥注入
    │   │   │   ├── AirPlayNativeEventDispatcher.kt # C++ RAOP 回调翻译、寻道防抖与会话映射
    │   │   │   ├── AirPlayKeyDecryptor.kt   # AirPort Express RSA 私钥解密与 Apple-Challenge 签名
    │   │   │   ├── AirPlayStateManager.kt   # AirPlay 会话状态与进度校准管理
    │   │   │   ├── DacpClient.kt            # Apple DACP 远端反向播控与寻道 HTTP 客户端
    │   │   │   ├── JmDnsDiscoveryService.kt # 基于 JmDNS 的 _raop._tcp 广播与 _dacp._tcp 端口监听
    │   │   │   └── NativeAirPlayCallback.kt # Native RAOP 回调接口契约
    │   │   ├── upnp/                        # UPnP / DLNA MediaRenderer 模块
    │   │   │   ├── UpnpRendererServer.kt    # SSDP 组播发现、HTTP/SOAP 控制服务与 DIDL-Lite 解析
    │   │   │   ├── StreamDemuxer.kt         # HTTP WAV/FLAC 流式解复用、LIST INFO 提取与 Range 寻道
    │   │   │   └── GenaEventNotifier.kt     # DLNA GENA 订阅管理与 LastChange 状态广播通知器
    │   │   ├── core/
    │   │   │   ├── audio/AudioFocusManager.kt # Android 11+ 系统音频焦点仲裁器
    │   │   │   ├── lyrics/
    │   │   │   │   ├── LyricsSyncClock.kt   # 单调时钟帧间线性插值、16ms 缓存与 EMA 延迟滤波
    │   │   │   │   └── LyricsTriggerManager.kt # 曲目切换单飞防抖歌词触发与状态发布器
    │   │   │   ├── nativebridge/NativeAudioEngine.kt # JNI Native 桥接单例接口
    │   │   │   ├── usb/
    │   │   │   │   ├── UsbDeviceManager.kt  # 单线程 HardwareExecutor、USB 枚举、授权与原位切频回滚
    │   │   │   │   └── UsbDeviceReceiver.kt # Direct Boot 感知的 USB 热插拔与开机广播接收器
    │   │   │   └── session/
    │   │   │       ├── PlaybackSessionCoordinator.kt # 全局会话协调器、事件鉴权、定时器与 DACP 串行队列
    │   │   │       ├── TriStateTransitionMatrix.kt   # 三态正交纯状态转换矩阵与副作用生成
    │   │   │       ├── TrackTransactionManager.kt    # 曲目代数事务与增量元数据合并管理器
    │   │   │       ├── HardwareFastPathController.kt # 无锁硬件快路径静音与切频控制器
    │   │   │       ├── ProtocolSessionBridge.kt      # 协议层与协调器通信桥接契约
    │   │   │       └── SessionEvents.kt              # 领域事件信封与载荷密封定义
    │   │   ├── data/
    │   │   │   ├── SettingsRepository.kt    # 基于 Device Protected Storage (DE) 的配置持久化仓库
    │   │   │   └── lyrics/                  # 多源歌词检索、L1/L2 缓存、评分、简繁转换与解析器
    │   │   │       ├── LyricsWaterfallFetcher.kt / LyricsCacheRepository.kt
    │   │   │       ├── LyricCandidateScorer.kt / MetadataNormalizer.kt / ChineseConverter.kt
    │   │   │       ├── parser/              # LrcParser.kt / TtmlParser.kt
    │   │   │       └── remote/              # AmllDataSource / LrclibDataSource / NeteaseDataSource / LyricsHttpClient
    │   │   ├── model/
    │   │   │   ├── PlaybackState.kt         # 全局不可变快照 PlayerSessionSnapshot 与核心状态枚举
    │   │   │   └── LyricModels.kt           # 逐字/逐行歌词时间轴与 LyricUiState 密封状态定义
    │   │   ├── service/
    │   │   │   ├── PlaybackService.kt       # mediaPlayback|connectedDevice 前台服务与系统锁/网络托管
    │   │   │   ├── PlaybackServiceSessionHost.kt # 快照副作用观察、MediaSession 同步与歌词联动
    │   │   │   └── MediaSessionMetadataHelper.kt # MediaSession 元数据与封面映射辅助器
    │   │   ├── util/                        # 分级日志 Logger、网卡过滤 NetworkUtils 与 PermissionHelper
    │   │   └── ui/                          # Android TV Compose 界面层
    │   │       ├── MainActivity.kt          # 顶层页面导航、物理遥控器按键拦截与防休眠常亮控制
    │   │       ├── background/              # GLES2 SurfaceView 硬件叠加渲染引擎 (MacBackground/MacTextureView/MacRenderer 等)
    │   │       ├── components/              # PlaybackControls / LyricsControlButton / ProgressBarComponent / SignalPathPill / FluidBackground
    │   │       ├── lyrics/                  # LyricsStreamView / SmoothLyricLineRow / WordSyncKaraokeText / LyricsRenderingMath / LyricStateViews
    │   │       ├── screen/                  # MainTvScreen / LyricsScreen / SettingsScreen / OnboardingWizard
    │   │       └── theme/                   # PureCastTheme 与 AppPalette 自适应深浅色彩系统
    │   └── cpp/                             # C++20 Native 核心层
    │       ├── jni_bridge.cpp               # JNI 方法映射、异常防护与线程级自动 Detach 封装
    │       ├── audio_engine.h / audio_engine.cpp # libusb 独占驱动、等时 URB 管线、Explicit Feedback 与三元组切频
    │       ├── ring_buffer.h                # 64 字节对齐 + mlock 的 SPSC 无锁环形缓冲区
    │       ├── pcm_formatter.h              # S16_LE / S24_3LE / S32_LE 子帧高位对齐转换器
    │       ├── uac_protocol.h               # UAC1 / UAC2 标准描述符与控制选择器协议定义
    │       ├── dr_flac.c / dr_flac.h & flac_decoder.h / flac_decoder.cpp # 基于 dr_flac 的 JNI InputStream 流式无损解码器
    │       ├── alac/                        # Apple Lossless (ALAC) 解码器实现
    │       ├── crypto/aes.c / aes.h         # AES-128-CBC 解密模块
    │       ├── raop/                        # Native RAOP RTSP 服务端、RTP 接收、NTP/Sync 门控、JitterBuffer 与 DMAP 解析
    │       ├── libusb/                      # 针对 Android usbfs 定制编译的 libusb-1.0 源码
    │       └── tests/                       # C++ 宿主机无硬件模拟测试套件 (ASan/UBSan + TSan)
    ├── test/                                # JVM + Robolectric 单元测试与会话状态机分支覆盖率验证
    └── androidTest/                         # Android TV 仪器化 UI 与服务集成测试
```

---

## 🔬 关键技术机制详解

### 1. 三态正交状态机与定时器矩阵

PureCast 摒弃了传统易产生竞态的单一播放状态枚举，通过 `TriStateTransitionMatrix` 维护三个正交状态维度：

| 状态维度 | 枚举类型 | 状态值集合 | 核心职责 |
| :--- | :--- | :--- | :--- |
| **传输管道态** | `TransportPipeState` | `DISCONNECTED`, `PREPARING`, `STREAMING`, `INTERRUPTED` | 反映 AirPlay / UPnP 网络协议流接收通道的连接与数据活性。 |
| **硬件管道态** | `HardwarePipelineState` | `DISCONNECTED`, `ATTACHED`, `PERMISSION_PENDING`, `RELEASED`, `EXCLUSIVE_MUTED`, `EXCLUSIVE_ACTIVE`, `AUDIO_RECONFIGURING` | 刻画 USB DAC 物理插拔、权限审批、内核驱动重绑让渡及等时传输锁相阶段。 |
| **业务会话态** | `BusinessSessionState` | `IDLE`, `PLAYING`, `TRANSITIONING`, `PAUSED`, `STOPPED` | 控制用户体验层的稳定阶段；`TRANSITIONING` 对外映射为 `PLAYING`（从 `PAUSED` 切歌进入时保持 `PAUSED`），防止切歌时界面闪烁。 |

协调器内置多项精密计时器以应对网络与硬件瞬态：

- **220ms 显式暂停防抖 (`DEBOUNCE_PAUSE_MS`)**：吸收远端协议在切歌瞬间下发的短暂 Pause 抖动，而用户通过电视遥控器主动点击暂停则以 `0ms` 立即生效。
- **800ms 切歌 UI 保护窗口 (`UI_PROTECTION_WINDOW_MS`)**：连续快速按键切歌期间锁定焦点与视觉连贯性。
- **1200ms 封面宽限期 (`ScheduleCoverGraceTimeout`)**：切歌边界保留上一曲封面，若新曲目在 1.2 秒内未提供封面才平滑清除，杜绝切歌封面黑屏闪烁。
- **5000ms 过渡态与 DACP 看门狗 (`TRANSITION_TIMEOUT_MS` / `REMOTE_COMMAND_TIMEOUT_MS`)**：防止建流无数据流入或远端未响应切歌指令导致 UI 卡死在 `TRANSITIONING`。
- **10s 默认硬件空闲让渡 (`HARDWARE_IDLE_RELEASE_MS`，可配置) 与 10m 挂起回收 (`SUSPEND_TIMEOUT_MS`)**：停止后自动归还声卡，长时间暂停后自动清理会话、DACP 凭据与低延迟 Wi-Fi 锁。

### 2. SPSC 世代防串扰与原位三元组切频

- **单字打包原子世代 (`epoch_and_flush_`)**：在 `AudioEngine` 中，64 位原子变量的高 62 位存储单调递增的 `buffer_epoch_`（起始为 `1`），低 2 位分别存储 `FLAG_PREROLLED`（100ms 预充就绪标志）与 `FLAG_FLUSHING`（切歌静音标志），利用单条 CAS 指令彻底消除多变量竞态。
- **原位切频与安全回滚 (`reconfigure_audio_format`)**：当音频规格发生变更（例如从 `44.1kHz/16bit` 切换至 `96kHz/24bit` 或 `192kHz/24bit`）时，优先在已打开的 `dev_handle_` 上原位重设 Alternate Setting 与硬件时钟；若硬件拒绝或发生管道错误，自动执行安全回滚并由 `UsbDeviceManager` 重新获取 `usbfs fd` 重开设备。
- **纯显式信令驱动与 FLUSH 后静音拦截门控 (`RaopRtp`)**：AirPlay 引擎以 RTSP 显式信令驱动状态迁移，杜绝网络短暂停包导致的误暂停；针对 iOS 在 `FLUSH` 后持续发送 44 字节全 0 静音保活帧的行为，在 `POST_FLUSH_SILENCE_PAUSE_FRAMES`（30 帧，~240ms）门控拦截下自动切换至 `PAUSED` 并在收到非静音真实有声帧后无感恢复。

### 3. RAOP NTP / Sync 闭环同步与扬声器真实进度反馈

- **NTP 对时与 250ms 延迟申报扣除**：`RaopRtp` 主动向客户端授时端口发送请求（PT=82/83）并用 8 样本最小 RTT 窗口锁定发射端单调时钟偏移；同时在 RTSP `RECORD` 阶段申报 `REPORTED_AUDIO_LATENCY_SAMPLES = 11025`（250ms @ 44.1kHz），结合 Sync 包（PT=84）将提前到达的音频帧平稳压在 `RaopJitterBuffer` 中，直至目标出声时刻放行，并在慢晶振滞后超限且环形缓冲高水位时受 `300ms` 冷却保护执行单帧平滑丢弃收敛。
- **扬声器真实出声进度闭环**：C++ 侧基于已写入缓冲的尾部 RTP 时间戳减去 `AudioEngine::get_ring_buffer_delay_ms()`（`StreamDemuxer` 同步扣除环形缓冲未消费采样），向 Kotlin 进度条与 `LyricsSyncClock` 提供真实的扬声器出声进度，使电视端大屏歌词与进度条与物理 DAC 声音毫秒级咬合。

---

## 🔒 权限声明与零录音隐私承诺

为了在无触控的 Android TV / 电视盒子上实现冷开机直启、静默接管与后台唤醒，PureCast 为 `MainActivity`、`PlaybackService` 与 `UsbDeviceReceiver` 启用了 `directBootAware="true"`（配置存储于 Device Protected Storage），并在首次启动时通过**开机权限与自检引导向导 (`OnboardingWizard`)** 引导完成以下关键授权：

1. **USB 声卡静默接管 (`RECORD_AUDIO`)**：
   > **零录音硬件调用承诺 (Zero-AudioRecord Privacy Guarantee)**：PureCast 纯粹作为局域网高保真音频渲染器与 USB 转盘，**全工程严禁且绝无任何 `AudioRecord` 硬件录音 API 调用，绝不采集任何麦克风环境声音**。申请该权限仅用于满足 Android 系统内核在无触控 TV 端对 USB Audio Class 声卡设备免弹窗静默接管的合规要求。
2. **悬浮窗与后台唤醒 (`SYSTEM_ALERT_WINDOW` & `USE_FULL_SCREEN_INTENT`)**：突破 Android 10+ 后台 Activity 启动限制（BAL），在电视待机或处于桌面收到手机端 AirPlay / UPnP 推流时，自动唤醒大屏播放界面。
3. **待机电池优化白名单 (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)**：配合 `PARTIAL_WAKE_LOCK`、`MulticastLock` 与推流期动态激活的 `WIFI_MODE_FULL_LOW_LATENCY` 低延迟锁，防止电视盒子熄屏或休眠时杀死后台组播发现与高码率推流服务。
4. **防权限自动回收白名单 (`Auto-Revoke Whitelist`, API 30+)**：防止长期闲置后系统自动重置已授予的 USB 与网络权限。
5. **前台服务与媒体通知 (`POST_NOTIFICATIONS`, Android 13+)**：保障 `mediaPlayback|connectedDevice` 类型前台服务通知与系统媒体会话状态正常展示。

---

## 🎮 遥控器交互与设置指南

### 物理遥控器快捷键

- **菜单键 (`KEYCODE_MENU`)**：在任意播放状态（开机引导向导展示期除外）下快速呼出或关闭全屏双栏“设置与硬件”侧边栏抽屉。
- **方向键与播控条 (`PlaybackControls`)**：主屏进度条为纯展示绘制控件，不干扰遥控器焦点流；方向键可在**上一曲 / 播放暂停 / 下一曲 / 歌词展开 / 设置**五个极简图标按钮间平滑切换。在不支持电视端切歌的 UPnP 流下，上一曲/下一曲按钮自动置灰不可获焦，物理按键触发时会弹出温和横幅提示而不抢夺焦点。
- **媒体控制键 (`MEDIA_PLAY_PAUSE` / `PLAY` / `PAUSE` / `STOP` / `PREVIOUS` / `NEXT`)**：全局拦截并直通控制当前活跃会话。
- **返回键 (`KEYCODE_BACK`)**：
  - **设置页**：采用 Apple TV 层级路由——处于自定义名称编辑态或焦点位于右侧配置项时，按返回键先退出编辑或将焦点交还左侧分类导航栏；焦点位于左侧导航栏时按返回键才退出设置页。
  - **全屏歌词页**：位于前台时按返回键优雅收起回退至主播放屏并自动恢复歌词按钮焦点。

### 信号链路胶囊 (`SignalPathPill`) 色彩语义

主屏幕右侧顶部的信号胶囊以 **`协议 · 编码格式 · 采样率/位深 | 设备名称`** 格式实时展示物理音频链路（待机态显示 `就绪 · 等待推流 | 设备名称`），并通过**圆点与设备名称颜色**直观传达硬件状态：

- 🟢 **亮绿色 (`#30D158`)**：USB DAC 硬件独占接管成功，处于 Bit-Perfect 源码直通与 UAC 物理时钟锁定状态。
- 🟡 **琥珀色 (`#FFD60A` / `#FF9F0A`)**：正在执行采样率重配（`切换采样率中...`）或等待 USB 权限授权（`等待 USB 权限...`）。
- 🔴 **亮红色 (`#FF453A`)**：未独占、声卡已让渡给系统共享输出或设备异常断开。
- ⚪ **中性灰 (`#8E8E93` / `#6E6E73`)**：就绪等待推流待机状态。

---

## 🛠️ 构建与测试

### 环境要求

- **Android SDK & 工具链**：`compileSdk = 34`、`targetSdk = 34`、`minSdk = 30`（Android 11 ~ 14）、Java 17、Kotlin `2.0.21`、AGP `8.5.2`；支持具备 USB Host 接口的 Android TV 电视与机顶盒（支持 `armeabi-v7a` 与 `arm64-v8a`）。
- **NDK & CMake**：NDK `26.3.11579264`、CMake `3.22.1+`，需支持 C++20 标准（`std::atomic`、缓存行对齐 `alignas(64)`）与 POSIX 线程库以编译 `libpurecast_usb.so`。

### 构建与质量门禁

在项目根目录下执行 Gradle 构建与校验命令：

```bash
# 构建 Debug APK（含 armeabi-v7a / arm64-v8a 分包及 Universal APK）
./gradlew assembleDebug

# 运行全量自动化校验门禁（JVM 单元测试 + JaCoCo 核心会话覆盖率校验 + Native Sanitizer CTest）
./gradlew check
```

> **`./gradlew check` 包含的强门禁**：
> 1. **JaCoCo 会话核心覆盖率 (`jacocoTestCoverageVerification`)**：强制要求 `PlaybackSessionCoordinator`、`TrackTransactionManager` 与 `TriStateTransitionMatrix` 指令与行覆盖率 ≥ 90%，后两者的分支覆盖率 = 100%。
> 2. **Native C++ Sanitizer 宿主机门禁 (`runNativeSanitizerTests`)**：自动使用 CMake 配置、编译并运行带 **ASan + UBSan** 与 **TSan** 的宿主机测试套件，通过 `--repeat until-fail:2` 校验零内存越界、零未定义行为与零并发竞态。

### 单独运行 C++ Native 核心单元测试

项目在 `app/src/main/cpp/tests/` 下内置了脱离真机硬件的宿主机 C++ 测试套件（包含模拟 Sony NW-ZX300A UAC2/UAC1 描述符与实时 URB 调度的 `MockLibusbController` 及 `MockJniInputStream`），覆盖：

1. **音频引擎测试 (`audio_engine_test.cpp`，9 个用例)**：Write-Before-Clear 门闩、`EXPLICIT_PAUSE` 不丢缓冲不改世代保活、多线程 SPSC 压力与 `warmup` 隔离、三元组原位切频（44.1k/16 → 96k/24 → 192k/24）与硬件错误回滚、`STOP_OR_TEARDOWN` 强制静音隔离、并发重入零 UAF / 零死锁校验、CAS 预充解锁防穿越以及 UAC1 3-byte 时钟与 Feature Unit 控制包校验。
2. **协议栈测试 (`protocol_native_test.cpp`，20 个用例)**：覆盖 `RaopServer` / `RaopRtp` / `FlacDecoder` 协同，包括暂停期间免疫远端冲刷、声卡重开后 `resume_after_reopen` 恢复、跨协议抢占 `preempt_active_client`、FLAC 解码器在暂停期遇世代失效立即退出、L24 96kHz 解析、JitterBuffer 背压滑动与序列号重锚定、主动 NTP Timing（PT=82/83）+ Sync（PT=84）门控、RTSP FLUSH 后静音拦截与有声自动唤醒，以及慢晶振漂移 300ms 冷却限频丢帧。

也可直接在宿主机运行 CMake / CTest：

```bash
cmake -S app/src/main/cpp/tests -B app/src/main/cpp/tests/build
cmake --build app/src/main/cpp/tests/build -j8
ctest --test-dir app/src/main/cpp/tests/build --output-on-failure --timeout 20
```

### 常用联调与诊断脚本 (`scripts/`)

```bash
# 无线安装 Debug APK 并自动授予核心运行时与白名单权限
bash scripts/adb_remote_install.sh <TV_IP> 5555

# 校验 armeabi-v7a / arm64-v8a 动态库产物与 ELF 符号完整性
bash scripts/build_and_verify_abi.sh

# 检查物理 USB DAC 内核声卡绑定、lsusb 挂载与实时日志链路
bash scripts/self_healing_diagnose.sh <TV_IP> 5555

# 推送 16-bit / 24-bit 无损 WAV 正弦波音频流进行端到端 UPnP 验证
python3 scripts/test_upnp_push.py <TV_IP> 7952 5 wav 96000 24
```
