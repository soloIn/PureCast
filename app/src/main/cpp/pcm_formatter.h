#pragma once

#include <cstdint>
#include <cstddef>
#include <cstring>

namespace purecast {

/**
 * 💡 UAC 物理音频子帧补零与高位对齐格式化器 (PCM Formatter)
 * 解决 Android 48kHz SRC 绕过后的硬件位深对齐关键问题：
 * 
 * 索尼 ZX300A 及绝大多数高端 USB DAC 声明的物理等时子帧通常为 3 字节 (S24_3LE) 或 4 字节 (S32_LE)。
 * 当上游输入 16-bit 无损音频 (CD 规格 44.1kHz / 16bit) 时：
 * 1. 若 DAC 端点为 S24_3LE: 将 16-bit 采样左移 8 位对齐至高位，低 8 位补零 (sample_16 << 8)。
 * 2. 若 DAC 端点为 S32_LE: 将 16-bit 采样左移 16 位对齐至高位，低 16 位补零 (sample_16 << 16)。
 *    ⚠️ 严禁仅左移 8 位导致音量严重衰减 48dB！
 */
class PcmFormatter {
public:
    enum class OutputFormat {
        S16_LE,   // 2 字节物理子帧
        S24_3LE,  // 3 字节物理子帧 (打包 24-bit)
        S32_LE    // 4 字节物理子帧 (32-bit slot)
    };

    /**
     * 将 16-bit PCM 打包转换为指定物理子帧格式
     * @param src_pcm16 输入的 16-bit 线性 PCM 数据 (Little-Endian)
     * @param src_bytes 输入字节数 (必须为 2 的倍数)
     * @param dst_buffer 输出目标缓冲区
     * @param format 目标物理子帧格式
     * @return 转换后写入的目标字节数
     */
    static size_t format_16bit_to_target(
        const uint8_t* src_pcm16,
        size_t src_bytes,
        uint8_t* dst_buffer,
        OutputFormat format
    ) {
        if (!src_pcm16 || !dst_buffer || src_bytes < 2) return 0;

        const size_t num_samples = src_bytes / sizeof(int16_t);
        const auto* in_samples = reinterpret_cast<const int16_t*>(src_pcm16);

        switch (format) {
            case OutputFormat::S16_LE: {
                // 原样直通
                std::memcpy(dst_buffer, src_pcm16, src_bytes);
                return src_bytes;
            }

            case OutputFormat::S24_3LE: {
                // 💡 16-bit 放入 24-bit slot: sample_16 << 8
                // 小端序存储: Byte0=0x00, Byte1=Low8, Byte2=High8
                uint8_t* out = dst_buffer;
                for (size_t i = 0; i < num_samples; ++i) {
                    const int16_t s = in_samples[i];
                    *out++ = 0x00; // 最低有效位补零
                    *out++ = static_cast<uint8_t>(s & 0xFF);
                    *out++ = static_cast<uint8_t>((s >> 8) & 0xFF);
                }
                return num_samples * 3;
            }

            case OutputFormat::S32_LE: {
                // 💡 16-bit 放入 32-bit slot: sample_16 << 16 (高位对齐，低16位补零)
                // 小端序存储: Byte0=0x00, Byte1=0x00, Byte2=Low8, Byte3=High8
                auto* out32 = reinterpret_cast<int32_t*>(dst_buffer);
                for (size_t i = 0; i < num_samples; ++i) {
                    const int32_t s = static_cast<int32_t>(in_samples[i]);
                    out32[i] = s << 16;
                }
                return num_samples * 4;
            }
        }
        return 0;
    }

    /**
     * 将 24-bit PCM 打包转换为指定物理子帧格式
     * @param src_pcm24 输入的 24-bit 打包 PCM 数据 (每采样 3 字节 Little-Endian)
     * @param src_bytes 输入字节数 (必须为 3 的倍数)
     * @param dst_buffer 输出目标缓冲区
     * @param format 目标物理子帧格式
     * @return 转换后写入的目标字节数
     */
    static size_t format_24bit_to_target(
        const uint8_t* src_pcm24,
        size_t src_bytes,
        uint8_t* dst_buffer,
        OutputFormat format
    ) {
        if (!src_pcm24 || !dst_buffer || src_bytes < 3) return 0;

        const size_t num_samples = src_bytes / 3;

        switch (format) {
            case OutputFormat::S24_3LE: {
                // 原样直通
                std::memcpy(dst_buffer, src_pcm24, src_bytes);
                return src_bytes;
            }

            case OutputFormat::S32_LE: {
                // 💡 24-bit 放入 32-bit slot: sample_24 << 8
                // 小端序存储: Byte0=0x00, Byte1=B0, Byte2=B1, Byte3=B2
                const uint8_t* in = src_pcm24;
                uint8_t* out = dst_buffer;
                for (size_t i = 0; i < num_samples; ++i) {
                    *out++ = 0x00;        // 最低有效位补零
                    *out++ = *in++;       // Byte0
                    *out++ = *in++;       // Byte1
                    *out++ = *in++;       // Byte2
                }
                return num_samples * 4;
            }

            case OutputFormat::S16_LE: {
                // 截断高 16 位 (不推荐但兜底)
                const uint8_t* in = src_pcm24;
                auto* out16 = reinterpret_cast<int16_t*>(dst_buffer);
                for (size_t i = 0; i < num_samples; ++i) {
                    const uint8_t b1 = in[1];
                    const uint8_t b2 = in[2];
                    in += 3;
                    out16[i] = static_cast<int16_t>(b1 | (b2 << 8));
                }
                return num_samples * 2;
            }
        }
        return 0;
    }
};

} // namespace purecast
