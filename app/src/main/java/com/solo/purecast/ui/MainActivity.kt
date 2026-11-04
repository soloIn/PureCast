package com.solo.purecast.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.screen.MainTvScreen
import com.solo.purecast.ui.theme.PureCastTheme
import com.solo.purecast.util.Logger

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.ui.i(TAG, "MainActivity onCreate: 启动 Apple TV 风格大屏交互界面")

        // 确保后台音频前台服务与 UPnP 监听已就绪
        PlaybackService.start(this)

        setContent {
            PureCastTheme {
                MainTvScreen(
                    onTogglePlayPause = { togglePlayPause() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.ui.i(TAG, "MainActivity onResume: 检查 USB DAC 授权状态...")
        PlaybackService.instance?.requestUsbPermission(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Logger.ui.i(TAG, "MainActivity onNewIntent, action=${intent.action}")
        PlaybackService.instance?.scanUsbDevice()
        PlaybackService.instance?.requestUsbPermission(this)
    }

    private fun togglePlayPause() {
        val currentStatus = PlaybackService.playbackStatus.value
        if (currentStatus == PlaybackStatus.PLAYING) {
            Logger.ui.i(TAG, "遥控器触发: 暂停播放")
            PlaybackService.instance?.pausePlayback()
        } else {
            Logger.ui.i(TAG, "遥控器触发: 恢复播放")
            PlaybackService.instance?.resumePlayback()
        }
    }

    /**
     * 💡 物理遥控器按键精确拦截
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Logger.ui.d(TAG, "收到遥控器按键事件: keyCode=$keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlayPause()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_STOP -> {
                PlaybackService.instance?.stopPlayback()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
