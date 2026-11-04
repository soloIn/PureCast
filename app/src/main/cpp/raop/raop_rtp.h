#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>
#include <thread>
#include <atomic>
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

private:
    void data_loop();
    void ctrl_loop();
    void timing_loop();

    int bind_udp_socket(int preferred_port, int& bound_port);
    static uint64_t get_ntp_timestamp();

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

    std::vector<uint8_t> aes_key_;
    std::vector<uint8_t> aes_iv_;
    std::atomic<bool> has_crypto_{false};

    alac::AlacDecoder alac_decoder_;
    RaopJitterBuffer jitter_buffer_;
};

} // namespace purecast::raop
