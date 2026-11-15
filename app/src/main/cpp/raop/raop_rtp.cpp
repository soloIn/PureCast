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
    watchdog_thread_ = std::thread(&RaopRtp::watchdog_loop, this);

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
    if (watchdog_thread_.joinable()) watchdog_thread_.join();

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
    resend_req_seq_.store(0, std::memory_order_relaxed);
    last_audio_packet_time_ms_.store(0, std::memory_order_relaxed);
    is_manually_paused_.store(false, std::memory_order_relaxed);
    is_implicitly_paused_.store(false, std::memory_order_relaxed);
    emitted_first_epoch_.store(0ULL, std::memory_order_release);
    track_duration_ms_.store(0, std::memory_order_release);
    track_position_ms_.store(0, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(crypto_mutex_);
        aes_key_.clear();
        aes_iv_.clear();
        has_crypto_.store(false, std::memory_order_release);
    }
    LOGI("RAOP RTP 接收引擎已完全停止");
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

uint64_t RaopRtp::get_monotonic_time_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

void RaopRtp::drain_jitter_buffer() {
    std::lock_guard<std::mutex> audio_lock(audio_packet_mutex_);
    drain_jitter_buffer_locked();
}

void RaopRtp::drain_jitter_buffer_locked() {
    if (is_manually_paused_.load(std::memory_order_acquire)) {
        return;
    }
    size_t next_frame_size = 0;
    std::vector<uint8_t> ready_frame;
    while (jitter_buffer_.peek_size(next_frame_size) &&
           AudioEngine::instance().get_buffer_available() >= next_frame_size &&
           jitter_buffer_.pop(ready_frame)) {
        const uint64_t cur_epoch = active_epoch_.load(std::memory_order_acquire);
        int written = AudioEngine::instance().write_pcm(ready_frame.data(), ready_frame.size(), cur_epoch);
        if (written > 0) {
            uint64_t prev_emitted = emitted_first_epoch_.load(std::memory_order_acquire);
            if (prev_emitted != cur_epoch &&
                emitted_first_epoch_.compare_exchange_strong(prev_emitted, cur_epoch, std::memory_order_acq_rel)) {
                FirstDataPacketCallback fcb_copy;
                int sr = 44100;
                int bits = 16;
                int ch = 2;
                std::string codec_str = "ALAC";
                {
                    std::lock_guard<std::mutex> flock(format_mutex_);
                    sr = format_sample_rate_;
                    bits = format_bit_depth_;
                    ch = format_channels_;
                    codec_str = format_codec_;
                }
                {
                    std::lock_guard<std::mutex> slock(state_mutex_);
                    fcb_copy = first_packet_cb_;
                }
                if (fcb_copy) {
                    fcb_copy(cur_epoch, sr, bits, ch, codec_str);
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
}

void RaopRtp::watchdog_loop() {
    LOGI("RAOP RTP 数据流无数据看门狗已启动");
    while (is_running_.load(std::memory_order_relaxed)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (!is_running_.load(std::memory_order_relaxed)) break;

        // 💡 修复 Finding 8: 周期性排空 JitterBuffer 中因环形缓冲填满而滞留的就绪帧，防止停顿后未送入 AudioEngine
        drain_jitter_buffer();

        uint64_t last_time = last_audio_packet_time_ms_.load(std::memory_order_relaxed);
        if (last_time > 0 && !is_manually_paused_.load(std::memory_order_relaxed) && !is_implicitly_paused_.load(std::memory_order_relaxed)) {
            uint64_t now = get_monotonic_time_ms();
            if (now > last_time && (now - last_time) >= 600) {
                // 💡 修复 Finding 2: 解耦隐式无数据饥饿暂停与显式手动暂停，绝不设置 is_manually_paused_ 锁死后续新包接入
                is_implicitly_paused_.store(true, std::memory_order_release);
                const uint64_t ep = active_epoch_.load(std::memory_order_acquire);
                LOGI("⚠️ 检测到 RTP 音频流停顿超 600ms (iPhone 隐式停止推流)，通知上层隐式暂停: epoch=%llu",
                     static_cast<unsigned long long>(ep));
                emit_state_callback(true, true, ep);
            }
        }
    }
    LOGI("RAOP RTP 数据流无数据看门狗已退出");
}

void RaopRtp::emit_state_callback(bool is_paused, bool is_implicit, uint64_t epoch) {
    StreamStateCallback cb_copy;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        cb_copy = state_cb_;
    }
    if (cb_copy) {
        cb_copy(is_paused, is_implicit, epoch);
    }
}

void RaopRtp::mark_paused_manually(bool paused, bool emit_callback) {
    is_manually_paused_.store(paused, std::memory_order_release);
    is_implicitly_paused_.store(false, std::memory_order_release);
    if (paused) {
        LOGI("收到手动标记暂停指令 (RTSP PAUSE)");
    } else {
        LOGI("收到手动标记恢复指令 (RTSP RECORD, emit=%d)", emit_callback ? 1 : 0);
        last_audio_packet_time_ms_.store(get_monotonic_time_ms(), std::memory_order_relaxed);
    }
    if (!emit_callback) {
        return;
    }
    emit_state_callback(paused, false, active_epoch_.load(std::memory_order_acquire));
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

    // 💡 修复 Finding 2: 解耦隐式无数据饥饿与显式手动暂停
    // 若此前看门狗因无数据触发了隐式暂停，新到达的音频包意味着推流恢复，立即自动解除隐式暂停并唤醒 AudioEngine
    if (is_implicitly_paused_.load(std::memory_order_acquire)) {
        is_implicitly_paused_.store(false, std::memory_order_release);
        LOGI("收到新音频数据包，自动解除隐式暂停并恢复 AudioEngine");
        AudioEngine::instance().resume_stream();
        const uint64_t ep = active_epoch_.load(std::memory_order_acquire);
        emit_state_callback(false, true, ep);
    }

    // 💡 更新最近音频数据包时间戳供看门狗超时检测 (绝不可在此盲目唤醒播放，须待解码确定非静音后方可唤醒)
    uint64_t now_ms = get_monotonic_time_ms();
    last_audio_packet_time_ms_.store(now_ms, std::memory_order_relaxed);

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

        // 💡 显式暂停保护：若当前处于显式手动暂停态 (RTSP PAUSE / 本机主动暂停)，丢弃在途音频帧，杜绝污染与延迟
        if (is_manually_paused_.load(std::memory_order_acquire)) {
            return;
        }

        // 💡 修复 Finding 3: 绝不丢弃正常全 0 音频帧，全量入队 JitterBuffer 以维持 RTP 序列号 seq 的绝对连续
        std::vector<uint8_t> pcm_copy(decode_pcm_buf_.data(), decode_pcm_buf_.data() + out_bytes);
        jitter_buffer_.push(seq, ts, std::move(pcm_copy));

        // 3. 💡 修复 Finding 8 & Finding 5: 排空已就绪音频帧并写入 AudioEngine 环形缓冲
        drain_jitter_buffer_locked();
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
            // RTP Sync 同步包 (包含 RTP 时间戳与 NTP 时间戳映射)
            LOGD("收到客户端 Sync 控制包 (len=%zd)", n);
        } else if (payload_type == 86) {
            // 💡 AirTunes/RAOP 重传音频数据包通过控制端口到达，交由 process_audio_packet 处理
            process_audio_packet(buffer.data(), n, payload_type);
        }
    }

    LOGI("RAOP RTP 控制接收线程退出");
}

void RaopRtp::timing_loop() {
    std::vector<uint8_t> buffer(128);

    LOGI("RAOP RTP 授时接收线程已启动");

    while (is_running_.load(std::memory_order_acquire)) {
        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);

        ssize_t n = recvfrom(timing_fd_, buffer.data(), buffer.size(), 0,
                             reinterpret_cast<struct sockaddr*>(&client_addr), &addr_len);
        if (n <= 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
            break;
        }

        // Timing 请求报文固定为 32 字节
        if (n == 32) {
            uint64_t recv_time = get_ntp_timestamp();
            // 回复报文: payload type 设置为 0x53 (带 marker 0xD3)
            buffer[0] = 0x80;
            buffer[1] = 0xD3;

            // 💡 关键修复: 将客户端请求中的 Transmit Timestamp (offset 24..31)
            // 原样写回回复报文的 Originate Timestamp (offset 8..15)，满足 RFC 5905 与 Apple RAOP 严格时钟校准规范
            std::memcpy(&buffer[8], &buffer[24], 8);

            // 写入接收时间戳 (offset 16)
            for (int i = 0; i < 8; ++i) {
                buffer[16 + i] = static_cast<uint8_t>((recv_time >> ((7 - i) * 8)) & 0xFF);
            }

            // 写入发送时间戳 (offset 24)
            uint64_t send_time = get_ntp_timestamp();
            for (int i = 0; i < 8; ++i) {
                buffer[24 + i] = static_cast<uint8_t>((send_time >> ((7 - i) * 8)) & 0xFF);
            }

            sendto(timing_fd_, buffer.data(), 32, 0,
                   reinterpret_cast<struct sockaddr*>(&client_addr), addr_len);
            LOGD("处理并回复客户端 NTP Timing 授时校准包");
        }
    }

    LOGI("RAOP RTP 授时接收线程退出");
}

} // namespace purecast::raop
