package com.solo.purecast.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.solo.purecast.util.Logger

/**
 * 💡 PureCast 现代系统音频焦点仲裁器 (Audio Focus Arbitrator)
 * 严格遵循 Android 11+ (API 30~34) 规范，使用 AudioFocusRequest.Builder。
 * 
 * 核心仲裁时序：
 * 1. UPnP 推流开始时：请求独占 Gain 焦点，成功后 Native 层接管 USB 声卡。
 * 2. 收到 AUDIOFOCUS_LOSS (其他 App 抢占声卡，如电视端爱奇艺/B站启动)：
 *    立即触发声卡动态让渡时序 (releaseDevice)，重新向 Linux 内核重绑 snd-usb-audio 驱动。
 * 3. 收到 AUDIOFOCUS_LOSS_TRANSIENT：暂时静音保活。
 */
class AudioFocusManager(
    private val context: Context,
    private val isPlaybackActiveProvider: (() -> Boolean)? = null,
    private val onPlaybackStateChange: (shouldPlay: Boolean) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    // 💡 仅当暂时失去焦点且之前确实在播放时才允许在重新获焦后自动恢复播放，防止主动暂停后被误恢复
    private var resumeOnFocusGain = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Logger.audio.i(TAG, "系统音频焦点发生变化: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Logger.audio.i(TAG, "重新获得音频焦点 (AUDIOFOCUS_GAIN), resumeOnFocusGain=$resumeOnFocusGain")
                hasFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    onPlaybackStateChange(true)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                Logger.audio.w(TAG, "完全失去音频焦点 (AUDIOFOCUS_LOSS)，执行声卡归还让渡时序...")
                hasFocus = false
                resumeOnFocusGain = false
                // 💡 彻底归还 USB 声卡给系统内核，确保 UsbDeviceConnection 同步释放并回传协调器
                val svc = com.solo.purecast.service.PlaybackService.instance
                if (svc != null) {
                    svc.handleAudioFocusLost()
                } else {
                    onPlaybackStateChange(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Logger.audio.w(TAG, "暂时失去音频焦点 (AUDIOFOCUS_LOSS_TRANSIENT)，暂停推流")
                val wasPlaying = isPlaybackActiveProvider?.invoke() ?: hasFocus
                resumeOnFocusGain = wasPlaying
                hasFocus = false
                if (wasPlaying) {
                    onPlaybackStateChange(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Logger.audio.i(TAG, "音频焦点短暂避让 (CAN_DUCK)，保持播放")
            }
        }
    }

    /**
     * 申请音频焦点
     * @return true 表示成功获取焦点，false 表示被系统拒绝
     */
    fun requestFocus(): Boolean {
        if (hasFocus) return true

        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener, handler)
            .build()

        focusRequest = request
        val res = audioManager.requestAudioFocus(request)
        hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            Logger.audio.i(TAG, "音频焦点被系统延迟授予 (AUDIOFOCUS_REQUEST_DELAYED)，将在获得焦点时自动恢复播放")
            resumeOnFocusGain = true
        } else if (!hasFocus) {
            resumeOnFocusGain = false
        }

        Logger.audio.i(TAG, "请求音频焦点结果: $res (granted=$hasFocus)")
        return hasFocus
    }

    /**
     * 放弃音频焦点
     */
    fun abandonFocus() {
        focusRequest?.let { req ->
            Logger.audio.i(TAG, "主动放弃系统音频焦点...")
            audioManager.abandonAudioFocusRequest(req)
            focusRequest = null
            hasFocus = false
            resumeOnFocusGain = false
        }
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}
