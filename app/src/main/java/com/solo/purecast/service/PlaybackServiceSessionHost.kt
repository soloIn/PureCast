package com.solo.purecast.service

import android.media.session.PlaybackState
import com.solo.purecast.core.session.ErrorClearedEvent
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.PlaybackSessionCoordinator
import com.solo.purecast.core.session.ProgressUpdateEvent
import com.solo.purecast.core.session.ProtocolResumeReopenResult
import com.solo.purecast.core.session.ProtocolSessionBridge
import com.solo.purecast.core.session.RemoteCommandType
import com.solo.purecast.core.session.RemoteSkipNextEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.SessionHardwareBridge
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamStopEvent
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 💡 根据 [SeekCapability] 计算系统 MediaSession 可用控制动作掩码
 * 严格基于能力契约判断：仅当明确可寻道 [SeekCapability.SEEKABLE] 时开放 `ACTION_SEEK_TO`。
 */
internal fun computeMediaSessionActions(seekCapability: SeekCapability): Long {
    val baseActions = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_STOP or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS

    return if (seekCapability == SeekCapability.SEEKABLE) {
        baseActions or PlaybackState.ACTION_SEEK_TO
    } else {
        baseActions
    }
}

/**
 * 💡 宿主机系统集成副作用端口（MediaSession、Notification、Locks、AudioFocus、协议抢占）
 */
interface ServiceSystemEffects {
    fun syncMediaSessionState(status: PlaybackStatus, positionMs: Long, seekCapability: SeekCapability)
    fun syncMediaSessionMetadata(metadata: TrackMetadata)
    fun syncNotification(snapshot: PlayerSessionSnapshot)
    fun acquireWifiLowLatencyLock()
    fun releaseWifiLowLatencyLock()
    fun requestAudioFocus(): Boolean
    fun abandonAudioFocus()
    fun wakeUpUiIfInBackground()
    fun notifyUpnpTransportState(state: String)
    fun preemptProtocol(protocol: StreamProtocol, sessionId: Long)
    fun seekProtocolStream(protocol: StreamProtocol, positionMs: Long)
    fun resumeProtocolAfterDeviceReopen(
        protocol: StreamProtocol,
        sessionId: Long,
        newEpoch: Long
    ): ProtocolResumeReopenResult = ProtocolResumeReopenResult.STREAMING_READY
}

/**
 * 💡 PlaybackService 会话宿主桥接器 (U5)
 *
 * 架构边界：
 * 1. 纯粹消费 [PlaybackSessionCoordinator.snapshot] 单向数据流；
 * 2. 驱动 MediaSession、前台通知、Wi-Fi 低延迟锁、音频焦点及遥控交互投递；
 * 3. 本身不包含任何状态机防抖或元数据合并分支。
 */
class PlaybackServiceSessionHost(
    private val scope: CoroutineScope,
    val coordinator: PlaybackSessionCoordinator,
    private val hardwareBridge: SessionHardwareBridge,
    private val systemEffects: ServiceSystemEffects,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val airPlayRealtimePositionProvider: () -> Long = { -1L }
) : ProtocolSessionBridge {
    private var snapshotObserverJob: Job? = null
    private var airPlayTickerJob: Job? = null
    private var lastSnapshot: PlayerSessionSnapshot? = null

    private var lastSyncedStatus: PlaybackStatus? = null
    private var lastSyncedSeekCap: SeekCapability? = null
    private var lastSyncedSessionId: Long = -1L
    private var lastSyncedTrackGen: Long = -1L
    private var lastSyncedPosMs: Long = 0L
    private var lastSyncedClockMs: Long = 0L

    @Volatile
    private var lastAnchorPosMs: Long = 0L

    @Volatile
    private var lastAnchorClockMs: Long = 0L

    @Volatile
    private var anchoredProgressRevision: Long = -1L

    fun startObserving() {
        if (snapshotObserverJob?.isActive == true) return
        snapshotObserverJob = scope.launch {
            coordinator.snapshot.collectLatest { next ->
                val prev = lastSnapshot
                lastSnapshot = next
                handleSnapshotUpdate(prev, next)
            }
        }
    }

    /**
     * 💡 建流硬件准入（由协议解复用回调在首帧写入前同步调用）：
     * 唤醒大屏 UI -> 申请音频焦点 -> 打开/三元组切频 USB DAC -> 返回最新有效 epoch (`< 0` 拒绝)
     */
    override fun dispatch(envelope: SessionEventEnvelope<SessionEventPayload>) {
        coordinator.dispatch(envelope)
    }

    override fun nextSessionId(): Long = coordinator.allocateSessionId()

    override fun nextSequence(): Long = coordinator.allocateSequence()

    fun acquireHardwareForStream(sampleRate: Int, bitDepth: Int, channels: Int): Long =
        acquireHardwareForStream(StreamProtocol.NONE, sampleRate, bitDepth, channels, "")

    /**
     * 💡 建流硬件准入（由协议解复用回调在首帧写入前同步调用）：
     * 唤醒大屏 UI -> 申请音频焦点 -> 同步抢占对侧协议 -> 打开/三元组切频 USB DAC -> 返回最新有效 epoch (`< 0` 拒绝)
     */
    override fun acquireHardwareForStream(
        protocol: StreamProtocol,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        codec: String
    ): Long {
        systemEffects.wakeUpUiIfInBackground()
        if (!systemEffects.requestAudioFocus()) {
            Logger.service.w(TAG, "申请音频焦点被拒绝，拒绝建流 ($protocol $sampleRate/$bitDepth/$channels)")
            return -1L
        }

        val currentSnap = coordinator.snapshot.value
        val shouldPreemptOther = protocol != StreamProtocol.NONE &&
            currentSnap.sessionId != 0L &&
            currentSnap.activeProtocol != StreamProtocol.NONE &&
            currentSnap.activeProtocol != protocol
        if (shouldPreemptOther) {
            Logger.service.i(
                TAG,
                "新协议 ($protocol) 建流前同步抢占对侧协议 (${currentSnap.activeProtocol}, sid=${currentSnap.sessionId})"
            )
            systemEffects.preemptProtocol(currentSnap.activeProtocol, currentSnap.sessionId)
        }

        val openResult = hardwareBridge.openDeviceWithCurrentFd(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            forceNewEpoch = shouldPreemptOther
        )
        if (openResult.isFailure) {
            Logger.service.w(
                TAG,
                "硬件独占建流/切频失败: ${openResult.exceptionOrNull()?.message}"
            )
            systemEffects.abandonAudioFocus()
            return -1L
        }
        return openResult.getOrThrow().epoch
    }

    fun pausePlayback() {
        val snap = coordinator.snapshot.value
        val pausedEpoch = hardwareBridge.emergencyMuteAndFlush(FlushReason.EXPLICIT_PAUSE)
        if (snap.sessionId == 0L || snap.activeProtocol == StreamProtocol.NONE) {
            return
        }

        if (snap.activeProtocol == StreamProtocol.AIRPLAY_1) {
            coordinator.sendDacpFireAndForget(RemoteCommandType.PAUSE)
        } else if (snap.activeProtocol == StreamProtocol.UPNP_DLNA) {
            systemEffects.notifyUpnpTransportState("PAUSED_PLAYBACK")
        }

        dispatchEnvelope(
            origin = EventOrigin.USER_ACTION,
            sessionId = snap.sessionId,
            deviceGeneration = snap.deviceGeneration,
            payload = ExplicitPauseEvent(
                flushReason = FlushReason.EXPLICIT_PAUSE,
                epoch = pausedEpoch
            )
        )
    }

    fun resumePlayback() {
        val snap = coordinator.snapshot.value
        if (snap.sessionId == 0L ||
            snap.activeProtocol == StreamProtocol.NONE ||
            snap.businessState == BusinessSessionState.IDLE ||
            snap.businessState == BusinessSessionState.STOPPED ||
            snap.businessState == BusinessSessionState.PLAYING
        ) {
            Logger.service.d(
                TAG,
                "当前业务态不允许恢复播放: state=${snap.businessState}, protocol=${snap.activeProtocol}"
            )
            return
        }

        systemEffects.requestAudioFocus()
        if (snap.activeProtocol == StreamProtocol.AIRPLAY_1) {
            // 💡 AirPlay 1 恢复播放严禁同步乐观强切 PLAYING（防止 TEARDOWN 断开后虚空播放与 1.75s 预充抢跑）：
            // 通过受追踪的串行 DACP PLAY 指令进入 TRANSITIONING，等真实有声帧过 Sync 门控再切 PLAYING；
            // 若对端已断开导致 DACP PLAY 失败/超时，由状态机自动终止会话。
            dispatchRemoteCommand(RemoteCommandType.PLAY)
            return
        } else if (snap.activeProtocol == StreamProtocol.UPNP_DLNA) {
            systemEffects.notifyUpnpTransportState("PLAYING")
        }

        dispatchEnvelope(
            origin = EventOrigin.USER_ACTION,
            sessionId = snap.sessionId,
            deviceGeneration = snap.deviceGeneration,
            payload = StreamResumeEvent(
                protocol = snap.activeProtocol,
                resumedEpoch = snap.currentEpoch
            )
        )
    }

    fun clearError(code: ErrorCode) {
        val snap = coordinator.snapshot.value
        if (!snap.errors.containsKey(code)) return
        dispatchEnvelope(
            origin = EventOrigin.SERVICE,
            sessionId = 0L,
            deviceGeneration = snap.deviceGeneration,
            payload = ErrorClearedEvent(code)
        )
    }

    fun stopPlayback() {
        val snap = coordinator.snapshot.value
        val stoppedEpoch = hardwareBridge.emergencyMuteAndFlush(FlushReason.STOP_OR_TEARDOWN)
        if (snap.sessionId != 0L && snap.activeProtocol != StreamProtocol.NONE) {
            systemEffects.preemptProtocol(snap.activeProtocol, snap.sessionId)
            dispatchEnvelope(
                origin = EventOrigin.USER_ACTION, // 💡 P0-1: 本机用户/系统主动停止，严禁伪造远端协议 origin
                sessionId = snap.sessionId,
                deviceGeneration = snap.deviceGeneration,
                payload = StreamStopEvent(
                    flushReason = FlushReason.STOP_OR_TEARDOWN,
                    newEpoch = stoppedEpoch,
                    isPreempted = false
                )
            )
        }
        systemEffects.notifyUpnpTransportState("STOPPED")
    }

    fun handleNextTrack() {
        dispatchRemoteCommand(RemoteCommandType.NEXT)
    }

    fun handlePreviousTrack() {
        dispatchRemoteCommand(RemoteCommandType.PREVIOUS)
    }

    fun togglePlayPause() {
        val status = coordinator.snapshot.value.playbackStatus
        if (status == PlaybackStatus.PLAYING) {
            pausePlayback()
        } else if (status == PlaybackStatus.PAUSED) {
            resumePlayback()
        }
    }

    /**
     * 💡 用户触发寻道 (SeekTo)，受当前流媒体寻道能力严格管控
     */
    fun seekTo(positionMs: Long) {
        val snap = coordinator.snapshot.value
        if (snap.metadata.seekCapability != SeekCapability.SEEKABLE) {
            Logger.service.w(TAG, "当前媒体不可寻道，忽略 seekTo($positionMs)")
            return
        }
        // 💡 R11 契约：乐观更新本地时钟锚点并立即发射乐观进度，彻底消除 500ms 进度条回弹
        val targetPos = positionMs.coerceAtLeast(0L)
        lastAnchorPosMs = targetPos
        lastAnchorClockMs = clockMs()
        dispatchEnvelope(
            origin = EventOrigin.USER_ACTION,
            sessionId = snap.sessionId,
            deviceGeneration = snap.deviceGeneration,
            payload = ProgressUpdateEvent(
                positionMs = targetPos,
                durationMs = snap.progressDurationMs,
                trackGeneration = snap.trackGeneration
            )
        )
        systemEffects.seekProtocolStream(snap.activeProtocol, targetPos)
    }

    private fun dispatchRemoteCommand(commandType: RemoteCommandType) {
        val snap = coordinator.snapshot.value
        dispatchEnvelope(
            origin = EventOrigin.USER_ACTION,
            sessionId = snap.sessionId,
            deviceGeneration = snap.deviceGeneration,
            payload = RemoteSkipNextEvent(
                commandType = commandType,
                commandId = coordinator.allocateCommandId()
            )
        )
    }

    private fun dispatchEnvelope(
        origin: EventOrigin,
        sessionId: Long,
        deviceGeneration: Long,
        payload: SessionEventPayload
    ) {
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = origin,
                sessionId = sessionId,
                deviceGeneration = deviceGeneration,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = clockMs(),
                payload = payload
            )
        )
    }

    private fun handleSnapshotUpdate(prev: PlayerSessionSnapshot?, next: PlayerSessionSnapshot) {
        val nowMs = clockMs()
        val expectedLinearPos = if (lastSyncedStatus == PlaybackStatus.PLAYING) {
            lastSyncedPosMs + (nowMs - lastSyncedClockMs).coerceAtLeast(0L)
        } else {
            lastSyncedPosMs
        }
        val hasProgressJump = next.progressPositionMs != lastSyncedPosMs &&
            (next.playbackStatus != PlaybackStatus.PLAYING ||
                kotlin.math.abs(next.progressPositionMs - expectedLinearPos) >= 1500L)

        val shouldSyncMediaSession = prev == null ||
            lastSyncedStatus != next.playbackStatus ||
            lastSyncedSeekCap != next.metadata.seekCapability ||
            lastSyncedSessionId != next.sessionId ||
            lastSyncedTrackGen != next.trackGeneration ||
            hasProgressJump
        if (shouldSyncMediaSession) {
            lastSyncedStatus = next.playbackStatus
            lastSyncedSeekCap = next.metadata.seekCapability
            lastSyncedSessionId = next.sessionId
            lastSyncedTrackGen = next.trackGeneration
            lastSyncedPosMs = next.progressPositionMs
            lastSyncedClockMs = nowMs
            systemEffects.syncMediaSessionState(
                status = next.playbackStatus,
                positionMs = next.progressPositionMs,
                seekCapability = next.metadata.seekCapability
            )
        }

        if (prev == null || prev.metadata != next.metadata) {
            systemEffects.syncMediaSessionMetadata(next.metadata)
        }

        val shouldUpdateNotification = prev == null ||
            prev.playbackStatus != next.playbackStatus ||
            prev.metadata != next.metadata ||
            prev.errors != next.errors ||
            prev.hardwareState != next.hardwareState ||
            prev.audioPath.dacName != next.audioPath.dacName
        if (shouldUpdateNotification) {
            systemEffects.syncNotification(next)
        }

        val wasActive = prev?.businessState == BusinessSessionState.PLAYING ||
            prev?.businessState == BusinessSessionState.TRANSITIONING
        val isActiveNow = next.businessState == BusinessSessionState.PLAYING ||
            next.businessState == BusinessSessionState.TRANSITIONING
        if (isActiveNow && !wasActive) {
            systemEffects.acquireWifiLowLatencyLock()
        } else if (!isActiveNow && (wasActive || prev == null)) {
            systemEffects.releaseWifiLowLatencyLock()
        }

        if ((next.businessState == BusinessSessionState.STOPPED || next.businessState == BusinessSessionState.IDLE) &&
            prev?.businessState != null &&
            prev.businessState != BusinessSessionState.STOPPED &&
            prev.businessState != BusinessSessionState.IDLE
        ) {
            systemEffects.abandonAudioFocus()
        }

        // 💡 AirPlay 1 在 PLAYING 期间维护单调时钟平滑进度推进，补偿 iOS 不周期推 Progress 包的特性
        val hasAuthoritativeRevisionChange = prev == null || prev.progressRevision != next.progressRevision
        val isFirstPlayAtZero = prev?.businessState != next.businessState &&
            next.businessState == BusinessSessionState.PLAYING &&
            next.progressPositionMs == 0L

        // 💡 开播保护：仅拦截开播前 3.5 秒内无排队延迟的 0ms 未就绪空包；
        // 携带明确 progressAnchorDelayMs > 0L 或真实非零协议进度必须刷新锚点，防止 Ticker 持续偏快。
        val currentServiceElapsed = (nowMs - lastAnchorClockMs).coerceAtLeast(0L)
        val currentServicePos = lastAnchorPosMs + currentServiceElapsed
        val isInitialProtocolLag = !next.lastProgressFromUserSeek &&
            next.progressAnchorDelayMs <= 0L &&
            prev != null &&
            prev.trackGeneration == next.trackGeneration &&
            prev.businessState == BusinessSessionState.PLAYING &&
            currentServicePos in 1L..3500L &&
            next.progressPositionMs == 0L

        if (!isInitialProtocolLag && (prev == null ||
            prev.sessionId != next.sessionId ||
            prev.trackGeneration != next.trackGeneration ||
            prev.businessState != next.businessState ||
            isFirstPlayAtZero ||
            hasAuthoritativeRevisionChange
        )) {
            lastAnchorPosMs = next.progressPositionMs
            // 💡 精准对齐扬声器真实出声时刻：
            // 优先使用 C++ 协议层计算的发端/本地排队延迟 (progressAnchorDelayMs)，否则首帧 0ms 开播默认推迟 100ms 硬件预充。
            val anchorDelayMs = when {
                next.progressAnchorDelayMs > 0L -> next.progressAnchorDelayMs
                isFirstPlayAtZero -> 100L
                else -> 0L
            }
            lastAnchorClockMs = clockMs() + anchorDelayMs
            anchoredProgressRevision = next.progressRevision
        } else if (hasAuthoritativeRevisionChange) {
            // 即使被开播防倒流拦截，也同步更新已观察版本号，防止 ticker 永久停摆
            anchoredProgressRevision = next.progressRevision
        }

        val needTicker = next.activeProtocol == StreamProtocol.AIRPLAY_1 &&
            next.businessState == BusinessSessionState.PLAYING &&
            next.sessionId != 0L
        if (needTicker) {
            ensureAirPlayTickerRunning(next.sessionId, next.deviceGeneration)
        } else {
            airPlayTickerJob?.cancel()
            airPlayTickerJob = null
        }
    }

    private fun ensureAirPlayTickerRunning(sessionId: Long, deviceGen: Long) {
        if (airPlayTickerJob?.isActive == true) return
        airPlayTickerJob = scope.launch {
            while (isActive) {
                delay(500L)
                val snap = coordinator.snapshot.value
                if (snap.activeProtocol != StreamProtocol.AIRPLAY_1 ||
                    snap.businessState != BusinessSessionState.PLAYING ||
                    snap.sessionId != sessionId
                ) {
                    break
                }
                // 💡 若协调器已收到新协议 Seek/进度而当前协程尚未刷新锚点，直接跳过本拍，严禁用旧锚点覆盖新进度！
                val anchorRev = anchoredProgressRevision
                if (snap.progressRevision != anchorRev) {
                    continue
                }
                val now = clockMs()
                val dur = snap.progressDurationMs
                // 💡 优先闭环读取 C++ 扬声器真实出声进度 (speaker_ts - start_ts)，仅在本机 Seek 乐观期、排队延迟期或锚点未就绪时回退系统时钟推演
                val canQueryRealtime = !snap.lastProgressFromUserSeek && now >= lastAnchorClockMs
                val realtimePos = if (canQueryRealtime) airPlayRealtimePositionProvider() else -1L
                var pos = if (realtimePos >= 0L) {
                    lastAnchorPosMs = realtimePos
                    lastAnchorClockMs = now
                    realtimePos
                } else {
                    val elapsed = (now - lastAnchorClockMs).coerceAtLeast(0L)
                    lastAnchorPosMs + elapsed
                }
                if (dur > 0L && pos > dur) {
                    pos = dur
                }
                if (pos != snap.progressPositionMs) {
                    dispatchEnvelope(
                        origin = EventOrigin.SERVICE, // 💡 P0-1: 内部进度平滑轮询使用 SERVICE，避免与协议事件竞争导致乱序丢包
                        sessionId = sessionId,
                        deviceGeneration = deviceGen,
                        payload = ProgressUpdateEvent(
                            positionMs = pos,
                            durationMs = dur,
                            trackGeneration = snap.trackGeneration,
                            expectedProgressRevision = anchorRev
                        )
                    )
                }
            }
        }
    }

    fun stop() {
        airPlayTickerJob?.cancel()
        airPlayTickerJob = null
        snapshotObserverJob?.cancel()
        snapshotObserverJob = null
        coordinator.shutdown()
    }

    companion object {
        private const val TAG = "PlaybackSessionHost"
    }
}
