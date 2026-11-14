#include "flac_decoder.h"
#include "dr_flac.h"
#include "audio_engine.h"
#include <vector>
#include <algorithm>
#include <thread>
#include <chrono>

namespace purecast {

struct JavaFlacStreamCtx {
    JNIEnv* env;
    jobject input_stream;
    jmethodID read_method;
    jmethodID skip_method;
    jbyteArray jbuffer;
    size_t jbuffer_capacity;
};

static size_t jvm_flac_read(void* user_data, void* buffer_out, size_t bytes_to_read) {
    auto* ctx = static_cast<JavaFlacStreamCtx*>(user_data);
    if (!ctx || !ctx->env || !ctx->input_stream) return 0;

    size_t total_read = 0;
    while (total_read < bytes_to_read) {
        size_t chunk = std::min(bytes_to_read - total_read, ctx->jbuffer_capacity);
        jint n = ctx->env->CallIntMethod(ctx->input_stream, ctx->read_method, ctx->jbuffer, 0, static_cast<jint>(chunk));
        if (ctx->env->ExceptionCheck() || n <= 0) {
            if (ctx->env->ExceptionCheck()) ctx->env->ExceptionClear();
            break;
        }
        ctx->env->GetByteArrayRegion(ctx->jbuffer, 0, n, static_cast<jbyte*>(buffer_out) + total_read);
        total_read += static_cast<size_t>(n);
    }
    return total_read;
}

static drflac_bool32 jvm_flac_seek(void* user_data, int offset, drflac_seek_origin origin) {
    auto* ctx = static_cast<JavaFlacStreamCtx*>(user_data);
    if (!ctx || !ctx->env || !ctx->input_stream) return DRFLAC_FALSE;

    if (origin == DRFLAC_SEEK_CUR && offset >= 0) {
        jlong to_skip = static_cast<jlong>(offset);
        while (to_skip > 0) {
            jlong skipped = ctx->env->CallLongMethod(ctx->input_stream, ctx->skip_method, to_skip);
            if (ctx->env->ExceptionCheck() || skipped <= 0) {
                if (ctx->env->ExceptionCheck()) ctx->env->ExceptionClear();
                return DRFLAC_FALSE;
            }
            to_skip -= skipped;
        }
        return DRFLAC_TRUE;
    }
    return DRFLAC_FALSE;
}

static std::atomic<int64_t> s_flac_decoded_frames{0};

int64_t FlacDecoder::get_decoded_frames() {
    return s_flac_decoded_frames.load(std::memory_order_relaxed);
}

void FlacDecoder::reset_decoded_frames() {
    s_flac_decoded_frames.store(0, std::memory_order_relaxed);
}

bool FlacDecoder::decode_stream(
    JNIEnv* env,
    jobject input_stream,
    int64_t (*on_format_ready)(int, int, int, int64_t, void*),
    void (*on_first_pcm_written)(void*),
    void* user_data
) {
    if (!env || !input_stream) return false;
    s_flac_decoded_frames.store(0, std::memory_order_relaxed);

    jclass is_class = env->GetObjectClass(input_stream);
    if (!is_class || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID read_method = env->GetMethodID(is_class, "read", "([BII)I");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        read_method = nullptr;
    }
    jmethodID skip_method = env->GetMethodID(is_class, "skip", "(J)J");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        skip_method = nullptr;
    }
    env->DeleteLocalRef(is_class);
    if (!read_method || !skip_method) {
        LOGE("获取 InputStream.read 或 skip 方法失败");
        return false;
    }

    constexpr size_t JBUF_SIZE = 16384;
    jbyteArray jbuffer = env->NewByteArray(static_cast<jsize>(JBUF_SIZE));
    if (!jbuffer) return false;

    JavaFlacStreamCtx ctx{
        env,
        input_stream,
        read_method,
        skip_method,
        jbuffer,
        JBUF_SIZE
    };

    drflac* pFlac = drflac_open(jvm_flac_read, jvm_flac_seek, nullptr, &ctx, nullptr);
    if (!pFlac) {
        LOGE("drflac_open 解析 FLAC 流头部失败");
        env->DeleteLocalRef(jbuffer);
        return false;
    }

    LOGI("FLAC 流解析成功: %u Hz, %u 声道, %u bit, 总采样点: %llu",
         pFlac->sampleRate, pFlac->channels, pFlac->bitsPerSample,
         static_cast<unsigned long long>(pFlac->totalPCMFrameCount));

    const unsigned int target_bits = (pFlac->bitsPerSample > 16) ? 24 : 16;
    int64_t expected_epoch = 0;
    if (on_format_ready) {
        expected_epoch = on_format_ready(
            static_cast<int>(pFlac->sampleRate),
            static_cast<int>(target_bits),
            static_cast<int>(pFlac->channels),
            static_cast<int64_t>(pFlac->totalPCMFrameCount),
            user_data
        );
    }
    if (expected_epoch <= 0) {
        LOGW("FLAC on_format_ready 返回无效世代 (%lld)，中止解码以避免串扰或硬件未就绪写入",
             static_cast<long long>(expected_epoch));
        drflac_close(pFlac);
        env->DeleteLocalRef(jbuffer);
        return false;
    }
    const uint64_t stream_epoch = static_cast<uint64_t>(expected_epoch);
    bool first_pcm_notified = false;
    bool aborted_by_epoch = false;

    constexpr size_t FRAMES_PER_READ = 2048;
    const unsigned int bits = pFlac->bitsPerSample;
    const unsigned int channels = pFlac->channels;

    auto write_pcm_chunk = [&](const uint8_t* data, size_t bytes_to_write) -> bool {
        size_t written_total = 0;
        while (written_total < bytes_to_write && AudioEngine::instance().is_running()) {
            if (AudioEngine::instance().get_current_buffer_epoch() != stream_epoch) {
                LOGW("FLAC 写入挂起期间世代已失效，提前退出: expected=%llu, actual=%llu",
                     static_cast<unsigned long long>(stream_epoch),
                     static_cast<unsigned long long>(AudioEngine::instance().get_current_buffer_epoch()));
                aborted_by_epoch = true;
                return false;
            }
            if (AudioEngine::instance().is_paused()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
            int n = AudioEngine::instance().write_pcm(
                data + written_total,
                bytes_to_write - written_total,
                stream_epoch
            );
            if (n > 0) {
                written_total += static_cast<size_t>(n);
                if (!first_pcm_notified) {
                    first_pcm_notified = true;
                    if (on_first_pcm_written) on_first_pcm_written(user_data);
                }
            } else if (n == -2 || n == -1) {
                LOGW("FLAC %u-bit 写入因世代失效或引擎退出而终止: ret=%d, epoch=%llu",
                     bits > 16 ? 24U : 16U, n, static_cast<unsigned long long>(stream_epoch));
                aborted_by_epoch = true;
                return false;
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
        }
        return written_total == bytes_to_write;
    };

    const bool is_24bit = (bits > 16);
    std::vector<drflac_int32> pcm32_frames(is_24bit ? FRAMES_PER_READ * channels : 0);
    std::vector<drflac_int16> pcm16_frames(is_24bit ? 0 : FRAMES_PER_READ * channels);
    std::vector<uint8_t> pcm24_bytes(is_24bit ? FRAMES_PER_READ * channels * 3 : 0);
    const size_t bytes_per_frame = channels * (is_24bit ? 3U : sizeof(drflac_int16));

    while (AudioEngine::instance().is_running() && !aborted_by_epoch) {
        if (AudioEngine::instance().get_current_buffer_epoch() != stream_epoch) {
            LOGW("FLAC 解码挂起期间世代已失效，提前退出: expected=%llu, actual=%llu",
                 static_cast<unsigned long long>(stream_epoch),
                 static_cast<unsigned long long>(AudioEngine::instance().get_current_buffer_epoch()));
            aborted_by_epoch = true;
            break;
        }
        if (AudioEngine::instance().is_paused()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        const drflac_uint64 frames_read = is_24bit
            ? drflac_read_pcm_frames_s32(pFlac, FRAMES_PER_READ, pcm32_frames.data())
            : drflac_read_pcm_frames_s16(pFlac, FRAMES_PER_READ, pcm16_frames.data());
        if (frames_read == 0) {
            LOGI("FLAC %u-bit 流解码到达 EOF 边界", is_24bit ? 24U : 16U);
            break;
        }

        const uint8_t* raw_bytes = nullptr;
        if (is_24bit) {
            // 💡 dr_flac 将 24-bit 采样在 s32 中高位对齐 (sample24 << 8)
            // 提取纯粹的 3 字节小端序原始采样 (Byte1=Low, Byte2=Mid, Byte3=High)
            const size_t total_samples = frames_read * channels;
            uint8_t* dst = pcm24_bytes.data();
            for (size_t i = 0; i < total_samples; ++i) {
                uint32_t u = static_cast<uint32_t>(pcm32_frames[i]);
                *dst++ = static_cast<uint8_t>((u >> 8) & 0xFF);
                *dst++ = static_cast<uint8_t>((u >> 16) & 0xFF);
                *dst++ = static_cast<uint8_t>((u >> 24) & 0xFF);
            }
            raw_bytes = pcm24_bytes.data();
        } else {
            raw_bytes = reinterpret_cast<const uint8_t*>(pcm16_frames.data());
        }

        if (write_pcm_chunk(raw_bytes, frames_read * bytes_per_frame)) {
            s_flac_decoded_frames.fetch_add(frames_read, std::memory_order_relaxed);
        }
    }

    drflac_close(pFlac);
    env->DeleteLocalRef(jbuffer);
    return !aborted_by_epoch;
}

} // namespace purecast
