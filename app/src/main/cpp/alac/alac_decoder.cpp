#include "alac_decoder.h"
#include <android/log.h>
#include <cstring>

#define ALAC_TAG "PureCastALAC"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, ALAC_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, ALAC_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, ALAC_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ALAC_TAG, __VA_ARGS__)

namespace purecast::alac {

AlacDecoder::AlacDecoder() {
    init(nullptr);
}

AlacDecoder::~AlacDecoder() {
    reset();
}

void AlacDecoder::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (alac_) {
        alac_free(alac_);
        alac_ = nullptr;
    }
}

bool AlacDecoder::init(const int32_t* fmtp) {
    std::lock_guard<std::mutex> lock(mutex_);

    // 💡 AirPlay 1 标准 44.1kHz / 16-bit 双声道默认 fmtp 配置
    static const int32_t default_fmtp[12] = {
        96,    // fmtp[0]: payload type
        352,   // fmtp[1]: frame_size (max samples per frame)
        0,     // fmtp[2]: 7a
        16,    // fmtp[3]: sample_size (bit depth)
        40,    // fmtp[4]: rice_historymult
        10,    // fmtp[5]: rice_initialhistory
        14,    // fmtp[6]: rice_kmodifier
        2,     // fmtp[7]: channels
        255,   // fmtp[8]: 80
        0,     // fmtp[9]: 82 (max sample size)
        0,     // fmtp[10]: 86 (bitrate)
        44100  // fmtp[11]: sample_rate
    };

    const int32_t* active_fmtp = fmtp ? fmtp : default_fmtp;

    int frame_size = active_fmtp[1];
    int sample_size = active_fmtp[3];
    int channels = active_fmtp[7] > 0 ? active_fmtp[7] : 2;
    int rate = active_fmtp[11];

    // 💡 修复 Finding 1 (CWE-122): AirPlay 1 ALAC 严格仅支持单声道 (1) 或立体声 (2)
    // 限制 channels 只能为 1 或 2，且单帧最大输出字节数不得超过 16384 (4096 samples * 2 ch * 2 bytes)，杜绝堆缓冲区溢出
    if (frame_size <= 0 || frame_size > 4096 || (channels != 1 && channels != 2) ||
        (frame_size * channels * 2 > 16384) || rate < 8000 || rate > 192000) {
        LOGE("ALAC 参数超出合法安全范围: frame_size=%d, channels=%d, rate=%d", frame_size, channels, rate);
        return false;
    }

    if (sample_size != 16) {
        LOGE("ALAC 目前仅支持 16-bit 线性 PCM (当前: %d-bit)", sample_size);
        return false;
    }

    if (alac_) {
        alac_free(alac_);
        alac_ = nullptr;
    }

    alac_ = alac_create(sample_size, channels);
    if (!alac_) {
        LOGE("创建底层 ALAC 解码实例失败");
        return false;
    }

    // 💡 注入来自 RTSP SDP a=fmtp 的核心解码参数
    alac_->setinfo_max_samples_per_frame = static_cast<uint32_t>(frame_size);
    alac_->setinfo_7a = static_cast<uint8_t>(active_fmtp[2]);
    alac_->setinfo_sample_size = static_cast<uint8_t>(sample_size);
    alac_->setinfo_rice_historymult = static_cast<uint8_t>(active_fmtp[4]);
    alac_->setinfo_rice_initialhistory = static_cast<uint8_t>(active_fmtp[5]);
    alac_->setinfo_rice_kmodifier = static_cast<uint8_t>(active_fmtp[6]);
    alac_->setinfo_7f = static_cast<uint8_t>(channels);
    alac_->setinfo_80 = static_cast<uint16_t>(active_fmtp[8]);
    alac_->setinfo_82 = static_cast<uint32_t>(active_fmtp[9]);
    alac_->setinfo_86 = static_cast<uint32_t>(active_fmtp[10]);
    alac_->setinfo_8a_rate = static_cast<uint32_t>(rate);

    // 💡 为残差误差与解压输出分配工作内存 (避免每帧解码动态 malloc)
    alac_allocate_buffers(alac_);

    frame_length_ = frame_size;
    bit_depth_ = sample_size;
    channels_ = channels;
    sample_rate_ = rate;

    LOGI("ALAC 解码器初始化成功: frame=%d, depth=%d-bit, ch=%d, rate=%d Hz",
         frame_length_, bit_depth_, channels_, sample_rate_);
    return true;
}

bool AlacDecoder::init(int frame_length, int bit_depth, int channels, int sample_rate) {
    int32_t custom_fmtp[12] = {
        96,
        frame_length,
        0,
        bit_depth,
        40,
        10,
        14,
        channels,
        255,
        0,
        0,
        sample_rate
    };
    return init(custom_fmtp);
}

bool AlacDecoder::decode_frame(const uint8_t* in_buffer, size_t in_bytes, uint8_t* out_pcm, size_t& out_bytes) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!alac_ || !in_buffer || in_bytes == 0 || !out_pcm) {
        out_bytes = 0;
        return false;
    }

    int outsize = 0;
    // 💡 显式设置输入缓冲区的硬边界，防范网络乱序/残包时 ALAC 解码器越界读取
    alac_->input_buffer_end = const_cast<unsigned char*>(in_buffer) + in_bytes;
    // 💡 遵循 Apple ALAC 标准解码时序解压单帧 RTP Payload
    alac_decode_frame(alac_, const_cast<unsigned char*>(in_buffer), out_pcm, &outsize);

    if (outsize <= 0) {
        LOGW("ALAC 解码单帧失败: in_bytes=%zu, outsize=%d", in_bytes, outsize);
        out_bytes = 0;
        return false;
    }

    out_bytes = static_cast<size_t>(outsize);
    return true;
}

} // namespace purecast::alac
