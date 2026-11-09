#pragma once

#include <jni.h>
#include <cstdint>

namespace purecast {

class FlacDecoder {
public:
    // 解码从 Java InputStream 输入的 FLAC 流并实时直通到 AudioEngine
    static bool decode_stream(
        JNIEnv* env,
        jobject input_stream,
        void (*on_format_ready)(int sample_rate, int bit_depth, int channels, int64_t total_pcm_frames, void* user_data),
        void* user_data
    );

    static int64_t get_decoded_frames();
    static void reset_decoded_frames();
};

} // namespace purecast
