package com.solo.purecast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.solo.purecast.R
import com.solo.purecast.core.audio.AudioFocusManager
import com.solo.purecast.core.session.ErrorClearedEvent
import com.solo.purecast.core.session.ErrorReportedEvent
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.HardwareFastPathController
import com.solo.purecast.core.session.HardwareReconfiguredEvent
import com.solo.purecast.core.session.ImplicitPauseEvent
import com.solo.purecast.core.session.MetadataPatchEvent
import com.solo.purecast.core.session.ProgressUpdateEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamSetupEvent
import com.solo.purecast.core.session.StreamStopEvent
import com.solo.purecast.core.session.StreamTeardownEvent
import com.solo.purecast.core.session.TrackCompleteEvent
import com.solo.purecast.core.session.TrackSwitchEvent
import com.solo.purecast.core.usb.UsbDeviceManager
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.MainActivity
import com.solo.purecast.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 💡 PureCast 核心播放前台服务
 * 深度集成：
 * 1. UsbDeviceManager: 冷启动设备识别、Linux usbfs fd 穿透与 10 秒空闲自动让渡
 * 2. AudioFocusManager: Android 11+ 标准音频焦点抢占与被动释放时序
 * 3. 动态 Wi-Fi 锁管理: PLAYING 时激活 WIFI_MODE_FULL_LOW_LATENCY，空闲时释放以节能
 * 4. 突破 BAL 限制: 收到推流主动唤醒 MainActivity 大屏，并提供 fullScreenIntent 通知保底
 * 5. MediaSession 深度集成: 响应遥控器媒体全局按键，同步播放状态机与元数据，绑定 Notification MediaStyle
 * 6. UPnP / DLNA MediaRenderer 协议栈托管与广播友好名称持久化
 */
class PlaybackService : LifecycleService() {

    lateinit var usbDeviceManager: UsbDeviceManager
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var settingsRepository: SettingsRepository
    private var upnpServer: com.solo.purecast.upnp.UpnpRendererServer? = null
    var airPlayServer: com.solo.purecast.airplay.AirPlayServerManager? = null
        private set

    enum class StreamSource {
        IDLE,
        UPNP,
        AIRPLAY
    }

    private var currentStreamSource = StreamSource.IDLE

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // 💡 系统级 MediaSession 会话管理器
    private var mediaSession: MediaSession? = null

    // 💡 AirPlay 进度平滑推进器 (基于单调时钟推算，补偿 iOS 连续播放时不发 RTSP progress 的特性)
    private val airPlayStateManager = com.solo.purecast.airplay.AirPlayStateManager()
    private var airPlayProgressJob: kotlinx.coroutines.Job? = null
    private var airPlaySuspendJob: kotlinx.coroutines.Job? = null
    private var airPlayResumeWatchdogJob: kotlinx.coroutines.Job? = null
    private var airPlayAnchorRealtimeMs: Long = 0L
    private var airPlayBasePositionMs: Long = 0L

    // 💡 U3 临时兼容桥：待 U5 切换为 PlaybackSessionCoordinator 后移除
    private val fastPath = HardwareFastPathController.default
    private val tempSessionCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val tempSequenceCounter = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var tempActiveSessionId: Long = 0L
    @Volatile
    private var tempActiveEpoch: Long = 1L

    // 💡 动态网络状态监听器 (解决开机自启时 Wi-Fi 尚未就绪导致服务瘫痪的问题)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.service.i(TAG, "PlaybackService onCreate: 启动核心服务...")

        settingsRepository = SettingsRepository(this)
        createNotificationChannel()
        setupMediaSession()
        startForegroundWithTypes()
        acquireStandingLocks()
        registerNetworkCallback()

        audioFocusManager = AudioFocusManager(this) { shouldPlay ->
            if (shouldPlay) {
                resumePlayback()
            } else {
                pausePlayback()
            }
        }

        val idleTimeout = settingsRepository.getIdleTimeoutSeconds()
        usbDeviceManager = UsbDeviceManager(this) { connected, name ->
            Logger.service.i(TAG, "USB DAC 硬件状态通知: connected=$connected, name=$name")
            _audioPathState.value = _audioPathState.value.copy(
                isDacConnected = connected,
                dacName = name ?: if (connected) "Sony DAC" else "未检测到 USB DAC",
                isExclusive = usbDeviceManager.isExclusive()
            )
            updateNotification()
        }.apply {
            setIdleTimeoutSeconds(idleTimeout)
        }

        // 冷启动扫描已连接的 USB DAC
        usbDeviceManager.scanAndAttachDevice()

        // 💡 启动 UPnP / DLNA MediaRenderer 服务端 (友好名称末尾追加 [UPnP] 协议后缀)
        val friendlyName = settingsRepository.getFriendlyName()
        if (settingsRepository.isUpnpEnabled()) {
            upnpServer = createUpnpServer(formatUpnpName(friendlyName)).apply {
                start()
            }
        }

        // 💡 启动原生 AirPlay 1 (RAOP) 接收服务端 (友好名称末尾追加 [AirPlay] 协议后缀)
        airPlayServer = createAirPlayServer()

        if (settingsRepository.isAirPlayEnabled()) {
            airPlayServer?.start(formatAirPlayName(friendlyName))
        }

        startBootNetworkWatchdog()
    }

    /**
     * 💡 开机冷启动阶段 Wi-Fi 就绪兜底守护：前 60 秒每隔 2~5 秒自检并尝试刷新一次网络广播
     * 防止 Android TV 固件在开机瞬间 Wi-Fi 建立连通时漏发或延迟派发 NetworkCallback
     */
    private fun startBootNetworkWatchdog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val checkDelays = listOf(2000L, 3000L, 5000L, 5000L, 10000L, 15000L, 20000L)
            for (d in checkDelays) {
                delay(d)
                val airplayBound = airPlayServer?.getCurrentBoundIp()
                val upnpBound = upnpServer?.getActiveIpAddress()

                val isAirplayOk = airplayBound != null &&
                        airplayBound.hostAddress?.startsWith("192.168.48.") != true &&
                        airplayBound.hostAddress?.startsWith("127.") != true
                val isUpnpOk = upnpBound != null &&
                        !upnpBound.startsWith("192.168.48.") &&
                        upnpBound != "127.0.0.1"

                if (!isAirplayOk || !isUpnpOk) {
                    Logger.service.i(TAG, "开机广播网络巡检守护：检测到未绑定至有效局域网 IP (AirPlay=${airplayBound?.hostAddress}, UPnP=$upnpBound)，触发自愈刷新...")
                    withContext(Dispatchers.Main) {
                        refreshNetworkServices()
                    }
                }
            }
        }
    }

    /**
     * 💡 初始化系统级 MediaSession
     */
    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession(this, "PureCastMediaSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        Logger.service.i(TAG, "MediaSession 收到 onPlay 回调")
                        if (currentStreamSource == StreamSource.AIRPLAY) {
                            airPlayServer?.dacpClient?.play()
                        }
                        resumePlayback()
                        upnpServer?.genaNotifier?.notifyTransportState("PLAYING")
                    }

                    override fun onPause() {
                        Logger.service.i(TAG, "MediaSession 收到 onPause 回调")
                        if (currentStreamSource == StreamSource.AIRPLAY) {
                            airPlayServer?.dacpClient?.pause()
                        }
                        pausePlayback()
                        upnpServer?.genaNotifier?.notifyTransportState("PAUSED_PLAYBACK")
                    }

                    override fun onStop() {
                        Logger.service.i(TAG, "MediaSession 收到 onStop 回调")
                        if (currentStreamSource == StreamSource.AIRPLAY) {
                            airPlayServer?.dacpClient?.stop()
                        }
                        stopPlayback()
                        upnpServer?.genaNotifier?.notifyTransportState("STOPPED")
                    }

                    override fun onSkipToNext() {
                        Logger.service.i(TAG, "MediaSession 收到 onSkipToNext 回调")
                        if (currentStreamSource == StreamSource.AIRPLAY) {
                            airPlayServer?.dacpClient?.next()
                        } else {
                            handleNextTrack()
                        }
                    }

                    override fun onSkipToPrevious() {
                        Logger.service.i(TAG, "MediaSession 收到 onSkipToPrevious 回调")
                        if (currentStreamSource == StreamSource.AIRPLAY) {
                            airPlayServer?.dacpClient?.previous()
                        } else {
                            handlePreviousTrack()
                        }
                    }

                    override fun onSeekTo(pos: Long) {
                        Logger.service.i(TAG, "MediaSession 收到 onSeekTo: $pos ms")
                    }
                })
                isActive = true
            }
            updateMediaSessionState()
            updateMediaSessionMetadata(_currentMetadata.value)
            Logger.service.i(TAG, "MediaSession 初始化完成并已激活")
        } catch (e: Exception) {
            Logger.service.e(TAG, "初始化 MediaSession 异常", e)
        }
    }

    /**
     * 💡 同步更新 MediaSession 播放状态与可用动作
     */
    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        val status = _playbackStatus.value
        val state = when (status) {
            PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            PlaybackStatus.STOPPED -> PlaybackState.STATE_STOPPED
            PlaybackStatus.IDLE -> PlaybackState.STATE_NONE
        }

        val baseActions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS

        val actions = if (_currentMetadata.value.isLiveStream) {
            baseActions
        } else {
            baseActions or PlaybackState.ACTION_SEEK_TO
        }

        val currentPosMs = when (currentStreamSource) {
            StreamSource.AIRPLAY -> _playbackPositionMs.value
            else -> (upnpServer?.getCurrentPlayPositionSeconds() ?: 0L) * 1000L
        }
        val playbackSpeed = if (status == PlaybackStatus.PLAYING) 1.0f else 0.0f

        val playbackState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, currentPosMs, playbackSpeed)
            .build()

        session.setPlaybackState(playbackState)
    }

    /**
     * 💡 同步更新 MediaSession 歌曲元数据
     */
    private fun updateMediaSessionMetadata(metadata: TrackMetadata) {
        val session = mediaSession ?: return
        try {
            val mediaMetadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, metadata.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, metadata.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, metadata.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, metadata.durationMs)
                .build()
            session.setMetadata(mediaMetadata)
        } catch (e: Exception) {
            Logger.service.w(TAG, "更新 MediaMetadata 异常", e)
        }
    }

    /**
     * 获取基础常驻锁 (组播锁与 CPU WakeLock 保证局域网保活)
     */
    private fun acquireStandingLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 💡 必须获取 MulticastLock，否则电视机休眠或息屏时无法响应 SSDP M-SEARCH 广播
            multicastLock = wifiManager.createMulticastLock("PureCast:MulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Logger.service.i(TAG, "成功获取 Wi-Fi 组播锁 (MulticastLock)")

            // 获取 CPU WakeLock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PureCast:WakeLock").apply {
                acquire(12 * 60 * 60 * 1000L) // 最长保护 12 小时
            }
            Logger.service.i(TAG, "成功获取 CPU WakeLock")
        } catch (e: Exception) {
            Logger.service.w(TAG, "获取系统常驻锁异常", e)
        }
    }

    /**
     * 💡 动态按需激活 Wi-Fi 低延迟锁 (仅在推流播放中持有)
     */
    private fun acquireWifiLowLatencyLock() {
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "PureCast:WifiLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Logger.service.i(TAG, "推流中: 已激活 Wi-Fi 低延迟锁 (WIFI_MODE_FULL_LOW_LATENCY)")
                }
            }
        } catch (e: Exception) {
            Logger.service.w(TAG, "激活 Wi-Fi 低延迟锁异常", e)
        }
    }

    /**
     * 💡 释放 Wi-Fi 低延迟锁进入节能待机
     */
    private fun releaseWifiLowLatencyLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Logger.service.i(TAG, "待机/暂停: 已释放 Wi-Fi 低延迟锁以降低电视功耗")
                }
            }
        } catch (e: Exception) {
            Logger.service.w(TAG, "释放 Wi-Fi 低延迟锁异常", e)
        }
    }

    private fun releaseSystemLocks() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            wakeLock?.let { if (it.isHeld) it.release() }
            Logger.service.i(TAG, "已安全释放所有系统锁")
        } catch (e: Exception) {
            Logger.service.w(TAG, "释放系统锁异常", e)
        }
    }

    /**
     * 💡 突破 Android 后台活动限制 (BAL)：收到推流时尝试将播放界面带至前台
     */
    fun wakeUpUiIfInBackground() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            startActivity(intent)
            Logger.service.i(TAG, "收到推流，尝试通过 FLAG_ACTIVITY_NEW_TASK 唤醒 MainActivity 大屏界面")
        } catch (e: Exception) {
            Logger.service.w(TAG, "后台唤醒 Activity 遇到 BAL 规则限制 (将依赖全屏通知与音频直通保底): ${e.message}")
        }
    }

    /**
     * 收到推流开始指令
     */
    fun startStreaming(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int = 2,
        source: StreamSource = StreamSource.UPNP,
        codec: String = "PCM"
    ): Boolean {
        Logger.service.i(TAG, "收到流式播放请求 (source=$source, codec=$codec): $sampleRate Hz, $bitDepth bit, $channels ch")

        // 💡 跨协议后发抢占保护：若 UPnP 推流启动而 AirPlay 正在播放，优雅中止 AirPlay
        if (source == StreamSource.UPNP && currentStreamSource == StreamSource.AIRPLAY) {
            Logger.service.i(TAG, "UPnP 新流到达，执行后发抢占中止正在运行的 AirPlay 流")
            airPlayServer?.stop()
        }
        currentStreamSource = source

        // 1. 动态获取低延迟锁，突破 BAL 拉起界面
        acquireWifiLowLatencyLock()
        wakeUpUiIfInBackground()

        // 2. 请求系统音频焦点
        if (!audioFocusManager.requestFocus()) {
            Logger.service.w(TAG, "申请音频焦点被系统拒绝，放弃推流")
            releaseWifiLowLatencyLock()
            return false
        }

        // 3. 接管声卡开启独占
        val claimed = usbDeviceManager.claimUsbExclusive(sampleRate, bitDepth, channels)
        if (!claimed) {
            Logger.service.w(TAG, "接管 USB 声卡独占失败，释放音频焦点与低延迟锁")
            audioFocusManager.abandonFocus()
            releaseWifiLowLatencyLock()
            return false
        }

        // 💡 确保新流开始时唤醒 Native 传输管道并重置 is_paused 状态
        fastPath.resumeStream()

        _playbackStatus.value = PlaybackStatus.PLAYING
        val currentProto = if (currentStreamSource == StreamSource.AIRPLAY) {
            com.solo.purecast.model.StreamProtocol.AIRPLAY_1
        } else {
            com.solo.purecast.model.StreamProtocol.UPNP_DLNA
        }
        val resolvedCodec = when (source) {
            StreamSource.AIRPLAY -> if (codec.isNotBlank()) codec else "ALAC"
            StreamSource.UPNP -> if (codec.isNotBlank()) codec else "PCM"
            else -> codec
        }
        _audioPathState.value = _audioPathState.value.copy(
            isExclusive = true,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            protocol = currentProto,
            codec = resolvedCodec
        )

        // 💡 若为 AirPlay 会话，启动单调时钟平滑进度推进器
        if (currentStreamSource == StreamSource.AIRPLAY) {
            airPlayResumeWatchdogJob?.cancel()
            airPlayResumeWatchdogJob = null
            airPlaySuspendJob?.cancel()
            airPlaySuspendJob = null
            if (_playbackPositionMs.value > 0L) {
                airPlayBasePositionMs = _playbackPositionMs.value
            } else {
                airPlayBasePositionMs = 0L
            }
            airPlayAnchorRealtimeMs = android.os.SystemClock.elapsedRealtime()
            launchAirPlayProgressTicker()
        }

        updateMediaSessionState()
        updateNotification()
        return true
    }

    /**
     * 💡 原生 AirPlay 1 推流请求事件 (SETUP 触发)
     */
    private fun onAirPlayStreamRequested(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        codec: String,
        clientIp: String,
        dacpPort: Int,
        token: String
    ): Boolean {
        Logger.service.i(TAG, "处理 AirPlay 推流请求 (后发抢占): client=$clientIp, codec=$codec")
        airPlaySuspendJob?.cancel()
        airPlaySuspendJob = null

        // 💡 后发抢占：若当前正在运行 UPnP 流，立即优雅中止旧流
        if (currentStreamSource == StreamSource.UPNP) {
            Logger.service.i(TAG, "AirPlay 新流到达，执行后发抢占中止旧 UPnP 流")
            upnpServer?.stopPlayback()
            upnpServer?.notifyTransportState("STOPPED")
        }

        currentStreamSource = StreamSource.AIRPLAY
        val started = startStreaming(sampleRate, bitDepth, channels, StreamSource.AIRPLAY, codec = codec)
        if (!started) {
            currentStreamSource = StreamSource.IDLE
        }
        return started
    }

    /**
     * 💡 启动 10 分钟软挂起超时守护：若长时间未恢复，彻底释放 DACP 会话与声卡独占
     */
    private fun scheduleAirPlaySuspendTimeout() {
        airPlaySuspendJob?.cancel()
        airPlaySuspendJob = lifecycleScope.launch(Dispatchers.Default) {
            delay(10 * 60 * 1000L)
            if (currentStreamSource == StreamSource.AIRPLAY && _playbackStatus.value == PlaybackStatus.PAUSED) {
                Logger.service.i(TAG, "AirPlay 挂起待机超时 (10分钟)，彻底释放会话与声卡")
                stopPlayback(StreamSource.AIRPLAY)
                airPlayServer?.clearDacpSession()
                _audioPathState.value = _audioPathState.value.copy(
                    protocol = com.solo.purecast.model.StreamProtocol.NONE
                )
            }
        }
    }

    /**
     * 💡 原生 AirPlay 1 推流停止事件 (TEARDOWN 触发)
     */
    private fun onAirPlayStreamStopped() {
        if (currentStreamSource == StreamSource.AIRPLAY) {
            Logger.service.i(TAG, "AirPlay 音频传输通道断开 (TEARDOWN)，进入挂起暂停状态并保持 DACP 会话与元数据")
            airPlayProgressJob?.cancel()
            airPlayProgressJob = null
            airPlayBasePositionMs = _playbackPositionMs.value

            fastPath.emergencyMuteAndFlush(FlushReason.EXPLICIT_PAUSE)
            _playbackStatus.value = PlaybackStatus.PAUSED
            releaseWifiLowLatencyLock()
            updateMediaSessionState()
            updateNotification()

            scheduleAirPlaySuspendTimeout()
        }
    }

    /**
     * 💡 原生 AirPlay 1 推流暂停事件 (iPhone 点击暂停或断流触发)
     */
    private fun onAirPlayStreamPaused() {
        if (currentStreamSource == StreamSource.AIRPLAY && _playbackStatus.value == PlaybackStatus.PLAYING) {
            Logger.service.i(TAG, "AirPlay 数据流暂停 (iPhone 暂停推流)，同步 TV 端播放状态机为 PAUSED")
            airPlayProgressJob?.cancel()
            airPlayProgressJob = null
            airPlayBasePositionMs = _playbackPositionMs.value

            fastPath.emergencyMuteAndFlush(FlushReason.EXPLICIT_PAUSE)
            _playbackStatus.value = PlaybackStatus.PAUSED
            releaseWifiLowLatencyLock()
            updateMediaSessionState()
            updateNotification()

            scheduleAirPlaySuspendTimeout()
        }
    }

    /**
     * 💡 原生 AirPlay 1 推流恢复事件 (iPhone 恢复播放触发)
     */
    private fun onAirPlayStreamResumed() {
        if (currentStreamSource == StreamSource.AIRPLAY && _playbackStatus.value == PlaybackStatus.PAUSED) {
            Logger.service.i(TAG, "AirPlay 数据流恢复 (iPhone 恢复推流)，同步 TV 端播放状态机为 PLAYING")
            airPlaySuspendJob?.cancel()
            airPlaySuspendJob = null
            acquireWifiLowLatencyLock()
            usbDeviceManager.cancelIdleRelease()
            if (!usbDeviceManager.isExclusive()) {
                val state = _audioPathState.value
                usbDeviceManager.claimUsbExclusive(state.sampleRate, state.bitDepth, state.channels)
            }
            fastPath.resumeStream()
            _playbackStatus.value = PlaybackStatus.PLAYING
            airPlayAnchorRealtimeMs = android.os.SystemClock.elapsedRealtime()
            launchAirPlayProgressTicker()
            updateMediaSessionState()
            updateNotification()
        }
    }

    /**
     * 💡 原生 AirPlay 1 DMAP 元数据更新事件 (SET_PARAMETER 触发)
     * 支持多包增量合并 (Smart Merge)，杜绝后序元数据包将封面或歌名相互覆盖冲刷
     */
    private fun onAirPlayMetadataReceived(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverArt: ByteArray?
    ) {
        val current = _currentMetadata.value
        val isNewTrack = (title.isNotEmpty() && title != current.title) ||
                (artist.isNotEmpty() && artist != current.artist)

        val mergedTitle = when {
            title.isNotEmpty() -> title
            current.title.isNotEmpty() && current.title != "AirPlay 音频直通" -> current.title
            else -> "AirPlay 音频直通"
        }
        val mergedArtist = when {
            artist.isNotEmpty() -> artist
            current.artist.isNotEmpty() && current.artist != "AirPlay 设备" -> current.artist
            else -> "AirPlay 设备"
        }
        val mergedAlbum = if (album.isNotEmpty()) album else current.album
        // 💡 模型真实性保证：换歌时若新曲目暂无封面（如无封面的网络电台/播客），模型层置 null，
        // 视觉平滑过渡与旧封面暂留由 UI 层的 persistentCover 负责兜底并设置 1.2s 超时优雅清除。
        val mergedCover = when {
            coverArt != null && coverArt.isNotEmpty() -> coverArt
            isNewTrack -> null
            else -> current.coverBytes
        }
        // 💡 换歌进入直播流/无时长电台保护：若为新曲目且 durationMs 未指定或 <= 0，
        // 必须置 0 并判定为直播流 (isLiveStream = true)，绝不沿用上一曲固定时长导致状态误判
        val mergedDuration = if (durationMs > 0) durationMs else if (isNewTrack) 0L else current.durationMs
        val isLive = mergedDuration <= 0

        if (isNewTrack) {
            // 💡 换歌时重置在途进度并取消旧 Ticker，避免上一曲尾部时间戳引发进度条跳动
            airPlayProgressJob?.cancel()
            airPlayProgressJob = null
            airPlayBasePositionMs = 0L
            _playbackPositionMs.value = 0L
        }

        val meta = TrackMetadata(
            title = mergedTitle,
            artist = mergedArtist,
            album = mergedAlbum,
            coverBytes = mergedCover,
            durationMs = mergedDuration,
            isLiveStream = isLive
        )
        updateMetadata(meta)
    }

    /**
     * 💡 原生 AirPlay 1 播放进度与总时长更新 (SET_PARAMETER progress: start/curr/end 触发)
     */
    private fun onAirPlayProgressUpdated(positionMs: Long, durationMs: Long) {
        // 💡 委托 AirPlayStateManager 过滤全零无效时间戳 (如 progress: 0/0/0)，杜绝暂停或缓冲时将当前进度冲刷归零
        if (!airPlayStateManager.handleProgressUpdate(positionMs, durationMs)) {
            return
        }
        val current = _currentMetadata.value
        val resolvedDuration = if (durationMs > 0) durationMs else current.durationMs
        if (resolvedDuration > 0 && current.durationMs != resolvedDuration) {
            val updated = current.copy(durationMs = resolvedDuration, isLiveStream = false)
            updateMetadata(updated)
        }
        val clampedPos = if (resolvedDuration > 0) positionMs.coerceIn(0L, resolvedDuration) else positionMs
        startOrUpdateAirPlayProgress(clampedPos, resolvedDuration)
        updateMediaSessionState()
    }

    private fun startOrUpdateAirPlayProgress(positionMs: Long, durationMs: Long) {
        airPlayBasePositionMs = positionMs
        airPlayAnchorRealtimeMs = android.os.SystemClock.elapsedRealtime()
        _playbackPositionMs.value = positionMs

        if (_playbackStatus.value == PlaybackStatus.PLAYING && currentStreamSource == StreamSource.AIRPLAY) {
            launchAirPlayProgressTicker()
        }
    }

    private fun launchAirPlayProgressTicker() {
        airPlayProgressJob?.cancel()
        airPlayProgressJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && _playbackStatus.value == PlaybackStatus.PLAYING && currentStreamSource == StreamSource.AIRPLAY) {
                delay(500)
                val elapsed = android.os.SystemClock.elapsedRealtime() - airPlayAnchorRealtimeMs
                val duration = _currentMetadata.value.durationMs
                var calculatedPos = airPlayBasePositionMs + elapsed
                if (duration > 0 && calculatedPos > duration) {
                    calculatedPos = duration
                }
                _playbackPositionMs.value = calculatedPos
            }
        }
    }

    fun pausePlayback() {
        Logger.service.i(TAG, "暂停音频播放 (流源: $currentStreamSource)...")
        airPlayResumeWatchdogJob?.cancel()
        airPlayResumeWatchdogJob = null
        airPlayProgressJob?.cancel()
        airPlayProgressJob = null
        airPlayBasePositionMs = _playbackPositionMs.value

        if (currentStreamSource == StreamSource.AIRPLAY) {
            airPlayServer?.dacpClient?.pause()
            scheduleAirPlaySuspendTimeout()
        } else if (currentStreamSource == StreamSource.UPNP) {
            // 💡 UPnP 暂停：挂起 10 分钟闲置让渡任务，防止无限占用 USB 声卡
            usbDeviceManager.scheduleIdleRelease(10 * 60 * 1000L)
        }

        fastPath.emergencyMuteAndFlush(FlushReason.EXPLICIT_PAUSE)
        _playbackStatus.value = PlaybackStatus.PAUSED
        releaseWifiLowLatencyLock()
        // 💡 暂停期间不触发 10 秒激进让渡，保持 USB DAC 独占与时钟锁相
        upnpServer?.notifyTransportState("PAUSED_PLAYBACK")
        updateMediaSessionState()
        updateNotification()
    }

    fun resumePlayback() {
        Logger.service.i(TAG, "恢复音频播放 (流源: $currentStreamSource)...")
        airPlayResumeWatchdogJob?.cancel()
        airPlayResumeWatchdogJob = null
        airPlaySuspendJob?.cancel()
        airPlaySuspendJob = null
        acquireWifiLowLatencyLock()
        usbDeviceManager.cancelIdleRelease()
        if (!usbDeviceManager.isExclusive()) {
            val state = _audioPathState.value
            usbDeviceManager.claimUsbExclusive(state.sampleRate, state.bitDepth, state.channels)
        }

        if (currentStreamSource == StreamSource.AIRPLAY) {
            val rtpActive = airPlayServer?.isRtpActive == true
            airPlayServer?.dacpClient?.play()
            if (!rtpActive) {
                Logger.service.i(TAG, "AirPlay RTP 通道已挂起断开 (处于 TEARDOWN 状态)，已通过 DACP 发送 play，等待客户端重连建流...")
                // 启动 5 秒重连看门狗，若超时未收到 SETUP/RECORD 建流则维持 PAUSED 并告警
                airPlayResumeWatchdogJob = lifecycleScope.launch(Dispatchers.Default) {
                    delay(5000L)
                    if (currentStreamSource == StreamSource.AIRPLAY && _playbackStatus.value == PlaybackStatus.PAUSED) {
                        Logger.service.w(TAG, "AirPlay 恢复播放超时 (5秒内未收到客户端重连 RTP)，维持 PAUSED 状态")
                        releaseWifiLowLatencyLock()
                        scheduleAirPlaySuspendTimeout()
                    }
                }
                return
            }
            airPlayAnchorRealtimeMs = android.os.SystemClock.elapsedRealtime()
            launchAirPlayProgressTicker()
        }

        fastPath.resumeStream()
        _playbackStatus.value = PlaybackStatus.PLAYING

        upnpServer?.notifyTransportState("PLAYING")
        updateMediaSessionState()
        updateNotification()
    }

    fun stopPlayback(source: StreamSource? = null) {
        if (source != null && currentStreamSource != source && currentStreamSource != StreamSource.IDLE) {
            Logger.service.w(TAG, "忽略非当前流源 ($source) 的 stopPlayback 请求，当前源为: $currentStreamSource")
            return
        }
        Logger.service.i(TAG, "停止音频播放 (流源: $currentStreamSource)...")
        airPlayResumeWatchdogJob?.cancel()
        airPlayResumeWatchdogJob = null
        airPlaySuspendJob?.cancel()
        airPlaySuspendJob = null
        airPlayProgressJob?.cancel()
        airPlayProgressJob = null
        airPlayBasePositionMs = 0L
        airPlayAnchorRealtimeMs = 0L

        if (currentStreamSource == StreamSource.AIRPLAY) {
            airPlayServer?.dacpClient?.stop()
            airPlayServer?.clearDacpSession()
        }

        currentStreamSource = StreamSource.IDLE
        _audioPathState.value = _audioPathState.value.copy(
            protocol = com.solo.purecast.model.StreamProtocol.NONE
        )

        fastPath.emergencyMuteAndFlush(FlushReason.STOP_OR_TEARDOWN)
        _playbackStatus.value = PlaybackStatus.STOPPED
        _playbackPositionMs.value = 0L
        releaseWifiLowLatencyLock()
        usbDeviceManager.scheduleIdleRelease() // 启动 10 秒空闲让渡
        audioFocusManager.abandonFocus()
        upnpServer?.notifyTransportState("STOPPED")
        updateMediaSessionState()
        updateNotification()
    }

    fun handleNextTrack() {
        Logger.service.i(TAG, "用户触发切下一曲 (流源: $currentStreamSource)")
        if (currentStreamSource == StreamSource.AIRPLAY) {
            airPlayServer?.dacpClient?.next()
        } else {
            Logger.service.w(TAG, "UPnP 推流由发送端控制曲目列表，不支持 TV 遥控器切歌")
        }
    }

    fun handlePreviousTrack() {
        Logger.service.i(TAG, "用户触发切上一曲 (流源: $currentStreamSource)")
        if (currentStreamSource == StreamSource.AIRPLAY) {
            airPlayServer?.dacpClient?.previous()
        } else {
            Logger.service.w(TAG, "UPnP 推流由发送端控制曲目列表，不支持 TV 遥控器切上一曲")
        }
    }

    fun updatePlaybackPosition(posMs: Long) {
        _playbackPositionMs.value = posMs
    }

    fun updateServerError(error: String?) {
        _serverError.value = error
    }

    fun updateMetadata(metadata: TrackMetadata) {
        _currentMetadata.value = metadata
        updateMediaSessionMetadata(metadata)
        updateNotification()
    }

    /**
     * 💡 更新 UPnP 与 AirPlay 局域网广播名称并即时通知服务更新 (自动追加 [UPnP] / [AirPlay] 协议后缀)
     */
    fun updateFriendlyName(newName: String) {
        settingsRepository.setFriendlyName(newName)
        upnpServer?.updateFriendlyName(formatUpnpName(newName))
        airPlayServer?.updateDeviceName(formatAirPlayName(newName))
    }

    /**
     * 💡 动态开启或关闭原生 AirPlay 1 服务
     */
    fun setAirPlayEnabled(enabled: Boolean) {
        settingsRepository.setAirPlayEnabled(enabled)
        if (enabled) {
            val name = settingsRepository.getFriendlyName()
            airPlayServer?.start(formatAirPlayName(name))
        } else {
            airPlayServer?.stop()
        }
    }

    /**
     * 💡 动态开启或关闭 UPnP / DLNA 服务
     */
    fun setUpnpEnabled(enabled: Boolean) {
        settingsRepository.setUpnpEnabled(enabled)
        if (enabled) {
            val name = settingsRepository.getFriendlyName()
            if (upnpServer == null) {
                upnpServer = createUpnpServer(formatUpnpName(name))
            } else {
                upnpServer?.updateFriendlyName(formatUpnpName(name))
            }
            upnpServer?.start()
        } else {
            upnpServer?.stop()
        }
    }

    fun setIdleTimeoutSeconds(seconds: Int) {
        settingsRepository.setIdleTimeoutSeconds(seconds)
        usbDeviceManager.setIdleTimeoutSeconds(seconds)
    }

    fun requestUsbPermission(activity: android.app.Activity) {
        usbDeviceManager.requestPermissionWithActivity(activity)
    }

    fun scanUsbDevice(): Boolean {
        return usbDeviceManager.scanAndAttachDevice()
    }

    private fun createNotificationChannel() {
        // 💡 使用 IMPORTANCE_HIGH 确保全屏通知 fullScreenIntent 能够在后台成功展示
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.service_notification_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val metadata = _currentMetadata.value
        val isPlaying = _playbackStatus.value == PlaybackStatus.PLAYING

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(metadata.title)
            .setContentText("${metadata.artist} • ${if (isPlaying) "Bit-Perfect 独占播放中" else "待机"}")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, isPlaying) // 💡 突破 BAL 限制的全屏通知保底
            .setStyle(mediaStyle)
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

    fun handleUsbDeviceAttachedFromIntent(device: android.hardware.usb.UsbDevice) {
        usbDeviceManager.onDeviceAttachedFromIntent(device)
    }

    fun handleUsbDeviceDetached(device: android.hardware.usb.UsbDevice?) {
        Logger.service.w(TAG, "收到 USB 设备拔出通知: ${device?.deviceName}")
        val isCurrentDacDetached = usbDeviceManager.handleDeviceDetached(device)
        if (isCurrentDacDetached && _playbackStatus.value == PlaybackStatus.PLAYING) {
            Logger.service.i(TAG, "当前活跃的 USB DAC 已物理拔出，自动暂停当前音频播放")
            pausePlayback()
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logger.service.i(TAG, "系统网络已连通，自检并刷新 UPnP / AirPlay 广播服务...")
                lifecycleScope.launch(Dispatchers.Main) {
                    refreshNetworkServices()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Logger.service.i(TAG, "系统网络链路属性变更 (IP地址变动)，刷新广播服务...")
                lifecycleScope.launch(Dispatchers.Main) {
                    refreshNetworkServices()
                }
            }

            override fun onLost(network: Network) {
                Logger.service.w(TAG, "系统网络已断开")
            }
        }
        networkCallback = callback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            Logger.service.i(TAG, "已成功注册网络状态监听器 (NetworkCallback)")
        } catch (e: Exception) {
            Logger.service.w(TAG, "按条件注册 NetworkCallback 失败，尝试默认注册", e)
            try {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } catch (e2: Exception) {
                Logger.service.e(TAG, "注册 DefaultNetworkCallback 亦失败", e2)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                connectivityManager?.unregisterNetworkCallback(it)
                Logger.service.i(TAG, "注销 NetworkCallback 监听器")
            } catch (e: Exception) {
                Logger.service.w(TAG, "注销 NetworkCallback 异常", e)
            }
            networkCallback = null
        }
    }

    @Synchronized
    fun refreshNetworkServices() {
        val friendlyName = settingsRepository.getFriendlyName()
        if (settingsRepository.isUpnpEnabled()) {
            if (upnpServer == null) {
                upnpServer = createUpnpServer(formatUpnpName(friendlyName)).apply {
                    start()
                }
            } else {
                upnpServer?.restartIfIpChanged()
            }
        }
        if (settingsRepository.isAirPlayEnabled()) {
            if (airPlayServer == null) {
                airPlayServer = createAirPlayServer().apply {
                    start(formatAirPlayName(friendlyName))
                }
            } else {
                airPlayServer?.restartIfIpChanged(formatAirPlayName(friendlyName))
            }
        }
    }

    private fun createUpnpServer(friendlyName: String): com.solo.purecast.upnp.UpnpRendererServer {
        return com.solo.purecast.upnp.UpnpRendererServer(
            scope = lifecycleScope,
            dispatchEvent = { envelope -> handleLegacySessionEnvelope(envelope) },
            allocateSessionId = { tempSessionCounter.incrementAndGet() },
            allocateSequence = { tempSequenceCounter.incrementAndGet() },
            onAcquireHardwareForStream = { sampleRate, bitDepth, channels, codec ->
                val started = startStreaming(sampleRate, bitDepth, channels, StreamSource.UPNP, codec = codec)
                if (!started) {
                    -1L
                } else {
                    val epoch = fastPath.getCurrentBufferEpoch().coerceAtLeast(1L)
                    tempActiveEpoch = epoch
                    epoch
                }
            },
            snapshotProvider = { buildLegacySessionSnapshot() },
            fastPath = fastPath,
            onWakeUpUi = { wakeUpUiIfInBackground() },
            friendlyName = friendlyName
        )
    }

    private fun createAirPlayServer(): com.solo.purecast.airplay.AirPlayServerManager {
        return com.solo.purecast.airplay.AirPlayServerManager(
            context = this,
            scope = lifecycleScope,
            dispatchEvent = { envelope -> handleLegacySessionEnvelope(envelope) },
            allocateSessionId = { tempSessionCounter.incrementAndGet() },
            allocateSequence = { tempSequenceCounter.incrementAndGet() },
            onAcquireHardwareForStream = { sampleRate, bitDepth, channels, codec ->
                Logger.service.i(TAG, "处理 AirPlay 推流请求 (U3 桥接抢占): codec=$codec")
                airPlaySuspendJob?.cancel()
                airPlaySuspendJob = null
                if (currentStreamSource == StreamSource.UPNP) {
                    upnpServer?.stopPlayback(isPreempted = true)
                }
                currentStreamSource = StreamSource.AIRPLAY
                val started = startStreaming(sampleRate, bitDepth, channels, StreamSource.AIRPLAY, codec = codec)
                if (!started) {
                    currentStreamSource = StreamSource.IDLE
                    -1L
                } else {
                    val epoch = fastPath.getCurrentBufferEpoch().coerceAtLeast(1L)
                    tempActiveEpoch = epoch
                    epoch
                }
            }
        )
    }

    private fun buildLegacySessionSnapshot(): PlayerSessionSnapshot {
        val business = when (_playbackStatus.value) {
            PlaybackStatus.IDLE -> BusinessSessionState.IDLE
            PlaybackStatus.PLAYING -> BusinessSessionState.PLAYING
            PlaybackStatus.PAUSED -> BusinessSessionState.PAUSED
            PlaybackStatus.STOPPED -> BusinessSessionState.STOPPED
        }
        val protocol = when (currentStreamSource) {
            StreamSource.AIRPLAY -> StreamProtocol.AIRPLAY_1
            StreamSource.UPNP -> StreamProtocol.UPNP_DLNA
            StreamSource.IDLE -> StreamProtocol.NONE
        }
        return PlayerSessionSnapshot(
            sessionId = tempActiveSessionId,
            businessState = business,
            activeProtocol = protocol,
            currentEpoch = tempActiveEpoch,
            metadata = _currentMetadata.value,
            audioPath = _audioPathState.value,
            progressPositionMs = _playbackPositionMs.value,
            progressDurationMs = _currentMetadata.value.durationMs
        )
    }

    private fun handleLegacySessionEnvelope(envelope: SessionEventEnvelope<SessionEventPayload>) {
        when (val payload = envelope.payload) {
            is StreamSetupEvent -> {
                tempActiveSessionId = envelope.sessionId
                tempActiveEpoch = payload.initialEpoch
                payload.initialMetadata?.let { updateMetadata(it) }
            }

            is FirstDataPacketEvent -> {
                tempActiveEpoch = payload.epoch
                if (envelope.origin == EventOrigin.AIRPLAY) {
                    onAirPlayStreamResumed()
                } else {
                    _playbackStatus.value = PlaybackStatus.PLAYING
                    updateMediaSessionState()
                    updateNotification()
                }
            }

            is TrackSwitchEvent -> {
                tempActiveEpoch = payload.newEpoch
                updatePlaybackPosition(0L)
            }

            is ExplicitPauseEvent, is ImplicitPauseEvent -> {
                if (envelope.origin == EventOrigin.AIRPLAY) {
                    onAirPlayStreamPaused()
                } else {
                    pausePlayback()
                }
            }

            is StreamResumeEvent -> {
                tempActiveEpoch = payload.resumedEpoch
                if (envelope.origin == EventOrigin.AIRPLAY) {
                    onAirPlayStreamResumed()
                } else {
                    resumePlayback()
                }
            }

            is TrackCompleteEvent -> {
                if (envelope.origin == EventOrigin.UPNP) {
                    stopPlayback(StreamSource.UPNP)
                }
                if (envelope.sessionId == tempActiveSessionId) {
                    tempActiveSessionId = 0L
                }
            }

            is StreamStopEvent -> {
                if (!payload.isPreempted) {
                    stopPlayback(if (envelope.origin == EventOrigin.AIRPLAY) StreamSource.AIRPLAY else StreamSource.UPNP)
                }
                if (envelope.sessionId == tempActiveSessionId) {
                    tempActiveSessionId = 0L
                }
            }

            is StreamTeardownEvent -> {
                if (payload.hardStop) {
                    stopPlayback(StreamSource.AIRPLAY)
                    if (envelope.sessionId == tempActiveSessionId) {
                        tempActiveSessionId = 0L
                    }
                } else {
                    onAirPlayStreamStopped()
                }
            }

            is MetadataPatchEvent -> {
                if (envelope.origin == EventOrigin.AIRPLAY) {
                    onAirPlayMetadataReceived(
                        title = payload.title.orEmpty(),
                        artist = payload.artist.orEmpty(),
                        album = payload.album.orEmpty(),
                        durationMs = payload.durationMs ?: 0L,
                        coverArt = payload.coverBytes
                    )
                } else {
                    val cur = _currentMetadata.value
                    val nextDurationMs = payload.durationMs ?: cur.durationMs
                    val nextDurState = payload.durationState ?: cur.durationState
                    updateMetadata(
                        cur.copy(
                            title = payload.title ?: cur.title,
                            artist = payload.artist ?: cur.artist,
                            album = payload.album ?: cur.album,
                            coverUri = payload.coverUri ?: cur.coverUri,
                            durationMs = nextDurationMs,
                            durationState = nextDurState,
                            seekCapability = payload.seekCapability ?: cur.seekCapability,
                            isLiveStream = nextDurState == DurationState.LIVE
                        )
                    )
                }
            }

            is ProgressUpdateEvent -> {
                if (envelope.origin == EventOrigin.AIRPLAY) {
                    onAirPlayProgressUpdated(payload.positionMs, payload.durationMs)
                } else {
                    updatePlaybackPosition(payload.positionMs)
                }
            }

            is HardwareReconfiguredEvent -> {
                tempActiveEpoch = payload.epoch
                _audioPathState.value = _audioPathState.value.copy(
                    sampleRate = payload.sampleRate,
                    bitDepth = payload.bitDepth,
                    channels = payload.channels
                )
            }

            is ErrorReportedEvent -> {
                updateServerError(payload.error.message)
            }

            is ErrorClearedEvent -> {
                updateServerError(null)
            }

            else -> Unit
        }
    }

    override fun onDestroy() {
        Logger.service.i(TAG, "PlaybackService onDestroy: 彻底清理服务...")
        instance = null
        unregisterNetworkCallback()
        airPlaySuspendJob?.cancel()
        airPlaySuspendJob = null
        airPlayProgressJob?.cancel()
        airPlayProgressJob = null
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        upnpServer?.stop()
        upnpServer = null
        airPlayServer?.stop()
        airPlayServer = null
        releaseWifiLowLatencyLock()
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

        private val _playbackPositionMs = MutableStateFlow(0L)
        val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

        private val _serverError = MutableStateFlow<String?>(null)
        val serverError: StateFlow<String?> = _serverError.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 💡 为自定义广播名称追加 AirPlay 协议标识后缀 (如 PureCast TV [AirPlay])
         */
        fun formatAirPlayName(baseName: String): String {
            val clean = baseName.removeSuffix(" [AirPlay]").removeSuffix(" [UPnP]").trim()
            return "$clean [AirPlay]"
        }

        /**
         * 💡 为自定义广播名称追加 UPnP 协议标识后缀 (如 PureCast TV [UPnP])
         */
        fun formatUpnpName(baseName: String): String {
            val clean = baseName.removeSuffix(" [AirPlay]").removeSuffix(" [UPnP]").trim()
            return "$clean [UPnP]"
        }
    }
}
