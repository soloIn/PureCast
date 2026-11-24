#include "raop_rtp.h"
#include "../crypto/aes.h"
#include "../audio_engine.h"

#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <arpa/inet.h>
#include <cstring>
#include <chrono>

#undef LOGD
#undef LOGI
#undef LOGW
#undef LOGE

#define RTP_TAG "PureCastRaopRtp"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, RTP_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, RTP_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, RTP_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RTP_TAG, __VA_ARGS__)

namespace purecast::raop {

RaopRtp::RaopRtp() {
    alac_decoder_.init(352, 16, 2, 44100);
    // 💡 预分配 16KB 解码缓冲区，消除每包热路径堆内存开销
    decode_pcm_buf_.resize(16384);
}

RaopRtp::~RaopRtp() {
    stop();
}

int RaopRtp::bind_udp_socket(int preferred_port, int& bound_port) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        LOGE("创建 UDP Socket 失败: %s", strerror(errno));
        return -1;
    }

    // 设置接收缓冲区为 1MB 避免高负载网络丢包
    int rcvbuf = 1024 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    // 💡 为无连接 UDP Socket 设置 50ms 接收超时，确保 stop()/抢占时在 macOS/Linux 上均可确定性唤醒 recvfrom 退出，
    // 防止 shutdown(SOCK_DGRAM) 返回 ENOTCONN 导致 join() 死锁。
    struct timeval rcv_tv{};
    rcv_tv.tv_sec = 0;
    rcv_tv.tv_usec = 50000;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rcv_tv, sizeof(rcv_tv));

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;

    int port = preferred_port;
    bool bound = false;
    for (int attempt = 0; attempt < 50; ++attempt) {
        addr.sin_port = htons(port);
        if (bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0) {
            bound = true;
            break;
        }
        port++;
    }

    if (!bound) {
        // 若指定端口范围均被占用，让内核自动分配
        addr.sin_port = 0;
        if (bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
            LOGE("内核自动绑定 UDP 端口失败: %s", strerror(errno));
            close(fd);
            return -1;
        }
    }

    socklen_t len = sizeof(addr);
    if (getsockname(fd, reinterpret_cast<struct sockaddr*>(&addr), &len) == 0) {
        bound_port = ntohs(addr.sin_port);
    } else {
        bound_port = port;
    }

    return fd;
}

bool RaopRtp::start(int& out_data_port, int& out_ctrl_port, int& out_timing_port) {
    if (is_running_.load(std::memory_order_relaxed)) {
        out_data_port = data_port_;
        out_ctrl_port = ctrl_port_;
        out_timing_port = timing_port_;
        return true;
    }

    data_fd_ = bind_udp_socket(7010, data_port_);
    ctrl_fd_ = bind_udp_socket(7011, ctrl_port_);
    timing_fd_ = bind_udp_socket(7012, timing_port_);

    if (data_fd_ < 0 || ctrl_fd_ < 0 || timing_fd_ < 0) {
        LOGE("初始化 RAOP UDP 端口组失败 (data=%d, ctrl=%d, timing=%d)", data_fd_, ctrl_fd_, timing_fd_);
        stop();
        return false;
    }

    out_data_port = data_port_;
    out_ctrl_port = ctrl_port_;
    out_timing_port = timing_port_;

    is_running_.store(true, std::memory_order_release);
    jitter_buffer_.reset();

    data_thread_ = std::thread(&RaopRtp::data_loop, this);
    ctrl_thread_ = std::thread(&RaopRtp::ctrl_loop, this);
    timing_thread_ = std::thread(&RaopRtp::timing_loop, this);
    sync_drain_thread_ = std::thread(&RaopRtp::sync_drain_loop, this);

    LOGI("RAOP RTP 服务启动成功: Data=%d, Control=%d, Timing=%d", data_port_, ctrl_port_, timing_port_);
    return true;
}

void RaopRtp::stop() {
    if (!is_running_.exchange(false)) {
        return;
    }

    LOGI("正在停止 RAOP RTP 接收引擎...");

    // 💡 步骤 1: 仅调用 shutdown 唤醒阻塞在 recvfrom 上的接收线程，切勿立即 close(fd) 产生竞态
    if (data_fd_ >= 0) shutdown(data_fd_, SHUT_RDWR);
    if (ctrl_fd_ >= 0) shutdown(ctrl_fd_, SHUT_RDWR);
    if (timing_fd_ >= 0) shutdown(timing_fd_, SHUT_RDWR);

    // 💡 步骤 2: 等待所有后台接收线程彻底退出并完成系统调用
    if (data_thread_.joinable()) data_thread_.join();
    if (ctrl_thread_.joinable()) ctrl_thread_.join();
    if (timing_thread_.joinable()) timing_thread_.join();
    if (sync_drain_thread_.joinable()) sync_drain_thread_.join();

    // 💡 步骤 3: 线程完全终止后，再统一执行 close 释放文件描述符，彻底杜绝并发竞态
    if (data_fd_ >= 0) {
        close(data_fd_);
        data_fd_ = -1;
    }
    if (ctrl_fd_ >= 0) {
        close(ctrl_fd_);
        ctrl_fd_ = -1;
    }
    if (timing_fd_ >= 0) {
        close(timing_fd_);
        timing_fd_ = -1;
    }

    jitter_buffer_.reset();
    first_data_seq_ = true;
    last_data_seq_ = 0;
    has_client_ctrl_.store(false, std::memory_order_release);
    has_client_timing_.store(false, std::memory_order_release);
    has_ntp_offset_.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(ntp_mutex_);
        ntp_sample_count_ = 0;
        ntp_sample_cursor_ = 0;
        ntp_offset_us_ = 0;
    }
    {
        std::lock_guard<std::mutex> lock(sync_mutex_);
        sync_rtp_ts_ = 0;
        sync_local_mono_us_ = 0;
        sync_phase_offset_samples_ = 0;
        has_sync_anchor_ = false;
    }
    late_sync_handover_pending_.store(false, std::memory_order_release);
    drift_drop_count_.store(0U, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(audio_packet_mutex_);
        sync_playback_started_ = false;
        last_drift_drop_mono_us_ = 0;
        awaiting_post_flush_audio_ = false;
        post_flush_silence_paused_ = false;
        post_flush_silence_frames_ = 0;
    }
    invalidate_track_rtp_anchor();
    resend_req_seq_.store(0, std::memory_order_relaxed);
    is_manually_paused_.store(false, std::memory_order_relaxed);
    emitted_first_epoch_.store(0ULL, std::memory_order_release);
    track_duration_ms_.store(0, std::memory_order_release);
    track_position_ms_.store(0, std::memory_order_release);
    last_written_rtp_ts_.store(0, std::memory_order_release);
    has_written_rtp_ts_.store(false, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(crypto_mutex_);
        aes_key_.clear();
        aes_iv_.clear();
        has_crypto_.store(false, std::memory_order_release);
    }
    LOGI("RAOP RTP 接收引擎已完全停止");
}

void RaopRtp::reset_buffer() {
    // 💡 统一在 audio_packet_mutex_ 锁内原子重置 JitterBuffer、已写时间戳游标与起播/静音门控态，
    // 与 process_audio_packet / drain_jitter_buffer_locked 保持完全一致的锁序 (audio_packet_mutex_ -> jitter_buffer_.mutex_)。
    // AirPlay 1 的 Sync 时钟映射 (rtp_now <-> ntp_time) 是会话级连续时钟：
    // FLUSH / RECORD 绝不能擦除 has_sync_anchor_（仅在 stop() 清零，超 15 秒未更新由 get_target_speaker_rtp_ts() 自动失效）。
    std::lock_guard<std::mutex> lock(audio_packet_mutex_);
    jitter_buffer_.reset();
    has_written_rtp_ts_.store(false, std::memory_order_release);
    sync_playback_started_ = false;
    last_drift_drop_mono_us_ = 0;
    awaiting_post_flush_audio_ = false;
    post_flush_silence_paused_ = false;
    post_flush_silence_frames_ = 0;
    {
        std::lock_guard<std::mutex> slock(sync_mutex_);
        sync_phase_offset_samples_ = 0;
    }
    late_sync_handover_pending_.store(false, std::memory_order_release);
    invalidate_track_rtp_anchor();
}

uint64_t RaopRtp::on_rtsp_flush() {
    std::lock_guard<std::mutex> lock(audio_packet_mutex_);
    const uint64_t new_epoch = AudioEngine::instance().emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
    active_epoch_.store(new_epoch, std::memory_order_release);
    jitter_buffer_.reset();
    has_written_rtp_ts_.store(false, std::memory_order_release);
    sync_playback_started_ = false;
    last_drift_drop_mono_us_ = 0;
    awaiting_post_flush_audio_ = true;
    post_flush_silence_paused_ = false;
    post_flush_silence_frames_ = 0;
    {
        std::lock_guard<std::mutex> slock(sync_mutex_);
        sync_phase_offset_samples_ = 0;
    }
    late_sync_handover_pending_.store(false, std::memory_order_release);
    invalidate_track_rtp_anchor();
    return new_epoch;
}

bool RaopRtp::is_pcm_frame_silent(const uint8_t* data, size_t size, int bit_depth) {
    if (data == nullptr || size == 0) return true;
    if (bit_depth == 24) {
        for (size_t i = 0; i + 2 < size; i += 3) {
            const uint32_t raw = static_cast<uint32_t>(data[i]) |
                                 (static_cast<uint32_t>(data[i + 1]) << 8) |
                                 (static_cast<uint32_t>(data[i + 2]) << 16);
            const int32_t sample = (raw & 0x800000U)
                ? static_cast<int32_t>(raw | 0xFF000000U)
                : static_cast<int32_t>(raw);
            if (sample > 1 || sample < -1) return false;
        }
        return true;
    }
    for (size_t i = 0; i + 1 < size; i += 2) {
        int16_t sample = static_cast<int16_t>(
            static_cast<uint16_t>(data[i]) | (static_cast<uint16_t>(data[i + 1]) << 8)
        );
        if (sample > 1 || sample < -1) return false;
    }
    return true;
}

void RaopRtp::set_client_control_endpoint(const std::string& client_ip, int client_ctrl_port) {
    if (client_ip.empty() || client_ctrl_port <= 0) return;
    struct sockaddr_in addr{};
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(client_ctrl_port);
    if (inet_pton(AF_INET, client_ip.c_str(), &addr.sin_addr) > 0) {
        {
            std::lock_guard<std::mutex> lock(ctrl_addr_mutex_);
            client_ctrl_addr_ = addr;
        }
        has_client_ctrl_.store(true, std::memory_order_release);
        LOGI("显式配置客户端控制端点: %s:%d (用于 RAOP 丢包重传)", client_ip.c_str(), client_ctrl_port);
    }
}

void RaopRtp::set_client_timing_endpoint(const std::string& client_ip, int client_timing_port) {
    if (client_ip.empty() || client_timing_port <= 0) return;
    struct sockaddr_in addr{};
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(client_timing_port);
    if (inet_pton(AF_INET, client_ip.c_str(), &addr.sin_addr) > 0) {
        {
            std::lock_guard<std::mutex> lock(timing_addr_mutex_);
            client_timing_addr_ = addr;
        }
        has_client_timing_.store(true, std::memory_order_release);
        LOGI("显式配置客户端 NTP Timing 授时端点: %s:%d", client_ip.c_str(), client_timing_port);
    }
}

bool RaopRtp::get_target_speaker_rtp_ts(uint32_t& out_ts) const {
    uint32_t anchor_rtp = 0;
    int64_t anchor_mono_us = 0;
    int32_t phase_offset = 0;
    {
        std::lock_guard<std::mutex> lock(sync_mutex_);
        if (!has_sync_anchor_) {
            return false;
        }
        anchor_rtp = sync_rtp_ts_;
        anchor_mono_us = sync_local_mono_us_;
        phase_offset = sync_phase_offset_samples_;
    }

    const int64_t now_us = get_monotonic_time_us();
    const int64_t elapsed_us = now_us - anchor_mono_us;
    // 若 Sync 锚点超过 15 秒未更新或时间戳严重倒置，视为失效并回退非门控模式
    if (elapsed_us < -500000LL || elapsed_us > 15000000LL) {
        return false;
    }

    int sr = 44100;
    {
        std::lock_guard<std::mutex> flock(format_mutex_);
        if (format_sample_rate_ > 0) sr = format_sample_rate_;
    }
    const int64_t elapsed_samples = (elapsed_us * static_cast<int64_t>(sr)) / 1000000LL;
    // 💡 精确扣除 RECORD 响应申报的 Audio-Latency (11025 采样 = 250ms)，并叠加迟到 Sync 的会话相位基线偏移
    out_ts = static_cast<uint32_t>(
        static_cast<int64_t>(anchor_rtp) + elapsed_samples -
        static_cast<int64_t>(REPORTED_AUDIO_LATENCY_SAMPLES) +
        static_cast<int64_t>(phase_offset)
    );
    return true;
}

bool RaopRtp::resolve_speaker_rtp_ts(uint32_t& out_ts, bool& is_live_audio) const {
    if (has_written_rtp_ts_.load(std::memory_order_acquire)) {
        const uint32_t written_ts = last_written_rtp_ts_.load(std::memory_order_acquire);
        int ch = 2;
        int bits = 16;
        {
            std::lock_guard<std::mutex> flock(format_mutex_);
            if (format_channels_ > 0) ch = format_channels_;
            if (format_bit_depth_ > 0) bits = format_bit_depth_;
        }
        const uint32_t bps = static_cast<uint32_t>(bits / 8);
        const uint32_t bytes_per_frame = static_cast<uint32_t>(ch) * (bps > 0 ? bps : 2U);
        const size_t ring_bytes = AudioEngine::instance().get_available_read();
        const uint32_t ring_unplayed_samples = (bytes_per_frame > 0)
            ? static_cast<uint32_t>(ring_bytes / bytes_per_frame)
            : 0U;
        out_ts = written_ts - ring_unplayed_samples;
        is_live_audio = true;
        return true;
    }

    uint32_t target_ts = 0;
    if (get_target_speaker_rtp_ts(target_ts)) {
        out_ts = target_ts;
        is_live_audio = false;
        return true;
    }

    is_live_audio = false;
    return false;
}

void RaopRtp::set_track_rtp_anchor(uint32_t start_rtp_ts, int64_t duration_ms) {
    std::lock_guard<std::mutex> lock(track_anchor_mutex_);
    if (duration_ms > 0) {
        track_start_rtp_ts_ = start_rtp_ts;
        track_anchor_duration_ms_ = duration_ms;
        has_track_start_rtp_ts_ = true;
    } else {
        has_track_start_rtp_ts_ = false;
        track_anchor_duration_ms_ = 0;
    }
}

void RaopRtp::invalidate_track_rtp_anchor() {
    std::lock_guard<std::mutex> lock(track_anchor_mutex_);
    has_track_start_rtp_ts_ = false;
    track_anchor_duration_ms_ = 0;
}

int64_t RaopRtp::get_realtime_track_position_ms() const {
    if (!is_running_.load(std::memory_order_acquire)) {
        return -1;
    }
    uint32_t start_ts = 0;
    int64_t dur_ms = 0;
    {
        std::lock_guard<std::mutex> lock(track_anchor_mutex_);
        if (!has_track_start_rtp_ts_ || track_anchor_duration_ms_ <= 0) {
            return -1;
        }
        start_ts = track_start_rtp_ts_;
        dur_ms = track_anchor_duration_ms_;
    }

    uint32_t speaker_ts = 0;
    bool is_live_audio = false;
    if (!resolve_speaker_rtp_ts(speaker_ts, is_live_audio) || !is_live_audio) {
        return -1;
    }

    const int32_t diff_samples = static_cast<int32_t>(speaker_ts - start_ts);
    // 排队期（无 FLUSH 自动切歌提前推送下首 progress）返回 -1，严禁归零覆盖上层 anchorDelayMs
    if (diff_samples < 0) {
        return -1;
    }

    int sr = 44100;
    {
        std::lock_guard<std::mutex> flock(format_mutex_);
        if (format_sample_rate_ > 0) sr = format_sample_rate_;
    }
    const int64_t pos_ms = (static_cast<int64_t>(diff_samples) * 1000LL) / static_cast<int64_t>(sr);
    if (pos_ms > dur_ms + 3000LL) {
        return -1;
    }
    return std::min(pos_ms, dur_ms);
}

void RaopRtp::send_resend_request(uint16_t first_seq, uint16_t count) {
    if (!has_client_ctrl_.load(std::memory_order_acquire) || ctrl_fd_ < 0 || count == 0) {
        return;
    }

    struct sockaddr_in target_addr{};
    {
        std::lock_guard<std::mutex> lock(ctrl_addr_mutex_);
        target_addr = client_ctrl_addr_;
    }

    // 💡 遵循 Apple RAOP 8 字节重传请求报文规范 (0x80, 0xd5, req_seq, first_seq, count)
    uint8_t req[8];
    req[0] = 0x80;
    req[1] = 0xd5;
    uint16_t rseq = resend_req_seq_.fetch_add(1, std::memory_order_relaxed);
    req[2] = static_cast<uint8_t>((rseq >> 8) & 0xFF);
    req[3] = static_cast<uint8_t>(rseq & 0xFF);
    req[4] = static_cast<uint8_t>((first_seq >> 8) & 0xFF);
    req[5] = static_cast<uint8_t>(first_seq & 0xFF);
    req[6] = static_cast<uint8_t>((count >> 8) & 0xFF);
    req[7] = static_cast<uint8_t>(count & 0xFF);

    ssize_t ret = sendto(ctrl_fd_, req, sizeof(req), 0,
                         reinterpret_cast<const struct sockaddr*>(&target_addr),
                         sizeof(target_addr));
    if (ret < 0) {
        LOGW("发送 RAOP 丢包重传请求失败: errno=%d", errno);
    } else {
        LOGI("⚡ 发送 RAOP 丢包重传请求: first_seq=%u, count=%u, req_seq=%u", first_seq, count, rseq);
    }
}

void RaopRtp::set_crypto_key(const std::vector<uint8_t>& aes_key, const std::vector<uint8_t>& aes_iv) {
    std::lock_guard<std::mutex> lock(crypto_mutex_);
    aes_key_ = aes_key;
    aes_iv_ = aes_iv;
    has_crypto_.store(!aes_key_.empty() && !aes_iv_.empty(), std::memory_order_release);
    LOGI("RAOP RTP 设置 AES 会话密钥完成 (key_len=%zu, iv_len=%zu)", aes_key_.size(), aes_iv_.size());
}

void RaopRtp::set_alac_config(int frame_length, int bit_depth, int channels, int sample_rate) {
    alac_decoder_.init(frame_length, bit_depth, channels, sample_rate);
    LOGI("RAOP RTP 更新 ALAC 配置: frame=%d, depth=%d, ch=%d, rate=%d",
         frame_length, bit_depth, channels, sample_rate);
}

int64_t RaopRtp::get_monotonic_time_us() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

uint64_t RaopRtp::mono_us_to_ntp(int64_t us) {
    if (us < 0) us = 0;
    const uint64_t sec = static_cast<uint64_t>(us / 1000000LL);
    const uint64_t rem_us = static_cast<uint64_t>(us % 1000000LL);
    const uint64_t frac = (rem_us << 32) / 1000000ULL;
    return (sec << 32) | (frac & 0xFFFFFFFFULL);
}

int64_t RaopRtp::ntp_to_us(uint64_t ntp) {
    const int64_t sec = static_cast<int64_t>(ntp >> 32);
    const uint64_t frac = ntp & 0xFFFFFFFFULL;
    const int64_t usec = static_cast<int64_t>((frac * 1000000ULL + 0x80000000ULL) >> 32);
    return sec * 1000000LL + usec;
}

void RaopRtp::drain_jitter_buffer() {
    std::lock_guard<std::mutex> audio_lock(audio_packet_mutex_);
    drain_jitter_buffer_locked();
}

void RaopRtp::drain_jitter_buffer_locked() {
    // 💡 显式暂停态（RTSP PAUSE 或 TV 端主动暂停 AudioEngine）立即停止排空与漂移丢帧：
    // 杜绝 AudioEngine 暂停冻结环形缓冲期间，慢晶振漂移收敛误判滞后狂丢帧。
    if (is_manually_paused_.load(std::memory_order_acquire) ||
        AudioEngine::instance().is_paused()) {
        // 💡 重置首包已上报世代：确保无 FLUSH 的暂停恢复后，首帧重新写入 AudioEngine 时能确定性触发 FirstDataPacketEvent
        emitted_first_epoch_.store(0ULL, std::memory_order_release);
        return;
    }

    int sr = 44100;
    int bits = 16;
    int ch = 2;
    std::string codec_str = "ALAC";
    {
        std::lock_guard<std::mutex> flock(format_mutex_);
        if (format_sample_rate_ > 0) sr = format_sample_rate_;
        if (format_bit_depth_ > 0) bits = format_bit_depth_;
        if (format_channels_ > 0) ch = format_channels_;
        codec_str = format_codec_;
    }
    const uint32_t bytes_per_frame = static_cast<uint32_t>(ch * (bits / 8));

    // 💡 基于 NTP Timing + Sync (PT=84) 计算此刻扬声器应出声的目标 RTP 时间戳
    uint32_t target_speaker_ts = 0;
    const bool has_sync_target = get_target_speaker_rtp_ts(target_speaker_ts);
    const int32_t usb_urb_samples = static_cast<int32_t>((static_cast<int64_t>(sr) * 8LL) / 1000LL);        // ~8ms USB URB 在途管线
    const int32_t target_ring_samples = static_cast<int32_t>((static_cast<int64_t>(sr) * 130LL) / 1000LL);   // ~130ms 目标环形缓冲水位 (>100ms 预充)
    const int32_t max_drift_late_samples = static_cast<int32_t>((static_cast<int64_t>(sr) * 45LL) / 1000LL); // 45ms 滞后追赶门限
    const int32_t max_sync_sanity_samples = static_cast<int32_t>(sr) * 4;                                    // 4s 异常时间戳防死锁保护

    size_t next_frame_size = 0;
    uint32_t head_ts = 0;
    bool has_head_ts = false;
    std::vector<uint8_t> ready_frame;
    uint32_t frame_ts = 0;
    bool has_frame_ts = false;

    while (jitter_buffer_.peek_head(next_frame_size, head_ts, has_head_ts)) {
        if (has_sync_target && has_head_ts) {
            const int32_t ahead_of_speaker = static_cast<int32_t>(head_ts - target_speaker_ts);
            if (ahead_of_speaker >= -max_sync_sanity_samples && ahead_of_speaker <= max_sync_sanity_samples) {
                const size_t ring_bytes = AudioEngine::instance().get_available_read();
                const uint32_t ring_unplayed_samples = (bytes_per_frame > 0)
                    ? static_cast<uint32_t>(ring_bytes / bytes_per_frame)
                    : 0U;

                if (!sync_playback_started_) {
                    if (late_sync_handover_pending_.exchange(false, std::memory_order_acq_rel)) {
                        // 💡 迟到 Sync 一次性平滑接管：已无门控开播时切入稳态水门，不影响后续排空后的起播门控重入
                        sync_playback_started_ = true;
                    } else {
                        // 💡 阶段 1 (起播门控)：在 head_ts 真正到达扬声器出声时刻 (减去 ~8ms USB URB 管线延迟) 前，
                        // 将音频帧压在 RaopJitterBuffer 中等待，杜绝比 iPhone 提前 1.7~2.0s 出声！
                        if (ahead_of_speaker > usb_urb_samples) {
                            break;
                        }
                        if (ahead_of_speaker < -max_drift_late_samples) {
                            // 首包已严重滞后于目标出声时刻，丢弃过期帧快速对齐
                            jitter_buffer_.pop(ready_frame, nullptr, nullptr);
                            continue;
                        }
                        sync_playback_started_ = true;
                        LOGI("🎯 Sync 精准起播对齐: head_ts=%u, target_speaker_ts=%u (偏差=%d 采样 / %.1f ms, buffered=%zu)",
                             head_ts, target_speaker_ts, ahead_of_speaker,
                             static_cast<double>(ahead_of_speaker) * 1000.0 / static_cast<double>(sr),
                             jitter_buffer_.buffered_packets());
                    }
                }

                if (sync_playback_started_) {
                    if (ring_unplayed_samples == 0 && ahead_of_speaker > max_drift_late_samples) {
                        // 缓冲已完全排空且后续帧仍远在未来，重新进入起播门控
                        sync_playback_started_ = false;
                        break;
                    }
                    // 💡 阶段 2 (稳态水门)：仅在环形缓冲需要补水至 target_ring_samples (~130ms) 时放行帧写入 AudioEngine
                    if (ahead_of_speaker > target_ring_samples + usb_urb_samples) {
                        break;
                    }
                    // 💡 慢晶振漂移收敛：若实测扬声器滞后超 45ms 且环形缓冲高于目标水位，单次最多丢弃 1 帧 (8ms) 并进入 300ms 冷却，杜绝单轮雪崩丢帧
                    if (has_written_rtp_ts_.load(std::memory_order_acquire)) {
                        const uint32_t written_ts = last_written_rtp_ts_.load(std::memory_order_acquire);
                        const uint32_t actual_speaker_ts = written_ts - ring_unplayed_samples;
                        const int32_t speaker_lag = static_cast<int32_t>(target_speaker_ts - actual_speaker_ts);
                        const int64_t now_us = get_monotonic_time_us();
                        if (speaker_lag > max_drift_late_samples &&
                            ring_unplayed_samples > static_cast<uint32_t>(target_ring_samples) &&
                            (last_drift_drop_mono_us_ == 0 || (now_us - last_drift_drop_mono_us_) >= DRIFT_DROP_COOLDOWN_US)) {
                            LOGD("Sync 慢晶振漂移收敛: 滞后 %d 采样 (%.1f ms)，丢弃 1 帧追平",
                                 speaker_lag, static_cast<double>(speaker_lag) * 1000.0 / static_cast<double>(sr));
                            jitter_buffer_.pop(ready_frame, nullptr, nullptr);
                            last_drift_drop_mono_us_ = now_us;
                            drift_drop_count_.fetch_add(1U, std::memory_order_relaxed);
                            break;
                        }
                    }
                }
            }
        }

        if (AudioEngine::instance().get_buffer_available() < next_frame_size) {
            break;
        }
        if (!jitter_buffer_.pop(ready_frame, &frame_ts, &has_frame_ts)) {
            break;
        }

        const uint64_t cur_epoch = active_epoch_.load(std::memory_order_acquire);
        int written = AudioEngine::instance().write_pcm(ready_frame.data(), ready_frame.size(), cur_epoch);
        if (written > 0) {
            if (has_frame_ts) {
                const uint32_t written_samples = (bytes_per_frame > 0)
                    ? static_cast<uint32_t>(static_cast<size_t>(written) / bytes_per_frame)
                    : 352U;
                last_written_rtp_ts_.store(frame_ts + written_samples, std::memory_order_release);
                has_written_rtp_ts_.store(true, std::memory_order_release);
            }

            uint64_t prev_emitted = emitted_first_epoch_.load(std::memory_order_acquire);
            if (prev_emitted != cur_epoch &&
                emitted_first_epoch_.compare_exchange_strong(prev_emitted, cur_epoch, std::memory_order_acq_rel)) {
                const bool should_resume_from_silence_pause = post_flush_silence_paused_;
                post_flush_silence_paused_ = false;
                FirstDataPacketCallback fcb_copy;
                StreamStateCallback scb_copy;
                {
                    std::lock_guard<std::mutex> slock(state_mutex_);
                    fcb_copy = first_packet_cb_;
                    if (should_resume_from_silence_pause) {
                        scb_copy = state_cb_;
                    }
                }
                if (fcb_copy) {
                    fcb_copy(cur_epoch, sr, bits, ch, codec_str);
                }
                if (scb_copy) {
                    LOGI("检测到 FLUSH 静音暂停后首帧有声音频到达出声时刻，发射恢复回调 (epoch=%llu)",
                         static_cast<unsigned long long>(cur_epoch));
                    scb_copy(false, cur_epoch);
                }
            }
        } else if (written < 0) {
            LOGW("RAOP drain_jitter_buffer write_pcm 返回错误或世代过期: ret=%d, epoch=%llu",
                 written, static_cast<unsigned long long>(cur_epoch));
            break;
        } else {
            // written == 0 说明环形缓冲容量瞬时发生变动，终止本轮消费并等待下个节拍
            break;
        }
    }

    // 💡 快晶振漂移防欠载：若 USB DAC 消耗略快于 iPhone 时钟导致环形缓冲逼近 100ms 预充下限，
    // 而下一真实帧仍未到 Sync 放行时刻，补写 1 帧 (8ms) 静音垫步以保活不断音并平滑拉回同步
    if (sync_playback_started_ && has_sync_target &&
        has_written_rtp_ts_.load(std::memory_order_acquire) &&
        jitter_buffer_.peek_head(next_frame_size, head_ts, has_head_ts) && has_head_ts) {
        const int32_t ahead_of_speaker = static_cast<int32_t>(head_ts - target_speaker_ts);
        const size_t ring_bytes = AudioEngine::instance().get_available_read();
        const uint32_t ring_unplayed_samples = (bytes_per_frame > 0)
            ? static_cast<uint32_t>(ring_bytes / bytes_per_frame)
            : 0U;
        const uint32_t min_safe_ring_samples = static_cast<uint32_t>((static_cast<int64_t>(sr) * 105LL) / 1000LL);
        if (ring_unplayed_samples > 0 &&
            ring_unplayed_samples < min_safe_ring_samples &&
            ahead_of_speaker > target_ring_samples + usb_urb_samples &&
            ahead_of_speaker <= max_sync_sanity_samples &&
            bytes_per_frame > 0) {
            const uint32_t pad_samples = static_cast<uint32_t>(
                std::max<int64_t>(352LL, (static_cast<int64_t>(sr) * 8LL) / 1000LL)
            );
            const size_t pad_bytes = static_cast<size_t>(pad_samples) * bytes_per_frame;
            if (AudioEngine::instance().get_buffer_available() >= pad_bytes) {
                if (pad_silence_buf_.size() < pad_bytes) {
                    pad_silence_buf_.assign(pad_bytes, 0);
                }
                const uint64_t cur_epoch = active_epoch_.load(std::memory_order_acquire);
                const int pad_written = AudioEngine::instance().write_pcm(pad_silence_buf_.data(), pad_bytes, cur_epoch);
                if (pad_written > 0) {
                    LOGD("Sync 快晶振漂移垫步: ring=%u 采样 < %u，插入 %u 采样 (~8ms) 静音防欠载并回拉对齐",
                         ring_unplayed_samples, min_safe_ring_samples, pad_samples);
                }
            }
        }
    }
}

void RaopRtp::sync_drain_loop() {
    LOGI("RAOP RTP Sync 门控排空调度线程已启动");
    while (is_running_.load(std::memory_order_relaxed)) {
        // 💡 10ms 调度节拍：纯数据面职责，仅负责排空到达 Sync 放行时刻或因背压滞留的就绪帧
        // 控制面暂停/恢复/停止严格由 RTSP 信令 (PAUSE / FLUSH / TEARDOWN / RECORD) 驱动，绝不在 UDP 层猜测停流
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        if (!is_running_.load(std::memory_order_relaxed)) break;
        drain_jitter_buffer();
    }
    LOGI("RAOP RTP Sync 门控排空调度线程已退出");
}

void RaopRtp::emit_state_callback(bool is_paused, uint64_t epoch) {
    StreamStateCallback cb_copy;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        cb_copy = state_cb_;
    }
    if (cb_copy) {
        cb_copy(is_paused, epoch);
    }
}

void RaopRtp::mark_paused_manually(bool paused, bool emit_callback) {
    is_manually_paused_.store(paused, std::memory_order_release);
    if (paused) {
        LOGI("收到手动标记暂停指令 (RTSP PAUSE)");
    } else {
        LOGI("收到手动标记恢复指令 (RTSP RECORD, emit=%d)", emit_callback ? 1 : 0);
    }
    if (!emit_callback) {
        return;
    }
    emit_state_callback(paused, active_epoch_.load(std::memory_order_acquire));
}

uint64_t RaopRtp::get_ntp_timestamp() {
    struct timeval tv{};
    gettimeofday(&tv, nullptr);
    // NTP 纪元从 1900-01-01 开始，相比 Unix 纪元 (1970) 偏移 2208988800 秒
    uint64_t sec = static_cast<uint64_t>(tv.tv_sec) + 2208988800ULL;
    uint64_t frac = (static_cast<uint64_t>(tv.tv_usec) * 4294967296ULL) / 1000000ULL;
    return (sec << 32) | (frac & 0xFFFFFFFFULL);
}

void RaopRtp::data_loop() {
    setpriority(PRIO_PROCESS, 0, -10); // 提升数据接收线程优先级
    std::vector<uint8_t> buffer(2048);

    LOGI("RAOP RTP 数据接收线程已启动");

    while (is_running_.load(std::memory_order_acquire)) {
        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);

        ssize_t n = recvfrom(data_fd_, buffer.data(), buffer.size(), 0,
                             reinterpret_cast<struct sockaddr*>(&client_addr), &addr_len);
        if (n <= 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
            break; // Socket 已关闭
        }

        if (n < 4) continue;

        uint8_t payload_type = buffer[1] & 0x7F;
        process_audio_packet(buffer.data(), n, payload_type);
    }

    LOGI("RAOP RTP 数据接收线程退出");
}

void RaopRtp::process_audio_packet(const uint8_t* data, ssize_t len, uint8_t payload_type) {
    if (data == nullptr || len < 12) return;

    // 💡 保护多线程 (data_thread_ 与 ctrl_thread_ 重传通道) 并发接入音频解码与缓冲队列
    std::lock_guard<std::mutex> audio_lock(audio_packet_mutex_);

    uint8_t expected_pt = audio_payload_type_.load(std::memory_order_relaxed);

    // 💡 AirTunes/RAOP 音频包类型:
    // expected_pt / 96: 标准实时音频数据包 (ALAC 或动态 L16/PCM)
    // 86: 重传音频数据包 (带有额外 4 字节前导头)
    // 10: RFC 3551 标准静态 L16 双声道 PCM (常用于非 Apple Music / 视频第三方投屏)
    if (payload_type != expected_pt && payload_type != 96 && payload_type != 86 && payload_type != 10) {
        static uint32_t unknown_drop_count = 0;
        if (unknown_drop_count++ % 100 == 0) {
            LOGW("process_audio_packet: 收到未知 payload_type=%u (期望=%u, 96, 86, 10), len=%zd, 忽略",
                 payload_type, expected_pt, len);
        }
        return;
    }

    // 💡 采样打印接收到的音频包，确保链路可观测
    static uint32_t audio_pkt_count = 0;
    uint32_t cur_cnt = audio_pkt_count++;
    if (cur_cnt < 10 || cur_cnt % 500 == 0) {
        LOGI("process_audio_packet: 收到音频包 [%u] pt=%u, len=%zd, has_crypto=%d",
             cur_cnt, payload_type, len, has_crypto_.load(std::memory_order_relaxed));
    }

    // 💡 控制面状态由 RTSP 信令严格驱动，音频数据面严禁越权猜测暂停或唤醒

    const uint8_t* pktp = data;
    ssize_t plen = len;

    if (payload_type == 86) {
        // 重传音频包跳过 4 字节
        if (plen < 16) return;
        pktp += 4;
        plen -= 4;
    }

    if (plen < 12) return;

    uint16_t seq = (static_cast<uint16_t>(pktp[2]) << 8) | pktp[3];
    uint32_t ts = (static_cast<uint32_t>(pktp[4]) << 24) |
                  (static_cast<uint32_t>(pktp[5]) << 16) |
                  (static_cast<uint32_t>(pktp[6]) << 8)  |
                  pktp[7];

    // 💡 实时丢包空洞检测: 发现主干音频包序列号跳跃时，立即向 iPhone 发送重传请求
    if (payload_type == expected_pt || payload_type == 96) {
        if (first_data_seq_) {
            last_data_seq_ = seq;
            first_data_seq_ = false;
        } else {
            int16_t diff = static_cast<int16_t>(seq - last_data_seq_);
            if (diff > 1 && diff <= 32) {
                uint16_t missing_start = static_cast<uint16_t>(last_data_seq_ + 1);
                uint16_t missing_count = static_cast<uint16_t>(diff - 1);
                send_resend_request(missing_start, missing_count);
            }
            if (diff > 0 || diff < -300) {
                // 💡 序列号正常递增，或者序列号反向跳跃超过 300 (切歌/客户端重置/环绕)，重置 last_data_seq_ 基准，防止卡死
                last_data_seq_ = seq;
            }
        }
    }

    const uint8_t* payload = pktp + 12;
    size_t payload_len = static_cast<size_t>(plen - 12);
    if (payload_len < 16) return;

    // 1. AES-128-CBC 解密
    // 💡 AirPlay 1 规范要求:
    // 每个音频包独立初始化 CBC 向量 (使用 ANNOUNCE 协商的 aes_iv_)
    // 解密长度为 16 字节整倍数 (payload_len & ~0xf)，尾部余数保持明文拷贝
    // 💡 针对 RFC 3551 L16 PCM (pt=10) 静态标准包，无需 AES 解密 (保持明文网络序传输)
    std::vector<uint8_t> decrypted_payload(payload_len);
    memcpy(decrypted_payload.data(), payload, payload_len);

    if (has_crypto_.load(std::memory_order_acquire) && payload_type != 10) {
        size_t aeslen = payload_len & ~0xf;
        if (aeslen > 0) {
            uint8_t key[16];
            uint8_t iv[16];
            bool key_valid = false;
            {
                std::lock_guard<std::mutex> lock(crypto_mutex_);
                if (aes_key_.size() >= 16 && aes_iv_.size() >= 16) {
                    memcpy(key, aes_key_.data(), 16);
                    memcpy(iv, aes_iv_.data(), 16);
                    key_valid = true;
                }
            }
            if (key_valid) {
                struct AES_ctx ctx;
                AES_init_ctx_iv(&ctx, key, iv);
                AES_CBC_decrypt_buffer(&ctx, decrypted_payload.data(), aeslen);
            }
        }
    }

    // 2. 音频解码 / 转换处理
    // 💡 修复 Finding 13: 复用已预分配的成员变量 decode_pcm_buf_，避免高频音频包热路径每包 16KB 堆分配
    if (decode_pcm_buf_.size() < 16384) {
        decode_pcm_buf_.resize(16384);
    }
    size_t out_bytes = 0;
    AudioCodec current_codec = audio_codec_.load(std::memory_order_acquire);

    if (current_codec == AudioCodec::PCM || payload_type == 10) {
        int bit_depth = 16;
        {
            std::lock_guard<std::mutex> f_lock(format_mutex_);
            if (format_bit_depth_ > 0) bit_depth = format_bit_depth_;
        }
        if (bit_depth == 24) {
            // 💡 L24 未压缩线性 PCM 处理 (RFC 3190):
            // L24 采样为 24-bit 有符号大端网络字节序 (MSB, MID, LSB)
            // 需逐采样转换为本地架构 Little-Endian (LSB, MID, MSB) 以便 AudioEngine 直通播放
            size_t valid_bytes = payload_len - (payload_len % 3);
            if (valid_bytes > decode_pcm_buf_.size()) {
                decode_pcm_buf_.resize(valid_bytes);
            }
            const uint8_t* src = decrypted_payload.data();
            uint8_t* dst = decode_pcm_buf_.data();
            for (size_t i = 0; i < valid_bytes; i += 3) {
                dst[i] = src[i + 2];     // LSB (低位)
                dst[i + 1] = src[i + 1]; // MID (中位)
                dst[i + 2] = src[i];     // MSB (高位)
            }
            out_bytes = valid_bytes;
        } else {
            // 💡 L16 未压缩线性 PCM 处理:
            // 遵循 RFC 3551 规范，L16 采样为 16-bit 有符号大端网络字节序 (Big-Endian)
            // 需逐采样转换为本地架构 Little-Endian (S16_LE) 以便 AudioEngine 直通播放
            size_t valid_bytes = payload_len & ~1;
            if (valid_bytes > decode_pcm_buf_.size()) {
                decode_pcm_buf_.resize(valid_bytes);
            }
            const uint8_t* src = decrypted_payload.data();
            uint8_t* dst = decode_pcm_buf_.data();
            for (size_t i = 0; i < valid_bytes; i += 2) {
                dst[i] = src[i + 1];     // 低位
                dst[i + 1] = src[i];     // 高位
            }
            out_bytes = valid_bytes;
        }
    } else {
        // 💡 ALAC 实时无损解码为 16-bit 44.1kHz PCM (S16_LE)
        if (!alac_decoder_.decode_frame(decrypted_payload.data(), payload_len, decode_pcm_buf_.data(), out_bytes)) {
            static uint32_t alac_fail_cnt = 0;
            if (alac_fail_cnt++ % 100 == 0) {
                LOGE("ALAC 解码单帧失败! payload_len=%zu, seq=%u, has_crypto=%d",
                     payload_len, seq, has_crypto_.load(std::memory_order_relaxed));
            }
            out_bytes = 0;
        }
    }

    if (out_bytes > 0) {
        // 💡 打印短包 (如 44 字节静音或特定音频帧) 原始十六进制以供深度链路核验
        if (len <= 64 && (cur_cnt % 500 == 0 || cur_cnt < 5)) {
            char hex_buf[70] = {0};
            for (size_t i = 0; i < std::min(payload_len, static_cast<size_t>(32)); ++i) {
                snprintf(hex_buf + i * 2, sizeof(hex_buf) - i * 2, "%02x", payload[i]);
            }
            LOGI("process_audio_packet: 短音频包原始负载 [pkt=%u, len=%zu]: %s", cur_cnt, payload_len, hex_buf);
        }

        // 💡 显式 RTSP PAUSE 保护：直接丢弃在途音频帧，杜绝旧帧堆积与恢复跳变
        if (is_manually_paused_.load(std::memory_order_acquire)) {
            emitted_first_epoch_.store(0ULL, std::memory_order_release);
            return;
        }

        // 💡 FLUSH 后静音拦截门控：iOS 暂停时会先发 RTSP FLUSH 再持续推 44B 全 0 静音包；
        // 在新世代尚未收到真实有声帧前，直接丢弃全 0 静音帧（AudioEngine 等时 URB 已自动泵全 0 保活 DAC），
        // 杜绝静音包触发 Sync 起播与 FirstDataPacketEvent 把状态机假复活回 PLAYING；
        // 若用户在 TV 端按暂停后又在手机端直接点继续播放，收到非全 0 真实有声帧时自动解除本机暂停标记恢复出声。
        if (awaiting_post_flush_audio_) {
            int cur_bit_depth = 16;
            {
                std::lock_guard<std::mutex> flock(format_mutex_);
                if (format_bit_depth_ > 0) cur_bit_depth = format_bit_depth_;
            }
            if (is_pcm_frame_silent(decode_pcm_buf_.data(), out_bytes, cur_bit_depth)) {
                post_flush_silence_frames_++;
                if (!post_flush_silence_paused_ && post_flush_silence_frames_ >= POST_FLUSH_SILENCE_PAUSE_FRAMES) {
                    post_flush_silence_paused_ = true;
                    const uint64_t cur_epoch = active_epoch_.load(std::memory_order_acquire);
                    LOGI("检测到 RTSP FLUSH 后连续 %u 帧全 0 静音包 (~%u ms)，判定发送端已暂停并保持静音",
                         post_flush_silence_frames_, post_flush_silence_frames_ * 8U);
                    emit_state_callback(true, cur_epoch);
                }
                return;
            }
            awaiting_post_flush_audio_ = false;
            post_flush_silence_frames_ = 0;
            if (AudioEngine::instance().is_paused()) {
                post_flush_silence_paused_ = true;
                AudioEngine::instance().resume_stream();
                LOGI("检测到 TV 端暂停且 FLUSH 后收到手机端真实有声帧，自动解除静音并恢复播放");
            }
        } else if (AudioEngine::instance().is_paused()) {
            // 💡 TV 端刚按暂停且尚未收到 iOS 响应 FLUSH 前的在途旧尾包：严格拦截杜绝尾音泄漏
            emitted_first_epoch_.store(0ULL, std::memory_order_release);
            return;
        }

        // 💡 正常播流期间保留全 0 音频帧入队 JitterBuffer 以维持 RTP 序列号 seq 连续性
        std::vector<uint8_t> pcm_copy(decode_pcm_buf_.data(), decode_pcm_buf_.data() + out_bytes);
        jitter_buffer_.push(seq, ts, std::move(pcm_copy));

        // 3. 💡 修复 Finding 8 & Finding 5: 排空已就绪音频帧并写入 AudioEngine 环形缓冲
        drain_jitter_buffer_locked();
    }
}

static inline uint32_t read_be32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8)  |
           static_cast<uint32_t>(p[3]);
}

static inline uint64_t read_be64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) {
        v = (v << 8) | static_cast<uint64_t>(p[i]);
    }
    return v;
}

static inline void write_be64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) {
        p[i] = static_cast<uint8_t>((v >> ((7 - i) * 8)) & 0xFF);
    }
}

void RaopRtp::handle_sync_packet(const uint8_t* data, ssize_t len) {
    if (data == nullptr || len < 20) return;

    const bool is_first_sync = (data[0] & 0x10) != 0;
    const uint32_t rtp_now = read_be32(data + 4);
    const uint64_t ntp_sender = read_be64(data + 8);
    const uint32_t rtp_next = read_be32(data + 16);
    const int64_t recv_mono_us = get_monotonic_time_us();

    int64_t sync_local_mono_us = recv_mono_us;
    bool used_ntp = false;
    if (has_ntp_offset_.load(std::memory_order_acquire) && ntp_sender != 0) {
        int64_t offset_us = 0;
        {
            std::lock_guard<std::mutex> lock(ntp_mutex_);
            offset_us = ntp_offset_us_;
        }
        const int64_t mapped_mono_us = ntp_to_us(ntp_sender) - offset_us;
        // 合理性保护：仅当 NTP 映射时刻与本地到达时刻相差在 ±500ms 内才采信，防止非标准客户端异常纪元污染
        const int64_t diff_us = mapped_mono_us - recv_mono_us;
        if (diff_us >= -500000LL && diff_us <= 500000LL) {
            sync_local_mono_us = mapped_mono_us;
            used_ntp = true;
        }
    }

    int sr = 44100;
    {
        std::lock_guard<std::mutex> flock(format_mutex_);
        if (format_sample_rate_ > 0) sr = format_sample_rate_;
    }

    // 💡 在获取 sync_mutex_ 之前预采样 live_speaker_ts，杜绝持锁期间反向调用 resolve_speaker_rtp_ts 引发重入锁序问题
    uint32_t live_speaker_ts = 0;
    bool has_live_speaker = false;
    if (has_written_rtp_ts_.load(std::memory_order_acquire)) {
        bool is_live = false;
        if (resolve_speaker_rtp_ts(live_speaker_ts, is_live) && is_live) {
            has_live_speaker = true;
        }
    }

    bool captured_late_phase = false;
    int32_t late_phase_samples = 0;
    uint32_t raw_target_ts = 0;
    {
        std::lock_guard<std::mutex> lock(sync_mutex_);
        const bool was_inactive_anchor = !has_sync_anchor_ || (recv_mono_us - sync_local_mono_us_) > 15000000LL;
        sync_rtp_ts_ = rtp_now;
        sync_local_mono_us_ = sync_local_mono_us;
        has_sync_anchor_ = true;
        if (was_inactive_anchor && has_live_speaker) {
            const int64_t elapsed_us = recv_mono_us - sync_local_mono_us;
            const int64_t elapsed_samples = (elapsed_us * static_cast<int64_t>(sr)) / 1000000LL;
            raw_target_ts = static_cast<uint32_t>(
                static_cast<int64_t>(rtp_now) + elapsed_samples -
                static_cast<int64_t>(REPORTED_AUDIO_LATENCY_SAMPLES)
            );
            const int32_t offset = static_cast<int32_t>(live_speaker_ts - raw_target_ts);
            const int32_t max_offset = static_cast<int32_t>(sr) * 4;
            if (offset >= -max_offset && offset <= max_offset) {
                sync_phase_offset_samples_ = offset;
                late_phase_samples = offset;
                captured_late_phase = true;
            }
        }
    }
    if (captured_late_phase) {
        late_sync_handover_pending_.store(true, std::memory_order_release);
    }

    const int32_t sender_latency_samples = static_cast<int32_t>(rtp_next - rtp_now);
    const double sender_latency_ms = static_cast<double>(sender_latency_samples) * 1000.0 / static_cast<double>(sr);

    if (captured_late_phase) {
        LOGI("⚡ 迟到 Sync 相位基线接管: live_speaker_ts=%u, raw_target_ts=%u, phase_offset=%d 采样 (%.1f ms)",
             live_speaker_ts, raw_target_ts, late_phase_samples,
             static_cast<double>(late_phase_samples) * 1000.0 / static_cast<double>(sr));
    }
    if (is_first_sync) {
        LOGI("⏱️ 收到首帧 Sync 同步包: rtp_now=%u, rtp_next=%u (发端缓冲=%.1f ms), ntp_locked=%d",
             rtp_now, rtp_next, sender_latency_ms, used_ntp ? 1 : 0);
    } else {
        static std::atomic<uint32_t> sync_log_counter{0};
        if ((sync_log_counter.fetch_add(1, std::memory_order_relaxed) % 5U) == 0U) {
            LOGD("收到客户端 Sync 控制包 (len=%zd): rtp_now=%u, rtp_next=%u (发端缓冲=%.1f ms), ntp_locked=%d",
                 len, rtp_now, rtp_next, sender_latency_ms, used_ntp ? 1 : 0);
        }
    }

    // 同步锚点更新后立即检查是否到达放行时刻
    drain_jitter_buffer();
}

void RaopRtp::send_timing_request() {
    if (!has_client_timing_.load(std::memory_order_acquire) || timing_fd_ < 0) {
        return;
    }

    struct sockaddr_in target_addr{};
    {
        std::lock_guard<std::mutex> lock(timing_addr_mutex_);
        target_addr = client_timing_addr_;
    }

    // 💡 遵循 RFC 5905 & Apple RAOP 32 字节 NTP Timing 请求报文 (0x80, 0xD2, seq=0x0007)
    // 将本地单调时钟编码进 Transmit Timestamp (offset 24..31)，客户端回包时将原样回显到 offset 8..15，
    // 从而直接解算出“发射端 NTP 时钟 - 本地单调时钟”的精确偏移，完全免疫 Android 系统墙钟跳变！
    uint8_t req[32];
    std::memset(req, 0, sizeof(req));
    req[0] = 0x80;
    req[1] = 0xD2;
    req[2] = 0x00;
    req[3] = 0x07;
    const uint64_t t1_ntp = mono_us_to_ntp(get_monotonic_time_us());
    write_be64(req + 24, t1_ntp);

    ssize_t ret = sendto(timing_fd_, req, sizeof(req), 0,
                         reinterpret_cast<const struct sockaddr*>(&target_addr),
                         sizeof(target_addr));
    if (ret < 0) {
        LOGD("发送主动 NTP Timing 请求失败: errno=%d", errno);
    }
}

void RaopRtp::handle_timing_reply(const uint8_t* data, ssize_t len, int64_t t4_mono_us) {
    if (data == nullptr || len < 32) return;

    const int64_t t1_mono_us = ntp_to_us(read_be64(data + 8));
    const int64_t t2_sender_us = ntp_to_us(read_be64(data + 16));
    const int64_t t3_sender_us = ntp_to_us(read_be64(data + 24));

    const int64_t rtt_us = (t4_mono_us - t1_mono_us) - (t3_sender_us - t2_sender_us);
    if (rtt_us < 0 || rtt_us > 500000LL) {
        LOGD("忽略异常 NTP Timing 样本: rtt=%lld us", static_cast<long long>(rtt_us));
        return;
    }

    // 💡 防溢出中值公式计算 sender_ntp_us - local_mono_us = ((T2 - T1) + (T3 - T4)) / 2
    const int64_t diff1 = t2_sender_us - t1_mono_us;
    const int64_t diff2 = t3_sender_us - t4_mono_us;
    const int64_t sample_offset_us = diff1 + (diff2 - diff1) / 2;

    int64_t best_rtt = rtt_us;
    int64_t best_offset = sample_offset_us;
    bool first_lock = false;
    {
        std::lock_guard<std::mutex> lock(ntp_mutex_);
        first_lock = (ntp_sample_count_ == 0);
        ntp_samples_[ntp_sample_cursor_] = NtpTimingSample{rtt_us, sample_offset_us};
        ntp_sample_cursor_ = (ntp_sample_cursor_ + 1) % NTP_FILTER_WINDOW;
        if (ntp_sample_count_ < NTP_FILTER_WINDOW) {
            ntp_sample_count_++;
        }

        // 💡 最小 RTT 滤波（剔除 Wi-Fi 排队毛刺，取最对称网络往返样本）
        best_rtt = ntp_samples_[0].rtt_us;
        best_offset = ntp_samples_[0].offset_us;
        for (size_t i = 1; i < ntp_sample_count_; ++i) {
            if (ntp_samples_[i].rtt_us < best_rtt) {
                best_rtt = ntp_samples_[i].rtt_us;
                best_offset = ntp_samples_[i].offset_us;
            }
        }
        ntp_offset_us_ = best_offset;
    }
    has_ntp_offset_.store(true, std::memory_order_release);

    if (first_lock) {
        LOGI("🎯 NTP Timing 时钟偏移首次锁定: rtt=%.2f ms, offset=%lld us",
             static_cast<double>(best_rtt) / 1000.0, static_cast<long long>(best_offset));
    } else {
        LOGD("NTP Timing 时钟校准更新: cur_rtt=%.2f ms, best_rtt=%.2f ms",
             static_cast<double>(rtt_us) / 1000.0, static_cast<double>(best_rtt) / 1000.0);
    }
}

void RaopRtp::ctrl_loop() {
    std::vector<uint8_t> buffer(2048); // 💡 扩大接收缓冲区至 2048 字节，避免 PT=86 重传音频包被截断

    LOGI("RAOP RTP 控制接收线程已启动");

    while (is_running_.load(std::memory_order_acquire)) {
        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);

        ssize_t n = recvfrom(ctrl_fd_, buffer.data(), buffer.size(), 0,
                             reinterpret_cast<struct sockaddr*>(&client_addr), &addr_len);
        if (n <= 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
            break;
        }

        if (n < 4) continue;

        // 💡 动态学习客户端控制端点地址与端口，确保丢包重传通道畅通 (加锁杜绝与 send_resend_request 竞态)
        if (!has_client_ctrl_.load(std::memory_order_acquire)) {
            {
                std::lock_guard<std::mutex> lock(ctrl_addr_mutex_);
                client_ctrl_addr_ = client_addr;
            }
            has_client_ctrl_.store(true, std::memory_order_release);
            char ip_str[INET_ADDRSTRLEN] = {0};
            inet_ntop(AF_INET, &(client_addr.sin_addr), ip_str, INET_ADDRSTRLEN);
            LOGI("通过控制端口学习到客户端端点: %s:%d", ip_str, ntohs(client_addr.sin_port));
        }

        uint8_t payload_type = buffer[1] & 0x7F;
        if (payload_type == 84) {
            // 💡 RTP Sync 同步包 (20 字节，包含此刻扬声器应出声的 RTP 时间戳与 NTP 时间戳映射)
            handle_sync_packet(buffer.data(), n);
        } else if (payload_type == 86) {
            // 💡 AirTunes/RAOP 重传音频数据包通过控制端口到达，交由 process_audio_packet 处理
            process_audio_packet(buffer.data(), n, payload_type);
        }
    }

    LOGI("RAOP RTP 控制接收线程退出");
}

void RaopRtp::timing_loop() {
    std::vector<uint8_t> buffer(128);
    int64_t last_req_mono_us = 0;

    LOGI("RAOP RTP 授时接收线程已启动");

    while (is_running_.load(std::memory_order_acquire)) {
        // 💡 主动向发射端发起 NTP Timing 对时：收敛前 200ms 快速轮询 3 次，锁定后每 3 秒维持校准
        if (has_client_timing_.load(std::memory_order_acquire)) {
            const int64_t now_us = get_monotonic_time_us();
            size_t count = 0;
            {
                std::lock_guard<std::mutex> lock(ntp_mutex_);
                count = ntp_sample_count_;
            }
            const int64_t interval_us = (count < 3) ? 200000LL : 3000000LL;
            if (last_req_mono_us == 0 || (now_us - last_req_mono_us) >= interval_us) {
                send_timing_request();
                last_req_mono_us = now_us;
            }
        }

        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);

        ssize_t n = recvfrom(timing_fd_, buffer.data(), buffer.size(), 0,
                             reinterpret_cast<struct sockaddr*>(&client_addr), &addr_len);
        if (n <= 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
            break;
        }

        // Timing 报文固定为 32 字节
        if (n == 32) {
            const uint8_t pt = buffer[1] & 0x7F;
            if (pt == 83) {
                // 💡 收到发射端返回的 NTP Timing 响应 (PT=83)，解算精确时钟偏移
                handle_timing_reply(buffer.data(), n, get_monotonic_time_us());
            } else if (pt == 82) {
                // 💡 被动回复客户端发起的 NTP Timing 请求 (PT=82)
                uint64_t recv_time = get_ntp_timestamp();
                buffer[0] = 0x80;
                buffer[1] = 0xD3;

                // 将客户端请求中的 Transmit Timestamp (offset 24..31) 原样写回 Originate Timestamp (offset 8..15)
                std::memcpy(&buffer[8], &buffer[24], 8);
                write_be64(&buffer[16], recv_time);
                write_be64(&buffer[24], get_ntp_timestamp());

                sendto(timing_fd_, buffer.data(), 32, 0,
                       reinterpret_cast<struct sockaddr*>(&client_addr), addr_len);
                LOGD("处理并回复客户端 NTP Timing 授时校准包");
            }
        }
    }

    LOGI("RAOP RTP 授时接收线程退出");
}

} // namespace purecast::raop
