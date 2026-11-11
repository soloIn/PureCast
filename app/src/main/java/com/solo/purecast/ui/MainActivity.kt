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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 顶层页面枚举 */
enum class TvNavScreen { PLAYBACK, SETTINGS }

/**
 * 💡 PureCast 大屏主入口 Activity
 * 严格遵从 AGENTS.md 约束与 10-Foot 遥控器大屏交互规范：
 * 1. 物理遥控器按键全局拦截：精准响应 KEYCODE_MENU（呼出/收起侧边栏）及媒体播放按键。
 * 2. 实时流保护：遥控器切歌键在 airupnp 等外部实时流时拦截并弹出温和 Toast 提示。
 * 3. 顶层页面导航架构：在播放页面与设置页面之间平滑切换，设置页面具备 100% 实心背景。
 */
class MainActivity : ComponentActivity() {

    // 💡 遥控器 MENU 键全局触发器 (以时间戳驱动 Compose 响应)
    private val menuKeyTrigger = mutableLongStateOf(0L)
    private var currentNavScreen: TvNavScreen = TvNavScreen.PLAYBACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.ui.i(TAG, "MainActivity onCreate: 启动 Apple TV 风格大屏交互界面")

        // 初始化外观色彩系统
        com.solo.purecast.ui.theme.AppPalette.init(this)

        // 确保后台音频前台服务与 UPnP 监听已就绪
        PlaybackService.start(this)
        handleUsbAttachedIntent(intent)

        setContent {
            PureCastTheme {
                var currentScreen by remember { mutableStateOf(TvNavScreen.PLAYBACK) }
                val audioPathState by remember {
                    PlaybackService.sessionSnapshot
                        .map { it.audioPath }
                        .distinctUntilChanged()
                }.collectAsState(initial = remember { PlaybackService.sessionSnapshot.value.audioPath })
                val playbackStatus by remember {
                    PlaybackService.sessionSnapshot
                        .map { it.playbackStatus }
                        .distinctUntilChanged()
                }.collectAsState(initial = remember { PlaybackService.sessionSnapshot.value.playbackStatus })

                // 💡 屏保智能防休眠联动：
                // 仅当位于播放界面 (PLAYBACK) 且实际处于播放中 (PLAYING / TRANSITIONING) 时，持有 FLAG_KEEP_SCREEN_ON 阻止系统屏保介入；
                // 一旦暂停 (PAUSED)、停止 (STOPPED)、无流空闲 (IDLE) 或进入设置界面 (SETTINGS)，立即清除常亮标志，恢复系统标准屏保倒计时
                LaunchedEffect(currentScreen, playbackStatus) {
                    currentNavScreen = currentScreen
                    updateKeepScreenOn()
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
                                onOpenSettings = { currentScreen = TvNavScreen.SETTINGS }
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
        Logger.ui.i(TAG, "MainActivity onResume: 检查 USB DAC 授权状态与屏保常亮状态...")
        PlaybackService.instance?.bindUsbPermissionActivity(this)
        checkAndRequestUsbPermission()
        updateKeepScreenOn()
    }

    private fun handleUsbAttachedIntent(intent: android.content.Intent?) {
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: android.hardware.usb.UsbDevice? = androidx.core.content.IntentCompat.getParcelableExtra(
                intent,
                android.hardware.usb.UsbManager.EXTRA_DEVICE,
                android.hardware.usb.UsbDevice::class.java
            )
            if (device != null) {
                Logger.ui.i(TAG, "从 Intent 捕获到 USB 设备插入: ${device.productName ?: device.deviceName}")
                PlaybackService.instance?.handleUsbDeviceAttachedFromIntent(device)
            }
        }
    }

    private fun checkAndRequestUsbPermission() {
        val audioState = PlaybackService.sessionSnapshot.value.audioPath
        if (audioState.isDacConnected) {
            Logger.ui.d(TAG, "当前 USB DAC 已就绪连接 (${audioState.dacName})，无需再次请求授权")
            return
        }
        PlaybackService.instance?.scanAndRequestUsbPermission(this)
    }

    private fun updateKeepScreenOn() {
        val shouldKeepScreenOn = currentNavScreen == TvNavScreen.PLAYBACK &&
                PlaybackService.sessionSnapshot.value.playbackStatus == PlaybackStatus.PLAYING
        if (shouldKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Logger.ui.i(TAG, "大屏处于有效播放中: 激活 FLAG_KEEP_SCREEN_ON (保持屏幕常亮)")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Logger.ui.i(TAG, "非播放状态或离开播放主页: 清除 FLAG_KEEP_SCREEN_ON (恢复系统屏保)")
        }
    }

    override fun onStop() {
        super.onStop()
        Logger.ui.i(TAG, "MainActivity onStop: 退入后台，清除 FLAG_KEEP_SCREEN_ON 释放屏保控制权")
        PlaybackService.instance?.bindUsbPermissionActivity(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Logger.ui.i(TAG, "MainActivity onNewIntent, action=${intent.action}")
        handleUsbAttachedIntent(intent)
        checkAndRequestUsbPermission()
    }

    private fun togglePlayPause() {
        Logger.ui.i(TAG, "遥控器触发: 播放/暂停切换")
        PlaybackService.instance?.togglePlayPause()
    }

    private fun handlePreviousTrack() {
        Logger.ui.i(TAG, "遥控器触发: 上一曲")
        PlaybackService.instance?.handlePreviousTrack()
    }

    private fun handleNextTrack() {
        Logger.ui.i(TAG, "遥控器触发: 下一曲")
        PlaybackService.instance?.handleNextTrack()
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

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Logger.ui.i(TAG, "遥控器触发: KEYCODE_MEDIA_PLAY 恢复播放")
                PlaybackService.instance?.resumePlayback()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                Logger.ui.i(TAG, "遥控器触发: KEYCODE_MEDIA_PAUSE 暂停播放")
                PlaybackService.instance?.pausePlayback()
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
