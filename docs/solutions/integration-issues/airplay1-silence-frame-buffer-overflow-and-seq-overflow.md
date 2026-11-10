---
title: AirPlay 1 静音保活帧饱和溢出与长暂停 RTP 序列号回绕丢包治理
date: 2026-11-10
category: integration-issues
module: airplay-protocol-engine
problem_type: integration_issue
component: infrastructure
severity: high
symptoms:
  - "iPhone暂停或非Apple Music推流时以125fps持续推送44字节纯静音ALAC包，导致RingBuffer在暂停期间饱和溢出丢包"
  - "长暂停超过4.3分钟(32768包)后恢复播放，RTP序列号差值溢出int16_t为负数，触发JitterBuffer全部丢弃吞音"
  - "第三方音频应用(如Spotify)推流显示ALAC引发编码格式质疑，且视频App内TV投屏走UPnP导致音频播放器无法出声"
  - "从暂停态恢复推流时，旧环形缓冲区残留样本与JitterBuffer未重置导致首帧破音或时钟脱节"
root_cause: logic_error
resolution_type: code_fix
tags:
  - airplay
  - raop
  - alac
  - silence-frame
  - jitter-buffer
  - integer-overflow
  - ring-buffer
  - audio-engine
---

# AirPlay 1 静音保活帧饱和溢出与长暂停 RTP 序列号回绕丢包治理

## Problem

在 Apple AirPlay 1 (RAOP) 协议实现中，当 iOS 端处于暂停或播放未接管系统全局 `AVAudioSession` 的音频时，系统 CoreAudio 会以 125 fps 的速率持续向网络推送固定 44 字节（负载 32 字节，解码后全为 0）的 ALAC 纯静音保活帧。旧逻辑存在两大级联缺陷：
1. **缓冲区饱和死锁**：在等时传输因暂停而停止读取时，依然将 125 fps 静音帧无节制写入 SPSC RingBuffer，导致 128KB 环形缓冲区瞬间饱和溢出并持续丢包，恢复播放时产生严重吞音与延迟；
2. **长暂停序号溢出全丢弃**：当手机暂停超过 4.3 分钟（累计超过 32,768 个音频包）后恢复推流时，新包序号与旧序号之差在 `int16_t` 运算下符号位翻转为负数，导致 JitterBuffer 将恢复播放后的全部有效音频包误判为“过期乱序包”直接丢弃，造成长达数分钟的严重静音吞音。

## Symptoms

1. **持续性静音包抓包特征**：在 iOS 暂停期间，抓包显示 RTP 端口持续收到包长为 44 字节（12 字节 RTP 头 + 32 字节负载）的数据包，且负载 32 字节 Hex 恒定不变为 `6e8a9292bfe2863a5304925fce8ffb4d584029e32bde5e53d491260d16334030`。经 AES 解密与 ALAC 解码后，产出 1408 字节全零 PCM。
2. **环形缓冲区高水位饱和丢包**：暂停期间，日志频繁出现 `环形缓冲区高水位饱和 (可用: 128 < 需求: 1408)，整包丢弃以消除波形断裂爆音`。
3. **长暂停后恢复播放无声（吞音）**：用户在手机暂停 5 分钟后点击继续播放，TV 界面虽切回 `PLAYING`，但音响长时间没有任何声音，抓包显示手机在发声，接收端却全量丢弃。
4. **第三方推流编码与路由混淆**：用户从 Spotify 投送音频时，界面依然显示 ALAC，引发是否未正确匹配源文件编码的困惑；而在第三方视频 App 内部点击“TV”投屏时，由于 App 走的是 UPnP/DLNA 视频流（发送视频 URL），纯音频播放器无法解码画面导致无声。

## What Didn't Work

- **尝试 1：怀疑 ALAC 解码器异常或 AES 解密密钥失效**
  - *做法*：反复排查 OpenSSL AES-128-CBC 与 ALAC 解码实现，怀疑解码器输出了全零错误。
  - *失败原因*：解码器完全正常工作。iOS AirPlay 1 在暂停保活时发出的数据就是该固定的 32 字节 ALAC 压缩静音帧，流本身就是 100% 的真实纯静音。
- **尝试 2：盲目根据 RTP 包到达唤醒播放状态机**
  - *做法*：在 `data_loop` 收到任何 RTP 音频包时，无条件将 `is_stream_paused_` 置为 `false` 并通知上层恢复播放。
  - *失败原因*：由于 iPhone 在暂停后以 125 fps 持续发送 44 字节静音包，盲目唤醒导致手机端点击暂停后 TV 立即在几毫秒内又被唤醒为 `PLAYING`，双向暂停同步彻底失效。
- **尝试 3：仅检测前 64 字节判定静音**
  - *做法*：在解码后的 PCM 帧中仅比对前 64 字节是否全为 0。
  - *失败原因*：对于单帧 1408 字节音频，64 字节仅代表前 16 个采样点（约 0.36ms）。极弱信号或前奏渐强音乐可能前 16 个采样恰好为 0 但后部有微弱声音，局域比对会误判整帧为静音并丢弃，破坏微弱动态细节。
- **尝试 4：依赖 JitterBuffer 的自然滑动与差值重排**
  - *做法*：长暂停后不对 JitterBuffer 做重置，依赖 `(int16_t)(seq - head_seq_) >= BUFFER_SIZE` 自动重置游标。
  - *失败原因*：忽略了 16-bit 整数的模运算边界。当暂停时长超过 32,768 包时，`(seq - head_seq_)` 强制转换为 `int16_t` 溢出为负数（例如 35000 变为 -30536），导致判定 `diff >= 64` 失败，直接落入 `seq_less(seq, head_seq_)` 分支被当作过期包全量 drop。

## Solution

### 1. 全帧 64-bit 并行无锁静音检测

采用 64 位宽并行比对扫描整帧 PCM 数据，保证 100% 全为零始判定为静音，纳秒级完成且绝不漏检弱音：

```cpp
// app/src/main/cpp/raop/raop_rtp.cpp
bool is_silence = true;
const uint64_t* p64 = reinterpret_cast<const uint64_t*>(pcm_frame.data());
const size_t words64 = out_bytes / sizeof(uint64_t);
for (size_t i = 0; i < words64; ++i) {
    if (p64[i] != 0ULL) {
        is_silence = false;
        break;
    }
}
if (is_silence) {
    for (size_t i = words64 * sizeof(uint64_t); i < out_bytes; ++i) {
        if (pcm_frame[i] != 0) {
            is_silence = false;
            break;
        }
    }
}
```

### 2. 静音保活帧拦截：绝不灌入环形缓冲区

当判定为静音帧或当前处于暂停状态时，仅累加静音计数以维护状态机感知，直接 `continue` 丢弃，彻底消除暂停期间 RingBuffer 饱和丢包：

```cpp
// app/src/main/cpp/raop/raop_rtp.cpp
if (is_silence) {
    uint32_t sil_cnt = continuous_silence_count_.fetch_add(1, std::memory_order_relaxed) + 1;
    // 连续 25 帧全静音 (~200ms) 说明 iPhone 处于暂停推流保活状态
    if (sil_cnt >= 25 && !is_stream_paused_.load(std::memory_order_relaxed)) {
        is_stream_paused_.store(true, std::memory_order_release);
        LOGI("⏸️ 检测到连续静音保活帧 (iPhone 暂停推流)，同步 TV 端播放状态机为 PAUSED");
        if (state_cb_) state_cb_(true);
    }
    // 💡 关键保护：静音保活帧绝不推入 JitterBuffer 和 AudioEngine 环形缓冲区
    continue;
}
```

### 3. 恢复推流序号重锚定与缓冲区刷新 (Sequence Number Re-anchoring)

当收到非静音有效音频恢复播放时，显式重置 JitterBuffer 与刷新环形缓冲区，彻底攻克长暂停后 `int16_t` 负数溢出丢包：

```cpp
// app/src/main/cpp/raop/raop_rtp.cpp
} else {
    uint32_t prev_sil_cnt = continuous_silence_count_.exchange(0, std::memory_order_relaxed);
    if (prev_sil_cnt > 0) {
        LOGI("🔊 检测到有效音频信号恢复 (结束了连续 %u 帧静音)", prev_sil_cnt);
    }

    if (is_stream_paused_.load(std::memory_order_relaxed) && !is_manually_paused_.load(std::memory_order_relaxed)) {
        is_stream_paused_.store(false, std::memory_order_release);
        LOGI("▶️ 检测到有效音频信号 (iPhone 恢复推流)，重置 JitterBuffer 并同步 TV 端状态机为 PLAYING");
        // 💡 序号重锚定：清除旧序号基准，使恢复后首包重新触发 first_packet_ = true
        jitter_buffer_.reset();
        AudioEngine::instance().flush_buffer();
        if (state_cb_) state_cb_(false);
    }

    if (AudioEngine::instance().is_paused()) {
        AudioEngine::instance().resume_stream();
    }
}
```

### 4. 厘清 AirPlay 1 传输架构与第三方应用格式机制

- **AirPlay 1 协议规范**：根据 Apple AirTunes/RAOP 规范，音频传输格式仅支持 `pt=96` (ALAC) 和 `pt=10` (L16 PCM)，协议栈本身不存在 AAC 实时推流分支。
- **iOS 混音转码链路**：第三方 App（如 Spotify 320kbps AAC）在 iOS 内部会先被解码为 PCM，随后由 iOS 系统级 CoreAudio AirPlay 驱动封装为 16-bit 44.1kHz ALAC 进行无损压缩传输，接收端解密还原出一致的 PCM。因此 PureCast 状态栏显示 **ALAC** 属于对物理传输链路的客观准确反映，且全程保持 Bit-Perfect 无二次有损损耗。

## Why This Works

1. **解耦传输保活与音频调度**：AirPlay 1 的 44 字节全零包是苹果为了维持 NAT 穿透、UDP 连接保活及毫秒级时钟对齐设计的协议机制。将“数据包到达”与“有效声音载荷”解耦，避免将保活数据误当音频输入，保障了 SPSC 环形缓冲区的平稳水位。
2. **规避 16-bit 序列号环绕数学陷阱**：在 RFC 3550 RTP 规范中，序列号为 16 位循环递增（0~65535）。当连续静音导致时钟间断跨越了 $2^{15} = 32768$ 包时，有符号差值强制转换必然发生符号反转。通过在状态恢复点主动触发 `jitter_buffer_.reset()`，将恢复后的首包重新确立为绝对时间基准点，消除了历史序号对当前播放的干扰。

## Prevention

1. **边界仿真测试**：在 [RaopProtocolIntegrationTest.kt](file:///Users/solo/Documents/PureCast/app/src/test/java/com/solo/purecast/RaopProtocolIntegrationTest.kt) 中补充了长暂停（35,000 包差异）和连续 25 帧静音双向状态机切换的单元测试用例。
2. **禁止局部静态状态机变量**：所有涉及跨会话生命周期的计数器（如 `continuous_silence_count_`）严禁使用函数内部 `static` 变量，必须声明在 Session/Channel 类成员中并在 `start()` / `stop()` 时显式重置。

## Related Issues

- [AirPlay 1 (RAOP) 协议状态混淆与会话生命周期治理](airplay1-pause-teardown-state-machine-and-dacp-control.md)
- [AirPlay RAOP 接收端稳定性与音频杂音治理](airplay-raop-receiver-stability-and-audio-pops.md)
- [Android TV Compose 界面重构与焦点回退治理](../ui-bugs/android-tv-compose-apple-tv-redesign-and-focus-resiliency.md)
