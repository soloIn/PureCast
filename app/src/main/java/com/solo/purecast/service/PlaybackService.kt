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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.solo.purecast.R
import com.solo.purecast.core.audio.AudioFocusManager
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.core.usb.UsbDeviceManager
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.MainActivity
import com.solo.purecast.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.service.i(TAG, "PlaybackService onCreate: 启动核心服务...")

        settingsRepository = SettingsRepository(this)
        createNotificationChannel()
        setupMediaSession()
        startForegroundWithTypes()
        acquireStandingLocks()

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

        // 💡 启动 UPnP / DLNA MediaRenderer 服务端 (采用用户持久化的 friendlyName)
        val friendlyName = settingsRepository.getFriendlyName()
        if (settingsRepository.isUpnpEnabled()) {
            upnpServer = com.solo.purecast.upnp.UpnpRendererServer(
                scope = lifecycleScope,
                playbackService = this,
                friendlyName = friendlyName
            ).apply {
                start()
            }
        }

        // 💡 启动原生 AirPlay 1 (RAOP) 接收服务端 (若配置开启)
        airPlayServer = com.solo.purecast.airplay.AirPlayServerManager(
            context = this,
            scope = lifecycleScope,
            onStreamRequestedListener = { sampleRate, bitDepth, channels, clientIp, dacpPort, token ->
                onAirPlayStreamRequested(sampleRate, bitDepth, channels, clientIp, dacpPort, token)
            },
            onStreamStoppedListener = {
                onAirPlayStreamStopped()
            },
            onMetadataReceivedListener = { title, artist, album, durationMs, coverArt ->
                onAirPlayMetadataReceived(title, artist, album, durationMs, coverArt)
            },
            onProgressUpdatedListener = { positionMs, durationMs ->
                onAirPlayProgressUpdated(positionMs, durationMs)
            }
        )

        if (settingsRepository.isAirPlayEnabled()) {
            airPlayServer?.start(friendlyName)
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
    fun startStreaming(sampleRate: Int, bitDepth: Int, channels: Int = 2): Boolean {
        Logger.service.i(TAG, "收到流式播放请求: $sampleRate Hz, $bitDepth bit, $channels ch")

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
        NativeAudioEngine.resumeStream()

        _playbackStatus.value = PlaybackStatus.PLAYING
        val currentProto = if (currentStreamSource == StreamSource.AIRPLAY) {
            com.solo.purecast.model.StreamProtocol.AIRPLAY_1
        } else {
            com.solo.purecast.model.StreamProtocol.UPNP_DLNA
        }
        _audioPathState.value = _audioPathState.value.copy(
            isExclusive = true,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            protocol = currentProto
        )

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
        clientIp: String,
        dacpPort: Int,
        token: String
    ): Boolean {
        Logger.service.i(TAG, "处理 AirPlay 推流请求 (后发抢占): client=$clientIp")

        // 💡 后发抢占：若当前正在运行 UPnP 流，立即优雅中止旧流
        if (currentStreamSource == StreamSource.UPNP) {
            Logger.service.i(TAG, "AirPlay 新流到达，执行后发抢占中止旧 UPnP 流")
            upnpServer?.stopPlayback()
            upnpServer?.notifyTransportState("STOPPED")
        }

        currentStreamSource = StreamSource.AIRPLAY
        val started = startStreaming(sampleRate, bitDepth, channels)
        if (!started) {
            currentStreamSource = StreamSource.IDLE
        }
        return started
    }

    /**
     * 💡 原生 AirPlay 1 推流停止事件 (TEARDOWN 触发)
     */
    private fun onAirPlayStreamStopped() {
        if (currentStreamSource == StreamSource.AIRPLAY) {
            Logger.service.i(TAG, "AirPlay 会话结束，停止播放")
            currentStreamSource = StreamSource.IDLE
            stopPlayback()
            _audioPathState.value = _audioPathState.value.copy(
                protocol = com.solo.purecast.model.StreamProtocol.NONE
            )
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
        val mergedCover = if (coverArt != null && coverArt.isNotEmpty()) coverArt else current.coverBytes
        val mergedDuration = if (durationMs > 0) durationMs else current.durationMs

        val meta = TrackMetadata(
            title = mergedTitle,
            artist = mergedArtist,
            album = mergedAlbum,
            coverBytes = mergedCover,
            durationMs = mergedDuration,
            isLiveStream = (mergedDuration <= 0)
        )
        updateMetadata(meta)
    }

    /**
     * 💡 原生 AirPlay 1 播放进度与总时长更新 (SET_PARAMETER progress: start/curr/end 触发)
     */
    private fun onAirPlayProgressUpdated(positionMs: Long, durationMs: Long) {
        val current = _currentMetadata.value
        if (durationMs > 0 && current.durationMs != durationMs) {
            val updated = current.copy(durationMs = durationMs, isLiveStream = false)
            updateMetadata(updated)
        }
        _playbackPositionMs.value = positionMs
        updateMediaSessionState()
    }

    fun pausePlayback() {
        Logger.service.i(TAG, "暂停音频播放...")
        NativeAudioEngine.pauseStream()
        _playbackStatus.value = PlaybackStatus.PAUSED
        releaseWifiLowLatencyLock()
        usbDeviceManager.scheduleIdleRelease() // 启动 10 秒空闲让渡
        upnpServer?.notifyTransportState("PAUSED_PLAYBACK")
        updateMediaSessionState()
        updateNotification()
    }

    fun resumePlayback() {
        Logger.service.i(TAG, "恢复音频播放...")
        acquireWifiLowLatencyLock()
        usbDeviceManager.cancelIdleRelease()
        if (!usbDeviceManager.isExclusive()) {
            val state = _audioPathState.value
            usbDeviceManager.claimUsbExclusive(state.sampleRate, state.bitDepth, state.channels)
        }
        NativeAudioEngine.resumeStream()
        _playbackStatus.value = PlaybackStatus.PLAYING
        upnpServer?.notifyTransportState("PLAYING")
        updateMediaSessionState()
        updateNotification()
    }

    fun stopPlayback() {
        Logger.service.i(TAG, "停止音频播放...")
        NativeAudioEngine.stopStream()
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
        Logger.service.i(TAG, "用户触发切下一曲")
        upnpServer?.notifyNextTrack()
    }

    fun handlePreviousTrack() {
        Logger.service.i(TAG, "用户触发切上一曲")
        upnpServer?.notifyPreviousTrack()
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
     * 💡 更新 UPnP 与 AirPlay 局域网广播名称并即时通知服务更新
     */
    fun updateFriendlyName(newName: String) {
        settingsRepository.setFriendlyName(newName)
        upnpServer?.updateFriendlyName(newName)
        airPlayServer?.updateDeviceName(newName)
    }

    /**
     * 💡 动态开启或关闭原生 AirPlay 1 服务
     */
    fun setAirPlayEnabled(enabled: Boolean) {
        settingsRepository.setAirPlayEnabled(enabled)
        if (enabled) {
            val name = settingsRepository.getFriendlyName()
            airPlayServer?.start(name)
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
                upnpServer = com.solo.purecast.upnp.UpnpRendererServer(
                    scope = lifecycleScope,
                    playbackService = this,
                    friendlyName = name
                )
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

    fun scanUsbDevice() {
        usbDeviceManager.scanAndAttachDevice()
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

    override fun onDestroy() {
        Logger.service.i(TAG, "PlaybackService onDestroy: 彻底清理服务...")
        instance = null
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
    }
}
