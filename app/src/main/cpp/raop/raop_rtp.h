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
     * 💡 设置音频数据流活动状态变更回调 (true 为暂停/断流，false 为恢复推流)
     */
    using StreamStateCallback = std::function<void(bool is_paused)>;
    void set_stream_state_callback(StreamStateCallback cb) {
        state_cb_ = std::move(cb);
    }

    /**
     * 💡 手动标记暂停或恢复状态 (由 RTSP PAUSE / RECORD 触发)
     */
    void mark_paused_manually(bool paused);

private:
    void data_loop();
    void ctrl_loop();
    void timing_loop();
    void watchdog_loop();

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

    alac::AlacDecoder alac_decoder_;
    RaopJitterBuffer jitter_buffer_;

    // 💡 客户端控制端点与重传状态维护
    struct sockaddr_in client_ctrl_addr_{};
    std::atomic<bool> has_client_ctrl_{false};
    std::atomic<uint16_t> resend_req_seq_{0};
    uint16_t last_data_seq_{0};
    bool first_data_seq_{true};

    // 💡 音频推流无数据看门狗监测 (检测 iPhone 点击暂停停止推流)
    StreamStateCallback state_cb_{nullptr};
    std::atomic<uint64_t> last_audio_packet_time_ms_{0};
    std::atomic<bool> is_stream_paused_{false};
};

} // namespace purecast::raop
