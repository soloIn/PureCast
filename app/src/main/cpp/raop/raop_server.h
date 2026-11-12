#pragma once

#include <string>
#include <functional>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstdint>

#include "raop_rtp.h"

namespace purecast::raop {

struct RaopStreamParams {
    int sample_rate{44100};
    int bit_depth{16};
    int channels{2};
    std::string codec{"ALAC"};
    std::string aes_key_base64;
    std::string aes_iv_base64;
    std::string client_ip;
    int client_control_port{0};
    int client_timing_port{0};
    std::string dacp_id;
    std::string active_remote_token;
    int dacp_port{0};
    int payload_type{96};
    std::vector<int32_t> fmtp;
};

class RaopServer {
public:
    using StreamRequestCallback = std::function<int64_t(int64_t session_id, int64_t sequence, const RaopStreamParams& params)>;
    using StreamStopCallback = std::function<void(int64_t session_id, int64_t sequence, int64_t epoch)>;
    using MetadataCallback = std::function<void(int64_t session_id, int64_t sequence, const std::string& title, const std::string& artist, const std::string& album, long duration_ms, const std::vector<uint8_t>& cover_art)>;
    using ChallengeCallback = std::function<std::string(const std::string& challenge_b64)>;
    using ProgressCallback = std::function<void(int64_t session_id, int64_t sequence, long position_ms, long duration_ms)>;
    using FlushCallback = std::function<void(int64_t session_id, int64_t sequence, int64_t new_epoch)>;
    using StreamPauseCallback = std::function<void(int64_t session_id, int64_t sequence, int64_t epoch, bool is_implicit)>;
    using StreamResumeCallback = std::function<void(int64_t session_id, int64_t sequence, int64_t epoch)>;
    using FirstDataPacketCallback = std::function<void(int64_t session_id, int64_t sequence, int64_t epoch, int sample_rate, int bit_depth, int channels, const std::string& codec)>;

    RaopServer();
    ~RaopServer();

    bool start(int port = 5000);
    void stop();

    /**
     * 💡 被其他协议抢占或 10 分钟挂起超时时：同步停撤 RaopRtp UDP 线程并关闭当前客户端 RTSP 连接，保留 listening server_fd_
     */
    void preempt_active_client();

    /**
     * 💡 设备因焦点丢失或拔插释放后重新独占恢复时：同步刷新 RaopRtp 活跃 epoch 并解除暂停
     */
    bool resume_after_reopen(uint64_t new_epoch);

    bool is_running() const { return is_running_.load(); }

    void set_stream_request_callback(StreamRequestCallback cb) { stream_request_cb_ = std::move(cb); }
    void set_stream_stop_callback(StreamStopCallback cb) { stream_stop_cb_ = std::move(cb); }
    void set_stream_pause_callback(StreamPauseCallback cb) { stream_pause_cb_ = std::move(cb); }
    void set_stream_resume_callback(StreamResumeCallback cb) { stream_resume_cb_ = std::move(cb); }
    void set_first_data_packet_callback(FirstDataPacketCallback cb) { first_data_packet_cb_ = std::move(cb); }
    void set_metadata_callback(MetadataCallback cb) { metadata_cb_ = std::move(cb); }
    void set_challenge_callback(ChallengeCallback cb) { challenge_cb_ = std::move(cb); }
    void set_progress_callback(ProgressCallback cb) { progress_cb_ = std::move(cb); }
    void set_flush_callback(FlushCallback cb) { flush_cb_ = std::move(cb); }

    void set_decrypted_aes_key(const std::vector<uint8_t>& key, const std::vector<uint8_t>& iv) {
        rtp_.set_crypto_key(key, iv);
    }

    RaopRtp& rtp() { return rtp_; }
    const RaopStreamParams& current_params() const { return current_params_; }

private:
    void server_loop();
    void handle_client(int client_fd);
    void handle_rtsp_request(int client_fd, const std::string& request);

    std::string parse_header(const std::string& request, const std::string& header_name);
    std::string parse_sdp_attribute(const std::string& sdp, const std::string& attr_name);
    int64_t next_seq() {
        return event_sequence_.fetch_add(1, std::memory_order_acq_rel) + 1;
    }

private:
    int server_port_{5000};
    int server_fd_{-1};
    std::mutex client_fd_mutex_;
    std::atomic<int> active_client_fd_{-1};
    std::atomic<bool> is_running_{false};
    std::thread server_thread_;

    RaopRtp rtp_;
    RaopStreamParams current_params_{};
    std::atomic<float> current_volume_db_{0.0f};
    std::atomic<int64_t> active_session_id_{0};
    std::atomic<int64_t> next_session_id_{0};
    std::atomic<int64_t> event_sequence_{0};
    std::atomic<bool> has_paused_without_flush_{false};

    StreamRequestCallback stream_request_cb_{nullptr};
    StreamStopCallback stream_stop_cb_{nullptr};
    StreamPauseCallback stream_pause_cb_{nullptr};
    StreamResumeCallback stream_resume_cb_{nullptr};
    FirstDataPacketCallback first_data_packet_cb_{nullptr};
    MetadataCallback metadata_cb_{nullptr};
    ChallengeCallback challenge_cb_{nullptr};
    ProgressCallback progress_cb_{nullptr};
    FlushCallback flush_cb_{nullptr};
};

} // namespace purecast::raop
