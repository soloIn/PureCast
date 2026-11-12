#include <jni.h>
#include <pthread.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include "audio_engine.h"
#include "flac_decoder.h"
#include "raop/raop_server.h"

struct AirPlayCallbackMethods {
    jmethodID on_stream_requested{nullptr};
    jmethodID on_stream_stopped{nullptr};
    jmethodID on_stream_paused{nullptr};
    jmethodID on_stream_resumed{nullptr};
    jmethodID on_track_switch_flushed{nullptr};
    jmethodID on_first_data_packet{nullptr};
    jmethodID on_metadata_received{nullptr};
    jmethodID on_progress_updated{nullptr};
    jmethodID on_challenge_requested{nullptr};
};

static JavaVM* g_jvm = nullptr;
static jobject g_airplay_callback = nullptr;
static AirPlayCallbackMethods g_cb_methods{};
// 💡 分离回调引用锁与服务生命周期锁，绝不在持有锁时调用 Java 回调或 join 线程，彻底消除 stopAirPlayServer 互锁
static std::mutex g_callback_mutex;
static std::recursive_mutex g_server_mutex;
static std::shared_ptr<purecast::raop::RaopServer> g_raop_server = nullptr;
static pthread_key_t g_jni_detach_key;
static pthread_once_t g_jni_key_once = PTHREAD_ONCE_INIT;

namespace {

inline bool clear_jni_exception(JNIEnv* env, const char* site) {
    if (env && env->ExceptionCheck()) {
        LOGE("JNI 交互发生未捕获 Java 异常 (%s)，已清除异常防止原生常驻线程触发 CheckJNI 中止", site);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

inline jstring safe_new_jstring(JNIEnv* env, const std::string& str, const char* site) {
    if (!env) return nullptr;
    jstring res = env->NewStringUTF(str.c_str());
    if (clear_jni_exception(env, site)) {
        return nullptr;
    }
    return res;
}

void detach_current_thread_on_exit(void* jvm_ptr) {
    if (jvm_ptr) {
        static_cast<JavaVM*>(jvm_ptr)->DetachCurrentThread();
    }
}

void init_jni_detach_key() {
    pthread_key_create(&g_jni_detach_key, detach_current_thread_on_exit);
}

struct ScopedJniCallback {
    JNIEnv* env{nullptr};
    jobject callback{nullptr};
    AirPlayCallbackMethods methods{};

    ScopedJniCallback() {
        if (!g_jvm) return;
        JNIEnv* cur_env = nullptr;
        jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&cur_env), JNI_VERSION_1_6);
        if (res == JNI_EDETACHED) {
            pthread_once(&g_jni_key_once, init_jni_detach_key);
            if (g_jvm->AttachCurrentThread(&cur_env, nullptr) != JNI_OK) {
                return;
            }
            // 💡 线程级持久附着 + pthread_key_t 退出自动 Detach，避免每次音频回调重复 Attach/Detach 开销
            if (pthread_getspecific(g_jni_detach_key) == nullptr) {
                pthread_setspecific(g_jni_detach_key, g_jvm);
            }
        } else if (res != JNI_OK) {
            return;
        }
        jobject local_cb = nullptr;
        AirPlayCallbackMethods local_methods{};
        {
            std::lock_guard<std::mutex> lock(g_callback_mutex);
            if (g_airplay_callback) {
                local_cb = cur_env->NewLocalRef(g_airplay_callback);
                if (clear_jni_exception(cur_env, "ScopedJniCallback::NewLocalRef")) {
                    local_cb = nullptr;
                }
                local_methods = g_cb_methods;
            }
        }
        if (!local_cb) {
            return;
        }
        env = cur_env;
        callback = local_cb;
        methods = local_methods;
    }

    ~ScopedJniCallback() {
        if (env && callback) {
            env->DeleteLocalRef(callback);
        }
    }

    explicit operator bool() const { return env != nullptr && callback != nullptr; }
};

} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static int64_t notify_java_stream_requested(
    int64_t session_id,
    int64_t sequence,
    const purecast::raop::RaopStreamParams& params
) {
    ScopedJniCallback jni;
    if (!jni || !jni.methods.on_stream_requested) return -1;

    jstring j_codec = safe_new_jstring(jni.env, params.codec, "onStreamRequested::codec");
    jstring j_client_ip = safe_new_jstring(jni.env, params.client_ip, "onStreamRequested::client_ip");
    jstring j_key = safe_new_jstring(jni.env, params.aes_key_base64, "onStreamRequested::key");
    jstring j_iv = safe_new_jstring(jni.env, params.aes_iv_base64, "onStreamRequested::iv");
    jstring j_dacp_id = safe_new_jstring(jni.env, params.dacp_id, "onStreamRequested::dacp_id");
    jstring j_token = safe_new_jstring(jni.env, params.active_remote_token, "onStreamRequested::token");

    jlong epoch_ret = jni.env->CallLongMethod(
        jni.callback,
        jni.methods.on_stream_requested,
        static_cast<jlong>(session_id),
        static_cast<jlong>(sequence),
        params.sample_rate,
        params.bit_depth,
        params.channels,
        j_codec,
        j_client_ip,
        j_key,
        j_iv,
        j_dacp_id,
        j_token,
        params.dacp_port
    );
    if (clear_jni_exception(jni.env, "onStreamRequested")) {
        epoch_ret = -1;
    }

    if (j_codec) jni.env->DeleteLocalRef(j_codec);
    if (j_client_ip) jni.env->DeleteLocalRef(j_client_ip);
    if (j_key) jni.env->DeleteLocalRef(j_key);
    if (j_iv) jni.env->DeleteLocalRef(j_iv);
    if (j_dacp_id) jni.env->DeleteLocalRef(j_dacp_id);
    if (j_token) jni.env->DeleteLocalRef(j_token);

    return static_cast<int64_t>(epoch_ret);
}

static void notify_java_session_epoch_event(
    jmethodID AirPlayCallbackMethods::*method_ptr,
    int64_t session_id,
    int64_t sequence,
    int64_t epoch
) {
    ScopedJniCallback jni;
    if (!jni) return;

    jmethodID mid = jni.methods.*method_ptr;
    if (mid) {
        jni.env->CallVoidMethod(
            jni.callback,
            mid,
            static_cast<jlong>(session_id),
            static_cast<jlong>(sequence),
            static_cast<jlong>(epoch)
        );
        clear_jni_exception(jni.env, "notify_java_session_epoch_event");
    }
}

static void notify_java_stream_stopped(int64_t session_id, int64_t sequence, int64_t epoch) {
    notify_java_session_epoch_event(&AirPlayCallbackMethods::on_stream_stopped, session_id, sequence, epoch);
}

static void notify_java_stream_paused(int64_t session_id, int64_t sequence, int64_t epoch, bool is_implicit) {
    ScopedJniCallback jni;
    if (!jni || !jni.methods.on_stream_paused) return;

    jni.env->CallVoidMethod(
        jni.callback,
        jni.methods.on_stream_paused,
        static_cast<jlong>(session_id),
        static_cast<jlong>(sequence),
        static_cast<jlong>(epoch),
        is_implicit ? JNI_TRUE : JNI_FALSE
    );
    clear_jni_exception(jni.env, "onStreamPaused");
}

static void notify_java_stream_resumed(int64_t session_id, int64_t sequence, int64_t epoch) {
    notify_java_session_epoch_event(&AirPlayCallbackMethods::on_stream_resumed, session_id, sequence, epoch);
}

static void notify_java_track_switch_flushed(int64_t session_id, int64_t sequence, int64_t new_epoch) {
    notify_java_session_epoch_event(&AirPlayCallbackMethods::on_track_switch_flushed, session_id, sequence, new_epoch);
}

static void notify_java_first_data_packet(
    int64_t session_id,
    int64_t sequence,
    int64_t epoch,
    int sample_rate,
    int bit_depth,
    int channels,
    const std::string& codec
) {
    ScopedJniCallback jni;
    if (!jni || !jni.methods.on_first_data_packet) return;

    jstring j_codec = safe_new_jstring(jni.env, codec, "onFirstDataPacket::codec");
    jni.env->CallVoidMethod(
        jni.callback,
        jni.methods.on_first_data_packet,
        static_cast<jlong>(session_id),
        static_cast<jlong>(sequence),
        static_cast<jlong>(epoch),
        sample_rate,
        bit_depth,
        channels,
        j_codec
    );
    clear_jni_exception(jni.env, "onFirstDataPacket");
    if (j_codec) jni.env->DeleteLocalRef(j_codec);
}

static void notify_java_metadata(
    int64_t session_id,
    int64_t sequence,
    const std::string& title,
    const std::string& artist,
    const std::string& album,
    long duration_ms,
    const std::vector<uint8_t>& cover_art
) {
    ScopedJniCallback jni;
    if (!jni || !jni.methods.on_metadata_received) return;

    jstring j_title = safe_new_jstring(jni.env, title, "onMetadataReceived::title");
    jstring j_artist = safe_new_jstring(jni.env, artist, "onMetadataReceived::artist");
    jstring j_album = safe_new_jstring(jni.env, album, "onMetadataReceived::album");
    jbyteArray j_cover = nullptr;

    if (!cover_art.empty()) {
        j_cover = jni.env->NewByteArray(static_cast<jsize>(cover_art.size()));
        if (clear_jni_exception(jni.env, "onMetadataReceived::NewByteArray")) {
            j_cover = nullptr;
        } else if (j_cover) {
            jni.env->SetByteArrayRegion(
                j_cover,
                0,
                static_cast<jsize>(cover_art.size()),
                reinterpret_cast<const jbyte*>(cover_art.data())
            );
            if (clear_jni_exception(jni.env, "onMetadataReceived::SetByteArrayRegion")) {
                jni.env->DeleteLocalRef(j_cover);
                j_cover = nullptr;
            }
        }
    }

    jni.env->CallVoidMethod(
        jni.callback,
        jni.methods.on_metadata_received,
        static_cast<jlong>(session_id),
        static_cast<jlong>(sequence),
        j_title,
        j_artist,
        j_album,
        static_cast<jlong>(duration_ms),
        j_cover
    );
    clear_jni_exception(jni.env, "onMetadataReceived");

    if (j_title) jni.env->DeleteLocalRef(j_title);
    if (j_artist) jni.env->DeleteLocalRef(j_artist);
    if (j_album) jni.env->DeleteLocalRef(j_album);
    if (j_cover) jni.env->DeleteLocalRef(j_cover);
}

static void notify_java_progress_updated(int64_t session_id, int64_t sequence, long position_ms, long duration_ms) {
    ScopedJniCallback jni;
    if (!jni || !jni.methods.on_progress_updated) return;

    jni.env->CallVoidMethod(
        jni.callback,
        jni.methods.on_progress_updated,
        static_cast<jlong>(session_id),
        static_cast<jlong>(sequence),
        static_cast<jlong>(position_ms),
        static_cast<jlong>(duration_ms)
    );
    clear_jni_exception(jni.env, "onProgressUpdated");
}

static std::string notify_java_challenge_requested(const std::string& challenge_b64) {
    ScopedJniCallback jni;
    if (!jni || !jni.methods.on_challenge_requested) return "";

    std::string result;
    jstring j_challenge = safe_new_jstring(jni.env, challenge_b64, "onChallengeRequested::arg");
    if (!j_challenge) return "";

    auto j_resp = static_cast<jstring>(
        jni.env->CallObjectMethod(jni.callback, jni.methods.on_challenge_requested, j_challenge)
    );
    if (clear_jni_exception(jni.env, "onChallengeRequested")) {
        if (j_resp) jni.env->DeleteLocalRef(j_resp);
        jni.env->DeleteLocalRef(j_challenge);
        return "";
    }
    if (j_resp) {
        const char* chars = jni.env->GetStringUTFChars(j_resp, nullptr);
        if (!clear_jni_exception(jni.env, "onChallengeRequested::GetStringUTFChars") && chars) {
            result = chars;
            jni.env->ReleaseStringUTFChars(j_resp, chars);
        }
        jni.env->DeleteLocalRef(j_resp);
    }
    jni.env->DeleteLocalRef(j_challenge);
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

JNIEXPORT jlong JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_reconfigureAudioFormat(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jint sample_rate,
    jint bit_depth,
    jint channels) {
    return static_cast<jlong>(
        purecast::AudioEngine::instance().reconfigure_audio_format(sample_rate, bit_depth, channels)
    );
}

JNIEXPORT jlong JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_emergencyMuteAndFlush(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jint reason) {
    auto flush_reason = purecast::FlushReason::FLUSH_SWITCH;
    switch (reason) {
        case 0: flush_reason = purecast::FlushReason::EXPLICIT_PAUSE; break;
        case 1: flush_reason = purecast::FlushReason::FLUSH_SWITCH; break;
        case 2: flush_reason = purecast::FlushReason::STOP_OR_TEARDOWN; break;
        case 3: flush_reason = purecast::FlushReason::PREEMPT; break;
        default: flush_reason = purecast::FlushReason::FLUSH_SWITCH; break;
    }
    return static_cast<jlong>(
        purecast::AudioEngine::instance().emergency_mute_and_flush(flush_reason)
    );
}

JNIEXPORT jlong JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_getCurrentBufferEpoch(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return static_cast<jlong>(
        purecast::AudioEngine::instance().get_current_buffer_epoch()
    );
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_writePcm___3BIIJ(
    JNIEnv *env,
    jobject /* thiz */,
    jbyteArray data,
    jint offset,
    jint length,
    jlong expected_epoch) {
    if (!data || length <= 0 || offset < 0) return 0;
    const jsize arr_len = env->GetArrayLength(data);
    if (offset > arr_len || length > arr_len - offset) return -22;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;

    int written = purecast::AudioEngine::instance().write_pcm(
        reinterpret_cast<const uint8_t*>(bytes + offset),
        static_cast<size_t>(length),
        expected_epoch > 0 ? static_cast<uint64_t>(expected_epoch) : 0ULL
    );

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_resumeStream(
    JNIEnv * /* env */,
    jobject /* thiz */) {
    return purecast::AudioEngine::instance().resume_stream();
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
    jmethodID on_format_ready_method;
    jmethodID on_first_pcm_method;
};

static int64_t jni_flac_format_cb(int sample_rate, int bit_depth, int channels, int64_t total_pcm_frames, void* user_data) {
    auto* cb_data = static_cast<JniFormatCallbackData*>(user_data);
    if (cb_data && cb_data->env && cb_data->callback_obj && cb_data->on_format_ready_method) {
        jlong epoch = cb_data->env->CallLongMethod(
            cb_data->callback_obj,
            cb_data->on_format_ready_method,
            sample_rate,
            bit_depth,
            channels,
            static_cast<jlong>(total_pcm_frames)
        );
        if (clear_jni_exception(cb_data->env, "jni_flac_format_cb")) {
            return -1;
        }
        return static_cast<int64_t>(epoch);
    }
    return -1;
}

static void jni_flac_first_pcm_cb(void* user_data) {
    auto* cb_data = static_cast<JniFormatCallbackData*>(user_data);
    if (cb_data && cb_data->env && cb_data->callback_obj && cb_data->on_first_pcm_method) {
        cb_data->env->CallVoidMethod(
            cb_data->callback_obj,
            cb_data->on_first_pcm_method
        );
        clear_jni_exception(cb_data->env, "jni_flac_first_pcm_cb");
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
    if (!cb_class || clear_jni_exception(env, "decodeFlacStream::GetObjectClass")) {
        if (cb_class) env->DeleteLocalRef(cb_class);
        return JNI_FALSE;
    }
    jmethodID cb_method = env->GetMethodID(cb_class, "onFormatReady", "(IIIJ)J");
    jmethodID first_pcm_method = env->GetMethodID(cb_class, "onFirstPcmWritten", "()V");
    env->DeleteLocalRef(cb_class);
    if (clear_jni_exception(env, "decodeFlacStream::GetMethodID") || !cb_method) return JNI_FALSE;

    JniFormatCallbackData cb_data{
        env,
        on_format_callback,
        cb_method,
        first_pcm_method
    };

    bool ok = purecast::FlacDecoder::decode_stream(
        env,
        input_stream,
        jni_flac_format_cb,
        jni_flac_first_pcm_cb,
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
    std::shared_ptr<purecast::raop::RaopServer> old_server;
    {
        std::lock_guard<std::recursive_mutex> slock(g_server_mutex);
        old_server = std::move(g_raop_server);
    }
    if (old_server) {
        old_server->stop();
        old_server.reset();
    }

    {
        std::lock_guard<std::mutex> clock(g_callback_mutex);
        if (g_airplay_callback) {
            env->DeleteGlobalRef(g_airplay_callback);
            g_airplay_callback = nullptr;
        }
        g_cb_methods = {};
        if (callback) {
            g_airplay_callback = env->NewGlobalRef(callback);
            jclass cb_cls = env->GetObjectClass(callback);
            if (!cb_cls || clear_jni_exception(env, "startAirPlayServer::GetObjectClass")) {
                if (cb_cls) env->DeleteLocalRef(cb_cls);
                env->DeleteGlobalRef(g_airplay_callback);
                g_airplay_callback = nullptr;
                return JNI_FALSE;
            }
            g_cb_methods.on_stream_requested = env->GetMethodID(
                cb_cls, "onStreamRequested",
                "(JJIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)J"
            );
            g_cb_methods.on_stream_stopped = env->GetMethodID(cb_cls, "onStreamStopped", "(JJJ)V");
            g_cb_methods.on_stream_paused = env->GetMethodID(cb_cls, "onStreamPaused", "(JJJZ)V");
            g_cb_methods.on_stream_resumed = env->GetMethodID(cb_cls, "onStreamResumed", "(JJJ)V");
            g_cb_methods.on_track_switch_flushed = env->GetMethodID(cb_cls, "onTrackSwitchFlushed", "(JJJ)V");
            g_cb_methods.on_first_data_packet = env->GetMethodID(cb_cls, "onFirstDataPacket", "(JJJIIILjava/lang/String;)V");
            g_cb_methods.on_metadata_received = env->GetMethodID(
                cb_cls, "onMetadataReceived",
                "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;J[B)V"
            );
            g_cb_methods.on_progress_updated = env->GetMethodID(cb_cls, "onProgressUpdated", "(JJJJ)V");
            g_cb_methods.on_challenge_requested = env->GetMethodID(
                cb_cls, "onChallengeRequested", "(Ljava/lang/String;)Ljava/lang/String;"
            );
            env->DeleteLocalRef(cb_cls);
            if (clear_jni_exception(env, "startAirPlayServer::GetMethodID")) {
                env->DeleteGlobalRef(g_airplay_callback);
                g_airplay_callback = nullptr;
                g_cb_methods = {};
                return JNI_FALSE;
            }
        }
    }

    auto server = std::make_shared<purecast::raop::RaopServer>();
    server->set_stream_request_callback(notify_java_stream_requested);
    server->set_stream_stop_callback(notify_java_stream_stopped);
    server->set_stream_pause_callback(notify_java_stream_paused);
    server->set_stream_resume_callback(notify_java_stream_resumed);
    server->set_first_data_packet_callback(notify_java_first_data_packet);
    server->set_metadata_callback(notify_java_metadata);
    server->set_challenge_callback(notify_java_challenge_requested);
    server->set_progress_callback(notify_java_progress_updated);
    server->set_flush_callback(notify_java_track_switch_flushed);

    bool ok = server->start(port);
    if (ok) {
        std::lock_guard<std::recursive_mutex> slock(g_server_mutex);
        g_raop_server = std::move(server);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_stopAirPlayServer(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::shared_ptr<purecast::raop::RaopServer> server_to_stop;
    {
        std::lock_guard<std::recursive_mutex> slock(g_server_mutex);
        server_to_stop = std::move(g_raop_server);
    }
    if (server_to_stop) {
        // 💡 无锁调用 stop() 等待线程 join，避免与回调线程互锁
        server_to_stop->stop();
        server_to_stop.reset();
    }

    std::lock_guard<std::mutex> clock(g_callback_mutex);
    if (g_airplay_callback) {
        env->DeleteGlobalRef(g_airplay_callback);
        g_airplay_callback = nullptr;
    }
    g_cb_methods = {};
}

JNIEXPORT void JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_preemptAirPlayClient(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::shared_ptr<purecast::raop::RaopServer> server;
    {
        std::lock_guard<std::recursive_mutex> slock(g_server_mutex);
        server = g_raop_server;
    }
    if (server) {
        server->preempt_active_client();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_solo_purecast_core_nativebridge_NativeAudioEngine_updateAirPlayActiveEpoch(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong new_epoch
) {
    if (new_epoch <= 0) return JNI_FALSE;
    std::shared_ptr<purecast::raop::RaopServer> server;
    {
        std::lock_guard<std::recursive_mutex> slock(g_server_mutex);
        server = g_raop_server;
    }
    if (!server) return JNI_FALSE;
    return server->resume_after_reopen(static_cast<uint64_t>(new_epoch)) ? JNI_TRUE : JNI_FALSE;
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
    if (key_len <= 0 || iv_len <= 0) return;

    std::vector<uint8_t> key(key_len);
    std::vector<uint8_t> iv(iv_len);

    env->GetByteArrayRegion(aes_key, 0, key_len, reinterpret_cast<jbyte*>(key.data()));
    env->GetByteArrayRegion(aes_iv, 0, iv_len, reinterpret_cast<jbyte*>(iv.data()));
    if (clear_jni_exception(env, "setAirPlayCryptoKey::GetByteArrayRegion")) return;

    std::shared_ptr<purecast::raop::RaopServer> server;
    {
        std::lock_guard<std::recursive_mutex> slock(g_server_mutex);
        server = g_raop_server;
    }
    if (server) {
        server->set_decrypted_aes_key(key, iv);
    }
}

} // extern "C"
