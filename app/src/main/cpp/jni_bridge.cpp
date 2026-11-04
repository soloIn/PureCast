#include <jni.h>
#include <string>
#include "audio_engine.h"

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

JNIEXPORT jboolean JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_isStreaming(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().is_streaming() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
