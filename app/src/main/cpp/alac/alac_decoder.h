#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace purecast::alac {

/**
 * 💡 AlacDecoder: Apple Lossless (ALAC) 整数音频解码器
 * 针对 16-bit / 44.1kHz 双声道立体声优化，提供零动态内存开销的实时 PCM 解包还原。
 */
class AlacDecoder {
public:
    AlacDecoder();
    ~AlacDecoder();

    /**
     * 初始化 ALAC 解码器参数 (从 RTSP fmtp 行提取或使用标准 44.1kHz/16-bit 默认值)
     * @param frame_length 默认每包采样帧数 (通常为 352 帧)
     * @param bit_depth 位深 (16)
     * @param channels 声道数 (2)
     * @param sample_rate 采样率 (44100)
     */
    bool init(int frame_length = 352, int bit_depth = 16, int channels = 2, int sample_rate = 44100);

    /**
     * 解码一帧 ALAC 音频包还原为交错 16-bit PCM 字节
     * @param in_buffer 加密解密后的 ALAC 二进制净荷
     * @param in_bytes 输入字节大小
     * @param out_pcm 输出 PCM 缓冲区 (大小至少 frame_length * channels * 2 字节)
     * @param out_bytes 解码写出的实际 PCM 字节数
     * @return true 成功，false 失败
     */
    bool decode_frame(const uint8_t* in_buffer, size_t in_bytes, uint8_t* out_pcm, size_t& out_bytes);

private:
    int frame_length_{352};
    int bit_depth_{16};
    int channels_{2};
    int sample_rate_{44100};

    // 内部解码工作缓冲区 (避免多线程或循环中重复 malloc)
    std::vector<int32_t> channel_pred_[2];
    std::vector<int32_t> mix_buffer_[2];
};

} // namespace purecast::alac
