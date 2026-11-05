#pragma once

#include <cstdint>
#include <cstddef>
#include <mutex>
#include "alac.h"

namespace purecast::alac {

/**
 * 💡 AlacDecoder: Apple Lossless (ALAC) 工业级音频解码器封装
 * 基于经典稳定验证的 Apple ALAC 解码实现，支持：
 * 1. 动态 Rice-Golomb 熵解码与历史预测修正
 * 2. 逐采样自适应 FIR 预测滤波器更新 (predictor_decompress_fir_adapt)
 * 3. 左右声道加权解交错 (Unmix)，输出标准 16-bit S16_LE 双声道 PCM
 * 4. 完美对接 RTSP ANNOUNCE 中的 a=fmtp 参数
 */
class AlacDecoder {
public:
    AlacDecoder();
    ~AlacDecoder();

    // 禁用拷贝与赋值以确保内存安全
    AlacDecoder(const AlacDecoder&) = delete;
    AlacDecoder& operator=(const AlacDecoder&) = delete;

    /**
     * 💡 初始化 ALAC 解码器参数
     * @param fmtp 指向包含 12 个整数的 fmtp 参数数组 (从 RTSP SDP a=fmtp 提取)；
     *             若为 nullptr，则自动采用标准 44.1kHz/16-bit 默认参数
     * @return true 成功，false 失败
     */
    bool init(const int32_t* fmtp = nullptr);

    /**
     * 💡 兼容老接口的重载初始化
     */
    bool init(int frame_length, int bit_depth, int channels, int sample_rate);

    /**
     * 💡 解码一帧 ALAC 音频包还原为交错 16-bit PCM 字节 (S16_LE)
     * @param in_buffer AES-128-CBC 解密后的 ALAC 二进制净荷
     * @param in_bytes 输入字节大小
     * @param out_pcm 输出 PCM 缓冲区 (大小至少 frame_length * channels * 2 字节)
     * @param out_bytes 解码写出的实际 PCM 字节数 (成功通常为 1408 字节)
     * @return true 成功，false 失败
     */
    bool decode_frame(const uint8_t* in_buffer, size_t in_bytes, uint8_t* out_pcm, size_t& out_bytes);

    void reset();

    int get_frame_length() const { return frame_length_; }
    int get_sample_rate() const { return sample_rate_; }
    int get_channels() const { return channels_; }
    int get_bit_depth() const { return bit_depth_; }

private:
    mutable std::mutex mutex_;
    alac_file* alac_{nullptr};

    int frame_length_{352};
    int sample_rate_{44100};
    int channels_{2};
    int bit_depth_{16};
};

} // namespace purecast::alac
