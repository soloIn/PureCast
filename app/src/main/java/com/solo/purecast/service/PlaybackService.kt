package com.solo.purecast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.solo.purecast.R
import com.solo.purecast.core.audio.AudioFocusManager
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.core.usb.UsbDeviceManager
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.MainActivity
import com.solo.purecast.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 💡 PureCast 核心播放前台服务
 * 深度集成：
 * 1. UsbDeviceManager: 冷启动设备识别、Linux usbfs fd 穿透与 10 秒空闲自动让渡
 * 2. AudioFocusManager: Android 11+ 标准音频焦点抢占与被动释放时序
 * 3. Wi-Fi Low Latency Lock & Multicast Lock: 保证 UPnP 发现与高码率推流 0 丢包
 * 4. 前台通知保活: 符合 API 34 mediaPlayback + connectedDevice 要求
 */
class PlaybackService : LifecycleService() {

    private lateinit var usbDeviceManager: UsbDeviceManager
    private lateinit var audioFocusManager: AudioFocusManager
    private var upnpServer: com.solo.purecast.upnp.UpnpRendererServer? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.service.i(TAG, "PlaybackService onCreate: 启动核心服务...")

        createNotificationChannel()
        startForegroundWithTypes()
        acquireSystemLocks()

        audioFocusManager = AudioFocusManager(this) { shouldPlay ->
            if (shouldPlay) {
                resumePlayback()
            } else {
                pausePlayback()
            }
        }

        usbDeviceManager = UsbDeviceManager(this) { connected, name ->
            Logger.service.i(TAG, "USB DAC 硬件状态通知: connected=$connected, name=$name")
            _audioPathState.value = _audioPathState.value.copy(
                isDacConnected = connected,
                dacName = name ?: if (connected) "Sony DAC" else "未检测到 USB DAC",
                isExclusive = usbDeviceManager.isExclusive()
            )
            updateNotification()
        }

        // 冷启动扫描已连接的 USB DAC
        usbDeviceManager.scanAndAttachDevice()

        // 💡 启动 UPnP / DLNA MediaRenderer 服务端 (SSDP广播与SOAP控制)
        upnpServer = com.solo.purecast.upnp.UpnpRendererServer(lifecycleScope, this).apply {
            start()
        }
    }

    private fun acquireSystemLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            // 💡 必须获取 MulticastLock，否则电视机休眠或息屏时无法响应 SSDP M-SEARCH 广播
            multicastLock = wifiManager.createMulticastLock("PureCast:MulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Logger.service.i(TAG, "成功获取 Wi-Fi 组播锁 (MulticastLock)")

            // 💡 获取低延迟全性能 Wi-Fi 锁，杜绝高码率 PCM 缓冲断流
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PureCast:WifiLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Logger.service.i(TAG, "成功获取 Wi-Fi 低延迟锁 (WIFI_MODE_FULL_LOW_LATENCY)")

            // 获取 CPU WakeLock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PureCast:WakeLock").apply {
                acquire(12 * 60 * 60 * 1000L) // 最长保护 12 小时
            }
            Logger.service.i(TAG, "成功获取 CPU WakeLock")
        } catch (e: Exception) {
            Logger.service.w(TAG, "获取系统锁异常", e)
        }
    }

    private fun releaseSystemLocks() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            wakeLock?.let { if (it.isHeld) it.release() }
            Logger.service.i(TAG, "已安全释放 Wi-Fi 锁与 WakeLock")
        } catch (e: Exception) {
            Logger.service.w(TAG, "释放系统锁异常", e)
        }
    }

    /**
     * 收到推流开始指令
     */
    fun startStreaming(sampleRate: Int, bitDepth: Int, channels: Int = 2): Boolean {
        Logger.service.i(TAG, "收到流式播放请求: $sampleRate Hz, $bitDepth bit, $channels ch")

        // 1. 请求系统音频焦点
        if (!audioFocusManager.requestFocus()) {
            Logger.service.w(TAG, "申请音频焦点被系统拒绝，放弃推流")
            return false
        }

        // 2. 接管声卡开启独占
        val claimed = usbDeviceManager.claimUsbExclusive(sampleRate, bitDepth, channels)
        if (!claimed) {
            Logger.service.w(TAG, "接管 USB 声卡独占失败")
            return false
        }

        _playbackStatus.value = PlaybackStatus.PLAYING
        _audioPathState.value = _audioPathState.value.copy(
            isExclusive = true,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels
        )

        updateNotification()
        return true
    }

    fun pausePlayback() {
        Logger.service.i(TAG, "暂停音频播放...")
        NativeAudioEngine.pauseStream()
        _playbackStatus.value = PlaybackStatus.PAUSED
        usbDeviceManager.scheduleIdleRelease() // 启动 10 秒空闲让渡
        updateNotification()
    }

    fun resumePlayback() {
        Logger.service.i(TAG, "恢复音频播放...")
        usbDeviceManager.cancelIdleRelease()
        if (!usbDeviceManager.isExclusive()) {
            val state = _audioPathState.value
            usbDeviceManager.claimUsbExclusive(state.sampleRate, state.bitDepth, state.channels)
        }
        NativeAudioEngine.resumeStream()
        _playbackStatus.value = PlaybackStatus.PLAYING
        updateNotification()
    }

    fun stopPlayback() {
        Logger.service.i(TAG, "停止音频播放...")
        NativeAudioEngine.stopStream()
        _playbackStatus.value = PlaybackStatus.STOPPED
        usbDeviceManager.scheduleIdleRelease() // 启动 10 秒空闲让渡
        audioFocusManager.abandonFocus()
        updateNotification()
    }

    fun updateMetadata(metadata: TrackMetadata) {
        _currentMetadata.value = metadata
        updateNotification()
    }

    fun setIdleTimeoutSeconds(seconds: Int) {
        usbDeviceManager.setIdleTimeoutSeconds(seconds)
    }

    fun requestUsbPermission(activity: android.app.Activity) {
        usbDeviceManager.requestPermissionWithActivity(activity)
    }

    fun scanUsbDevice() {
        usbDeviceManager.scanAndAttachDevice()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_notification_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val metadata = _currentMetadata.value
        val isPlaying = _playbackStatus.value == PlaybackStatus.PLAYING

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(metadata.title)
            .setContentText("${metadata.artist} • ${if (isPlaying) "Bit-Perfect 播放中" else "待机"}")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithTypes() {
        val notification = buildNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
            Logger.service.i(TAG, "已启动前台服务 (mediaPlayback | connectedDevice)")
        } catch (e: Exception) {
            Logger.service.e(TAG, "startForeground 失败!", e)
        }
    }

    override fun onDestroy() {
        Logger.service.i(TAG, "PlaybackService onDestroy: 彻底清理服务...")
        instance = null
        upnpServer?.stop()
        upnpServer = null
        releaseSystemLocks()
        audioFocusManager.abandonFocus()
        usbDeviceManager.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "purecast_playback_channel"
        private const val NOTIFICATION_ID = 1001

        var instance: PlaybackService? = null
            private set

        private val _playbackStatus = MutableStateFlow(PlaybackStatus.IDLE)
        val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

        private val _currentMetadata = MutableStateFlow(TrackMetadata())
        val currentMetadata: StateFlow<TrackMetadata> = _currentMetadata.asStateFlow()

        private val _audioPathState = MutableStateFlow(AudioPathState())
        val audioPathState: StateFlow<AudioPathState> = _audioPathState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
