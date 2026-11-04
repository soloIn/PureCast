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
    static constexpr size_t BUFFER_SIZE = 128; // 128 帧 * 352 采样 / 44100 = 约 1021ms 容量
    static constexpr size_t PRE_ROLL_PACKETS = 10; // 10 帧 * 8ms = 约 80ms 预充
    static constexpr int16_t MAX_REORDER_TOLERANCE = 16; // 乱序等待上限 16 帧 (~128ms)
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
     * @return true 成功获取到音频帧或静音补偿帧；false 处于预充或等待后续包到达
     */
    bool pop(std::vector<uint8_t>& out_pcm);

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

private:
    mutable std::mutex mutex_;
    std::vector<JitterPacket> slots_;

    uint16_t head_seq_{0};
    uint16_t max_seq_seen_{0};
    bool first_packet_{true};

    std::atomic<bool> is_buffering_{true};
    std::atomic<uint32_t> lost_packets_{0};
};

} // namespace purecast::raop
