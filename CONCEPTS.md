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
