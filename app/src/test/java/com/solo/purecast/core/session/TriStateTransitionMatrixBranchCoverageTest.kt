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
}
