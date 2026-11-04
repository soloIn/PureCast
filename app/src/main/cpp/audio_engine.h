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

    int release_device();

    [[nodiscard]] bool is_streaming() const;

    [[nodiscard]] size_t get_buffer_available() const;

    [[nodiscard]] size_t get_buffer_capacity() const;

    [[nodiscard]] size_t get_available_read() const;

private:
    void event_thread_func();
    static void LIBUSB_CALL isoc_transfer_cb(struct libusb_transfer* transfer);
    void process_isoc_transfer(struct libusb_transfer* transfer);

    int configure_clock(int sample_rate);
    bool parse_audio_descriptors(libusb_device* dev);

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

    std::thread event_thread_;

    static constexpr int NUM_TRANSFERS = 8;
    static constexpr int PACKETS_PER_TRANSFER = 8;
    std::vector<struct libusb_transfer*> transfers_;
    std::vector<std::vector<uint8_t>> transfer_buffers_;

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
