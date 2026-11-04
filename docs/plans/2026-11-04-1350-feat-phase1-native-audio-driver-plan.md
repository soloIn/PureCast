---
title: PureCast 阶段一：底层音频驱动与高解析数据直通完善 - Plan
type: feat
date: 2026-11-04
origin: 项目背景与核心目标.md
artifact_contract: ce-unified-plan/v1
product_contract_source: legacy-requirements
execution: code
---

# PureCast 阶段一：底层音频驱动与高解析数据直通完善实现计划

---

## Goal Capsule

- **Objective**: 彻底修复并强化 PureCast 的 C++ Native 等时驱动层，使其完整支持 24-bit 高解析 PCM 输入与硬件子帧的动态对齐；发送 UAC Feature Unit 控制包锁定 0.0dB Unity Gain 满幅直通；增加切换采样率与唤醒时的 50ms 静音预充（DAC Warmup）；提升等时 URB 调度线程至 Linux 实时音频优先级 (`nice -19`) 并通过 `mlock()` 锁定物理内存；升级自动化推流测试套件，实现 24-bit/96kHz 高解析流自动化验证。
- **Means**: 改造 `app/src/main/cpp/audio_engine.cpp` 与 `pcm_formatter.h`，以 `current_bit_depth_` 为依据动态分流 16-bit 与 24-bit PCM 搬运；解析 UAC AudioControl 接口的 Feature Unit 实体并发送 `FU_VOLUME_CONTROL`；在 URB 线程入口调用 `setpriority(PRIO_PROCESS, 0, -19)` 与 `mlock()`；更新 `scripts/test_upnp_push.py`。
- **Authority Hierarchy**:
  1. 产品行为以 `Product Contract` 中明确的 R-IDs 为唯一权威；
  2. 底层驱动机制与技术实现以 `Planning Contract` 中明确的 KTD-IDs 为唯一权威；
  3. 实现单元（U-IDs）严格遵循上述规范，不任意扩展业务边界。
- **Stop Conditions**: 纯 32 位/64 位 NDK 编译通过，`test_upnp_push.py` 分别成功推送 16-bit/44.1kHz 与 24-bit/96kHz 音频，索尼 ZX300A 屏幕分别精准跳变为 `24-bit / 44.1kHz` 与 `24-bit / 96kHz`，且无爆音、无杂音、音量满幅无衰减。
- **Execution Profile**: 适用于 `ce-work` 执行，依赖无线 ADB（`192.168.2.179:5555`）连接真实 EF-15 电视盒子与索尼 ZX300A 验证。

---

## Product Contract

### Summary
本实施计划为 PureCast 驱动层与高解析直通的专项落地计划。针对当前代码中硬编码 16-bit 输入导致的 24-bit 高解析流破音隐患、缺少 Feature Unit 硬件音量锁定、缺少 DAC Warmup 静音预充以及缺少实时调度优先级与物理内存锁定的问题进行彻底修复，构筑坚不可摧的 Bit-Perfect 硬件直通底盘。

### Problem Frame
虽然已成功实现了 UAC2 显式时钟反馈闭环与连续紧凑 URB 内存打包，消除了 8kHz 蜂鸣杂音，但当前底层驱动仍存在如下缺陷：
1. `fill_isoc_transfer` 写死了单采样 2 字节（16-bit），当接收 MacBook `swyh-rs` 推送的 24-bit/96kHz 高解析流时，会将 3 字节当 2 字节消费，导致严重的音频破损错位；
2. 未将 UAC Feature Unit 硬件音量显式锁定在 0.0dB，部分固件默认音量衰减或非满幅；
3. 切换采样率时 DAC 内部物理继电器动作存在 50ms 延迟，首帧未做静音预充容易产生物理切频爆音；
4. 调度线程运行在普通 Linux 优先级且内存未锁定，系统高负载时可能被内核换页引发偶发卡顿。

### Key Decisions
- **KD1. 动态输入位深（16-bit / 24-bit）分流与高位对齐**: 引擎根据当前流的 `current_bit_depth_`（16 或 24）动态计算从 RingBuffer 提取的字节数，并匹配调用 `PcmFormatter::format_16bit_to_target` 或 `PcmFormatter::format_24bit_to_target` (session-settled: user-directed — chosen over 强制将所有输入在 Java 层转成 16-bit: 保证 24-bit 高解析音源数学上的 100% Bit-Perfect 零损伤直通). *Governs R3.*
- **KD2. UAC Feature Unit 主音量锁定 0.0 dB (Unity Gain)**: 解析 AC 接口描述符提取 Feature Unit ID，发送 Class-specific 控制请求将 Master 音量设置为 0x0000（0.0dB 满幅直通），防止硬件数字衰减 (session-settled: user-directed — chosen over 忽略硬件音量控制: 确保各类 DAC 均达到硬件满幅). *Governs R3.*
- **KD3. DAC Warmup 50ms 全零静音首发机制**: 建立流连接或采样率改变时，在 URB 调度线程向 DAC 提交首批音频数据前，先发送 50ms 全零数据包，等待 DAC 物理继电器切频稳定 (session-settled: user-directed — chosen over 直接提交有效音频: 消除切换采样率时的硬件继电器咔嗒爆音). *Governs R3.*
- **KD4. Linux 实时调度提升与物理内存锁定 (`mlock`)**: 在 C++ URB 事件循环线程中使用 POSIX 接口设置 `nice -19`，对 RingBuffer 物理内存执行 `mlock()` (session-settled: user-directed — chosen over 依赖 Android 默认进程调度: 杜绝 Android 电视盒子低算力下的换页抖动与爆音). *Governs R3.*

### Requirements
- **R3-P1. 24-bit 高解析音频动态对齐直通**: 支持上游 24-bit PCM 输入；在 `S24_3LE` 子帧下直接原样复制，在 `S32_LE` 子帧下执行 `sample_24 << 8`，高位严格对齐、低 8 位补零。
- **R3-P2. UAC Feature Unit 音量锁定 0.0dB**: 支持 UAC1 与 UAC2 Feature Unit 解析，在打开设备时发送 `FU_VOLUME_CONTROL` 将左右声道及主音量锁定在 0dB。
- **R3-P3. 采样率切换 DAC Warmup**: 切换采样率时，向等时端点持续输出 50ms 全零静音帧，随后再启动真实音频消费。
- **R3-P4. 实时优先级与内存锁定**: URB 事件调度线程设置 `nice -19`（`URGENT_AUDIO`），RingBuffer 内存页调用 `mlock()` 锁定物理 RAM。
- **R3-P5. 自动化高解析推流验证**: 提供自动化推流工具，验证 16-bit/44.1kHz 与 24-bit/96kHz 真实音频直通与 ZX300A 屏幕指示跳变。

### Scope Boundaries
- **In Scope**:
  - `app/src/main/cpp/audio_engine.h` 与 `app/src/main/cpp/audio_engine.cpp` 改造。
  - `app/src/main/cpp/uac_protocol.h` 增加 Feature Unit 描述符与控制常数。
  - `app/src/main/cpp/pcm_formatter.h` 校验与优化。
  - `scripts/test_upnp_push.py` 增强，支持高解析 24-bit 生成与推流。
- **Deferred to Follow-Up Work**:
  - DSD (DoP) 硬件直通支持（后续专项迭代）。
  - 大屏 UI 封面渲染与进度条（阶段二实现）。
  - 权限向导与 UPnP MediaSession（阶段三实现）。

### Success Criteria
- **SC1. 24-bit 高解析流真机验证**: 推送 24-bit/96kHz 音频流，索尼 ZX300A 屏幕显示 `96000 @ 24`（或 `32`），声音纯净无杂音，LLUSBDAC 模块无 overrun/underrun 报错。
- **SC2. 零切频爆音**: 在 44.1kHz 与 96kHz 之间交替切换播放时，DAC 继电器平滑过渡，无咔嗒爆音。
- **SC3. 满幅直通输出**: 音频输出声压达到 0dB Unity Gain，无软件或硬件衰减。

---

## Planning Contract

### Key Technical Decisions
- **KTD1. C++ 动态输入位深计算与分流**:
  - 在 `AudioEngine` 中保存 `current_bit_depth_`（16 或 24）；
  - 单微帧所需输入字节计算：`input_bytes_needed = samples_this_pkt * current_channels_ * (current_bit_depth_ / 8)`；
  - 根据 `current_bit_depth_` 分别调用 `PcmFormatter::format_16bit_to_target` 或 `PcmFormatter::format_24bit_to_target`。
- **KTD2. UAC Feature Unit 控制包标准格式**:
  - 解析 AudioControl 描述符中的 `FEATURE_UNIT` (SubType 0x06)；
  - UAC2: 发送 `bRequest = 0x01 (CS_CUR)`, `wValue = (FU_VOLUME_CONTROL << 8) | channel_number`, `wIndex = (fu_id << 8) | ac_iface`, 数据载荷 2 字节小端序 `0x0000` (0.0 dB)；
  - UAC1: 发送 `bRequest = 0x01 (SET_CUR)`, 数据载荷 2 字节 `0x0000`。
- **KTD3. DAC Warmup 状态机设计**:
  - 引入 `warmup_packets_remaining_` 计数器（例如 400 个 microframes = 50ms）；
  - 当 `warmup_packets_remaining_ > 0` 时，直接 `memset(pkt_buf, 0, target_pkt_bytes)` 并递减计数器，不读取 RingBuffer。
- **KTD4. POSIX 调度与 mlock 容错包装**:
  - 调用 `setpriority(PRIO_PROCESS, 0, -19)`，即使由于 SELinux 限制返回非 0 亦记录 warning 并优雅降级；
  - 对 `ring_buffer_->data()` 及其 URB buffer 执行 `mlock(ptr, size)`。

---

## Implementation Units

### U1. 24-bit 高解析输入动态格式化与对齐直通 (Dynamic 24-bit PCM Demuxing & Packing)
- **Goal**: 解决驱动层硬编码 16-bit 输入的缺陷，根据 `current_bit_depth_` 动态从 RingBuffer 提取 16-bit（2字节）或 24-bit（3字节）PCM，并正确向 `S24_3LE` / `S32_LE` 物理子帧高位对齐。
- **Requirements**: R3-P1
- **Dependencies**: None
- **Files**:
  - `app/src/main/cpp/audio_engine.h`
  - `app/src/main/cpp/audio_engine.cpp`
  - `app/src/main/cpp/pcm_formatter.h`
- **Approach**:
  1. 在 `fill_isoc_transfer` 中计算单包所需输入字节数：`size_t bytes_per_sample = current_bit_depth_ / 8;`，`size_t raw_input_bytes = samples_this_pkt * current_channels_ * bytes_per_sample;`。
  2. 从 `ring_buffer_` 读取 `raw_input_bytes` 数据。
  3. 分流处理：
     - 若 `current_bit_depth_ == 16`，调用 `PcmFormatter::format_16bit_to_target`。
     - 若 `current_bit_depth_ == 24`，调用 `PcmFormatter::format_24bit_to_target`。
  4. 验证在 `S32_LE` 容器下，24-bit 采样向高位移动 8 位（`sample_24 << 8`），低位补 0。
- **Test scenarios**:
  - 单元测试验证 24-bit 采样 `0x12, 0x34, 0x56` 转换为 `S24_3LE` 时原样保留。
  - 单元测试验证 24-bit 采样 `0x12, 0x34, 0x56` 转换为 `S32_LE` 时输出 `0x00, 0x12, 0x34, 0x56`。
  - 真机联调：通过 Python 脚本推送 24-bit/96kHz WAV 流，确认 ZX300A 屏幕指示为 24-bit/96kHz 且声音清晰无失真。
- **Verification**: 真机推流 24-bit/96kHz 音频，Native 日志打印 `24-bit 输入格式化就绪`，音频波形干净无毛刺。

---

### U2. UAC Feature Unit 音量锁定 0.0dB (UAC Hardware Volume Lock)
- **Goal**: 自动解析 USB DAC 的 AudioControl 接口描述符，定位 Feature Unit，发送控制请求将主音量及左右声道音量强制锁定在 0.0dB（Unity Gain 100% 满幅直通）。
- **Requirements**: R3-P2
- **Dependencies**: None
- **Files**:
  - `app/src/main/cpp/uac_protocol.h`
  - `app/src/main/cpp/audio_engine.h`
  - `app/src/main/cpp/audio_engine.cpp`
- **Approach**:
  1. 在 `uac_protocol.h` 中增加 Feature Unit 描述符结构与常数：`CS_INTERFACE (0x24)`, `FEATURE_UNIT (0x06)`, `FU_VOLUME_CONTROL (0x02)`。
  2. 在 `parse_audio_descriptors` 遍历 AudioControl 接口的 `extra` 描述符，捕获 `feature_unit_id`。
  3. 在 `open_device` 时钟配置完成后，调用新函数 `lock_hardware_volume(uint8_t fu_id)`：
     - 循环发送控制请求锁定 Master（Channel 0）、Left（Channel 1）、Right（Channel 2）音量为 `0x0000`（0.0dB）。
     - 支持 UAC2 与 UAC1 协议分支。
- **Test scenarios**:
  - 真机连接索尼 ZX300A，查看 logcat 是否打印 `成功锁定 UAC Feature Unit (id=X) 硬件音量为 0.0dB`。
  - 模拟不支持 Feature Unit 的 USB 声卡，确保调用安全跳过且不返回错误。
- **Verification**: 打开 USB 设备时 logcat 成功输出 Feature Unit 音量配置成功日志，声卡音量处于满幅直通状态。

---

### U3. 采样率切换 50ms 静音预充（DAC Warmup & Relays Protection）
- **Goal**: 在建立推流或采样率切换时，向 USB DAC 持续填充 50ms 全零静音包，保护硬件继电器免受切频瞬间的物理爆音冲击。
- **Requirements**: R3-P3
- **Dependencies**: U1
- **Files**:
  - `app/src/main/cpp/audio_engine.h`
  - `app/src/main/cpp/audio_engine.cpp`
- **Approach**:
  1. 在 `AudioEngine` 中增加 `std::atomic<int> warmup_packets_remaining_{0}`。
  2. 在 `open_device` 成功配置时钟后，根据 `packet_rate_`（例如 8000 packets/s）计算 50ms 所需的 packet 数：`warmup_packets = (packet_rate_ * 50) / 1000`（约 400 个微帧），存入 `warmup_packets_remaining_`。
  3. 在 `fill_isoc_transfer` 中优先判断：
     ```cpp
     if (warmup_packets_remaining_ > 0) {
         warmup_packets_remaining_--;
         std::memset(pkt_buf, 0, target_pkt_bytes);
     }
     ```
  4. 待 Warmup 包发送完毕后，再激活 `has_pre_rolled_` 检查并消费 RingBuffer 音频数据。
- **Test scenarios**:
  - 真机测试：在 44.1kHz 与 96kHz 音频流之间快速交替切换推流，监听耳机或音箱是否存在切换瞬间的爆音或撕裂。
- **Verification**: 切换采样率时耳机内仅有极轻微的硬件继电器吸合声，完全无数字音频毛刺与杂音。

---

### U4. Linux 实时调度等级提升与物理内存锁定 (Real-time Scheduling & mlock)
- **Goal**: 将等时传输线程调度优先级提升至 Linux 最高实时音频等级（`nice -19`），并使用 `mlock()` 锁定内存页，防止 Android 系统内存回收或换页引起微秒级抖动。
- **Requirements**: R3-P4
- **Dependencies**: None
- **Files**:
  - `app/src/main/cpp/audio_engine.cpp`
- **Approach**:
  1. 在 `event_thread_func` 线程函数最开始，引入 `<sys/resource.h>` 与 `<sys/mman.h>`。
  2. 调用 `setpriority(PRIO_PROCESS, 0, -19)`，并将结果输出至 logcat。
  3. 在初始化 `ring_buffer_` 后，调用 `mlock(ring_buffer_->raw_buffer(), ring_buffer_->capacity())` 锁定内存。
  4. 对等时传输分配的 `transfer_buffers_` 也执行 `mlock`；在 `release_device` 时对应执行 `munlock`。
- **Test scenarios**:
  - 查看 logcat 日志，确认 `setpriority` 与 `mlock` 成功执行无崩溃。
  - 在 TV 盒子上运行高 CPU/IO 负载操作（如同时后台解压文件），验证推流依然丝滑无爆音。
- **Verification**: 日志显示调度优先级已提升至 -19，内存锁定成功，高压测试零丢包。

---

### U5. 高解析 24-bit/96kHz 与 24-bit/192kHz 自动化测试套件 (High-Res Automation Test Suite)
- **Goal**: 升级 `scripts/test_upnp_push.py`，支持自动化合成 24-bit/96kHz 与 24-bit/192kHz 正弦波音频，并向电视盒子推送以验证高解析全链路。
- **Requirements**: R3-P5
- **Dependencies**: U1, U3
- **Files**:
  - `scripts/test_upnp_push.py`
- **Approach**:
  1. 增强 `generate_sine_wav` 函数，支持参数 `sample_rate=96000`, `bit_depth=24`。
  2. 针对 24-bit，利用 Python `struct.pack("<i", val)[:3]` 实现标准 3 字节小端序打包。
  3. 支持命令行参数切换：`python3 scripts/test_upnp_push.py 192.168.2.179 7952 5 wav 96000 24`。
  4. 推流后自动化抓取 TV logcat 校验 `CS_SAM_FREQ_CONTROL` 与 24-bit 直通。
- **Test scenarios**:
  - 运行 `python3 scripts/test_upnp_push.py 192.168.2.179 7952 5 wav 96000 24`，验证 ZX300A 显示屏跳变为 96000 Hz / 24 bit。
- **Verification**: 自动化脚本运行通过，ZX300A 屏幕拍照确认显示 96000 Hz。

---

## Verification Contract

### 质量门禁与验证命令
1. **代码编译与 ABI 校验**:
   ```bash
   ./gradlew clean assembleDebug
   ./scripts/build_and_verify_abi.sh
   ```
2. **真机无线安装与部署**:
   ```bash
   ./scripts/adb_remote_install.sh 127.0.0.1 5555
   ```
3. **标准 16-bit/44.1kHz 与高解析 24-bit/96kHz 自动化推流对比测试**:
   ```bash
   python3 scripts/test_upnp_push.py 127.0.0.1 7952 5 wav 44100 16
   python3 scripts/test_upnp_push.py 127.0.0.1 7952 5 wav 96000 24
   ```
4. **日志与硬件状态断言**:
   ```bash
   adb logcat -d | grep PureCastNative | tail -n 30
   ```
   *预期产物*: 日志输出 Feature Unit 锁定 0.0dB、24-bit 格式化就绪、DAC Warmup 50ms 注入完成、显式反馈时钟锁定，ZX300A 屏幕显示 `96000 @ 24`。

---

## Definition of Done

- [ ] 动态支持 16-bit 与 24-bit 输入 PCM 直通，高位严格对齐，低位按需补零。
- [ ] UAC Feature Unit 主音量成功锁定为 0.0dB。
- [ ] 切换采样率时有 50ms 静音预充，消除硬件继电器切频爆音。
- [ ] URB 调度线程获得 `nice -19` 实时优先级，RingBuffer 内存成功执行 `mlock()`。
- [ ] 高解析推流自动化脚本顺利跑通并通过真机断言。
