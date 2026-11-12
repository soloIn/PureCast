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
    continuous_silence_count_.store(0, std::memory_order_relaxed);

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
    continuous_silence_count_.store(0, std::memory_order_relaxed);
    first_data_seq_ = true;
    last_data_seq_ = 0;
    has_client_ctrl_.store(false, std::memory_order_release);
    resend_req_seq_.store(0, std::memory_order_relaxed);
    last_audio_packet_time_ms_.store(0, std::memory_order_relaxed);
    is_stream_paused_.store(false, std::memory_order_relaxed);
    is_manually_paused_.store(false, std::memory_order_relaxed);
    emitted_first_epoch_.store(0ULL, std::memory_order_release);
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
    std::memset(&client_ctrl_addr_, 0, sizeof(client_ctrl_addr_));
    client_ctrl_addr_.sin_family = AF_INET;
    client_ctrl_addr_.sin_port = htons(client_ctrl_port);
    if (inet_pton(AF_INET, client_ip.c_str(), &client_ctrl_addr_.sin_addr) > 0) {
        has_client_ctrl_.store(true, std::memory_order_release);
        LOGI("显式配置客户端控制端点: %s:%d (用于 RAOP 丢包重传)", client_ip.c_str(), client_ctrl_port);
    }
}

void RaopRtp::send_resend_request(uint16_t first_seq, uint16_t count) {
    if (!has_client_ctrl_.load(std::memory_order_acquire) || ctrl_fd_ < 0 || count == 0) {
        return;
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
                         reinterpret_cast<const struct sockaddr*>(&client_ctrl_addr_),
                         sizeof(client_ctrl_addr_));
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

void RaopRtp::watchdog_loop() {
    LOGI("RAOP RTP 数据流无数据看门狗已启动");
    while (is_running_.load(std::memory_order_relaxed)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        if (!is_running_.load(std::memory_order_relaxed)) break;

        uint64_t last_time = last_audio_packet_time_ms_.load(std::memory_order_relaxed);
        if (last_time > 0 &&
            !is_stream_paused_.load(std::memory_order_relaxed) &&
            !is_manually_paused_.load(std::memory_order_acquire)) {
            uint64_t now = get_monotonic_time_ms();
            bool in_transition = now < track_transition_deadline_ms_.load(std::memory_order_acquire);
            if (!in_transition && now > last_time && (now - last_time) >= 600) {
                // 💡 二次双检与原子 CAS 切换：确保在与新包到达的微秒级竞态中不产生状态颠簸
                bool expected = false;
                if (is_stream_paused_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
                    uint64_t recheck_time = last_audio_packet_time_ms_.load(std::memory_order_relaxed);
                    StreamStateCallback cb_copy;
                    uint64_t pause_epoch = 0ULL;
                    bool should_notify = false;
                    {
                        std::lock_guard<std::mutex> lock(state_mutex_);
                        // 💡 竞态防抖三检：在获取锁后再次校验 is_stream_paused_ 与时间戳
                        if (recheck_time == last_time &&
                            is_stream_paused_.load(std::memory_order_acquire) &&
                            !is_manually_paused_.load(std::memory_order_acquire)) {
                            LOGI("⚠️ 检测到 RTP 音频流停顿超 600ms (iPhone 暂停推流)，C++ 立即无锁静音并通知隐式暂停");
                            pause_epoch = AudioEngine::instance().emergency_mute_and_flush(FlushReason::EXPLICIT_PAUSE);
                            cb_copy = state_cb_;
                            should_notify = true;
                        } else if (recheck_time != last_time) {
                            is_stream_paused_.store(false, std::memory_order_release);
                        } else {
                            LOGI("🔄 监测到 data_loop 已在临界区恢复推流，放弃分发迟到的暂停回调");
                        }
                    }
                    if (should_notify && cb_copy) {
                        cb_copy(true, true, pause_epoch);
                    }
                }
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
    is_stream_paused_.store(paused, std::memory_order_release);
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
    std::vector<uint8_t> pcm_frame(16384); // 💡 支持最大 4096 采样 * 4 字节，杜绝内存越界
    std::vector<uint8_t> decrypted_payload(2048);

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

        // RTP 包至少 12 字节头部
        if (n < 12) continue;

        uint8_t payload_type = buffer[1] & 0x7F;
        uint8_t expected_pt = audio_payload_type_.load(std::memory_order_relaxed);

        // 💡 AirTunes/RAOP 音频包类型:
        // expected_pt / 96: 标准实时音频数据包 (ALAC 或动态 L16/PCM)
        // 86: 重传音频数据包 (带有额外 4 字节前导头)
        // 10: RFC 3551 标准静态 L16 双声道 PCM (常用于非 Apple Music / 视频第三方投屏)
        if (payload_type != expected_pt && payload_type != 96 && payload_type != 86 && payload_type != 10) {
            static uint32_t unknown_drop_count = 0;
            if (unknown_drop_count++ % 100 == 0) {
                LOGW("data_loop: 收到未知 payload_type=%u (期望=%u, 96, 86, 10), len=%zd, 忽略",
                     payload_type, expected_pt, n);
            }
            continue;
        }

        // 💡 采样打印接收到的音频包，确保链路可观测
        static uint32_t audio_pkt_count = 0;
        uint32_t cur_cnt = audio_pkt_count++;
        if (cur_cnt < 10 || cur_cnt % 500 == 0) {
            LOGI("data_loop: 收到音频包 [%u] pt=%u, len=%zd, has_crypto=%d",
                 cur_cnt, payload_type, n, has_crypto_.load(std::memory_order_relaxed));
        }

        // 💡 更新最近音频数据包时间戳供看门狗超时检测 (绝不可在此盲目唤醒播放，须待解码确定非静音后方可唤醒)
        uint64_t now_ms = get_monotonic_time_ms();
        last_audio_packet_time_ms_.store(now_ms, std::memory_order_relaxed);

        const uint8_t* pktp = buffer.data();
        ssize_t plen = n;

        if (payload_type == 86) {
            // 重传音频包跳过 4 字节
            pktp += 4;
            plen -= 4;
        }

        if (plen < 12) continue;

        uint16_t seq = (static_cast<uint16_t>(pktp[2]) << 8) | pktp[3];
        uint32_t ts = (static_cast<uint32_t>(pktp[4]) << 24) |
                      (static_cast<uint32_t>(pktp[5]) << 16) |
                      (static_cast<uint32_t>(pktp[6]) << 8)  |
                      pktp[7];

        // 💡 实时丢包空洞检测: 发现主干音频包序列号跳跃时，立即向 iPhone 发送重传请求
        if (payload_type == 96) {
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
                if (diff > 0) {
                    last_data_seq_ = seq;
                }
            }
        }

        const uint8_t* payload = pktp + 12;
        size_t payload_len = static_cast<size_t>(plen - 12);
        if (payload_len < 16) continue;

        // 1. AES-128-CBC 解密
        // 💡 AirPlay 1 规范要求:
        // 每个音频包独立初始化 CBC 向量 (使用 ANNOUNCE 协商的 aes_iv_)
        // 解密长度为 16 字节整倍数 (payload_len & ~0xf)，尾部余数保持明文拷贝
        // 💡 针对 RFC 3551 L16 PCM (pt=10) 静态标准包，无需 AES 解密 (保持明文网络序传输)
        decrypted_payload.resize(payload_len);
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
        size_t out_bytes = 0;
        AudioCodec current_codec = audio_codec_.load(std::memory_order_acquire);

        if (current_codec == AudioCodec::PCM || payload_type == 10) {
            // 💡 L16 未压缩线性 PCM 处理:
            // 遵循 RFC 3551 规范，L16 采样为 16-bit 有符号大端网络字节序 (Big-Endian)
            // 需逐采样转换为本地架构 Little-Endian (S16_LE) 以便 AudioEngine 直通播放
            size_t valid_bytes = payload_len & ~1;
            if (valid_bytes > pcm_frame.size()) {
                pcm_frame.resize(valid_bytes);
            }
            const uint8_t* src = decrypted_payload.data();
            uint8_t* dst = pcm_frame.data();
            for (size_t i = 0; i < valid_bytes; i += 2) {
                dst[i] = src[i + 1];     // 低位
                dst[i + 1] = src[i];     // 高位
            }
            out_bytes = valid_bytes;
        } else {
            // 💡 ALAC 实时无损解码为 16-bit 44.1kHz PCM (S16_LE)
            if (!alac_decoder_.decode_frame(decrypted_payload.data(), payload_len, pcm_frame.data(), out_bytes)) {
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
            if (n <= 64 && (cur_cnt % 500 == 0 || cur_cnt < 5)) {
                char hex_buf[70] = {0};
                for (size_t i = 0; i < std::min(payload_len, static_cast<size_t>(32)); ++i) {
                    snprintf(hex_buf + i * 2, sizeof(hex_buf) - i * 2, "%02x", payload[i]);
                }
                LOGI("data_loop: 短音频包原始负载 [pkt=%u, len=%zu]: %s", cur_cnt, payload_len, hex_buf);
            }

            // 💡 全帧深度静音感知与诊断: 采用 64 位宽并行比对高速扫描整个 PCM 帧，确保 100% 全为零始判定为静音
            bool is_silence = true;
            const uint64_t* p64 = reinterpret_cast<const uint64_t*>(pcm_frame.data());
            const size_t words64 = out_bytes / sizeof(uint64_t);
            for (size_t i = 0; i < words64; ++i) {
                if (p64[i] != 0ULL) {
                    is_silence = false;
                    break;
                }
            }
            if (is_silence) {
                for (size_t i = words64 * sizeof(uint64_t); i < out_bytes; ++i) {
                    if (pcm_frame[i] != 0) {
                        is_silence = false;
                        break;
                    }
                }
            }

            if (is_silence) {
                // 💡 关键双向同步防抖: 在切歌豁免期内忽略静音判定并重置计数，防止切歌期间累计的静音帧在豁免期结束后立即突发误判为 PAUSED
                bool in_transition = get_monotonic_time_ms() < track_transition_deadline_ms_.load(std::memory_order_acquire);
                if (in_transition) {
                    continuous_silence_count_.store(0, std::memory_order_relaxed);
                    skipped_silence_in_transition_.store(true, std::memory_order_relaxed);
                    continue;
                }
                uint32_t sil_cnt = continuous_silence_count_.fetch_add(1, std::memory_order_relaxed) + 1;
                // 门限由 25 帧提高至 180 帧 (~1.44 秒)
                // 彻底杜绝音乐中的自然休止符、前奏淡入、网络瞬间微抖动误触发 PAUSED 导致的大屏闪烁与焦点抢占
                if (sil_cnt >= 180 && !is_stream_paused_.load(std::memory_order_relaxed)) {
                    bool expected = false;
                    if (is_stream_paused_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
                        uint64_t pause_ep = AudioEngine::instance().emergency_mute_and_flush(FlushReason::EXPLICIT_PAUSE);
                        LOGI("⏸️ 检测到连续静音保活帧 (持续 %u 帧 ~1.44s，iPhone 暂停推流)，C++ 立即静音并上报隐式暂停", sil_cnt);
                        emit_state_callback(true, true, pause_ep);
                    }
                }
                if (sil_cnt == 200 || sil_cnt % 1000 == 0) {
                    float vol = current_volume_db_.load(std::memory_order_relaxed);
                    LOGW("⚠️ ALAC/PCM 连续解码产出全静音帧 (已持续 %u 帧, 当前包长=%zd, 客户端音量=%.2f dB)",
                         sil_cnt, n, vol);
                }
                // 💡 关键保护：静音保活帧绝不推入 JitterBuffer 和 AudioEngine 环形缓冲区！
                // 避免在暂停期间以 125fps 持续将静音包塞满 RingBuffer 导致高水位丢包与恢复时的吞音延迟
                continue;
            } else {
                uint32_t prev_sil_cnt = continuous_silence_count_.exchange(0, std::memory_order_relaxed);
                if (prev_sil_cnt > 0) {
                    LOGI("🔊 检测到有效音频信号恢复 (结束了连续 %u 帧静音)", prev_sil_cnt);
                }

                // 💡 关键时钟基准重置与状态同步：
                // 1. 仅当 TV 端与远端均未主动暂停时，才允许有效音频解除隐式静默暂停并唤醒；
                // 2. 非暂停状态下若此前有隐式暂停或切歌过渡静音断层，重置 JitterBuffer 并更新 epoch。
                const bool tv_or_manual_paused =
                    is_manually_paused_.load(std::memory_order_acquire) || AudioEngine::instance().is_paused();
                bool was_paused = !tv_or_manual_paused && is_stream_paused_.load(std::memory_order_relaxed);
                bool had_transition_silence = !tv_or_manual_paused &&
                    skipped_silence_in_transition_.exchange(false, std::memory_order_acq_rel);

                if (was_paused || had_transition_silence) {
                    if (was_paused) {
                        is_stream_paused_.store(false, std::memory_order_release);
                        LOGI("▶️ 检测到有效音频信号 (iPhone 恢复推流)，重置 JitterBuffer 并同步 TV 端状态机为 PLAYING");
                    }
                    if (had_transition_silence) {
                        LOGI("🔄 切歌过渡期丢弃静音帧后首个有效音频帧到达，重置 JitterBuffer 游标消除序列号断层");
                    }
                    jitter_buffer_.reset();
                    uint64_t next_ep = AudioEngine::instance().emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
                    active_epoch_.store(next_ep, std::memory_order_release);
                    if (was_paused) {
                        AudioEngine::instance().resume_stream();
                        emit_state_callback(false, false, next_ep);
                    }
                }
            }

            // 💡 若处于隐式/手动暂停或 TV 端 / 音频焦点显式暂停态，严禁越权调用 resume_stream 并在暂停期间丢弃音频帧防止灌满 RingBuffer
            if (is_stream_paused_.load(std::memory_order_acquire) ||
                is_manually_paused_.load(std::memory_order_acquire) ||
                AudioEngine::instance().is_paused()) {
                continue;
            }

            std::vector<uint8_t> pcm_copy(pcm_frame.data(), pcm_frame.data() + out_bytes);
            jitter_buffer_.push(seq, ts, std::move(pcm_copy));

            // 3. 将连续就绪的 PCM 帧出队并携带 active_epoch_ 推入 SPSC 环形缓冲区
            std::vector<uint8_t> ready_frame;
            while (jitter_buffer_.pop(ready_frame)) {
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
                    LOGW("RAOP write_pcm 返回错误或世代过期: ret=%d, epoch=%llu",
                         written, static_cast<unsigned long long>(cur_epoch));
                    break;
                }
                if (cur_cnt < 5) {
                    LOGI("AudioEngine 写入首批 PCM 帧: bytes=%zu, ret=%d, epoch=%llu",
                         ready_frame.size(), written, static_cast<unsigned long long>(cur_epoch));
                }
            }
        }
    }

    LOGI("RAOP RTP 数据接收线程退出");
}

void RaopRtp::ctrl_loop() {
    std::vector<uint8_t> buffer(512);

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

        // 💡 动态学习客户端控制端点地址与端口，确保丢包重传通道畅通
        if (!has_client_ctrl_.load(std::memory_order_relaxed)) {
            client_ctrl_addr_ = client_addr;
            has_client_ctrl_.store(true, std::memory_order_release);
            char ip_str[INET_ADDRSTRLEN] = {0};
            inet_ntop(AF_INET, &(client_addr.sin_addr), ip_str, INET_ADDRSTRLEN);
            LOGI("通过控制端口学习到客户端端点: %s:%d", ip_str, ntohs(client_addr.sin_port));
        }

        uint8_t payload_type = buffer[1] & 0x7F;
        if (payload_type == 84) {
            // RTP Sync 同步包 (包含 RTP 时间戳与 NTP 时间戳映射)
            LOGD("收到客户端 Sync 控制包 (len=%zd)", n);
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
