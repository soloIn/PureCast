#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>
#include <thread>
#include <atomic>
#include <functional>
#include <netinet/in.h>

#include "raop_jitter_buffer.h"
#include "../alac/alac_decoder.h"

namespace purecast::raop {

/**
 * 💡 RAOP 音频编码格式类型
 */
enum class AudioCodec {
    ALAC,   // Apple Lossless (由 alac_decoder_ 实时解压为 S16_LE PCM)
    PCM     // L16 未压缩 16-bit 线性 PCM (网络大端字节序，需转换为本地 S16_LE)
};

/**
 * 💡 RaopRtp: 原生 AirPlay 1 (RAOP) UDP 接收与流处理引擎
 * 负责音频数据包解密、ALAC 解码、JitterBuffer 重排、控制包与 NTP 授时对齐。
 */
class RaopRtp {
public:
    RaopRtp();
    ~RaopRtp();

    // 禁用拷贝与赋值
    RaopRtp(const RaopRtp&) = delete;
    RaopRtp& operator=(const RaopRtp&) = delete;

    /**
     * 启动 UDP 接收服务并获取分配的端口
     * @param out_data_port 输出数据接收端口
     * @param out_ctrl_port 输出控制端口
     * @param out_timing_port 输出授时端口
     * @return true 启动成功，false 端口绑定失败
     */
    bool start(int& out_data_port, int& out_ctrl_port, int& out_timing_port);

    /**
     * 停止 UDP 接收并释放 socket 资源
     */
    void stop();

    /**
     * 配置会话解密密钥与初始化向量
     */
    void set_crypto_key(const std::vector<uint8_t>& aes_key, const std::vector<uint8_t>& aes_iv);

    /**
     * 配置 ALAC 解码器参数
     */
    void set_alac_config(int frame_length, int bit_depth, int channels, int sample_rate);

    /**
     * 💡 传入 RTSP ANNOUNCE 中的 a=fmtp 参数初始化 ALAC 解码器
     */
    void init_alac(const int32_t* fmtp) {
        alac_decoder_.init(fmtp);
    }

    /**
     * 💡 配置推流音频格式类型 (ALAC 或 PCM)
     */
    void set_audio_codec(AudioCodec codec) {
        audio_codec_.store(codec, std::memory_order_release);
    }

    AudioCodec get_audio_codec() const {
        return audio_codec_.load(std::memory_order_acquire);
    }

    /**
     * 💡 配置协商的 RTP 音频 Payload Type (默认 96，亦可为 10、94 等)
     */
    void set_audio_payload_type(uint8_t pt) {
        audio_payload_type_.store(pt, std::memory_order_release);
    }

    uint8_t get_audio_payload_type() const {
        return audio_payload_type_.load(std::memory_order_acquire);
    }

    /**
     * 是否正在运行
     */
    bool is_running() const { return is_running_.load(std::memory_order_relaxed); }

    /**
     * 重置 Jitter 缓冲状态
     */
    void reset_buffer() { jitter_buffer_.reset(); }

    /**
     * 💡 设置客户端控制端口端点，用于发送 RAOP 丢包重传请求 (0xd5)
     */
    void set_client_control_endpoint(const std::string& client_ip, int client_ctrl_port);

    /**
     * 💡 向客户端发送 RAOP 标准 8 字节丢包重传请求
     */
    void send_resend_request(uint16_t first_seq, uint16_t count);

    /**
     * 💡 设置音频数据流活动状态变更回调 (is_paused: 暂停/恢复, is_implicit: 静默/断流隐式暂停触发, epoch: 当前世代号)
     */
    using StreamStateCallback = std::function<void(bool is_paused, bool is_implicit, uint64_t epoch)>;
    void set_stream_state_callback(StreamStateCallback cb) {
        std::lock_guard<std::mutex> lock(state_mutex_);
        state_cb_ = std::move(cb);
    }

    /**
     * 💡 新流/新世代首包有效解码 PCM 写入回调，用于驱动协议无关的 STREAMING / PLAYING 迁移
     */
    using FirstDataPacketCallback = std::function<void(uint64_t epoch, int sample_rate, int bit_depth, int channels, const std::string& codec)>;
    void set_first_data_packet_callback(FirstDataPacketCallback cb) {
        std::lock_guard<std::mutex> lock(state_mutex_);
        first_packet_cb_ = std::move(cb);
    }

    /**
     * 💡 绑定当前推流写入所校验的 AudioEngine buffer_epoch
     */
    void set_active_epoch(uint64_t epoch) {
        active_epoch_.store(epoch, std::memory_order_release);
    }

    uint64_t get_active_epoch() const {
        return active_epoch_.load(std::memory_order_acquire);
    }

    /**
     * 💡 配置当前流格式元信息供首包回调上报
     */
    void set_format_info(int sample_rate, int bit_depth, int channels, const std::string& codec) {
        std::lock_guard<std::mutex> lock(format_mutex_);
        format_sample_rate_ = sample_rate;
        format_bit_depth_ = bit_depth;
        format_channels_ = channels;
        format_codec_ = codec;
    }

    /**
     * 💡 手动标记暂停或恢复状态 (由 RTSP PAUSE / RECORD 触发)
     * @param emit_callback true 时通知上层同步业务态 (PAUSE 显式暂停防抖 / PAUSE 后无 FLUSH 恢复)，false 用于 SETUP/FLUSH 后的初始 RECORD 启动阶段
     */
    void mark_paused_manually(bool paused, bool emit_callback = true);

    /**
     * 💡 设置实时音量分贝 (单位 dB, -144.0 为静音, 0.0 为最大)
     */
    void set_volume(float vol_db) {
        current_volume_db_.store(vol_db, std::memory_order_relaxed);
    }
    float get_volume() const {
        return current_volume_db_.load(std::memory_order_relaxed);
    }

    /**
     * 💡 状态只读探测接口 (供协议测试与状态审计使用)
     */
    bool is_paused() const {
        return is_manually_paused_.load(std::memory_order_acquire) ||
               is_implicitly_paused_.load(std::memory_order_acquire);
    }
    bool is_stream_paused() const {
        return is_paused();
    }
    bool is_manually_paused() const {
        return is_manually_paused_.load(std::memory_order_acquire);
    }
    bool is_implicitly_paused() const {
        return is_implicitly_paused_.load(std::memory_order_acquire);
    }
    bool has_crypto() const {
        return has_crypto_.load(std::memory_order_acquire);
    }
    size_t buffered_jitter_packets() const {
        return jitter_buffer_.buffered_packets();
    }

    /**
     * 💡 尝试排空 JitterBuffer 中堆积的就绪音频帧至 AudioEngine (防突发推流填满后暂停导致的卡滞欠载)
     */
    void drain_jitter_buffer();

    /**
     * 💡 更新当前曲目播放进度与总时长
     */
    void update_track_progress(int64_t position_ms, int64_t duration_ms) {
        if (duration_ms > 0) {
            track_duration_ms_.store(duration_ms, std::memory_order_release);
            if (position_ms >= 0) {
                track_position_ms_.store(position_ms, std::memory_order_release);
            }
        } else if (duration_ms < 0) {
            track_duration_ms_.store(0, std::memory_order_release);
            track_position_ms_.store(0, std::memory_order_release);
        }
    }

    int64_t get_track_duration_ms() const {
        return track_duration_ms_.load(std::memory_order_acquire);
    }

    int64_t get_track_position_ms() const {
        return track_position_ms_.load(std::memory_order_acquire);
    }

private:
    void data_loop();
    void ctrl_loop();
    void timing_loop();
    void watchdog_loop();
    void drain_jitter_buffer_locked();
    void emit_state_callback(bool is_paused, bool is_implicit, uint64_t epoch);
    void process_audio_packet(const uint8_t* data, ssize_t len, uint8_t payload_type);

    int bind_udp_socket(int preferred_port, int& bound_port);
    static uint64_t get_ntp_timestamp();
    static uint64_t get_monotonic_time_ms();

private:
    std::atomic<bool> is_running_{false};

    int data_fd_{-1};
    int ctrl_fd_{-1};
    int timing_fd_{-1};

    int data_port_{0};
    int ctrl_port_{0};
    int timing_port_{0};

    std::thread data_thread_;
    std::thread ctrl_thread_;
    std::thread timing_thread_;
    std::thread watchdog_thread_;

    std::mutex crypto_mutex_;
    std::vector<uint8_t> aes_key_;
    std::vector<uint8_t> aes_iv_;
    std::atomic<bool> has_crypto_{false};

    std::atomic<AudioCodec> audio_codec_{AudioCodec::ALAC};
    std::atomic<uint8_t> audio_payload_type_{96};
    alac::AlacDecoder alac_decoder_;
    RaopJitterBuffer jitter_buffer_;
    std::mutex audio_packet_mutex_;
    // 💡 修复 Finding 13: 预分配解码 PCM 缓冲区，杜绝热路径每音频包 16KB 堆分配
    std::vector<uint8_t> decode_pcm_buf_;

    // 💡 客户端控制端点与重传状态维护
    mutable std::mutex ctrl_addr_mutex_;
    struct sockaddr_in client_ctrl_addr_{};
    std::atomic<bool> has_client_ctrl_{false};
    std::atomic<uint16_t> resend_req_seq_{0};
    uint16_t last_data_seq_{0};
    bool first_data_seq_{true};

    // 💡 音频推流状态与首包状态监测 (单静音保活防爆音，控制面由显式信令 100% 驱动)
    std::mutex state_mutex_;
    StreamStateCallback state_cb_{nullptr};
    FirstDataPacketCallback first_packet_cb_{nullptr};
    std::mutex format_mutex_;
    int format_sample_rate_{44100};
    int format_bit_depth_{16};
    int format_channels_{2};
    std::string format_codec_{"ALAC"};
    std::atomic<uint64_t> active_epoch_{1ULL};
    std::atomic<uint64_t> emitted_first_epoch_{0ULL};
    std::atomic<uint64_t> last_audio_packet_time_ms_{0};
    std::atomic<bool> is_manually_paused_{false};
    // 💡 修复 Finding 2: 解耦隐式无数据饥饿暂停与显式手动暂停
    std::atomic<bool> is_implicitly_paused_{false};
    std::atomic<float> current_volume_db_{0.0f};
    std::atomic<int64_t> track_duration_ms_{0};
    std::atomic<int64_t> track_position_ms_{0};
};

} // namespace purecast::raop
