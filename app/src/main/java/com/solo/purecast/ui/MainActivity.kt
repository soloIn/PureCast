package com.solo.purecast.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.screen.MainTvScreen
import com.solo.purecast.ui.screen.SettingsScreen
import com.solo.purecast.ui.theme.PureCastTheme
import com.solo.purecast.util.Logger

/** 顶层页面枚举 */
enum class TvNavScreen { PLAYBACK, SETTINGS }

/**
 * 💡 PureCast 大屏主入口 Activity
 * 严格遵从 AGENTS.md 约束与 10-Foot 遥控器大屏交互规范：
 * 1. 物理遥控器按键全局拦截：精准响应 KEYCODE_MENU（呼出/收起侧边栏）及媒体播放按键。
 * 2. 实时流保护：遥控器切歌键在 airupnp 等外部实时流时拦截并弹出温和 Toast 提示。
 * 3. 防烧屏按键重置：任意遥控器按键输入即刻唤醒控制栏并重置 3 分钟无操作计时器。
 * 4. 顶层页面导航架构：在播放页面与设置页面之间平滑切换，设置页面具备 100% 实心背景。
 */
class MainActivity : ComponentActivity() {

    // 💡 遥控器 MENU 键与全局按键触发器 (以时间戳驱动 Compose 响应)
    private val menuKeyTrigger = mutableLongStateOf(0L)
    private val userInteractionTrigger = mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.ui.i(TAG, "MainActivity onCreate: 启动 Apple TV 风格大屏交互界面")

        // 初始化外观色彩系统
        com.solo.purecast.ui.theme.AppPalette.init(this)

        // 确保后台音频前台服务与 UPnP 监听已就绪
        PlaybackService.start(this)

        setContent {
            PureCastTheme {
                var currentScreen by remember { mutableStateOf(TvNavScreen.PLAYBACK) }
                val audioPathState by PlaybackService.audioPathState.collectAsState()
                val playbackStatus by PlaybackService.playbackStatus.collectAsState()

                // 💡 屏保智能防休眠联动：
                // 仅当位于播放界面 (PLAYBACK) 且实际处于播放中 (PLAYING) 时，持有 FLAG_KEEP_SCREEN_ON 阻止系统屏保介入；
                // 一旦暂停 (PAUSED)、停止 (STOPPED)、无流空闲 (IDLE) 或进入设置界面 (SETTINGS)，立即清除常亮标志，恢复系统标准屏保倒计时
                LaunchedEffect(currentScreen, playbackStatus) {
                    val shouldKeepScreenOn = currentScreen == TvNavScreen.PLAYBACK && playbackStatus == PlaybackStatus.PLAYING
                    if (shouldKeepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Logger.ui.i(TAG, "大屏处于有效播放中: 激活 FLAG_KEEP_SCREEN_ON (保持屏幕常亮)")
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Logger.ui.i(TAG, "非播放状态或离开播放主页: 清除 FLAG_KEEP_SCREEN_ON (恢复系统屏保)")
                    }
                }

                // 物理 MENU 键响应：在播放页与设置页之间平滑切换
                LaunchedEffect(menuKeyTrigger.longValue) {
                    if (menuKeyTrigger.longValue > 0L) {
                        currentScreen = if (currentScreen == TvNavScreen.PLAYBACK) TvNavScreen.SETTINGS else TvNavScreen.PLAYBACK
                    }
                }

                Crossfade(
                    targetState = currentScreen,
                    animationSpec = tween(300),
                    label = "PageNavigation"
                ) { screen ->
                    when (screen) {
                        TvNavScreen.PLAYBACK -> {
                            MainTvScreen(
                                onTogglePlayPause = { togglePlayPause() },
                                onPreviousTrack = { handlePreviousTrack() },
                                onNextTrack = { handleNextTrack() },
                                onOpenSettings = { currentScreen = TvNavScreen.SETTINGS },
                                userInteractionTrigger = userInteractionTrigger.longValue
                            )
                        }
                        TvNavScreen.SETTINGS -> {
                            SettingsScreen(
                                audioState = audioPathState,
                                onDismiss = { currentScreen = TvNavScreen.PLAYBACK }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.ui.i(TAG, "MainActivity onResume: 检查 USB DAC 授权状态...")
        PlaybackService.instance?.requestUsbPermission(this)
    }

    override fun onStop() {
        super.onStop()
        Logger.ui.i(TAG, "MainActivity onStop: 退入后台，清除 FLAG_KEEP_SCREEN_ON 释放屏保控制权")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun handlePreviousTrack() {
        Logger.ui.i(TAG, "遥控器触发: 上一曲")
        PlaybackService.instance?.handlePreviousTrack()
        Toast.makeText(this, "已请求上一曲", Toast.LENGTH_SHORT).show()
    }

    private fun handleNextTrack() {
        Logger.ui.i(TAG, "遥控器触发: 下一曲")
        PlaybackService.instance?.handleNextTrack()
        Toast.makeText(this, "已请求下一曲", Toast.LENGTH_SHORT).show()
    }

    /**
     * 💡 全局按键分发：按键即重置 3 分钟防烧屏休眠倒计时
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            userInteractionTrigger.longValue = System.currentTimeMillis()
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 💡 物理遥控器按键精确拦截
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Logger.ui.d(TAG, "收到遥控器按键事件: keyCode=$keyCode")
        when (keyCode) {
            // 💡 拦截遥控器 MENU 键呼出/关闭设置侧边栏抽屉
            KeyEvent.KEYCODE_MENU -> {
                Logger.ui.i(TAG, "遥控器触发: KEYCODE_MENU 呼出全功能设置侧边栏")
                menuKeyTrigger.longValue = System.currentTimeMillis()
                return true
            }

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

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                handlePreviousTrack()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                handleNextTrack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
