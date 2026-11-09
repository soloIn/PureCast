#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include "audio_engine.h"
#include "flac_decoder.h"
#include "raop/raop_server.h"

static JavaVM* g_jvm = nullptr;
static jobject g_airplay_callback = nullptr;
static std::recursive_mutex g_airplay_mutex;
static std::unique_ptr<purecast::raop::RaopServer> g_raop_server = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static bool notify_java_stream_requested(const purecast::raop::RaopStreamParams& params) {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (!g_jvm || !g_airplay_callback) return true;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return false;
        }
        attached = true;
    }

    jclass cls = env->GetObjectClass(g_airplay_callback);
    jmethodID mid = env->GetMethodID(
        cls,
        "onStreamRequested",
        "(IIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Z"
    );

    jstring j_client_ip = env->NewStringUTF(params.client_ip.c_str());
    jstring j_key = env->NewStringUTF(params.aes_key_base64.c_str());
    jstring j_iv = env->NewStringUTF(params.aes_iv_base64.c_str());
    jstring j_dacp_id = env->NewStringUTF(params.dacp_id.c_str());
    jstring j_token = env->NewStringUTF(params.active_remote_token.c_str());

    jboolean ret = JNI_TRUE;
    if (mid) {
        ret = env->CallBooleanMethod(
            g_airplay_callback,
            mid,
            params.sample_rate,
            params.bit_depth,
            params.channels,
            j_client_ip,
            j_key,
            j_iv,
            j_dacp_id,
            j_token,
            params.dacp_port
        );
    }

    env->DeleteLocalRef(j_client_ip);
    env->DeleteLocalRef(j_key);
    env->DeleteLocalRef(j_iv);
    env->DeleteLocalRef(j_dacp_id);
    env->DeleteLocalRef(j_token);
    env->DeleteLocalRef(cls);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
    return ret == JNI_TRUE;
}

static void notify_java_stream_stopped() {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (!g_jvm || !g_airplay_callback) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }

    jclass cls = env->GetObjectClass(g_airplay_callback);
    jmethodID mid = env->GetMethodID(cls, "onStreamStopped", "()V");
    if (mid) {
        env->CallVoidMethod(g_airplay_callback, mid);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void notify_java_stream_paused() {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (!g_jvm || !g_airplay_callback) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }

    jclass cls = env->GetObjectClass(g_airplay_callback);
    jmethodID mid = env->GetMethodID(cls, "onStreamPaused", "()V");
    if (mid) {
        env->CallVoidMethod(g_airplay_callback, mid);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void notify_java_stream_resumed() {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (!g_jvm || !g_airplay_callback) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }

    jclass cls = env->GetObjectClass(g_airplay_callback);
    jmethodID mid = env->GetMethodID(cls, "onStreamResumed", "()V");
    if (mid) {
        env->CallVoidMethod(g_airplay_callback, mid);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void notify_java_metadata(
    const std::string& title,
    const std::string& artist,
    const std::string& album,
    long duration_ms,
    const std::vector<uint8_t>& cover_art
) {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (!g_jvm || !g_airplay_callback) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }

    jclass cls = env->GetObjectClass(g_airplay_callback);
    jmethodID mid = env->GetMethodID(
        cls,
        "onMetadataReceived",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J[B)V"
    );

    if (mid) {
        jstring j_title = env->NewStringUTF(title.c_str());
        jstring j_artist = env->NewStringUTF(artist.c_str());
        jstring j_album = env->NewStringUTF(album.c_str());
        jbyteArray j_cover = nullptr;

        if (!cover_art.empty()) {
            j_cover = env->NewByteArray(static_cast<jsize>(cover_art.size()));
            env->SetByteArrayRegion(
                j_cover,
                0,
                static_cast<jsize>(cover_art.size()),
                reinterpret_cast<const jbyte*>(cover_art.data())
            );
        }

        env->CallVoidMethod(
            g_airplay_callback,
            mid,
            j_title,
            j_artist,
            j_album,
            static_cast<jlong>(duration_ms),
            j_cover
        );

        env->DeleteLocalRef(j_title);
        env->DeleteLocalRef(j_artist);
        env->DeleteLocalRef(j_album);
        if (j_cover) env->DeleteLocalRef(j_cover);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static void notify_java_progress_updated(long position_ms, long duration_ms) {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (!g_jvm || !g_airplay_callback) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }

    jclass cls = env->GetObjectClass(g_airplay_callback);
    jmethodID mid = env->GetMethodID(
        cls,
        "onProgressUpdated",
        "(JJ)V"
    );

    if (mid) {
        env->CallVoidMethod(
            g_airplay_callback,
            mid,
            static_cast<jlong>(position_ms),
            static_cast<jlong>(duration_ms)
        );
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static std::string notify_java_challenge_requested(const std::string& challenge_b64) {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (!g_jvm || !g_airplay_callback) return "";

    JNIEnv* env = nullptr;
    bool attached = false;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return "";
        }
        attached = true;
    }

    std::string result;
    jclass cls = env->GetObjectClass(g_airplay_callback);
    jmethodID mid = env->GetMethodID(
        cls,
        "onChallengeRequested",
        "(Ljava/lang/String;)Ljava/lang/String;"
    );

    if (mid) {
        jstring j_challenge = env->NewStringUTF(challenge_b64.c_str());
        auto j_resp = (jstring)env->CallObjectMethod(g_airplay_callback, mid, j_challenge);
        if (j_resp) {
            const char* chars = env->GetStringUTFChars(j_resp, nullptr);
            if (chars) {
                result = chars;
                env->ReleaseStringUTFChars(j_resp, chars);
            }
            env->DeleteLocalRef(j_resp);
        }
        env->DeleteLocalRef(j_challenge);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
    return result;
}

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

static void jni_flac_format_cb(int sample_rate, int bit_depth, int channels, int64_t total_pcm_frames, void* user_data) {
    auto* cb_data = static_cast<JniFormatCallbackData*>(user_data);
    if (cb_data && cb_data->env && cb_data->callback_obj && cb_data->callback_method) {
        cb_data->env->CallVoidMethod(
            cb_data->callback_obj,
            cb_data->callback_method,
            sample_rate,
            bit_depth,
            channels,
            static_cast<jlong>(total_pcm_frames)
        );
    }
}

JNIEXPORT jlong JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_getFlacDecodedFrames(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return static_cast<jlong>(purecast::FlacDecoder::get_decoded_frames());
}

JNIEXPORT void JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_resetFlacDecodedFrames(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    purecast::FlacDecoder::reset_decoded_frames();
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
    jmethodID cb_method = env->GetMethodID(cb_class, "onFormatReady", "(IIIJ)V");
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

JNIEXPORT jboolean JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_startAirPlayServer(
    JNIEnv* env,
    jobject /* thiz */,
    jint port,
    jobject callback
) {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (g_airplay_callback) {
        env->DeleteGlobalRef(g_airplay_callback);
        g_airplay_callback = nullptr;
    }

    if (callback) {
        g_airplay_callback = env->NewGlobalRef(callback);
    }

    if (!g_raop_server) {
        g_raop_server = std::make_unique<purecast::raop::RaopServer>();
    }

    g_raop_server->set_stream_request_callback(notify_java_stream_requested);
    g_raop_server->set_stream_stop_callback(notify_java_stream_stopped);
    g_raop_server->set_stream_pause_callback(notify_java_stream_paused);
    g_raop_server->set_stream_resume_callback(notify_java_stream_resumed);
    g_raop_server->set_metadata_callback(notify_java_metadata);
    g_raop_server->set_challenge_callback(notify_java_challenge_requested);
    g_raop_server->set_progress_callback(notify_java_progress_updated);
    g_raop_server->set_flush_callback([]() {
        purecast::AudioEngine::instance().flush_buffer();
    });

    bool ok = g_raop_server->start(port);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_stopAirPlayServer(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (g_raop_server) {
        g_raop_server->stop();
        g_raop_server.reset();
    }

    if (g_airplay_callback) {
        env->DeleteGlobalRef(g_airplay_callback);
        g_airplay_callback = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_setAirPlayCryptoKey(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray aes_key,
    jbyteArray aes_iv
) {
    if (!aes_key || !aes_iv) return;

    jsize key_len = env->GetArrayLength(aes_key);
    jsize iv_len = env->GetArrayLength(aes_iv);

    std::vector<uint8_t> key(key_len);
    std::vector<uint8_t> iv(iv_len);

    env->GetByteArrayRegion(aes_key, 0, key_len, reinterpret_cast<jbyte*>(key.data()));
    env->GetByteArrayRegion(aes_iv, 0, iv_len, reinterpret_cast<jbyte*>(iv.data()));

    std::lock_guard<std::recursive_mutex> lock(g_airplay_mutex);
    if (g_raop_server) {
        g_raop_server->set_decrypted_aes_key(key, iv);
    }
}

} // extern "C"
