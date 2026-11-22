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
     * 💡 向发射端 RTSP RECORD 响应申报的硬件音频延迟 (11025 采样 = 250.0ms @ 44.1kHz)。
     * iPhone 会将 Sync.rtp_now 相对真实扬声器出声时刻 (progress.curr_ts) 提前该采样数下发，
     * 因此 Sync 出声门控必须在计算目标扬声器时间戳时将其精确扣除。
     */
    static constexpr uint32_t REPORTED_AUDIO_LATENCY_SAMPLES = 11025U;

    /**
     * 重置 Jitter 缓冲状态、已写入 RTP 时间戳游标与起播门控态
     * （保留会话级连续 Sync 时钟锚点与已收敛的 NTP 时钟偏移，仅在 stop() 中清零）
     */
    void reset_buffer();

    /**
     * 💡 原子执行 RTSP FLUSH 切歌/寻道排空：在 audio_packet_mutex_ 锁内一次性完成
     * AudioEngine emergency_mute_and_flush、active_epoch 更新、JitterBuffer 重置与静音门控开启，
     * 彻底消除 FLUSH 期间 sync_drain_thread 抢跑补写快晶振静音垫步或首包错拿旧 epoch 的竞态窗口。
     */
    uint64_t on_rtsp_flush();

    /**
     * 获取最新已写入 AudioEngine 环形缓冲的音频帧尾部 RTP 时间戳
     */
    bool get_last_written_rtp_ts(uint32_t& out_ts) const {
        if (!has_written_rtp_ts_.load(std::memory_order_acquire)) {
            return false;
        }
        out_ts = last_written_rtp_ts_.load(std::memory_order_acquire);
        return true;
    }

    /**
     * 💡 设置客户端控制端口端点，用于发送 RAOP 丢包重传请求 (0xd5)
     */
    void set_client_control_endpoint(const std::string& client_ip, int client_ctrl_port);

    /**
     * 💡 设置客户端 NTP Timing 授时端口端点，用于主动对时 (PT=82/83) 校准本地单调时钟与发射端 NTP 偏移
     */
    void set_client_timing_endpoint(const std::string& client_ip, int client_timing_port);

    /**
     * 💡 查询基于 NTP Timing + Sync (PT=84) 推算出的当前扬声器应出声目标 RTP 时间戳
     */
    bool get_target_speaker_rtp_ts(uint32_t& out_ts) const;

    /**
     * 💡 是否已通过 NTP Timing 锁定发射端时钟偏移（供诊断与单元测试使用）
     */
    bool is_ntp_clock_locked() const {
        return has_ntp_offset_.load(std::memory_order_acquire);
    }

    /**
     * 💡 向客户端发送 RAOP 标准 8 字节丢包重传请求
     */
    void send_resend_request(uint16_t first_seq, uint16_t count);

    /**
     * 💡 设置音频数据流显式信令状态变更回调 (is_paused: RTSP PAUSE 暂停 / RECORD 恢复, epoch: 当前世代号)
     */
    using StreamStateCallback = std::function<void(bool is_paused, uint64_t epoch)>;
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
        return is_manually_paused_.load(std::memory_order_acquire);
    }
    bool is_stream_paused() const {
        return is_paused();
    }
    bool is_manually_paused() const {
        return is_manually_paused_.load(std::memory_order_acquire);
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
    struct NtpTimingSample {
        int64_t rtt_us{0};
        int64_t offset_us{0}; // sender_ntp_us - local_mono_us
    };

    void data_loop();
    void ctrl_loop();
    void timing_loop();
    void sync_drain_loop();
    void drain_jitter_buffer_locked();
    void emit_state_callback(bool is_paused, uint64_t epoch);
    void process_audio_packet(const uint8_t* data, ssize_t len, uint8_t payload_type);
    void handle_sync_packet(const uint8_t* data, ssize_t len);
    void send_timing_request();
    void handle_timing_reply(const uint8_t* data, ssize_t len, int64_t t4_mono_us);

    int bind_udp_socket(int preferred_port, int& bound_port);
    static uint64_t get_ntp_timestamp();
    static int64_t get_monotonic_time_us();
    static uint64_t mono_us_to_ntp(int64_t us);
    static int64_t ntp_to_us(uint64_t ntp);
    static bool is_pcm_frame_silent(const uint8_t* data, size_t size, int bit_depth);

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
    std::thread sync_drain_thread_;

    std::mutex crypto_mutex_;
    std::vector<uint8_t> aes_key_;
    std::vector<uint8_t> aes_iv_;
    std::atomic<bool> has_crypto_{false};

    std::atomic<AudioCodec> audio_codec_{AudioCodec::ALAC};
    std::atomic<uint8_t> audio_payload_type_{96};
    alac::AlacDecoder alac_decoder_;
    RaopJitterBuffer jitter_buffer_;
    std::mutex audio_packet_mutex_;
    // 💡 修复 Finding 13: 预分配解码 PCM 缓冲区与快晶振静音垫步缓冲区，杜绝音频热路径堆分配
    std::vector<uint8_t> decode_pcm_buf_;
    std::vector<uint8_t> pad_silence_buf_;

    // 💡 客户端控制端点与重传状态维护
    mutable std::mutex ctrl_addr_mutex_;
    struct sockaddr_in client_ctrl_addr_{};
    std::atomic<bool> has_client_ctrl_{false};
    std::atomic<uint16_t> resend_req_seq_{0};
    uint16_t last_data_seq_{0};
    bool first_data_seq_{true};

    // 💡 客户端 NTP Timing 授时端点与最小 RTT 时钟偏移滤波器 (PT=82/83)
    mutable std::mutex timing_addr_mutex_;
    struct sockaddr_in client_timing_addr_{};
    std::atomic<bool> has_client_timing_{false};
    mutable std::mutex ntp_mutex_;
    static constexpr size_t NTP_FILTER_WINDOW = 8;
    NtpTimingSample ntp_samples_[NTP_FILTER_WINDOW]{};
    size_t ntp_sample_count_{0};
    size_t ntp_sample_cursor_{0};
    int64_t ntp_offset_us_{0}; // sender_ntp_us - local_mono_us
    std::atomic<bool> has_ntp_offset_{false};

    // 💡 RAOP Sync 控制包 (PT=84) 精准出声同步锚点
    mutable std::mutex sync_mutex_;
    uint32_t sync_rtp_ts_{0};
    int64_t sync_local_mono_us_{0};
    bool has_sync_anchor_{false};
    bool sync_playback_started_{false}; // 仅在 audio_packet_mutex_ 下访问

    // 💡 RTSP FLUSH 后静音拦截门控（仅在 audio_packet_mutex_ 下访问）：
    // iOS 暂停时发 FLUSH 并持续推 44B 全 0 静音包，拦截静音包不入队/不触发起播，
    // 连续静音达 ~240ms (30 帧) 即主动通知上层切 PAUSED，直到收到真实有声帧再恢复播放。
    static constexpr uint32_t POST_FLUSH_SILENCE_PAUSE_FRAMES = 30U;
    bool awaiting_post_flush_audio_{false};
    bool post_flush_silence_paused_{false};
    uint32_t post_flush_silence_frames_{0};

    // 💡 音频推流状态与首包状态监测 (单静音保活防爆音，控制面由显式信令 100% 驱动)
    std::mutex state_mutex_;
    StreamStateCallback state_cb_{nullptr};
    FirstDataPacketCallback first_packet_cb_{nullptr};
    mutable std::mutex format_mutex_;
    int format_sample_rate_{44100};
    int format_bit_depth_{16};
    int format_channels_{2};
    std::string format_codec_{"ALAC"};
    std::atomic<uint64_t> active_epoch_{1ULL};
    std::atomic<uint64_t> emitted_first_epoch_{0ULL};
    std::atomic<bool> is_manually_paused_{false};
    std::atomic<float> current_volume_db_{0.0f};
    std::atomic<int64_t> track_duration_ms_{0};
    std::atomic<int64_t> track_position_ms_{0};
    std::atomic<uint32_t> last_written_rtp_ts_{0};
    std::atomic<bool> has_written_rtp_ts_{false};
};

} // namespace purecast::raop
