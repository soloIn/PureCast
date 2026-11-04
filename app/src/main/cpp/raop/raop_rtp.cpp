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

    LOGI("RAOP RTP 服务启动成功: Data=%d, Control=%d, Timing=%d", data_port_, ctrl_port_, timing_port_);
    return true;
}

void RaopRtp::stop() {
    if (!is_running_.exchange(false)) {
        return;
    }

    LOGI("正在停止 RAOP RTP 接收引擎...");

    // 关闭 Socket 唤醒接收阻塞
    if (data_fd_ >= 0) {
        shutdown(data_fd_, SHUT_RDWR);
        close(data_fd_);
        data_fd_ = -1;
    }
    if (ctrl_fd_ >= 0) {
        shutdown(ctrl_fd_, SHUT_RDWR);
        close(ctrl_fd_);
        ctrl_fd_ = -1;
    }
    if (timing_fd_ >= 0) {
        shutdown(timing_fd_, SHUT_RDWR);
        close(timing_fd_);
        timing_fd_ = -1;
    }

    if (data_thread_.joinable()) data_thread_.join();
    if (ctrl_thread_.joinable()) ctrl_thread_.join();
    if (timing_thread_.joinable()) timing_thread_.join();

    jitter_buffer_.reset();
    has_crypto_.store(false, std::memory_order_release);
    LOGI("RAOP RTP 接收引擎已完全停止");
}

void RaopRtp::set_crypto_key(const std::vector<uint8_t>& aes_key, const std::vector<uint8_t>& aes_iv) {
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

    while (is_running_.load(std::memory_order_relaxed)) {
        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);

        ssize_t n = recvfrom(data_fd_, buffer.data(), buffer.size(), 0,
                             reinterpret_cast<struct sockaddr*>(&client_addr), &addr_len);
        if (n <= 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break; // Socket 已关闭
        }

        // RTP 包至少 12 字节头部
        if (n < 12) continue;

        uint8_t payload_type = buffer[1] & 0x7F;
        // 💡 AirTunes/RAOP 音频包类型:
        // 0x60 (96): 标准实时音频数据包
        // 0x56 (86): 重传音频数据包 (带有额外 4 字节前导头)
        // 0x54 (84): Sync 控制包 (非音频数据，直接忽略)
        if (payload_type != 96 && payload_type != 86) {
            continue;
        }

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

        const uint8_t* payload = pktp + 12;
        size_t payload_len = static_cast<size_t>(plen - 12);
        if (payload_len < 16) continue;

        // 1. AES-128-CBC 解密
        // 💡 AirPlay 1 规范要求:
        // 每个音频包独立初始化 CBC 向量 (使用 ANNOUNCE 协商的 aes_iv_)
        // 解密长度为 16 字节整倍数 (payload_len & ~0xf)，尾部余数保持明文拷贝
        decrypted_payload.resize(payload_len);
        memcpy(decrypted_payload.data(), payload, payload_len);

        if (has_crypto_.load(std::memory_order_relaxed)) {
            size_t aeslen = payload_len & ~0xf;
            if (aeslen > 0) {
                uint8_t iv[16];
                memcpy(iv, aes_iv_.data(), 16);
                struct AES_ctx ctx;
                AES_init_ctx_iv(&ctx, aes_key_.data(), iv);
                AES_CBC_decrypt_buffer(&ctx, decrypted_payload.data(), aeslen);
            }
        }

        // 2. ALAC 实时无损解码为 16-bit 44.1kHz PCM (S16_LE)
        size_t out_bytes = 0;
        if (alac_decoder_.decode_frame(decrypted_payload.data(), payload_len, pcm_frame.data(), out_bytes)) {
            if (out_bytes > 0) {
                std::vector<uint8_t> pcm_copy(pcm_frame.data(), pcm_frame.data() + out_bytes);
                jitter_buffer_.push(seq, ts, std::move(pcm_copy));

                // 3. 将连续就绪的 PCM 帧出队并推入 SPSC 环形缓冲区
                std::vector<uint8_t> ready_frame;
                while (jitter_buffer_.pop(ready_frame)) {
                    AudioEngine::instance().write_pcm(ready_frame.data(), ready_frame.size());
                }
            }
        }
    }

    LOGI("RAOP RTP 数据接收线程退出");
}

void RaopRtp::ctrl_loop() {
    std::vector<uint8_t> buffer(512);

    LOGI("RAOP RTP 控制接收线程已启动");

    while (is_running_.load(std::memory_order_relaxed)) {
        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);

        ssize_t n = recvfrom(ctrl_fd_, buffer.data(), buffer.size(), 0,
                             reinterpret_cast<struct sockaddr*>(&client_addr), &addr_len);
        if (n <= 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }

        if (n < 4) continue;
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

    while (is_running_.load(std::memory_order_relaxed)) {
        struct sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);

        ssize_t n = recvfrom(timing_fd_, buffer.data(), buffer.size(), 0,
                             reinterpret_cast<struct sockaddr*>(&client_addr), &addr_len);
        if (n <= 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }

        // Timing 请求报文固定为 32 字节
        if (n == 32) {
            uint64_t recv_time = get_ntp_timestamp();
            // 回复报文: payload type 设置为 0x53 (带 marker 0xD3)
            buffer[0] = 0x80;
            buffer[1] = 0xD3;

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
