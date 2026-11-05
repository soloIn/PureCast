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
An OpenGL ES 2.0 distortion-mesh background renderer adapted from Apple Music for Android TV (`MacBackground`). It extracts characteristic palette colors from the current album artwork and runs continuous, subtle low-poly mesh rotation and multi-pass Gaussian blur to create an organic liquid-light immersion on big screens with minimal SoC overhead.
*Aliases: GLES2 动态材质流光背景, 封面动态液态流光*
