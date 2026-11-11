#pragma once

#include <memory>
#include <thread>
#include <atomic>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <string>
#include <cstdint>

#ifdef __ANDROID__
#include <android/log.h>
#define PURECAST_TAG "PureCastNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, PURECAST_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, PURECAST_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, PURECAST_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PURECAST_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define PURECAST_TAG "PureCastNative"
#define LOGD(...) do { } while (0)
#define LOGI(...) do { std::fprintf(stderr, "[I/" PURECAST_TAG "] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGW(...) do { std::fprintf(stderr, "[W/" PURECAST_TAG "] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[E/" PURECAST_TAG "] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

#include "libusb.h"
#include "ring_buffer.h"
#include "pcm_formatter.h"
#include "uac_protocol.h"

namespace purecast {

/**
 * 💡 硬件快路径静音与冲刷原因枚举 (必须与 Kotlin FlushReason 序号严格对齐)
 */
enum class FlushReason : int {
    EXPLICIT_PAUSE = 0,   // 显式暂停：仅静音保活，绝不递增 epoch，绝不清空缓冲
    FLUSH_SWITCH = 1,     // 切歌/Seek：递增 epoch 排空旧代残余并自动 100ms 预充后解除静音
    STOP_OR_TEARDOWN = 2, // 停止或拆除传输：显式静音并递增 epoch 排空缓冲
    PREEMPT = 3           // 跨协议抢占：解除旧暂停、递增 epoch 并在新流 100ms 预充后自动出声
};

class AudioEngine {
public:
    static AudioEngine& instance();

    AudioEngine();
    ~AudioEngine();

    // 禁用拷贝与赋值
    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    std::string get_libusb_version() const;

    int init_context();

    int open_device(int fd, int sample_rate, int bit_depth, int channels);

    /**
     * 💡 原位音频格式三元组重配 (或安全回滚)
     * @return 成功返回新激活的 buffer_epoch (>= 1)，失败返回负错误码
     */
    int64_t reconfigure_audio_format(int sample_rate, int bit_depth, int channels);

    /**
     * 💡 无锁硬件快路径：立即静音保活并按语义控制世代排空
     * 严禁获取 op_mutex_，严禁取消 URB，严禁跨线程调用 ring_buffer_->clear()
     * @return 最新有效的 buffer_epoch (>= 1)
     */
    uint64_t emergency_mute_and_flush(FlushReason reason);

    /**
     * 💡 带世代防串扰与“写先于清”消费追平门闩的 PCM 直通写入
     * @return >0 实际写入字节，0 整包满载丢弃，-1 引擎未运行，-2 世代已过期，-3 消费端追平超时
     */
    int write_pcm(const uint8_t* data, size_t size, uint64_t expected_epoch);

    int resume_stream();

    int release_device();

    [[nodiscard]] uint64_t get_current_buffer_epoch() const {
        return epoch_and_flush_.load(std::memory_order_acquire) >> 1;
    }

    [[nodiscard]] uint64_t get_consumed_buffer_epoch() const {
        return consumed_epoch_.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool is_streaming() const;
    [[nodiscard]] bool is_running() const { return is_running_.load(std::memory_order_acquire); }
    // 💡 R2 契约：is_paused() 仅反映显式暂停，不包含切歌 Flush，确保 FLAC 解码器不会误眠死锁
    [[nodiscard]] bool is_paused() const { return is_explicitly_paused_.load(std::memory_order_acquire); }
    [[nodiscard]] bool is_flushing_until_reroll() const {
        return (epoch_and_flush_.load(std::memory_order_acquire) & 1ULL) != 0ULL;
    }
    [[nodiscard]] bool is_muted() const { return is_paused() || is_flushing_until_reroll(); }
    [[nodiscard]] int get_active_transfers() const { return active_transfers_.load(std::memory_order_acquire); }
    [[nodiscard]] int get_warmup_packets_remaining() const {
        return warmup_packets_remaining_.load(std::memory_order_acquire);
    }

    [[nodiscard]] size_t get_buffer_available() const;

    [[nodiscard]] size_t get_buffer_capacity() const;

    [[nodiscard]] size_t get_available_read() const;

private:
    void event_thread_func();
    static void LIBUSB_CALL isoc_transfer_cb(struct libusb_transfer* transfer);
    void fill_isoc_transfer(struct libusb_transfer* transfer);
    void process_isoc_transfer(struct libusb_transfer* transfer);

    // 💡 UAC 显式时钟反馈 (Explicit Feedback) 回调与处理
    static void LIBUSB_CALL feedback_transfer_cb(struct libusb_transfer* transfer);
    void process_feedback_transfer(struct libusb_transfer* transfer);

    int configure_clock(int sample_rate);
    int lock_hardware_volume(uint8_t fu_id);
    bool parse_audio_descriptors(libusb_device* dev, int target_bit_depth);

    static bool is_valid_audio_format(int sample_rate, int bit_depth, int channels);
    void reset_ring_state_for_epoch(uint64_t target_epoch);
    bool try_unlock_preroll(uint64_t target_epoch, bool* cas_unmuted = nullptr);

    uint64_t advance_epoch_and_set_flushing(bool flushing);
    int stop_transfer_pipeline_locked();
    int start_transfer_pipeline_locked(int sample_rate, int bit_depth, int channels);
    void release_claimed_interfaces_locked();

private:
    libusb_context* ctx_{nullptr};
    libusb_device_handle* dev_handle_{nullptr};
    int current_fd_{-1};

    uac::AudioEndpointInfo endpoint_info_{};
    PcmFormatter::OutputFormat current_output_format_{PcmFormatter::OutputFormat::S16_LE};

    std::unique_ptr<SpscRingBuffer> ring_buffer_{nullptr};

    std::atomic<bool> is_running_{false};
    // 💡 双静音标志隔离：显式暂停 vs 切歌 Flush 预充自动解除
    std::atomic<bool> is_explicitly_paused_{false};
    // 💡 高 63 位为单调递增的 buffer_epoch_ (初始 1)，最低位 bit0 为 is_flushing_until_reroll_，单条 CAS 杜绝竞态覆盖
    std::atomic<uint64_t> epoch_and_flush_{(1ULL << 1) | 0ULL};
    std::atomic<uint64_t> consumed_epoch_{1ULL};
    std::atomic<size_t> pre_roll_threshold_bytes_{0};

    std::atomic<bool> is_claimed_{false};
    std::atomic<bool> is_control_claimed_{false};
    std::atomic<bool> has_pre_rolled_{false};
    // 💡 在途等时传输 URB 计数器 (用于在 release/reconfigure 时安全同步等待所有取消回调完成，杜绝 Use-After-Free)
    std::atomic<int> active_transfers_{0};
    // 💡 DAC Warmup 50ms 静音预充微帧计数器 (仅首次开流/物理重配设置，切歌 Flush 绝不重置)
    std::atomic<int> warmup_packets_remaining_{0};
    // 💡 连续下溢微帧计数器 (用于软抗抖策略判定是否达到 50ms 持续饥饿门限)
    std::atomic<int> consecutive_underruns_{0};

    std::thread event_thread_;

    // 💡 适配 USB DAC (如 ZX300A Explicit Feedback)，配置 8 个并发 URB，每个包含 8 个微帧 (1ms)
    static constexpr int NUM_TRANSFERS = 8;
    static constexpr int PACKETS_PER_TRANSFER = 8;
    std::vector<struct libusb_transfer*> transfers_;
    std::vector<std::vector<uint8_t>> transfer_buffers_;

    // 💡 显式时钟反馈 URB 队列 (Explicit Feedback In Pipeline)
    static constexpr int NUM_FEEDBACK_TRANSFERS = 4;
    std::vector<struct libusb_transfer*> feedback_transfers_;
    std::vector<std::vector<uint8_t>> feedback_buffers_;

    // 💡 UAC2 Explicit Feedback 闭环伺服参数
    std::atomic<uint32_t> feedback_rate_q16_{0}; // Q16.16 格式瞬时微帧采样率
    std::atomic<bool> feedback_locked_{false};   // 是否已成功完成首次硬件时钟锁定
    uint32_t phase_accumulator_{0};              // 16.16 相位累加器 (仅消费线程修改)

    int current_sample_rate_{44100};
    int current_bit_depth_{16};
    int current_channels_{2};
    size_t packet_size_{0};

    // 💡 Bresenham 时钟步进参数 (仅消费线程与 pipeline 初始化边界修改)
    uint32_t bresenham_accum_{0};
    uint32_t base_samples_per_packet_{5};
    uint32_t remainder_samples_{4100};
    uint32_t packet_rate_{8000};

    std::atomic<int> transfer_err_count_{0};
    std::atomic<int> feedback_err_count_{0};
    std::atomic<int> overrun_log_count_{0};

    std::recursive_mutex op_mutex_;
};

} // namespace purecast
