package com.solo.purecast.core.session

import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.PlaybackError
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.model.TransportPipeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💡 精准补齐 TriStateTransitionMatrix 在 TrackBoundaryEvent 与 ProgressUpdateEvent 中的全部分支覆盖
 */
class TriStateTransitionMatrixBranchCoverageTest {

    private fun <T : SessionEventPayload> envelope(
        payload: T,
        origin: EventOrigin = EventOrigin.AIRPLAY,
        sessionId: Long = 1L,
        sequence: Long = 1L
    ): SessionEventEnvelope<T> = SessionEventEnvelope(
        origin = origin,
        sessionId = sessionId,
        deviceGeneration = 1L,
        sequence = sequence,
        timestampElapsedMs = 1000L,
        payload = payload
    )

    @Test
    fun trackBoundaryAirPlayColdStartPlaceholderPreservesOrResetsProgressAndDuration() {
        val tm1 = TrackTransactionManager()
        val ctxWithProgress = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                businessState = BusinessSessionState.PLAYING,
                metadata = TrackMetadata(title = "AirPlay 音频直通"),
                progressPositionMs = 45_000L,
                progressDurationMs = 240_000L
            )
        )
        val out1 = TriStateTransitionMatrix.reduce(
            ctx = ctxWithProgress,
            envelope = envelope(TrackBoundaryEvent(trackKey = "k1", trackGeneration = 2L)),
            trackManager = tm1,
            nowMs = 1000L
        )
        assertEquals(45_000L, out1.nextContext.snapshot.progressPositionMs)
        assertEquals(240_000L, out1.nextContext.snapshot.progressDurationMs)

        // AirPlay 占位标题但进度/时长均为 0 时回落 0L
        val tm2 = TrackTransactionManager()
        val ctxZeroProgress = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                businessState = BusinessSessionState.PLAYING,
                metadata = TrackMetadata(title = "AirPlay 音频直通"),
                progressPositionMs = 0L,
                progressDurationMs = 0L
            )
        )
        val out2 = TriStateTransitionMatrix.reduce(
            ctx = ctxZeroProgress,
            envelope = envelope(TrackBoundaryEvent(trackKey = "k2", trackGeneration = 2L)),
            trackManager = tm2,
            nowMs = 1000L
        )
        assertEquals(0L, out2.nextContext.snapshot.progressPositionMs)
        assertEquals(0L, out2.nextContext.snapshot.progressDurationMs)

        // 显式携带 initialPositionMs 与 initialDurationMs 时优先采纳
        val tm3 = TrackTransactionManager()
        val out3 = TriStateTransitionMatrix.reduce(
            ctx = ctxWithProgress,
            envelope = envelope(
                TrackBoundaryEvent(
                    trackKey = "k3",
                    trackGeneration = 2L,
                    initialPositionMs = 12_000L,
                    initialDurationMs = 180_000L
                )
            ),
            trackManager = tm3,
            nowMs = 1000L
        )
        assertEquals(12_000L, out3.nextContext.snapshot.progressPositionMs)
        assertEquals(180_000L, out3.nextContext.snapshot.progressDurationMs)
    }

    @Test
    fun trackBoundaryCompletesRemoteSkipAcrossAllOriginAndHardwareCombinations() {
        val timeoutErr = PlaybackError(
            code = ErrorCode.REMOTE_COMMAND_TIMEOUT,
            message = "timeout",
            recoverable = true,
            timestampMs = 500L
        )

        // 1. transportState == PREPARING 时不提前结束遥控切歌过渡态
        val tmPreparing = TrackTransactionManager()
        val ctxPreparing = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.PREPARING,
                businessState = BusinessSessionState.TRANSITIONING,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                uiProtectionActive = true,
                transitionOriginState = BusinessSessionState.PLAYING
            ),
            activeRemoteCommandId = 10L,
            activeUiProtectionToken = 20L,
            preCommandBusinessState = BusinessSessionState.PLAYING
        )
        val outPreparing = TriStateTransitionMatrix.reduce(
            ctx = ctxPreparing,
            envelope = envelope(TrackBoundaryEvent(trackKey = "p1", trackGeneration = 2L)),
            trackManager = tmPreparing,
            nowMs = 1000L
        )
        assertEquals(BusinessSessionState.TRANSITIONING, outPreparing.nextContext.snapshot.businessState)
        assertEquals(10L, outPreparing.nextContext.activeRemoteCommandId)

        // 2. activeRemoteCommandId == 0L 但 transitionOriginState != null，STREAMING + PLAYING + EXCLUSIVE_MUTED -> 恢复独占发声
        val tmOrigin = TrackTransactionManager()
        val ctxOrigin = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.STREAMING,
                businessState = BusinessSessionState.TRANSITIONING,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                uiProtectionActive = true,
                transitionOriginState = BusinessSessionState.PLAYING,
                errors = mapOf(ErrorCode.REMOTE_COMMAND_TIMEOUT to timeoutErr)
            ),
            activeRemoteCommandId = 0L,
            activeUiProtectionToken = 21L,
            preCommandBusinessState = BusinessSessionState.PLAYING
        )
        val outOrigin = TriStateTransitionMatrix.reduce(
            ctx = ctxOrigin,
            envelope = envelope(TrackBoundaryEvent(trackKey = "o1", trackGeneration = 2L)),
            trackManager = tmOrigin,
            nowMs = 1000L
        )
        assertEquals(BusinessSessionState.PLAYING, outOrigin.nextContext.snapshot.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, outOrigin.nextContext.snapshot.hardwareState)
        assertFalse(outOrigin.nextContext.snapshot.uiProtectionActive)
        assertNull(outOrigin.nextContext.snapshot.transitionOriginState)
        assertTrue(outOrigin.nextContext.snapshot.errors.isEmpty())
        assertEquals(0L, outOrigin.nextContext.activeRemoteCommandId)
        assertEquals(0L, outOrigin.nextContext.activeUiProtectionToken)
        assertTrue(outOrigin.effects.contains(TransitionEffect.ResumeNativeOutput))
        assertTrue(outOrigin.effects.contains(TransitionEffect.CancelRemoteCommandTimeout))
        assertTrue(outOrigin.effects.contains(TransitionEffect.CancelTransitionTimeout))

        // 3. preCommandBusinessState == PAUSED -> 回落 PAUSED 且不触发 ResumeNativeOutput
        val tmPaused = TrackTransactionManager()
        val ctxPaused = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.STREAMING,
                businessState = BusinessSessionState.TRANSITIONING,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                transitionOriginState = BusinessSessionState.PAUSED
            ),
            activeRemoteCommandId = 11L,
            preCommandBusinessState = BusinessSessionState.PAUSED
        )
        val outPaused = TriStateTransitionMatrix.reduce(
            ctx = ctxPaused,
            envelope = envelope(TrackBoundaryEvent(trackKey = "pa1", trackGeneration = 2L)),
            trackManager = tmPaused,
            nowMs = 1000L
        )
        assertEquals(BusinessSessionState.PAUSED, outPaused.nextContext.snapshot.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, outPaused.nextContext.snapshot.hardwareState)
        assertFalse(outPaused.effects.contains(TransitionEffect.ResumeNativeOutput))

        // 4. PLAYING 但硬件处于非独占态 (RELEASED) -> 恢复 PLAYING 但不触发 ResumeNativeOutput
        val tmReleased = TrackTransactionManager()
        val ctxReleased = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.STREAMING,
                businessState = BusinessSessionState.TRANSITIONING,
                hardwareState = HardwarePipelineState.RELEASED,
                transitionOriginState = BusinessSessionState.PLAYING
            ),
            activeRemoteCommandId = 12L,
            preCommandBusinessState = BusinessSessionState.PLAYING
        )
        val outReleased = TriStateTransitionMatrix.reduce(
            ctx = ctxReleased,
            envelope = envelope(TrackBoundaryEvent(trackKey = "r1", trackGeneration = 2L)),
            trackManager = tmReleased,
            nowMs = 1000L
        )
        assertEquals(BusinessSessionState.PLAYING, outReleased.nextContext.snapshot.businessState)
        assertEquals(HardwarePipelineState.RELEASED, outReleased.nextContext.snapshot.hardwareState)
        assertFalse(outReleased.effects.contains(TransitionEffect.ResumeNativeOutput))
    }

    @Test
    fun progressUpdateRevisionCheckAndServiceNonAuthoritativeBranch() {
        val tm = TrackTransactionManager()
        val baseCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 2L,
                businessState = BusinessSessionState.PLAYING,
                progressPositionMs = 10_000L,
                progressDurationMs = 200_000L,
                progressRevision = 5L
            )
        )

        // 1. SERVICE 轮询包携带过期 expectedProgressRevision -> 丢弃
        val dropped = TriStateTransitionMatrix.reduce(
            ctx = baseCtx,
            envelope = envelope(
                payload = ProgressUpdateEvent(
                    positionMs = 11_000L,
                    durationMs = 200_000L,
                    trackGeneration = 2L,
                    expectedProgressRevision = 4L
                ),
                origin = EventOrigin.SERVICE
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(10_000L, dropped.nextContext.snapshot.progressPositionMs)
        assertEquals(5L, dropped.nextContext.snapshot.progressRevision)

        // 2. SERVICE 轮询包携带匹配 expectedProgressRevision -> 放行但不递增 progressRevision
        val matched = TriStateTransitionMatrix.reduce(
            ctx = baseCtx,
            envelope = envelope(
                payload = ProgressUpdateEvent(
                    positionMs = 12_000L,
                    durationMs = 200_000L,
                    trackGeneration = 2L,
                    expectedProgressRevision = 5L
                ),
                origin = EventOrigin.SERVICE
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(12_000L, matched.nextContext.snapshot.progressPositionMs)
        assertEquals(5L, matched.nextContext.snapshot.progressRevision)
        assertFalse(matched.nextContext.snapshot.lastProgressFromUserSeek)

        // 3. SERVICE 轮询包未指定 expectedProgressRevision (-1L) -> 放行且不递增 progressRevision
        val unchecked = TriStateTransitionMatrix.reduce(
            ctx = baseCtx,
            envelope = envelope(
                payload = ProgressUpdateEvent(
                    positionMs = 13_000L,
                    durationMs = 200_000L,
                    trackGeneration = 2L,
                    expectedProgressRevision = -1L
                ),
                origin = EventOrigin.SERVICE
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(13_000L, unchecked.nextContext.snapshot.progressPositionMs)
        assertEquals(5L, unchecked.nextContext.snapshot.progressRevision)
    }

    @Test
    fun progressUpdateUserActionAndAntiRewindBoundaryBranches() {
        val tm = TrackTransactionManager()

        // 1. USER_ACTION 显式寻道至 0ms（即便当前在 1..3500ms 区间内）必须放行并标记 lastProgressFromUserSeek=true
        val ctxEarly = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 2L,
                businessState = BusinessSessionState.PLAYING,
                progressPositionMs = 1_500L,
                progressDurationMs = 200_000L,
                progressRevision = 3L
            )
        )
        val userSeek = TriStateTransitionMatrix.reduce(
            ctx = ctxEarly,
            envelope = envelope(
                payload = ProgressUpdateEvent(positionMs = 0L, durationMs = 200_000L, trackGeneration = 2L),
                origin = EventOrigin.USER_ACTION
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(0L, userSeek.nextContext.snapshot.progressPositionMs)
        assertEquals(4L, userSeek.nextContext.snapshot.progressRevision)
        assertTrue(userSeek.nextContext.snapshot.lastProgressFromUserSeek)

        // 2. 非 USER_ACTION 且 progressPositionMs == 0L (< 1L) 时允许 0ms 更新
        val ctxAtZero = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 2L,
                businessState = BusinessSessionState.PLAYING,
                progressPositionMs = 0L,
                progressDurationMs = 200_000L,
                progressRevision = 3L
            )
        )
        val fromZero = TriStateTransitionMatrix.reduce(
            ctx = ctxAtZero,
            envelope = envelope(
                payload = ProgressUpdateEvent(positionMs = 0L, durationMs = 200_000L, trackGeneration = 2L),
                origin = EventOrigin.AIRPLAY
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(0L, fromZero.nextContext.snapshot.progressPositionMs)
        assertEquals(4L, fromZero.nextContext.snapshot.progressRevision)

        // 3. 非 USER_ACTION 且 progressPositionMs == 5000L (> 3500L) 时允许 0ms 协议进度更新
        val ctxPastWindow = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 2L,
                businessState = BusinessSessionState.PLAYING,
                progressPositionMs = 5_000L,
                progressDurationMs = 200_000L,
                progressRevision = 3L
            )
        )
        val pastWindow = TriStateTransitionMatrix.reduce(
            ctx = ctxPastWindow,
            envelope = envelope(
                payload = ProgressUpdateEvent(positionMs = 0L, durationMs = 200_000L, trackGeneration = 2L),
                origin = EventOrigin.AIRPLAY
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(0L, pastWindow.nextContext.snapshot.progressPositionMs)
        assertEquals(4L, pastWindow.nextContext.snapshot.progressRevision)

        // 4. 非 USER_ACTION 在 1..3500ms 区间内收到 0ms 占位包被拦截，非零包放行
        val blockedZero = TriStateTransitionMatrix.reduce(
            ctx = ctxEarly,
            envelope = envelope(
                payload = ProgressUpdateEvent(positionMs = 0L, durationMs = 200_000L, trackGeneration = 2L),
                origin = EventOrigin.AIRPLAY
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(1_500L, blockedZero.nextContext.snapshot.progressPositionMs)

        val allowedNonZero = TriStateTransitionMatrix.reduce(
            ctx = ctxEarly,
            envelope = envelope(
                payload = ProgressUpdateEvent(positionMs = 50L, durationMs = 200_000L, trackGeneration = 2L),
                origin = EventOrigin.AIRPLAY
            ),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(50L, allowedNonZero.nextContext.snapshot.progressPositionMs)
    }

    @Test
    fun remotePlayCommandAndRollbackCoverAllHardwareAndTransportBranches() {
        val tm = TrackTransactionManager()

        // 1. PLAYING 态收到 PLAY 直接忽略，防止卡在 TRANSITIONING 5 秒报错
        val playingCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.STREAMING,
                businessState = BusinessSessionState.PLAYING,
                hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE
            )
        )
        val ignoredPlay = TriStateTransitionMatrix.reduce(
            ctx = playingCtx,
            envelope = envelope(RemoteSkipNextEvent(RemoteCommandType.PLAY, commandId = 101L), origin = EventOrigin.USER_ACTION),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(playingCtx, ignoredPlay.nextContext)
        assertTrue(ignoredPlay.effects.isEmpty())

        // 2. PAUSED + DISCONNECTED -> 发 DACP PLAY 但不发 OpenDeviceOnResume / ResumeNativeOutput
        val disconnectedPausedCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.DISCONNECTED,
                businessState = BusinessSessionState.PAUSED,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED
            )
        )
        val outDisconnected = TriStateTransitionMatrix.reduce(
            ctx = disconnectedPausedCtx,
            envelope = envelope(RemoteSkipNextEvent(RemoteCommandType.PLAY, commandId = 102L), origin = EventOrigin.USER_ACTION),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(BusinessSessionState.TRANSITIONING, outDisconnected.nextContext.snapshot.businessState)
        assertEquals(RemoteCommandType.PLAY, outDisconnected.nextContext.activeRemoteCommandType)
        assertFalse(outDisconnected.effects.contains(TransitionEffect.ResumeNativeOutput))
        assertTrue(outDisconnected.effects.none { it is TransitionEffect.OpenDeviceOnResume })

        // 2b. DISCONNECTED 态下 PLAY 超时 -> 直接断开会话归 IDLE 并附带错误与通知协议层
        val outDisconnectedTimeout = TriStateTransitionMatrix.reduce(
            ctx = outDisconnected.nextContext,
            envelope = envelope(RemoteCommandTimeoutEvent(commandId = 102L), origin = EventOrigin.SERVICE),
            trackManager = tm,
            nowMs = 6000L
        )
        assertEquals(BusinessSessionState.IDLE, outDisconnectedTimeout.nextContext.snapshot.businessState)
        assertEquals(0L, outDisconnectedTimeout.nextContext.snapshot.sessionId)
        assertTrue(outDisconnectedTimeout.nextContext.snapshot.errors.containsKey(ErrorCode.REMOTE_COMMAND_TIMEOUT))
        assertTrue(outDisconnectedTimeout.effects.any { it is TransitionEffect.NotifyPreemptedProtocol })

        // 3. PAUSED + INTERRUPTED + DISCONNECTED(硬件拔出) -> 不发硬件恢复副作用
        val detachedHwCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 2L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.INTERRUPTED,
                businessState = BusinessSessionState.PAUSED,
                hardwareState = HardwarePipelineState.DISCONNECTED
            )
        )
        val outDetachedHw = TriStateTransitionMatrix.reduce(
            ctx = detachedHwCtx,
            envelope = envelope(RemoteSkipNextEvent(RemoteCommandType.PLAY, commandId = 103L), origin = EventOrigin.USER_ACTION, sessionId = 2L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertFalse(outDetachedHw.effects.contains(TransitionEffect.ResumeNativeOutput))
        assertTrue(outDetachedHw.effects.none { it is TransitionEffect.OpenDeviceOnResume })

        // 4. PAUSED + INTERRUPTED + RELEASED -> 发射 OpenDeviceOnResume
        val releasedHwCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 3L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.INTERRUPTED,
                businessState = BusinessSessionState.PAUSED,
                hardwareState = HardwarePipelineState.RELEASED
            )
        )
        val outReleasedHw = TriStateTransitionMatrix.reduce(
            ctx = releasedHwCtx,
            envelope = envelope(RemoteSkipNextEvent(RemoteCommandType.PLAY, commandId = 104L), origin = EventOrigin.USER_ACTION, sessionId = 3L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertTrue(outReleasedHw.effects.any { it is TransitionEffect.OpenDeviceOnResume })

        // 5. PAUSED + INTERRUPTED + AUDIO_RECONFIGURING -> 不提前发 ResumeNativeOutput
        val reconfigHwCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 4L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.INTERRUPTED,
                businessState = BusinessSessionState.PAUSED,
                hardwareState = HardwarePipelineState.AUDIO_RECONFIGURING
            )
        )
        val outReconfigHw = TriStateTransitionMatrix.reduce(
            ctx = reconfigHwCtx,
            envelope = envelope(RemoteSkipNextEvent(RemoteCommandType.PLAY, commandId = 105L), origin = EventOrigin.USER_ACTION, sessionId = 4L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertFalse(outReconfigHw.effects.contains(TransitionEffect.ResumeNativeOutput))

        // 6. PAUSED + INTERRUPTED + EXCLUSIVE_MUTED -> 发射 ResumeNativeOutput；期间收到 TrackBoundaryEvent 不提前结束 TRANSITIONING；超时回退 PAUSED 并重设 SuspendTimeout
        val mutedHwCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 5L,
                trackGeneration = 1L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.INTERRUPTED,
                businessState = BusinessSessionState.PAUSED,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED
            )
        )
        val outMutedHw = TriStateTransitionMatrix.reduce(
            ctx = mutedHwCtx,
            envelope = envelope(RemoteSkipNextEvent(RemoteCommandType.PLAY, commandId = 106L), origin = EventOrigin.USER_ACTION, sessionId = 5L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertTrue(outMutedHw.effects.contains(TransitionEffect.ResumeNativeOutput))

        val outBoundaryDuringPlay = TriStateTransitionMatrix.reduce(
            ctx = outMutedHw.nextContext,
            envelope = envelope(TrackBoundaryEvent(trackKey = "k_play", trackGeneration = 2L), sessionId = 5L),
            trackManager = tm,
            nowMs = 1500L
        )
        assertEquals(BusinessSessionState.TRANSITIONING, outBoundaryDuringPlay.nextContext.snapshot.businessState)
        assertEquals(RemoteCommandType.PLAY, outBoundaryDuringPlay.nextContext.activeRemoteCommandType)

        val outTimeoutPaused = TriStateTransitionMatrix.reduce(
            ctx = outBoundaryDuringPlay.nextContext,
            envelope = envelope(RemoteCommandTimeoutEvent(commandId = 106L), origin = EventOrigin.SERVICE, sessionId = 5L),
            trackManager = tm,
            nowMs = 6000L
        )
        assertEquals(BusinessSessionState.PAUSED, outTimeoutPaused.nextContext.snapshot.businessState)
        assertEquals("远端设备未响应播放指令", outTimeoutPaused.nextContext.snapshot.errors[ErrorCode.REMOTE_COMMAND_TIMEOUT]?.message)
        assertTrue(outTimeoutPaused.effects.any { it is TransitionEffect.ScheduleSuspendTimeout })

        // 7. NEXT 指令超时使用“远端设备未响应切歌指令”文案
        val nextSkipCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 6L,
                activeProtocol = StreamProtocol.AIRPLAY_1,
                transportState = TransportPipeState.STREAMING,
                businessState = BusinessSessionState.TRANSITIONING,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED
            ),
            activeRemoteCommandId = 107L,
            activeRemoteCommandType = RemoteCommandType.NEXT,
            preCommandBusinessState = BusinessSessionState.PLAYING
        )
        val outNextTimeout = TriStateTransitionMatrix.reduce(
            ctx = nextSkipCtx,
            envelope = envelope(RemoteCommandTimeoutEvent(commandId = 107L), origin = EventOrigin.SERVICE, sessionId = 6L),
            trackManager = tm,
            nowMs = 6000L
        )
        assertEquals("远端设备未响应切歌指令", outNextTimeout.nextContext.snapshot.errors[ErrorCode.REMOTE_COMMAND_TIMEOUT]?.message)
    }

    @Test
    fun hardwareIdleTimeoutStoppedCompatAndDisconnectNotifyGuardsAndUpnpResumeDisconnected() {
        val tm = TrackTransactionManager()

        // 1. HardwareIdleTimeoutEvent 在兼容 STOPPED 态下正常释放硬件；在 PLAYING 态下直接忽略
        val stoppedCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                businessState = BusinessSessionState.STOPPED,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED
            )
        )
        val outStoppedIdle = TriStateTransitionMatrix.reduce(
            ctx = stoppedCtx,
            envelope = envelope(HardwareIdleTimeoutEvent, origin = EventOrigin.SERVICE, sessionId = 0L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(HardwarePipelineState.RELEASED, outStoppedIdle.nextContext.snapshot.hardwareState)
        assertTrue(outStoppedIdle.effects.contains(TransitionEffect.ReleaseHardwareDevice))

        val playingIdleCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 8L,
                businessState = BusinessSessionState.PLAYING,
                hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE
            )
        )
        val outPlayingIdle = TriStateTransitionMatrix.reduce(
            ctx = playingIdleCtx,
            envelope = envelope(HardwareIdleTimeoutEvent, origin = EventOrigin.SERVICE, sessionId = 8L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(playingIdleCtx, outPlayingIdle.nextContext)
        assertTrue(outPlayingIdle.effects.isEmpty())

        // 2. StreamTeardownEvent(hardStop=true) 在 sessionId == 0L 或 protocol == NONE 时不发射 NotifyPreemptedProtocol
        val zeroSidCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 0L,
                activeProtocol = StreamProtocol.NONE,
                businessState = BusinessSessionState.PAUSED
            )
        )
        val outZeroSid = TriStateTransitionMatrix.reduce(
            ctx = zeroSidCtx,
            envelope = envelope(StreamTeardownEvent(hardStop = true), sessionId = 0L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertTrue(outZeroSid.effects.none { it is TransitionEffect.NotifyPreemptedProtocol })

        val noneProtoCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 9L,
                activeProtocol = StreamProtocol.NONE,
                businessState = BusinessSessionState.PAUSED
            )
        )
        val outNoneProto = TriStateTransitionMatrix.reduce(
            ctx = noneProtoCtx,
            envelope = envelope(StreamTeardownEvent(hardStop = true), sessionId = 9L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertTrue(outNoneProto.effects.none { it is TransitionEffect.NotifyPreemptedProtocol })

        // 3. StreamResumeEvent: STOPPED 态忽略；UPNP_DLNA + DISCONNECTED 允许恢复
        val stoppedResumeCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 10L,
                activeProtocol = StreamProtocol.UPNP_DLNA,
                businessState = BusinessSessionState.STOPPED,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED
            )
        )
        val outStoppedResume = TriStateTransitionMatrix.reduce(
            ctx = stoppedResumeCtx,
            envelope = envelope(StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 19L), origin = EventOrigin.UPNP, sessionId = 10L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(stoppedResumeCtx, outStoppedResume.nextContext)
        assertTrue(outStoppedResume.effects.isEmpty())

        val upnpDisconnectedCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 10L,
                activeProtocol = StreamProtocol.UPNP_DLNA,
                transportState = TransportPipeState.DISCONNECTED,
                businessState = BusinessSessionState.PAUSED,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED
            )
        )
        val outUpnpResume = TriStateTransitionMatrix.reduce(
            ctx = upnpDisconnectedCtx,
            envelope = envelope(StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 20L), origin = EventOrigin.UPNP, sessionId = 10L),
            trackManager = tm,
            nowMs = 1000L
        )
        assertEquals(BusinessSessionState.PLAYING, outUpnpResume.nextContext.snapshot.businessState)
    }

    @Test
    fun networkStreamErrorDismissAndPositiveAnchorDelayBranches() {
        val tm = TrackTransactionManager()
        val netErr = PlaybackError(
            code = ErrorCode.NETWORK_STREAM_ERROR,
            message = "network glitch",
            recoverable = true,
            timestampMs = 500L
        )

        // 1. ErrorReportedEvent: NETWORK_STREAM_ERROR 分别覆盖 timestampMs > 0L 与 timestampMs == 0L 回退 nowMs
        val baseCtx = TransitionContext(snapshot = PlayerSessionSnapshot(sessionId = 1L))
        val errWithTs = TriStateTransitionMatrix.reduce(
            ctx = baseCtx,
            envelope = envelope(ErrorReportedEvent(netErr), origin = EventOrigin.SERVICE),
            trackManager = tm,
            nowMs = 2000L
        )
        assertTrue(
            errWithTs.effects.contains(
                TransitionEffect.ScheduleTransientErrorDismiss(ErrorCode.NETWORK_STREAM_ERROR, 500L)
            )
        )

        val errWithoutTs = TriStateTransitionMatrix.reduce(
            ctx = baseCtx,
            envelope = envelope(ErrorReportedEvent(netErr.copy(timestampMs = 0L)), origin = EventOrigin.SERVICE),
            trackManager = tm,
            nowMs = 2000L
        )
        assertTrue(
            errWithoutTs.effects.contains(
                TransitionEffect.ScheduleTransientErrorDismiss(ErrorCode.NETWORK_STREAM_ERROR, 2000L)
            )
        )

        val nonNetworkErr = TriStateTransitionMatrix.reduce(
            ctx = baseCtx,
            envelope = envelope(
                ErrorReportedEvent(
                    PlaybackError(
                        code = ErrorCode.USB_OPEN_FAILED,
                        message = "usb open failed",
                        recoverable = true,
                        timestampMs = 2000L
                    )
                ),
                origin = EventOrigin.SERVICE
            ),
            trackManager = tm,
            nowMs = 2000L
        )
        assertTrue(nonNetworkErr.effects.isEmpty())

        // 2. FirstDataPacketEvent 清除已有 NETWORK_STREAM_ERROR 并取消其延时消失定时器
        val firstPacketCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                activeProtocol = StreamProtocol.UPNP_DLNA,
                businessState = BusinessSessionState.TRANSITIONING,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                errors = mapOf(ErrorCode.NETWORK_STREAM_ERROR to netErr)
            )
        )
        val outFirst = TriStateTransitionMatrix.reduce(
            ctx = firstPacketCtx,
            envelope = envelope(
                FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 2L, 44100, 16, 2, "FLAC"),
                origin = EventOrigin.UPNP
            ),
            trackManager = tm,
            nowMs = 2100L
        )
        assertFalse(outFirst.nextContext.snapshot.errors.containsKey(ErrorCode.NETWORK_STREAM_ERROR))
        assertTrue(outFirst.effects.contains(TransitionEffect.CancelTransientErrorDismiss(ErrorCode.NETWORK_STREAM_ERROR)))

        // 3. TrackSwitchEvent 在 PAUSED 与 PLAYING 态均清除 NETWORK_STREAM_ERROR 并取消其定时器
        val pausedSwitchCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                businessState = BusinessSessionState.PAUSED,
                hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                errors = mapOf(ErrorCode.NETWORK_STREAM_ERROR to netErr)
            )
        )
        val outPausedSwitch = TriStateTransitionMatrix.reduce(
            ctx = pausedSwitchCtx,
            envelope = envelope(TrackSwitchEvent(newEpoch = 3L)),
            trackManager = tm,
            nowMs = 2200L
        )
        assertFalse(outPausedSwitch.nextContext.snapshot.errors.containsKey(ErrorCode.NETWORK_STREAM_ERROR))
        assertTrue(outPausedSwitch.effects.contains(TransitionEffect.CancelTransientErrorDismiss(ErrorCode.NETWORK_STREAM_ERROR)))

        val playingSwitchCtx = pausedSwitchCtx.copy(
            snapshot = pausedSwitchCtx.snapshot.copy(businessState = BusinessSessionState.PLAYING)
        )
        val outPlayingSwitch = TriStateTransitionMatrix.reduce(
            ctx = playingSwitchCtx,
            envelope = envelope(TrackSwitchEvent(newEpoch = 4L)),
            trackManager = tm,
            nowMs = 2300L
        )
        assertFalse(outPlayingSwitch.nextContext.snapshot.errors.containsKey(ErrorCode.NETWORK_STREAM_ERROR))
        assertTrue(outPlayingSwitch.effects.contains(TransitionEffect.CancelTransientErrorDismiss(ErrorCode.NETWORK_STREAM_ERROR)))

        // 4. ProgressUpdateEvent: 非 USER_ACTION 但携带 anchorDelayMs > 0L 时放行 0ms 进度更新
        val earlyPlayingCtx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                trackGeneration = 2L,
                businessState = BusinessSessionState.PLAYING,
                progressPositionMs = 1500L,
                progressDurationMs = 200_000L,
                progressRevision = 3L
            )
        )
        val outAnchorDelay = TriStateTransitionMatrix.reduce(
            ctx = earlyPlayingCtx,
            envelope = envelope(
                ProgressUpdateEvent(
                    positionMs = 0L,
                    durationMs = 200_000L,
                    trackGeneration = 2L,
                    anchorDelayMs = 500L
                ),
                origin = EventOrigin.AIRPLAY
            ),
            trackManager = tm,
            nowMs = 2400L
        )
        assertEquals(0L, outAnchorDelay.nextContext.snapshot.progressPositionMs)
        assertEquals(500L, outAnchorDelay.nextContext.snapshot.progressAnchorDelayMs)
    }
}
