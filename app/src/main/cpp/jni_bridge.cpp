#include <jni.h>
#include <string>
#include "audio_engine.h"
#include "flac_decoder.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_getLibusbVersion(
    JNIEnv *env,
    jobject /* thiz */) {
    std::string ver = purecast::AudioEngine::instance().get_libusb_version();
    return env->NewStringUTF(ver.c_str());
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_initNativeContext(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().init_context();
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_openDevice(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jint fd,
    jint sample_rate,
    jint bit_depth,
    jint channels) {
    return purecast::AudioEngine::instance().open_device(fd, sample_rate, bit_depth, channels);
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_writePcm(
    JNIEnv *env,
    jobject /* thiz */,
    jbyteArray data,
    jint offset,
    jint length) {
    if (!data || length <= 0) return 0;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;

    int written = purecast::AudioEngine::instance().write_pcm(
        reinterpret_cast<const uint8_t*>(bytes + offset),
        static_cast<size_t>(length)
    );

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_pauseStream(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().pause_stream();
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_resumeStream(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().resume_stream();
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_stopStream(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().stop_stream();
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_releaseDevice(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().release_device();
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_getBufferAvailable(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return static_cast<jint>(purecast::AudioEngine::instance().get_buffer_available());
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_getBufferCapacity(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return static_cast<jint>(purecast::AudioEngine::instance().get_buffer_capacity());
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_getAvailableRead(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return static_cast<jint>(purecast::AudioEngine::instance().get_available_read());
}

JNIEXPORT jboolean JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_isStreaming(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().is_streaming() ? JNI_TRUE : JNI_FALSE;
}

struct JniFormatCallbackData {
    JNIEnv* env;
    jobject callback_obj;
    jmethodID callback_method;
};

static void jni_flac_format_cb(int sample_rate, int bit_depth, int channels, void* user_data) {
    auto* cb_data = static_cast<JniFormatCallbackData*>(user_data);
    if (cb_data && cb_data->env && cb_data->callback_obj && cb_data->callback_method) {
        cb_data->env->CallVoidMethod(
            cb_data->callback_obj,
            cb_data->callback_method,
            sample_rate,
            bit_depth,
            channels
        );
    }
}

JNIEXPORT jboolean JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_decodeFlacStream(
    JNIEnv* env,
    jobject /* thiz */,
    jobject input_stream,
    jobject on_format_callback
) {
    if (!input_stream || !on_format_callback) return JNI_FALSE;

    jclass cb_class = env->GetObjectClass(on_format_callback);
    jmethodID cb_method = env->GetMethodID(cb_class, "onFormatReady", "(III)V");
    if (!cb_method) return JNI_FALSE;

    JniFormatCallbackData cb_data{
        env,
        on_format_callback,
        cb_method
    };

    bool ok = purecast::FlacDecoder::decode_stream(
        env,
        input_stream,
        jni_flac_format_cb,
        &cb_data
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
