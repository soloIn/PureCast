package com.solo.purecast.service

import android.media.session.PlaybackState
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.PlaybackSessionCoordinator
import com.solo.purecast.core.session.ProgressUpdateEvent
import com.solo.purecast.core.session.RemoteCommandType
import com.solo.purecast.core.session.RemoteSkipNextEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionHardwareBridge
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamStopEvent
import com.solo.purecast.model.BusinessSessionState
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
 * 严格取代脆弱的 `isLiveStream` 布尔判断：仅当明确可寻道 [SeekCapability.SEEKABLE] 时开放 `ACTION_SEEK_TO`。
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
    private val onSendAirPlayDacpCommand: (RemoteCommandType) -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private var snapshotObserverJob: Job? = null
    private var airPlayTickerJob: Job? = null
    private var lastSnapshot: PlayerSessionSnapshot? = null

    @Volatile
    private var lastAnchorPosMs: Long = 0L

    @Volatile
    private var lastAnchorClockMs: Long = 0L

    @Volatile
    private var lastTickerEmittedPosMs: Long = -1L

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
    fun acquireHardwareForStream(sampleRate: Int, bitDepth: Int, channels: Int): Long {
        systemEffects.wakeUpUiIfInBackground()
        if (!systemEffects.requestAudioFocus()) {
            Logger.service.w(TAG, "申请音频焦点被拒绝，拒绝建流 ($sampleRate/$bitDepth/$channels)")
            return -1L
        }

        val openResult = hardwareBridge.openDeviceWithCurrentFd(sampleRate, bitDepth, channels)
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

        val origin = originForProtocol(snap.activeProtocol)
        if (snap.activeProtocol == StreamProtocol.AIRPLAY_1) {
            onSendAirPlayDacpCommand(RemoteCommandType.PAUSE)
        } else if (snap.activeProtocol == StreamProtocol.UPNP_DLNA) {
            systemEffects.notifyUpnpTransportState("PAUSED_PLAYBACK")
        }

        coordinator.dispatch(
            SessionEventEnvelope(
                origin = origin,
                sessionId = snap.sessionId,
                deviceGeneration = snap.deviceGeneration,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = clockMs(),
                payload = ExplicitPauseEvent(
                    flushReason = FlushReason.EXPLICIT_PAUSE,
                    epoch = pausedEpoch
                )
            )
        )
    }

    fun resumePlayback() {
        val snap = coordinator.snapshot.value
        if (snap.sessionId == 0L || snap.activeProtocol == StreamProtocol.NONE) {
            return
        }

        systemEffects.requestAudioFocus()
        if (snap.activeProtocol == StreamProtocol.AIRPLAY_1) {
            onSendAirPlayDacpCommand(RemoteCommandType.PLAY)
        } else if (snap.activeProtocol == StreamProtocol.UPNP_DLNA) {
            systemEffects.notifyUpnpTransportState("PLAYING")
        }

        coordinator.dispatch(
            SessionEventEnvelope(
                origin = originForProtocol(snap.activeProtocol),
                sessionId = snap.sessionId,
                deviceGeneration = snap.deviceGeneration,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = clockMs(),
                payload = StreamResumeEvent(
                    protocol = snap.activeProtocol,
                    resumedEpoch = snap.currentEpoch
                )
            )
        )
    }

    fun stopPlayback() {
        val snap = coordinator.snapshot.value
        val stoppedEpoch = hardwareBridge.emergencyMuteAndFlush(FlushReason.STOP_OR_TEARDOWN)
        if (snap.sessionId != 0L && snap.activeProtocol != StreamProtocol.NONE) {
            systemEffects.preemptProtocol(snap.activeProtocol, snap.sessionId)
            coordinator.dispatch(
                SessionEventEnvelope(
                    origin = originForProtocol(snap.activeProtocol),
                    sessionId = snap.sessionId,
                    deviceGeneration = snap.deviceGeneration,
                    sequence = coordinator.allocateSequence(),
                    timestampElapsedMs = clockMs(),
                    payload = StreamStopEvent(
                        flushReason = FlushReason.STOP_OR_TEARDOWN,
                        newEpoch = stoppedEpoch,
                        isPreempted = false
                    )
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

    private fun dispatchRemoteCommand(commandType: RemoteCommandType) {
        val snap = coordinator.snapshot.value
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USER_ACTION,
                sessionId = snap.sessionId,
                deviceGeneration = snap.deviceGeneration,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = clockMs(),
                payload = RemoteSkipNextEvent(
                    commandType = commandType,
                    commandId = coordinator.allocateCommandId()
                )
            )
        )
    }

    private fun handleSnapshotUpdate(prev: PlayerSessionSnapshot?, next: PlayerSessionSnapshot) {
        systemEffects.syncMediaSessionState(
            status = next.playbackStatus,
            positionMs = next.progressPositionMs,
            seekCapability = next.metadata.seekCapability
        )

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

        // 💡 依据业务稳态管理 Wi-Fi 低延迟锁与音频焦点
        when (next.businessState) {
            BusinessSessionState.PLAYING, BusinessSessionState.TRANSITIONING -> {
                systemEffects.acquireWifiLowLatencyLock()
            }
            BusinessSessionState.PAUSED -> {
                systemEffects.releaseWifiLowLatencyLock()
            }
            BusinessSessionState.STOPPED, BusinessSessionState.IDLE -> {
                systemEffects.releaseWifiLowLatencyLock()
                if (prev?.businessState != null &&
                    prev.businessState != BusinessSessionState.STOPPED &&
                    prev.businessState != BusinessSessionState.IDLE
                ) {
                    systemEffects.abandonAudioFocus()
                }
            }
        }

        // 💡 AirPlay 1 在 PLAYING 期间维护单调时钟平滑进度推进，补偿 iOS 不周期推 Progress 包的特性
        val isProtocolProgressUpdate = next.progressPositionMs != lastTickerEmittedPosMs
        if (prev == null ||
            prev.sessionId != next.sessionId ||
            prev.trackGeneration != next.trackGeneration ||
            prev.businessState != next.businessState ||
            (isProtocolProgressUpdate && prev.progressPositionMs != next.progressPositionMs)
        ) {
            lastAnchorPosMs = next.progressPositionMs
            lastAnchorClockMs = clockMs()
        }

        val needTicker = next.activeProtocol == StreamProtocol.AIRPLAY_1 &&
            next.businessState == BusinessSessionState.PLAYING &&
            next.sessionId != 0L
        if (needTicker) {
            ensureAirPlayTickerRunning(next.sessionId, next.deviceGeneration)
        } else {
            airPlayTickerJob?.cancel()
            airPlayTickerJob = null
            lastTickerEmittedPosMs = -1L
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
                val elapsed = (clockMs() - lastAnchorClockMs).coerceAtLeast(0L)
                var pos = lastAnchorPosMs + elapsed
                val dur = snap.progressDurationMs
                if (dur > 0L && pos > dur) {
                    pos = dur
                }
                if (pos != snap.progressPositionMs) {
                    lastTickerEmittedPosMs = pos
                    coordinator.dispatch(
                        SessionEventEnvelope(
                            origin = EventOrigin.AIRPLAY,
                            sessionId = sessionId,
                            deviceGeneration = deviceGen,
                            sequence = coordinator.allocateSequence(),
                            timestampElapsedMs = clockMs(),
                            payload = ProgressUpdateEvent(
                                positionMs = pos,
                                durationMs = dur
                            )
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

    private fun originForProtocol(protocol: StreamProtocol): EventOrigin = when (protocol) {
        StreamProtocol.AIRPLAY_1 -> EventOrigin.AIRPLAY
        else -> EventOrigin.UPNP
    }

    companion object {
        private const val TAG = "PlaybackSessionHost"
    }
}
