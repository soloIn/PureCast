#include "alac_decoder.h"
#include <cstring>
#include <algorithm>

namespace purecast::alac {

namespace {

struct BitBuffer {
    const uint8_t* data;
    size_t size;
    size_t byte_pos{0};
    int bit_pos{7};

    bool has_bits(size_t n) const {
        size_t bits_left = (size - byte_pos) * 8 - (7 - bit_pos);
        return bits_left >= n;
    }

    uint32_t read_bits(int n) {
        uint32_t result = 0;
        for (int i = 0; i < n; ++i) {
            if (byte_pos >= size) return result;
            int bit = (data[byte_pos] >> bit_pos) & 1;
            result = (result << 1) | bit;
            bit_pos--;
            if (bit_pos < 0) {
                bit_pos = 7;
                byte_pos++;
            }
        }
        return result;
    }

    int32_t read_signed_bits(int n) {
        uint32_t val = read_bits(n);
        if (val & (1U << (n - 1))) {
            return (int32_t)(val | (~0U << n));
        }
        return (int32_t)val;
    }

    uint32_t read_rice(int k, int limit) {
        uint32_t q = 0;
        while (q < (uint32_t)limit && read_bits(1) == 0) {
            q++;
        }
        if (q >= (uint32_t)limit) {
            return (q << k) | read_bits(k);
        }
        return (q << k) | read_bits(k);
    }
};

static inline int32_t un_k_zigzag(uint32_t v) {
    return (v & 1) ? -(int32_t)((v + 1) >> 1) : (int32_t)(v >> 1);
}

} // namespace

AlacDecoder::AlacDecoder() = default;
AlacDecoder::~AlacDecoder() = default;

bool AlacDecoder::init(int frame_length, int bit_depth, int channels, int sample_rate) {
    frame_length_ = frame_length;
    bit_depth_ = bit_depth;
    channels_ = channels;
    sample_rate_ = sample_rate;

    for (int i = 0; i < 2; ++i) {
        channel_pred_[i].resize(frame_length_, 0);
        mix_buffer_[i].resize(frame_length_, 0);
    }
    return true;
}

bool AlacDecoder::decode_frame(const uint8_t* in_buffer, size_t in_bytes, uint8_t* out_pcm, size_t& out_bytes) {
    if (!in_buffer || in_bytes == 0 || !out_pcm) {
        out_bytes = 0;
        return false;
    }

    BitBuffer bits{in_buffer, in_bytes, 0, 7};
    int channels = (int)bits.read_bits(3);

    // 💡 AirPlay 1 典型帧为立体声或未压缩立体声
    if (channels != 0 && channels != 1 && channels != 7) {
        // 未知声道模式，清零返回
        out_bytes = 0;
        return false;
    }

    if (channels == 7) {
        // 未压缩直接直通模式 (PCM 16-bit LE)
        size_t samples = std::min((size_t)frame_length_ * 2, (in_bytes - bits.byte_pos) / 2);
        size_t copy_bytes = samples * 2;
        memcpy(out_pcm, in_buffer + bits.byte_pos, copy_bytes);
        out_bytes = copy_bytes;
        return true;
    }

    // 💡 标准 ALAC 差分熵解码与立体声混合解包
    int interlacing_shift = (int)bits.read_bits(8);
    int interlacing_leftweight = (int)bits.read_bits(8);

    int num_channels = 2;
    for (int ch = 0; ch < num_channels; ++ch) {
        int prediction_type = (int)bits.read_bits(4);
        int prediction_quantitization = (int)bits.read_bits(4);
        int ricemodifier = (int)bits.read_bits(3);
        int num_coeffs = (int)bits.read_bits(5);

        int16_t coeffs[32];
        for (int i = 0; i < num_coeffs; ++i) {
            coeffs[i] = (int16_t)bits.read_signed_bits(16);
        }

        int k = 4;
        for (int i = 0; i < frame_length_; ++i) {
            uint32_t val = bits.read_rice(k, 30);
            channel_pred_[ch][i] = un_k_zigzag(val);
            if (val > (15U << k)) k++;
            else if (val < (1U << k) && k > 0) k--;
        }

        // 简易自适应预测器恢复
        if (prediction_type == 0) {
            // 无预测，纯残差
            for (int i = 0; i < frame_length_; ++i) {
                mix_buffer_[ch][i] = channel_pred_[ch][i];
            }
        } else {
            // 线性预测器恢复
            mix_buffer_[ch][0] = channel_pred_[ch][0];
            for (int i = 1; i < frame_length_; ++i) {
                int64_t pred = 0;
                for (int c = 0; c < num_coeffs && (i - 1 - c) >= 0; ++c) {
                    pred += (int64_t)mix_buffer_[ch][i - 1 - c] * coeffs[c];
                }
                pred >>= prediction_quantitization;
                mix_buffer_[ch][i] = (int32_t)(channel_pred_[ch][i] + pred);
            }
        }
    }

    // 💡 立体声声道反交错 (Unmix)
    int16_t* pcm_out = reinterpret_cast<int16_t*>(out_pcm);
    for (int i = 0; i < frame_length_; ++i) {
        int32_t l = mix_buffer_[0][i];
        int32_t r = mix_buffer_[1][i];

        if (interlacing_shift > 0) {
            l = l + r - ((r * interlacing_leftweight) >> interlacing_shift);
            r = l - r;
        }

        // 饱和截断至 16-bit signed
        pcm_out[i * 2 + 0] = (int16_t)std::clamp(l, -32768, 32767);
        pcm_out[i * 2 + 1] = (int16_t)std::clamp(r, -32768, 32767);
    }

    out_bytes = frame_length_ * 2 * sizeof(int16_t);
    return true;
}

} // namespace purecast::alac
