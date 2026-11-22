package com.solo.purecast.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.solo.purecast.R
import com.solo.purecast.airplay.AirPlayServerManager
import com.solo.purecast.core.audio.AudioFocusManager
import com.solo.purecast.core.lyrics.LyricsTriggerManager
import com.solo.purecast.core.session.HardwareFastPathController
import com.solo.purecast.core.session.PlaybackSessionCoordinator
import com.solo.purecast.core.session.ProtocolResumeReopenResult
import com.solo.purecast.core.usb.UsbDeviceManager
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.data.lyrics.LyricsCacheRepository
import com.solo.purecast.data.lyrics.LyricsWaterfallFetcher
import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.DefaultLyricsHttpClient
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.data.lyrics.remote.NeteaseDataSource
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.MainActivity
import com.solo.purecast.upnp.UpnpRendererServer
import com.solo.purecast.util.Logger
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 💡 PureCast 核心播放前台服务（薄宿主容器）
 * 深度集成：
 * 1. UsbDeviceManager: 冷启动设备识别、Linux usbfs fd 穿透、Serial HardwareExecutor 与空闲让渡
 * 2. PlaybackSessionCoordinator & PlaybackServiceSessionHost: 三状态正交会话协调与系统副作用桥接
 * 3. AudioFocusManager: Android 11+ 标准音频焦点抢占与外部丢失即时归还声卡
 * 4. 动态 Wi-Fi 锁管理 & 突破 BAL 限制的大屏唤醒 + fullScreenIntent 保底
 * 5. MediaSession & 前台 MediaStyle 通知同步
 * 6. UPnP / DLNA 与 AirPlay 1 (RAOP) 无状态协议服务端托管
 */
class PlaybackService : LifecycleService() {

    lateinit var usbDeviceManager: UsbDeviceManager
        private set
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var coordinator: PlaybackSessionCoordinator
    private lateinit var sessionHost: PlaybackServiceSessionHost
    private lateinit var lyricsCacheRepository: LyricsCacheRepository
    private lateinit var lyricsTriggerManager: LyricsTriggerManager

    private val fastPath = HardwareFastPathController.default

    // 💡 协调器事件循环独立运行在受限单线程 IO 后台调度器（内部包含阻塞式 libusb/HardwareExecutor 同步调用，避免占用 Dispatchers.Default 计算池）
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // 💡 轻量有序状态副作用（快照分发、MediaSession 状态、通知同步与歌词触发）串行运行在单线程后台队列
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serviceEffectsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // 💡 阻塞型冷启动/网络 IO（组播锁、NetworkCallback、UPnP/AirPlay 协议启停、封面解码）独立运行于并发 IO 池，
    // 杜绝网卡枚举或锁竞争饥饿 serviceEffectsScope 导致快照与歌词分发延迟。
    private val serviceIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val systemResourcesLock = Any()
    @Volatile
    private var isDestroyed = false

    @Volatile
    private var upnpServer: UpnpRendererServer? = null
    @Volatile
    var airPlayServer: AirPlayServerManager? = null
        private set

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent != null) {
            val attachedDevice = IntentCompat.getParcelableExtra(
                intent,
                EXTRA_ATTACHED_USB_DEVICE,
                UsbDevice::class.java
            )
            if (attachedDevice != null) {
                Logger.service.i(TAG, "从 onStartCommand 捕获到待处理 USB 设备: ${attachedDevice.productName ?: attachedDevice.deviceName}")
                handleUsbDeviceAttachedFromIntent(attachedDevice)
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Logger.service.i(TAG, "PlaybackService onCreate: 启动核心服务 (三状态协调器架构)...")

        settingsRepository = SettingsRepository.getInstance(this)
        createNotificationChannel()
        setupMediaSession()
        startForegroundWithTypes()
        // 💡 组播锁、WakeLock 与网络监听注册迁移至独立 IO 池，避免阻塞冷启动主线程与快照分发队列
        serviceIoScope.launch {
            acquireStandingLocks()
            registerNetworkCallback()
        }

        audioFocusManager = AudioFocusManager(
            context = this,
            isPlaybackActiveProvider = {
                if (::coordinator.isInitialized) {
                    coordinator.snapshot.value.playbackStatus == PlaybackStatus.PLAYING
                } else {
                    false
                }
            },
            onAudioFocusLost = {
                handleAudioFocusLost()
            }
        ) { shouldPlay ->
            if (shouldPlay) {
                resumePlayback()
            } else {
                pausePlayback()
            }
        }

        val idleTimeout = settingsRepository.getIdleTimeoutSeconds()
        usbDeviceManager = UsbDeviceManager(
            context = this,
            dispatchEvent = { envelope ->
                if (::coordinator.isInitialized) {
                    coordinator.dispatch(envelope)
                }
            }
        )

        coordinator = PlaybackSessionCoordinator(
            scope = coordinatorScope,
            hardwareBridge = usbDeviceManager,
            dacpCommandSender = { commandType, _ ->
                val client = airPlayServer?.dacpClient
                    ?: return@PlaybackSessionCoordinator Result.failure(
                        IllegalStateException("AirPlay 服务未就绪，无法发送 DACP 指令")
                    )
                client.executeRemoteCommand(commandType)
            },
            onSessionTerminated = { protocol, sessionId ->
                preemptProtocolSession(protocol, sessionId)
            },
            onProtocolResumeAfterReopen = { protocol, sessionId, newEpoch ->
                resumeProtocolAfterDeviceReopen(protocol, sessionId, newEpoch)
            },
            clockMs = { SystemClock.elapsedRealtime() }
        ).apply {
            setIdleTimeoutSeconds(idleTimeout)
        }

        val systemEffects = object : ServiceSystemEffects {
            override fun syncMediaSessionState(
                status: PlaybackStatus,
                positionMs: Long,
                seekCapability: SeekCapability
            ) {
                updateMediaSessionState(status, positionMs, seekCapability)
            }

            override fun syncMediaSessionMetadata(metadata: TrackMetadata) {
                updateMediaSessionMetadata(metadata)
            }

            override fun syncNotification(snapshot: PlayerSessionSnapshot) {
                updateNotification(snapshot)
            }

            override fun acquireWifiLowLatencyLock() {
                this@PlaybackService.acquireWifiLowLatencyLock()
            }

            override fun releaseWifiLowLatencyLock() {
                this@PlaybackService.releaseWifiLowLatencyLock()
            }

            override fun requestAudioFocus(): Boolean = audioFocusManager.requestFocus()

            override fun abandonAudioFocus() {
                audioFocusManager.abandonFocus()
            }

            override fun wakeUpUiIfInBackground() {
                this@PlaybackService.wakeUpUiIfInBackground()
            }

            override fun notifyUpnpTransportState(state: String) {
                upnpServer?.genaNotifier?.notifyTransportState(state)
            }

            override fun preemptProtocol(protocol: StreamProtocol, sessionId: Long) {
                preemptProtocolSession(protocol, sessionId)
            }

            override fun seekProtocolStream(protocol: StreamProtocol, positionMs: Long) {
                Logger.service.i(TAG, "执行协议寻道: protocol=$protocol, positionMs=$positionMs")
                when (protocol) {
                    StreamProtocol.UPNP_DLNA -> {
                        val ok = upnpServer?.seekToPositionMs(positionMs) ?: false
                        if (!ok) {
                            Logger.service.w(TAG, "UPnP seekToPositionMs 失败: $positionMs")
                        }
                    }
                    StreamProtocol.AIRPLAY_1 -> {
                        airPlayServer?.notifySeekTriggered()
                        lifecycleScope.launch {
                            val dacp = airPlayServer?.dacpClient
                            val result = dacp?.setPlayingTime(positionMs)
                            if (result?.isFailure == true) {
                                Logger.service.w(TAG, "AirPlay DACP setPlayingTime 失败: ${result.exceptionOrNull()?.message}")
                            }
                        }
                    }
                    StreamProtocol.NONE -> Unit
                }
            }

            override fun resumeProtocolAfterDeviceReopen(
                protocol: StreamProtocol,
                sessionId: Long,
                newEpoch: Long
            ): ProtocolResumeReopenResult {
                return this@PlaybackService.resumeProtocolAfterDeviceReopen(protocol, sessionId, newEpoch)
            }
        }

        sessionHost = PlaybackServiceSessionHost(
            scope = serviceEffectsScope,
            coordinator = coordinator,
            hardwareBridge = usbDeviceManager,
            systemEffects = systemEffects,
            clockMs = { SystemClock.elapsedRealtime() }
        )

        // 💡 初始化歌词多源检索与生命周期防抖触发器 (U4, U7)
        lyricsCacheRepository = LyricsCacheRepository(
            diskCacheDir = java.io.File(cacheDir, "lyrics")
        )
        val lyricsHttpClient = DefaultLyricsHttpClient()
        val amllSource = AmllDataSource(lyricsHttpClient)
        val lrclibSource = LrclibDataSource(lyricsHttpClient)
        val neteaseSource = NeteaseDataSource(lyricsHttpClient)
        val waterfallFetcher = LyricsWaterfallFetcher(
            cacheRepository = lyricsCacheRepository,
            amllDataSource = amllSource,
            lrclibDataSource = lrclibSource,
            neteaseDataSource = neteaseSource
        )
        lyricsTriggerManager = LyricsTriggerManager(
            scope = serviceEffectsScope,
            cacheRepository = lyricsCacheRepository,
            waterfallFetcher = waterfallFetcher
        )

        serviceEffectsScope.launch {
            lyricsTriggerManager.lyricUiState.collect { state ->
                _lyricsUiState.value = state
            }
        }

        // 💡 单向发布不可变全局快照与驱动歌词生命周期，必须在发起硬件扫描前启动观察（后台调度，不抢占 UI 主线程）
        serviceEffectsScope.launch {
            coordinator.snapshot.collect { snap ->
                _sessionSnapshot.value = snap
                lyricsTriggerManager.onSessionSnapshotChanged(snap)
            }
        }
        sessionHost.startObserving()

        // 冷启动异步扫描已连接的 USB DAC，避免阻塞 Service.onCreate 主线程
        usbDeviceManager.scanAndAttachDeviceAsync()

        // 💡 UPnP 网卡枚举/监听与 AirPlay 原生服务启动迁移至独立 IO 池，彻底消除冷启动主线程与快照队列阻塞
        serviceIoScope.launch {
            startProtocolServersIfEnabled()
        }

        // 💡 必须在所有 lateinit 核心协作者（sessionHost、usbDeviceManager、coordinator 等）就绪后再对外发布单实例引用
        instance = this
        pendingPermissionActivityRef?.get()?.let { act ->
            registerUsbPermissionActivity(act)
        }
        startBootNetworkWatchdog()
    }

    private fun preemptProtocolSession(protocol: StreamProtocol, sessionId: Long) {
        Logger.service.i(TAG, "终止/抢占协议底层会话: protocol=$protocol, sessionId=$sessionId")
        when (protocol) {
            StreamProtocol.UPNP_DLNA -> upnpServer?.stopPlayback(isPreempted = true, targetSessionId = sessionId)
            StreamProtocol.AIRPLAY_1 -> airPlayServer?.preemptActiveSession(targetGlobalSessionId = sessionId)
            StreamProtocol.NONE -> Unit
        }
    }

    private fun resumeProtocolAfterDeviceReopen(
        protocol: StreamProtocol,
        sessionId: Long,
        newEpoch: Long
    ): ProtocolResumeReopenResult = when (protocol) {
        StreamProtocol.AIRPLAY_1 ->
            airPlayServer?.resumeAfterDeviceReopen(sessionId, newEpoch)
                ?: ProtocolResumeReopenResult.PRODUCER_DEAD

        StreamProtocol.UPNP_DLNA ->
            upnpServer?.resumeStreamAfterDeviceReopen(sessionId)
                ?: ProtocolResumeReopenResult.PRODUCER_DEAD

        StreamProtocol.NONE -> ProtocolResumeReopenResult.PRODUCER_DEAD
    }

    fun clearError(code: ErrorCode) {
        if (::sessionHost.isInitialized) {
            sessionHost.clearError(code)
        }
    }

    /**
     * 💡 获取当前歌词缓存条目数与占用字节数（请在 IO 线程调用）
     */
    fun getLyricsCacheStats(): LyricsCacheRepository.CacheStats {
        return if (::lyricsCacheRepository.isInitialized) {
            lyricsCacheRepository.getCacheStats()
        } else {
            LyricsCacheRepository.CacheStats(0, 0L)
        }
    }

    /**
     * 💡 清空 L1/L2 歌词缓存及临时文件，并触发当前正在播放曲目重新检索最新歌词（请在 IO 线程调用）
     */
    fun clearAllCaches(): LyricsCacheRepository.CacheStats {
        // 💡 先取消并重置在途歌词检索任务，防止在途网络结果在清盘瞬间回写旧缓存
        if (::lyricsTriggerManager.isInitialized) {
            lyricsTriggerManager.reset()
        }
        val stats = if (::lyricsCacheRepository.isInitialized) {
            lyricsCacheRepository.clearAll()
        } else {
            LyricsCacheRepository.CacheStats(0, 0L)
        }
        if (::lyricsTriggerManager.isInitialized && ::coordinator.isInitialized) {
            val currentSnap = coordinator.snapshot.value
            serviceEffectsScope.launch {
                if (currentSnap.sessionId != 0L) {
                    lyricsTriggerManager.onSessionSnapshotChanged(currentSnap)
                }
            }
        }
        Logger.service.i(TAG, "用户主动清空缓存完成: count=${stats.fileCount}, bytes=${stats.totalBytes}")
        return stats
    }

    /**
     * 💡 开机冷启动阶段 Wi-Fi 就绪兜底守护：前 60 秒定期自检并刷新网络广播
     */
    private fun startBootNetworkWatchdog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val checkDelays = listOf(2000L, 3000L, 5000L, 5000L, 10000L, 15000L, 20000L)
            for (d in checkDelays) {
                delay(d)
                val airplayEnabled = settingsRepository.isAirPlayEnabled()
                val upnpEnabled = settingsRepository.isUpnpEnabled()
                val airplayBound = airPlayServer?.getCurrentBoundIp()
                val upnpBound = upnpServer?.getActiveIpAddress()

                val isAirplayOk = !airplayEnabled || (
                    airplayBound != null &&
                        airplayBound.hostAddress?.startsWith("192.168.48.") != true &&
                        airplayBound.hostAddress?.startsWith("127.") != true
                    )
                val isUpnpOk = !upnpEnabled || (
                    upnpBound != null &&
                        !upnpBound.startsWith("192.168.48.") &&
                        upnpBound != "127.0.0.1"
                    )

                if (!isAirplayOk || !isUpnpOk) {
                    Logger.service.i(
                        TAG,
                        "开机广播网络巡检守护：检测到未绑定至有效局域网 IP (AirPlay=${airplayBound?.hostAddress}, UPnP=$upnpBound)，触发自愈刷新..."
                    )
                    serviceIoScope.launch {
                        refreshNetworkServices()
                    }
                }
            }
        }
    }

    private fun setupMediaSession() {
        try {
            mediaSession = MediaSession(this, "PureCastMediaSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        Logger.service.i(TAG, "MediaSession 收到 onPlay 回调")
                        resumePlayback()
                    }

                    override fun onPause() {
                        Logger.service.i(TAG, "MediaSession 收到 onPause 回调")
                        pausePlayback()
                    }

                    override fun onStop() {
                        Logger.service.i(TAG, "MediaSession 收到 onStop 回调")
                        stopPlayback()
                    }

                    override fun onSkipToNext() {
                        Logger.service.i(TAG, "MediaSession 收到 onSkipToNext 回调")
                        handleNextTrack()
                    }

                    override fun onSkipToPrevious() {
                        Logger.service.i(TAG, "MediaSession 收到 onSkipToPrevious 回调")
                        handlePreviousTrack()
                    }

                    override fun onSeekTo(pos: Long) {
                        Logger.service.i(TAG, "MediaSession 收到 onSeekTo: $pos ms")
                        seekTo(pos)
                    }
                })
                isActive = true
            }
            val initial = _sessionSnapshot.value
            updateMediaSessionState(
                status = initial.playbackStatus,
                positionMs = initial.progressPositionMs,
                seekCapability = initial.metadata.seekCapability
            )
            updateMediaSessionMetadata(initial.metadata)
            Logger.service.i(TAG, "MediaSession 初始化完成并已激活")
        } catch (e: Exception) {
            Logger.service.e(TAG, "初始化 MediaSession 异常", e)
        }
    }

    private fun updateMediaSessionState(
        status: PlaybackStatus,
        positionMs: Long,
        seekCapability: SeekCapability
    ) {
        val session = mediaSession ?: return
        val state = when (status) {
            PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            PlaybackStatus.STOPPED -> PlaybackState.STATE_STOPPED
            PlaybackStatus.IDLE -> PlaybackState.STATE_NONE
        }
        val actions = computeMediaSessionActions(seekCapability)
        val playbackSpeed = if (status == PlaybackStatus.PLAYING) 1.0f else 0.0f
        val playbackState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, positionMs, playbackSpeed)
            .build()
        session.setPlaybackState(playbackState)
    }

    private val mediaSessionMetadataHelper = MediaSessionMetadataHelper()

    private fun updateMediaSessionMetadata(metadata: TrackMetadata) {
        mediaSessionMetadataHelper.updateMediaSessionMetadata(mediaSession, metadata, serviceIoScope)
    }

    private fun acquireStandingLocks() {
        synchronized(systemResourcesLock) {
            if (isDestroyed) return
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                // 💡 必须获取 MulticastLock，否则电视机休眠或息屏时无法响应 SSDP/mDNS 广播
                multicastLock = wifiManager.createMulticastLock("PureCast:MulticastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                // 💡 常驻获取 PARTIAL_WAKE_LOCK，防止电视待机/息屏期间 CPU 休眠导致无法接收 AirPlay/UPnP 推流唤醒；
                //    并在每次活跃推流时通过 acquireWifiLowLatencyLock 按 12h 续期，消除 7x24 运行过期失效
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PureCast:WakeLock").apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
                Logger.service.i(TAG, "成功获取常驻 Wi-Fi 组播锁 (MulticastLock) 与 CPU WakeLock")
            } catch (e: Exception) {
                Logger.service.w(TAG, "获取系统常驻锁异常", e)
            }
        }
    }

    private fun acquireWifiLowLatencyLock() {
        synchronized(systemResourcesLock) {
            if (isDestroyed) return
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
                // 💡 活跃推流时续期常驻 CPU PARTIAL_WAKE_LOCK（12h 滑动窗口），防止长时间运行后超时释放
                if (wakeLock == null) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PureCast:WakeLock").apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
                Logger.service.i(TAG, "推流中: 已续期常驻 CPU WakeLock (12h)")
            } catch (e: Exception) {
                Logger.service.w(TAG, "激活 Wi-Fi 低延迟锁/续期 WakeLock 异常", e)
            }
        }
    }

    private fun releaseWifiLowLatencyLock() {
        synchronized(systemResourcesLock) {
            try {
                wifiLock?.let {
                    if (it.isHeld) {
                        it.release()
                        Logger.service.i(TAG, "待机/暂停: 已释放 Wi-Fi 低延迟锁以降低电视功耗 (常驻 WakeLock 保持待命)")
                    }
                }
            } catch (e: Exception) {
                Logger.service.w(TAG, "释放 Wi-Fi 低延迟锁异常", e)
            }
        }
    }

    private fun releaseSystemLocks() {
        synchronized(systemResourcesLock) {
            try {
                multicastLock?.let { if (it.isHeld) it.release() }
                multicastLock = null
                wifiLock?.let { if (it.isHeld) it.release() }
                wifiLock = null
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                Logger.service.i(TAG, "已安全释放所有系统锁")
            } catch (e: Exception) {
                Logger.service.w(TAG, "释放系统锁异常", e)
            }
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

    fun pausePlayback() {
        if (!::sessionHost.isInitialized) return
        sessionHost.pausePlayback()
    }

    fun seekTo(positionMs: Long) {
        if (!::sessionHost.isInitialized) return
        sessionHost.seekTo(positionMs)
    }

    fun resumePlayback() {
        if (!::sessionHost.isInitialized) return
        sessionHost.resumePlayback()
    }

    fun stopPlayback() {
        if (!::sessionHost.isInitialized) return
        sessionHost.stopPlayback()
    }

    fun togglePlayPause() {
        if (!::sessionHost.isInitialized) return
        sessionHost.togglePlayPause()
    }

    fun handleNextTrack() {
        if (!::sessionHost.isInitialized) return
        sessionHost.handleNextTrack()
    }

    fun handlePreviousTrack() {
        if (!::sessionHost.isInitialized) return
        sessionHost.handlePreviousTrack()
    }

    /**
     * 💡 外部音频焦点彻底丢失（AUDIOFOCUS_LOSS）时的统一协调收口：
     * 先暂停并快路径静音，再在硬件串行线程立即归还 USB DAC 给系统。
     */
    fun handleAudioFocusLost() {
        if (::sessionHost.isInitialized) {
            sessionHost.pausePlayback()
        }
        if (::usbDeviceManager.isInitialized) {
            usbDeviceManager.releaseForExternalFocusLoss()
        }
    }

    fun updateFriendlyName(newName: String) {
        settingsRepository.setFriendlyName(newName)
        serviceIoScope.launch {
            applyFriendlyNameUpdate(newName)
        }
    }

    @Synchronized
    private fun applyFriendlyNameUpdate(newName: String) {
        if (isDestroyed) return
        upnpServer?.updateFriendlyName(formatUpnpName(newName))
        airPlayServer?.updateDeviceName(formatAirPlayName(newName))
    }

    fun setAirPlayEnabled(enabled: Boolean) {
        settingsRepository.setAirPlayEnabled(enabled)
        serviceIoScope.launch {
            applyAirPlayEnabled(enabled)
        }
    }

    @Synchronized
    private fun applyAirPlayEnabled(enabled: Boolean) {
        if (isDestroyed) return
        if (enabled) {
            val name = settingsRepository.getFriendlyName()
            if (airPlayServer == null) {
                airPlayServer = createAirPlayServer()
            }
            airPlayServer?.start(formatAirPlayName(name))
        } else {
            airPlayServer?.stop()
        }
    }

    fun setUpnpEnabled(enabled: Boolean) {
        settingsRepository.setUpnpEnabled(enabled)
        serviceIoScope.launch {
            applyUpnpEnabled(enabled)
        }
    }

    @Synchronized
    private fun applyUpnpEnabled(enabled: Boolean) {
        if (isDestroyed) return
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
        coordinator.setIdleTimeoutSeconds(seconds)
    }

    fun registerUsbPermissionActivity(activity: Activity?) {
        registerUsbPermissionActivityStatic(activity)
    }

    /**
     * 💡 供设置界面手动让渡声卡：暂停播放并立即调用 releaseForExternalFocusLoss 归还系统驱动
     */
    fun releaseUsbExclusiveManually() {
        if (::sessionHost.isInitialized) {
            sessionHost.pausePlayback()
        }
        if (::usbDeviceManager.isInitialized) {
            usbDeviceManager.releaseForExternalFocusLoss()
        }
        Logger.service.i(TAG, "用户手动触发声卡让渡")
    }

    /**
     * 💡 供设置界面手动独占接管声卡：枚举设备、校验权限并尝试直通打开 (异步执行避免阻塞 UI 线程)
     */
    fun claimUsbExclusiveManually(activity: Activity, onResult: (Boolean, String) -> Unit) {
        if (!::usbDeviceManager.isInitialized) {
            onResult(false, "USB 管理模块尚未初始化")
            return
        }
        usbDeviceManager.bindPermissionActivity(activity)
        lifecycleScope.launch(Dispatchers.IO) {
            val attached = usbDeviceManager.scanAndAttachDevice()
            val snap = sessionSnapshot.value
            val hasPerm = usbDeviceManager.hasPermission()

            if (!attached && !snap.audioPath.isDacConnected) {
                withContext(Dispatchers.Main) {
                    usbDeviceManager.scanAndRequestPermissionAsync(activity)
                    onResult(false, "未连接可用 USB DAC 设备")
                }
                return@launch
            }

            if (!hasPerm) {
                withContext(Dispatchers.Main) {
                    usbDeviceManager.scanAndRequestPermissionAsync(activity)
                    onResult(false, "正在请求 USB 权限，请在电视弹窗中确认")
                }
                return@launch
            }

            val rate = snap.audioPath.sampleRate.takeIf { it > 0 } ?: 44100
            val bits = snap.audioPath.bitDepth.takeIf { it > 0 } ?: 16
            val ch = snap.audioPath.channels.takeIf { it > 0 } ?: 2
            val openRes = usbDeviceManager.openDeviceWithCurrentFd(rate, bits, ch, forceNewEpoch = true)
            withContext(Dispatchers.Main) {
                openRes.fold(
                    onSuccess = { success ->
                        onResult(true, "已成功独占接管: ${success.dacName}")
                    },
                    onFailure = { err ->
                        onResult(false, "独占接管失败: ${err.message}")
                    }
                )
            }
        }
    }

    fun bindUsbPermissionActivity(activity: Activity?) {
        if (!::usbDeviceManager.isInitialized) return
        usbDeviceManager.bindPermissionActivity(activity)
    }

    fun requestUsbPermission(activity: Activity) {
        if (!::usbDeviceManager.isInitialized) return
        usbDeviceManager.requestPermissionWithActivity(activity)
    }

    fun scanAndRequestUsbPermission(activity: Activity) {
        if (!::usbDeviceManager.isInitialized) return
        usbDeviceManager.scanAndRequestPermissionAsync(activity)
    }

    fun handleUsbDeviceAttachedFromIntent(device: UsbDevice) {
        if (!::usbDeviceManager.isInitialized) return
        usbDeviceManager.onDeviceAttachedAsync(device)
    }

    fun handleUsbDeviceDetached(device: UsbDevice?) {
        Logger.service.w(TAG, "收到 USB 设备拔出通知，异步投递给 HardwareExecutor: ${device?.deviceName}")
        if (!::usbDeviceManager.isInitialized) return
        usbDeviceManager.handleDeviceDetachedAsync(device)
    }

    private fun createNotificationChannel() {
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

    private fun buildNotification(snapshot: PlayerSessionSnapshot): Notification {
        val metadata = snapshot.metadata
        val isPlaying = snapshot.playbackStatus == PlaybackStatus.PLAYING

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(metadata.title)
            .setContentText("${metadata.artist} • ${if (isPlaying) "Bit-Perfect 独占播放中" else "待机"}")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, isPlaying)
            .setStyle(mediaStyle)
            .setOngoing(isPlaying)
            .build()
    }

    private fun updateNotification(snapshot: PlayerSessionSnapshot) {
        val notification = buildNotification(snapshot)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithTypes() {
        val notification = buildNotification(_sessionSnapshot.value)
        val fullType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                fullType
            )
            Logger.service.i(TAG, "已启动前台服务 (mediaPlayback | connectedDevice)")
        } catch (e: SecurityException) {
            // 💡 Android 14 冷开机未接管 USB 前若 connectedDevice 权限不满足，平滑降级为 mediaPlayback 保障前台保活
            Logger.service.w(TAG, "startForeground(mediaPlayback|connectedDevice) 被系统拒绝，降级为 mediaPlayback: ${e.message}")
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } catch (e2: Exception) {
                Logger.service.e(TAG, "降级 startForeground(mediaPlayback) 亦失败!", e2)
            }
        } catch (e: Exception) {
            Logger.service.e(TAG, "startForeground 失败!", e)
        }
    }

    private fun registerNetworkCallback() {
        synchronized(systemResourcesLock) {
            if (isDestroyed) return
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Logger.service.i(TAG, "系统网络已连通，自检并刷新 UPnP / AirPlay 广播服务...")
                    serviceIoScope.launch {
                        refreshNetworkServices()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    Logger.service.i(TAG, "系统网络链路属性变更 (IP地址变动)，刷新广播服务...")
                    serviceIoScope.launch {
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
    }

    private fun unregisterNetworkCallback() {
        synchronized(systemResourcesLock) {
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
    }

    @Synchronized
    private fun startProtocolServersIfEnabled() {
        if (isDestroyed) return
        val friendlyName = settingsRepository.getFriendlyName()
        if (settingsRepository.isUpnpEnabled() && upnpServer == null) {
            upnpServer = createUpnpServer(formatUpnpName(friendlyName)).apply {
                start()
            }
        }
        if (airPlayServer == null) {
            airPlayServer = createAirPlayServer()
        }
        if (settingsRepository.isAirPlayEnabled()) {
            airPlayServer?.start(formatAirPlayName(friendlyName))
        }
    }

    @Synchronized
    private fun stopProtocolServers() {
        upnpServer?.stop()
        upnpServer = null
        airPlayServer?.destroy()
        airPlayServer = null
    }

    @Synchronized
    fun refreshNetworkServices() {
        if (isDestroyed) return
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

    private fun createUpnpServer(friendlyName: String): UpnpRendererServer {
        return UpnpRendererServer(
            scope = lifecycleScope,
            sessionBridge = sessionHost,
            snapshotProvider = { coordinator.snapshot.value },
            fastPath = fastPath,
            onWakeUpUi = { wakeUpUiIfInBackground() },
            clockMs = { SystemClock.elapsedRealtime() },
            friendlyName = friendlyName
        )
    }

    private fun createAirPlayServer(): AirPlayServerManager {
        return AirPlayServerManager(
            context = this,
            scope = lifecycleScope,
            sessionBridge = sessionHost
        )
    }

    override fun onDestroy() {
        Logger.service.i(TAG, "PlaybackService onDestroy: 彻底清理服务...")
        isDestroyed = true
        instance = null
        mediaSessionMetadataHelper.clear()
        unregisterNetworkCallback()
        if (::sessionHost.isInitialized) {
            sessionHost.stop()
        }
        serviceIoScope.cancel()
        serviceEffectsScope.cancel()
        coordinatorScope.cancel()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        stopProtocolServers()
        releaseWifiLowLatencyLock()
        releaseSystemLocks()
        if (::audioFocusManager.isInitialized) {
            audioFocusManager.abandonFocus()
        }
        if (::lyricsTriggerManager.isInitialized) {
            lyricsTriggerManager.reset()
        }
        if (::usbDeviceManager.isInitialized) {
            usbDeviceManager.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "purecast_playback_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_ATTACHED_USB_DEVICE = "com.solo.purecast.extra.ATTACHED_USB_DEVICE"

        @Volatile
        var instance: PlaybackService? = null
            private set

        @Volatile
        private var pendingPermissionActivityRef: WeakReference<Activity>? = null

        /**
         * 💡 静态绑定或注销 USB 授权请求宿主 Activity (冷启动时即便 instance 尚未就绪亦可预存)
         */
        fun registerUsbPermissionActivityStatic(activity: Activity?) {
            if (activity != null) {
                pendingPermissionActivityRef = WeakReference(activity)
                instance?.let { service ->
                    if (service::usbDeviceManager.isInitialized) {
                        service.usbDeviceManager.bindPermissionActivity(activity)
                        service.usbDeviceManager.scanAndRequestPermissionAsync(activity)
                    }
                }
            } else {
                pendingPermissionActivityRef = null
                instance?.let { service ->
                    if (service::usbDeviceManager.isInitialized) {
                        service.usbDeviceManager.bindPermissionActivity(null)
                    }
                }
            }
        }

        private val _sessionSnapshot = MutableStateFlow(PlayerSessionSnapshot())
        val sessionSnapshot: StateFlow<PlayerSessionSnapshot> = _sessionSnapshot.asStateFlow()

        private val _lyricsUiState = MutableStateFlow<LyricUiState>(LyricUiState.Idle)
        val lyricsUiState: StateFlow<LyricUiState> = _lyricsUiState.asStateFlow()

        fun start(context: Context, attachedUsbDevice: UsbDevice? = null) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                if (attachedUsbDevice != null) {
                    putExtra(EXTRA_ATTACHED_USB_DEVICE, attachedUsbDevice)
                }
            }
            context.startForegroundService(intent)
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
