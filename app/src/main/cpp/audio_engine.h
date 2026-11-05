#pragma once

#include <memory>
#include <thread>
#include <atomic>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <string>
#include <android/log.h>

#include "libusb.h"
#include "ring_buffer.h"
#include "pcm_formatter.h"
#include "uac_protocol.h"

#define PURECAST_TAG "PureCastNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, PURECAST_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, PURECAST_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, PURECAST_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PURECAST_TAG, __VA_ARGS__)

namespace purecast {

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

    int write_pcm(const uint8_t* data, size_t size);

    int pause_stream();

    int resume_stream();

    int stop_stream();

    void flush_buffer();

    int release_device();

    [[nodiscard]] bool is_streaming() const;
    [[nodiscard]] bool is_running() const { return is_running_.load(std::memory_order_relaxed); }
    [[nodiscard]] bool is_paused() const { return is_paused_.load(std::memory_order_relaxed); }

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

private:
    libusb_context* ctx_{nullptr};
    libusb_device_handle* dev_handle_{nullptr};
    int current_fd_{-1};

    uac::AudioEndpointInfo endpoint_info_{};
    PcmFormatter::OutputFormat current_output_format_{PcmFormatter::OutputFormat::S16_LE};

    std::unique_ptr<SpscRingBuffer> ring_buffer_{nullptr};

    std::atomic<bool> is_running_{false};
    std::atomic<bool> is_paused_{false};
    std::atomic<bool> is_claimed_{false};
    std::atomic<bool> has_pre_rolled_{false};
    // 💡 在途等时传输 URB 计数器 (用于在 release 时安全同步等待所有取消回调完成，杜绝 Use-After-Free)
    std::atomic<int> active_transfers_{0};
    // 💡 DAC Warmup 50ms 静音预充微帧计数器 (消除切频与初次开流时继电器吸合物理爆音)
    std::atomic<int> warmup_packets_remaining_{0};

    std::thread event_thread_;

    // 💡 适配 USB DAC (如 ZX300A Explicit Feedback)，配置 8 个并发 URB，每个包含 8 个微帧 (1ms)
    // 总硬件队列深度达 8ms，彻底杜绝 Android TV 偶发 2~3ms 线程调度延迟导致的 USB 硬件欠载与 ERROR 累加
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
    uint32_t phase_accumulator_{0};             // 16.16 相位累加器

    int current_sample_rate_{44100};
    int current_bit_depth_{16};
    int current_channels_{2};
    size_t packet_size_{0};

    // 💡 Bresenham 时钟步进参数 (44.1kHz 分数包对齐与锁相)
    uint32_t bresenham_accum_{0};
    uint32_t base_samples_per_packet_{5};
    uint32_t remainder_samples_{4100};
    uint32_t packet_rate_{8000};

    std::recursive_mutex op_mutex_;
};

} // namespace purecast
