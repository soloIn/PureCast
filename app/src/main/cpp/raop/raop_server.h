#pragma once

#include <string>
#include <functional>
#include <thread>
#include <atomic>
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
    using StreamRequestCallback = std::function<bool(const RaopStreamParams& params)>;
    using StreamStopCallback = std::function<void()>;
    using MetadataCallback = std::function<void(const std::string& title, const std::string& artist, const std::string& album, long duration_ms, const std::vector<uint8_t>& cover_art)>;
    using ChallengeCallback = std::function<std::string(const std::string& challenge_b64)>;
    using ProgressCallback = std::function<void(long position_ms, long duration_ms)>;
    using FlushCallback = std::function<void()>;
    using StreamPauseCallback = std::function<void()>;
    using StreamResumeCallback = std::function<void()>;

    RaopServer();
    ~RaopServer();

    bool start(int port = 5000);
    void stop();

    bool is_running() const { return is_running_.load(); }

    void set_stream_request_callback(StreamRequestCallback cb) { stream_request_cb_ = std::move(cb); }
    void set_stream_stop_callback(StreamStopCallback cb) { stream_stop_cb_ = std::move(cb); }
    void set_stream_pause_callback(StreamPauseCallback cb) { stream_pause_cb_ = std::move(cb); }
    void set_stream_resume_callback(StreamResumeCallback cb) { stream_resume_cb_ = std::move(cb); }
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

private:
    int server_port_{5000};
    int server_fd_{-1};
    std::atomic<bool> is_running_{false};
    std::thread server_thread_;

    RaopRtp rtp_;
    RaopStreamParams current_params_{};

    StreamRequestCallback stream_request_cb_{nullptr};
    StreamStopCallback stream_stop_cb_{nullptr};
    StreamPauseCallback stream_pause_cb_{nullptr};
    StreamResumeCallback stream_resume_cb_{nullptr};
    MetadataCallback metadata_cb_{nullptr};
    ChallengeCallback challenge_cb_{nullptr};
    ProgressCallback progress_cb_{nullptr};
    FlushCallback flush_cb_{nullptr};
};

} // namespace purecast::raop
