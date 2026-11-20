#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <mutex>
#include <atomic>

namespace purecast::raop {

/**
 * 💡 JitterPacket: 单帧 RTP 解码后的 PCM 音频数据与时间元数据
 */
struct JitterPacket {
    uint16_t seq_num{0};
    uint32_t timestamp{0};
    std::vector<uint8_t> pcm;
    bool valid{false};
};

/**
 * 💡 RaopJitterBuffer: UDP 抖动重排与丢包静音补偿缓冲区
 * - 乱序重排: 16 位循环序列号回环安全判定 (RFC 3550 标准)
 * - 预充门限 (Pre-Roll): 开流累积 50~100ms 缓冲，杜绝初始微抖动下溢
 * - 丢包补偿: 序列号超差超时自动插入静音帧，严格对齐 44.1kHz 硬件时钟轴
 */
class RaopJitterBuffer {
public:
    static constexpr size_t BUFFER_SIZE = 512; // 512 帧 * 352 采样 / 44100 = 约 4.08s 容量（完整容纳 AirPlay 2s 同步缓冲与抖动余量）
    static constexpr size_t PRE_ROLL_PACKETS = 25; // 25 帧 * 8ms = 约 200ms 预充 (无 Sync 包回退路径防声卡 Underrun)
    static constexpr int16_t MAX_REORDER_TOLERANCE = 8; // 乱序等待上限 8 帧 (~64ms，预留充足局域网 RTT 供 RAOP 0xd5 重传包返回，超差后插补静音帧垫步)
    static constexpr size_t FRAME_BYTES = 352 * 2 * 2; // 352 采样 * 2 声道 * 16-bit (2 字节) = 1408 字节

    RaopJitterBuffer();
    ~RaopJitterBuffer() = default;

    // 禁用拷贝与赋值
    RaopJitterBuffer(const RaopJitterBuffer&) = delete;
    RaopJitterBuffer& operator=(const RaopJitterBuffer&) = delete;

    /**
     * 重置缓冲区状态与序列号游标
     */
    void reset();

    /**
     * 写入已解码的 PCM 帧
     * @param seq RTP 序列号
     * @param timestamp RTP 时间戳
     * @param pcm 解码后的 PCM 数据
     */
    void push(uint16_t seq, uint32_t timestamp, std::vector<uint8_t> pcm);

    /**
     * 按时钟序列出队下一帧 PCM
     * @param out_pcm 输出的 PCM 帧
     * @param out_timestamp 可选输出该帧对应的 RTP 时间戳（用于计算扬声器真实出声位置）
     * @param out_has_timestamp 可选输出该时间戳是否有效（丢包首帧尚未出队过有效包时为 false）
     * @return true 成功获取到音频帧或静音补偿帧；false 处于预充或等待后续包到达
     */
    bool pop(std::vector<uint8_t>& out_pcm, uint32_t* out_timestamp = nullptr, bool* out_has_timestamp = nullptr);

    /**
     * 💡 修复 Finding 5: 探查下一待出队帧的字节尺寸，不弹出数据亦不改变游标与有效状态
     * @param out_size 输出的待消费帧字节大小
     * @return true 存在就绪帧；false 处于预充或正在等待乱序包
     */
    bool peek_size(size_t& out_size);

    /**
     * 💡 探查队首待出队帧的字节尺寸与 RTP 时间戳（供 Sync 精准出声时间门控使用，不弹出数据）
     */
    bool peek_head(size_t& out_size, uint32_t& out_timestamp, bool& out_has_timestamp);

    /**
     * 获取当前 JitterBuffer 中缓存的有效音频包数量
     */
    size_t buffered_packets() const { return buffered_count_.load(std::memory_order_relaxed); }

    /**
     * 当前是否处于预充阶段
     */
    bool is_buffering() const { return is_buffering_.load(std::memory_order_relaxed); }

    /**
     * 获取累计丢包补偿帧数
     */
    uint32_t lost_packets_count() const { return lost_packets_.load(std::memory_order_relaxed); }

private:
    static inline bool seq_less(uint16_t a, uint16_t b) {
        return static_cast<int16_t>(a - b) < 0;
    }

    mutable std::mutex mutex_;
    std::vector<JitterPacket> slots_;

    uint16_t head_seq_{0};
    uint16_t max_seq_seen_{0};
    bool first_packet_{true};
    uint32_t last_popped_timestamp_{0};
    bool has_popped_timestamp_{false};

    std::atomic<bool> is_buffering_{true};
    std::atomic<size_t> buffered_count_{0};
    std::atomic<uint32_t> lost_packets_{0};
    size_t frame_bytes_{FRAME_BYTES};
};

} // namespace purecast::raop
