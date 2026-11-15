---
title: "AirPlay 1 信令数据面解耦与 JitterBuffer 背压滑动流控治理"
date: "2026-11-15"
category: "integration-issues"
module: "airplay-protocol-engine"
problem_type: "integration_issue"
component: "infrastructure"
severity: "critical"
symptoms:
  - "iOS 端暂停推流时被误判为切歌导致进度清零，且在电视端暂停态下 Seek 或切歌时扬声器声音偷跑"
  - "突发连续推流触发环形缓冲区背压时，JitterBuffer 满溢误判为跳变并清空整秒音频引发 DAC 欠载断流爆音"
  - "RTP 序列号负向跳变门限过大导致短暂停顿或切歌后新推流被当成过期包大面积误丢"
  - "切歌换代时 TrackBoundaryEvent 将上一首残留早期进度 (<3000ms) 错误继承给新歌导致大屏进度闪烁"
  - "超大专辑封面在跨进程传输时引发 TransactionTooLargeException 并冲刷清空大屏封面"
root_cause: "logic_error"
resolution_type: "code_fix"
tags:
  - "airplay"
  - "raop"
  - "jitter-buffer"
  - "backpressure"
  - "state-machine"
  - "pause-contract"
  - "audio-engine"
  - "mediasession"
---

# AirPlay 1 信令数据面解耦与 JitterBuffer 背压滑动流控治理

## Problem

在 PureCast 的 Android TV 原生投屏架构演进中，AirPlay 1 协议栈与音频引擎长期存在深层次的控制面与数据面耦合。历史实现尝试通过“统计全零静音帧包数”来推测 iOS 客户端是否暂停，引发了状态机震荡与丢帧。同时，消费端背压处理不当与序列号跳变判定失误，导致客户端突发推流时发生整秒级缓存清空与 USB DAC 断流爆音；暂停契约在寻道预充时被破坏导致声音偷跑。

## Symptoms

1. **暂停态音画跳变与声音偷跑**：在电视端或手机端暂停后，用户在客户端拖动进度条（Seek）或后台切歌，AirPlay 发送预充音频包。状态机将 `AIRPLAY_1` 首包无条件判定为恢复，导致扬声器突然偷跑发声，大屏控制台强行变为 `PLAYING`。
2. **突发推流整秒音频清空与硬件爆音**：iOS 客户端在切歌或开播初期以数倍速连续发送 2 秒音频包（突发推流）。当底层 `AudioEngine` 环形缓冲饱和触发背压停止出队时，`RaopJitterBuffer` 积攒满 128 帧，后续到达的连续合法音频包触发 `seq - head_seq_ >= BUFFER_SIZE` 判定，被误判为“序列号跳变异常”，整整 1 秒（128 帧）高品质音频被暴力清零，直接导致 USB DAC 欠载断流产生剧烈物理爆音。
3. **切歌后长时间无声**：`RaopJitterBuffer` 负向跳变门限设为 16384，当手机端短暂停顿后切歌导致 RTP 序列号发生小范围负向跳变（例如 lag 在 128 到 16384 之间），接收端将其判定为过期包直接丢弃，新歌曲前数秒至十余秒完全静音。
4. **切歌进度闪烁跳变**：RTSP `FLUSH` 为保全暂停进度未将进度归零，紧随其后的 `TrackBoundaryEvent` 因防抖区间将上一首曲目残留的 `<3000ms` 进度赋予新歌，大屏显示错误初始进度。
5. **系统级 Binder 异常与封面抹除**：高解析度专辑封面通过 `MediaSession.setMetadata` 传递时超出 1MB 事务缓冲区限制，抛出 `TransactionTooLargeException` 导致封面渲染失败甚至服务崩溃。

## What Didn't Work

- **尝试 1：数静音包猜暂停（Heuristic Silence Counting）**
  - *做法*：在 RTP 接收线程中统计连续全零静音帧包数，达到 5 包即判定为客户端暂停。
  - *失败原因*：iOS 客户端推流策略极其多变（包含第三方播放器渐弱、通知音混音、静音音轨等）。这种启发式计数极易在安静乐段误判为暂停；更严重的是在 `jitter_buffer_.push()` 之前丢弃静音帧导致 RTP 序列号出现人为断层，破坏了 RFC 3550 乱序重排时钟轴。
- **尝试 2：序列号跳变时无差别清空 JitterBuffer**
  - *做法*：当 `seq - head_seq_ >= BUFFER_SIZE` 时，无条件清空所有 slots 并重置 `head_seq_ = seq`。
  - *失败原因*：混淆了“网络远距离跳变”与“下游消费端背压”。在下游 `AudioEngine` 空间不足暂停出队时，上游正常连续递增的 RTP 包填满 128 槽位后同样满足该条件，导致整秒缓存被直接抹除。
- **尝试 3：仅依赖 `FirstDataPacketEvent` 作为唯一播放唤醒源**
  - *做法*：只要处于 `PAUSED` 且收到 `StreamProtocol.AIRPLAY_1` 首包，就无条件跃迁为 `PLAYING`。
  - *失败原因*：AirPlay 在暂停态执行 Seek 或切歌时，iOS 必须向服务器推送预充电平与缓冲，此时客户端信令仍未处于播放态，首包自动激活直接违反暂停契约并导致声音偷跑。

## Solution

### 1. 信令面与数据面彻底解耦与显式暂停契约守卫

彻底废除基于静音包猜测暂停的脏代码。正常全零 PCM 完整压入 JitterBuffer 维持序列号连续与 UAC2 时钟：
- **信令驱动**：暂停完全由显式 RTSP `FLUSH`、`TEARDOWN`（软挂起）以及大屏遥控指令驱动；
- **暂停契约守卫**：在 [TriStateTransitionMatrix.kt](file:///Users/solo/Documents/PureCast/app/src/main/java/com/solo/purecast/core/session/TriStateTransitionMatrix.kt#L164-L187) 中，只要当前处于 `PAUSED` 态，到达的 `FirstDataPacketEvent` 坚决维持 `PAUSED` 业务态与 `EXCLUSIVE_MUTED` 硬件态持续泵 0 保活，绝不调用 `ResumeNativeOutput`；真正的播放恢复统一交由显式 `RECORD` 信令触发的 `StreamResumeEvent` 处理。

### 2. JitterBuffer 背压滑动淘汰与跳变门限对齐

在 [raop_jitter_buffer.cpp](file:///Users/solo/Documents/PureCast/app/src/main/cpp/raop/raop_jitter_buffer.cpp#L45-L95) 中，严格区分网络异常断层与消费端背压：
- **负向跳变对齐**：负向重锚定门限改为 `lag >= static_cast<int16_t>(BUFFER_SIZE)`，超出 128 帧容量的合法新推流立即重锚定游标；
- **背压滑动淘汰**：当 `seq - head_seq_ >= BUFFER_SIZE` 时，比对 `seq - max_seq_seen_`。若非远距离断层，严禁清空 `slots_`，通过循环弹出淘汰最旧的槽位推进 `head_seq_`，完整保留已有高品质音频，消除 DAC 欠载断流；
- **先探查后出队 (Peek-Size Before Pop)**：在 `raop_rtp.cpp` 的消费循环中先调用 `jitter_buffer_.peek_size`，仅在环形缓冲空间充裕时才执行 `pop()`，杜绝出队后无处写入导致丢包。

### 3. 跨曲目边界进度单调复位

在 [TriStateTransitionMatrix.kt](file:///Users/solo/Documents/PureCast/app/src/main/java/com/solo/purecast/core/session/TriStateTransitionMatrix.kt#L640-L655) 中约束：仅在会话首次开播冷启动（`snap.trackGeneration == 0L`）且音频先于元数据走字时保留 `<3000ms` 进度；只要此前已有活跃曲目（`snap.trackGeneration > 0L`）发生切歌换代，新曲目进度坚决归零 (`0L`)。

### 4. MediaSession 异步下采样与哈希缓存

在 [MediaSessionMetadataHelper.kt](file:///Users/solo/Documents/PureCast/app/src/main/java/com/solo/purecast/service/MediaSessionMetadataHelper.kt#L27-L63) 中：
- 将超大封面在后台协程下采样限制为 `512x512 RGB_565`，彻底避免 Binder 事务溢出；
- 维护 `lastCoverMetadataHash`，歌名或播放进度频繁刷新时直接复用已解码的 Bitmap，避免重复解码与界面闪烁。

## Why This Works

1. **状态机确定性**：将播放/暂停控制权从易变的高频 RTP 数据流剥离，回归到严肃可靠的 RTSP 信令协议状态机，使网络毛刺与静音数据不再具备干扰业务状态的能力。
2. **滑动窗口保护**：背压发生时丢弃最旧的数据包是网络实时音频的最佳降级策略，它保证了 JitterBuffer 始终保留距离当前最近的 1 秒完整连续音频，下游恢复读取时无缝衔接，杜绝声卡饥饿。
3. **世代生命周期隔离**：以 `trackGeneration` 作为曲目进度的刚性隔离栅栏，消除了跨曲目状态泄漏。

## Prevention

1. **严禁在数据面引入状态机猜测逻辑**：禁止通过检测音频包内容（如全零、静音）来猜测协议状态；协议状态只能由明确的会话信令与用户动作驱动。
2. **缓冲区操作必须具备背压探查**：所有环形队列/JitterBuffer 必须遵循“Peek 可用空间 -> 确认下游可容纳 -> Pop 消费”三部曲，严禁在未确认下游可用空间前出队。
3. **单元测试必须覆盖背压全满场景**：Native 测试套件必须包含 `test_jitter_buffer_burst_backpressure_does_not_wipe`，断言推流超出容量时有效数据包被保全而非被误判清空。

## Related Issues

- [airplay-raop-receiver-stability-and-audio-pops.md](file:///Users/solo/Documents/PureCast/docs/solutions/integration-issues/airplay-raop-receiver-stability-and-audio-pops.md) -- 整包原子写入与 JitterBuffer 乱序重排基础
- [airplay1-pause-teardown-state-machine-and-dacp-control.md](file:///Users/solo/Documents/PureCast/docs/solutions/integration-issues/airplay1-pause-teardown-state-machine-and-dacp-control.md) -- AirPlay 软挂起与 DACP 反向建流
- [airplay1-silence-frame-buffer-overflow-and-seq-overflow.md](file:///Users/solo/Documents/PureCast/docs/solutions/integration-issues/airplay1-silence-frame-buffer-overflow-and-seq-overflow.md) -- 早期静音包排空与序列号溢出治理（已被本文之信令数据面彻底解耦与滑动流控取代）
