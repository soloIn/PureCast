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

bool FlacDecoder::decode_stream(
    JNIEnv* env,
    jobject input_stream,
    void (*on_format_ready)(int, int, int, void*),
    void* user_data
) {
    if (!env || !input_stream) return false;

    jclass is_class = env->GetObjectClass(input_stream);
    jmethodID read_method = env->GetMethodID(is_class, "read", "([BII)I");
    jmethodID skip_method = env->GetMethodID(is_class, "skip", "(J)J");
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

    if (on_format_ready) {
        on_format_ready(static_cast<int>(pFlac->sampleRate),
                        static_cast<int>(pFlac->bitsPerSample),
                        static_cast<int>(pFlac->channels),
                        user_data);
    }

    constexpr size_t FRAMES_PER_READ = 2048;
    std::vector<drflac_int16> pcm_frames(FRAMES_PER_READ * pFlac->channels);
    const size_t bytes_per_frame = pFlac->channels * sizeof(drflac_int16);

    while (AudioEngine::instance().is_streaming()) {
        drflac_uint64 frames_read = drflac_read_pcm_frames_s16(pFlac, FRAMES_PER_READ, pcm_frames.data());
        if (frames_read == 0) {
            LOGI("FLAC 流解码到达 EOF 边界");
            break;
        }

        const uint8_t* raw_bytes = reinterpret_cast<const uint8_t*>(pcm_frames.data());
        size_t bytes_to_write = frames_read * bytes_per_frame;
        size_t written_total = 0;

        while (written_total < bytes_to_write && AudioEngine::instance().is_streaming()) {
            int n = AudioEngine::instance().write_pcm(raw_bytes + written_total, bytes_to_write - written_total);
            if (n > 0) {
                written_total += static_cast<size_t>(n);
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
        }
    }

    drflac_close(pFlac);
    env->DeleteLocalRef(jbuffer);
    return true;
}

} // namespace purecast
