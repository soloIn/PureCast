#pragma once

#include <jni.h>

namespace purecast {

class FlacDecoder {
public:
    // 解码从 Java InputStream 输入的 FLAC 流并实时直通到 AudioEngine
    static bool decode_stream(
        JNIEnv* env,
        jobject input_stream,
        void (*on_format_ready)(int sample_rate, int bit_depth, int channels, void* user_data),
        void* user_data
    );
};

} // namespace purecast
