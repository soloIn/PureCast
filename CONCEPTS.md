# PureCast Domain Concepts

## Core Concepts

### Bit-Perfect Audio
Audio playback that preserves the exact sample rate, bit depth, and PCM samples of the original source file without software resamplers, mixers, or Android's fixed 48 kHz Sample Rate Conversion (SRC).
*Aliases: Bit-Perfect, 源码直通, 无损直通*

### Explicit Feedback Loop
A hardware synchronization mechanism in USB Audio Class 2.0 (UAC2) where an asynchronous audio sink (USB DAC) reports its instantaneous crystal clock consumption back to the host via an isochronous IN feedback endpoint in Q16.16 fixed-point format, allowing the host's phase accumulator to pace packet delivery to match hardware crystal frequency without buffer overruns or underruns.
*Aliases: UAC2 Explicit Feedback, 显式时钟反馈闭环*

### SPSC RingBuffer
A lock-free single-producer single-consumer circular byte buffer that decouples variable-rate network audio streaming (WAV demuxing or FLAC decoding) from real-time Linux audio isochronous URB transfer scheduling.
*Aliases: Ring Buffer, 无锁环形缓冲区*

### Exclusive Hardware Claim
A rootless hardware claim pattern where an application unbinds the Linux kernel `snd-usb-audio` driver via usbfs and claims the USB audio streaming interface for exclusive direct bit-perfect transmission, subsequently releasing the claim and reattaching the kernel driver when audio playback goes idle.
*Aliases: USB 独占接管, 声卡让渡*

### RAOP (Remote Audio Output Protocol)
Apple's streaming audio transport protocol in AirPlay 1, combining RTSP for session control and key negotiation with UDP/RTP for encrypted ALAC streaming and client-side clock synchronization.
*Aliases: AirTunes, RAOP, AirPlay 1 协议栈*

### Jitter Buffer
A multi-packet resequencing buffer in network streaming receivers that absorbs variable UDP packet arrival latency, restores strictly monotonic sequence ordering, and compensates for packet loss via silence frame synthesis.
*Aliases: 抖动缓冲区, RTP 重排队列*

### Drop Whole Packet
An acoustic protection invariant in real-time PCM ring buffers where any incoming packet that exceeds remaining buffer capacity is dropped entirely rather than partially written, preventing stereo channel misalignment and phase-discontinuity pops.
*Aliases: 整包丢弃原则, 完整帧原子写入*

### Signal Path Capsule
A read-only, non-interactive TV UI status badge placed above the track title, formatted as `协议 · 音频格式 | 设备名称`. It conveys real-time streaming protocol, sample rate/bit depth, and USB DAC exclusive claim state purely through color semantics (green text for bit-perfect exclusive claim, red text for shared output/let-go/error) without redundant textual suffixes.
*Aliases: 音频品质胶囊, 链路状态指示徽章*

### Dynamic Mesh Fluid Background
An OpenGL ES 2.0 distortion-mesh background renderer adapted from Apple Music for Android TV (`MacBackground`). It extracts characteristic palette colors from the current album artwork and runs continuous, subtle low-poly mesh rotation and multi-pass Gaussian blur to create an organic liquid-light immersion on big screens with zero-allocation VSYNC frames and dual-mode theme adaptation.
*Aliases: GLES2 动态材质流光背景, 封面动态液态流光*

### D-Pad Focus Engine
A directional navigation model for Android TV split-pane layouts that couples focus-change observation with hierarchical BackHandler interceptors to ensure the remote's Back button retreats up the navigation tree rather than exiting the screen.
*Aliases: Hierarchical Back Navigation, D-Pad 层级焦点导航*

### Direct Boot Environment
An Android execution state during system boot before the user unlocks the device, where Credential Encrypted Storage is locked and unavailable. Background components marked with `directBootAware="true"` execute within Device Protected Storage context (`createDeviceProtectedStorageContext`) and must not access standard user `SharedPreferences` to prevent startup crashes.
*Aliases: 直启模式, 存储保护上下文*

### Session Monotonic Token
A strictly monotonically increasing atomic token (`AtomicLong`) that gates asynchronous coroutine lifecycles in stream demuxers. It ensures obsolete stream tasks in cancellation cleanup blocks cannot mutate playback state or terminate newer active playback sessions.
*Aliases: 单调会话令牌, 切歌生命周期栅栏*

### Stream Source Preemption
An arbitration policy across heterogeneous streaming protocols (e.g., UPnP/DLNA and AirPlay) where a newer incoming stream actively shuts down the incumbent stream, resets the underlying SPSC RingBuffer, and claims exclusive USB DAC playback to prevent dual-stream mixing.
*Aliases: 跨协议后发抢占, 推流仲裁*

### AirPlay Soft Suspend
A session management state in AirPlay 1 where transport-layer audio transmission channels (RTP over UDP) are detached via RTSP `TEARDOWN` upon pause, but application-level session context (track metadata, protocol association, and DACP control credentials) remains active and preserved under a watchdog timeout, enabling TV remote reverse resumption and preventing premature USB DAC release.
*Aliases: 软挂起状态, 传输层解耦挂起, Transport Detached State*

### DACP Reverse Wakeup
A reverse-control workflow over Apple Digital Audio Control Protocol where a TV receiver sends HTTP media control commands (such as `play`) to an AirPlay sender whose audio transmission channel has been suspended, instructing the sender to re-establish RTSP SETUP and RECORD streaming to resume playback without user interaction on the mobile device.
*Aliases: DACP 反控唤醒, 遥控反向建流*

### Silence Keep-Alive Frame
A continuous stream of fixed 44-byte ALAC packets (32-byte payload, decoding to 1408 bytes of zeros) transmitted at 125 fps by iOS CoreAudio during AirPlay 1 pause states or un-focused audio sessions to preserve UDP port NAT bindings and RTP clock synchronization without breaking the session. Receivers queue these frames normally into the jitter buffer to preserve strictly monotonic RTP sequence numbers and UAC2 clock pacing, while the playback state machine is decoupled and driven by explicit RTSP signaling (FLUSH, TEARDOWN soft-suspend, and RECORD resume).
*Aliases: 静音保活帧, 协议心跳帧*

### Jitter Buffer Backpressure Sliding
A flow control degradation pattern in network audio jitter buffers where, under downstream buffer backpressure, the buffer discards the oldest audio packets to advance `head_seq_` and retain the newest contiguous audio window, preventing sudden buffer purges, USB DAC underruns, and physical audio pops.
*Aliases: 抖动缓冲背压滑动, 最旧帧平滑淘汰*

### Sequence Number Re-anchoring
A resynchronization procedure in real-time UDP audio receivers where, following an extended pause exceeding the 16-bit sequence difference boundary ($2^{15} = 32768$ packets), the jitter buffer is explicitly reset upon the arrival of the first non-silent audio packet to establish a new monotonic sequence baseline (`first_packet_ = true`), preventing signed integer overflow (`int16_t` wrapping negative) from discarding valid incoming stream packets as out-of-order drops.
*Aliases: 序号重锚定, 时钟基准重置*

### Strict Waterfall Lyrics Fallback
A sequential network retrieval hierarchy for lyric metadata that queries L1 memory cache, L2 disk cache, AMLL TTML DB (1.5s timeout), LRCLIB exact match (1.5s timeout), and LRCLIB fuzzy search (2.0s timeout) strictly one stage at a time, minimizing TV network connection load and thread exhaustion while maintaining syllable-level TTML quality prioritization.
*Aliases: 严格瀑布流歌词降级, 阶梯式多源歌词检索*

### Monotonic Clock Frame Interpolation
A high-frequency rendering clock synchronization algorithm for big-screen lyric displays that bridges low-frequency player progress updates (500ms~1000ms) with 60fps/120fps VSYNC frame presentation by linearly extrapolating elapsed time using `SystemClock.elapsedRealtime()`, capped by a protective upper limit to eliminate line-scroll stutter and enable smooth syllable-level karaoke sweeping.
*Aliases: 单调时钟帧间平滑插值, 歌词高频时钟外推*

### Seek Deadband Suppressor
A temporal hysteresis gate in lyric rendering time synchronization that establishes a 250ms deadband window immediately following a coarse seek or timestamp jump ($|\Delta t| > 1500\text{ms}$), suppressing backward-sliding jitter packets caused by network buffering readjustments and preventing visual oscillation or text bouncing.
*Aliases: 寻道回弹抑制死区, 时间戳跳变滞后滤波*

### Immersive Lyrics Screen Expansion
A 10-Foot UI presentation paradigm adapted from Apple Music for Android TV, where a dedicated subtitle/lyrics control button in the primary playback controls initiates a spring-animated transition between the standard 42%/58% cover-dominant screen and a 35%/65% lyrics-dominant view featuring active line enlargement, dynamic syllable sweep, and dual-language secondary translation.
*Aliases: Apple Music TV 风格大屏歌词展开, 沉浸式双模歌词交互*

### Golden Ratio Lyrics Viewport
A 10-Foot UI viewing ergonomics rule in Android TV lyric streams that anchors the currently sung lyric line at exactly 38% from the top of the viewport rather than 50% dead center, aligning with the human eye's natural rest angle on large elevated displays (65-85 inch TV at a 2.5-4m viewing distance) and dynamically adjusting `LazyColumn` contentPadding (`targetCenterY - halfItemHeight`) to ensure edge lines scroll smoothly to the anchor without hitting rigid scroll boundaries.
*Aliases: 38% 黄金视线分割点, 歌词黄金视线锚定*

### Zero-Recomposition Syllable Sweep
A 60fps/120fps hardware-accelerated text rendering architecture for syllable-level karaoke lyrics in Compose TV. It completely bypasses Compose layout and recomposition cycles during continuous time updates by deferring timeline querying to the `drawWithContent` phase (`currentTimeProvider`), segmenting multi-line wrapped text by visual layout lines (`layoutResult.getLineForOffset`), and applying zero-allocation direct rectangle fill (`drawRect(color)`) or single-pass horizontal gradient brushes purely in the native GPU rendering pipeline.
*Aliases: 纯 Draw 阶段零重组逐字扫光, 无分配卡拉OK扫光*


