package com.solo.purecast.airplay

import com.solo.purecast.core.session.ErrorClearedEvent
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.ImplicitPauseEvent
import com.solo.purecast.core.session.MetadataPatchEvent
import com.solo.purecast.core.session.ProgressUpdateEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamSetupEvent
import com.solo.purecast.core.session.StreamTeardownEvent
import com.solo.purecast.core.session.TrackBoundaryEvent
import com.solo.purecast.core.session.TrackSwitchEvent
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * 💡 Unit U3 测试：验证 AirPlay 无状态事件分派器会话映射、顺序校验与时长防闪烁语义
 */
class AirPlayServerEventDispatchTest {

    private fun createDispatcher(): Pair<AirPlayNativeEventDispatcher, MutableList<SessionEventEnvelope<SessionEventPayload>>> {
        val events = mutableListOf<SessionEventEnvelope<SessionEventPayload>>()
        val globalSessionGen = AtomicLong(100L)
        val seqGen = AtomicLong(0L)
        var nextEpoch = 10L
        val dispatcher = AirPlayNativeEventDispatcher(
            dispatchEvent = { events += it },
            allocateSessionId = { globalSessionGen.incrementAndGet() },
            allocateSequence = { seqGen.incrementAndGet() },
            onAcquireHardwareForStream = { _, _, _, _ ->
                nextEpoch++
                nextEpoch
            },
            onDecryptAndInjectCryptoKey = { _, _ -> },
            onUpdateDacpTarget = { _, _, _, _ -> },
            onSolveChallenge = { "mock-resp" },
            clockMs = { 1234L }
        )
        return dispatcher to events
    }

    @Test
    fun setupToFirstPacketToExplicitAndImplicitPauseAndResumeTransitions() {
        val (dispatcher, events) = createDispatcher()

        val ep = dispatcher.onStreamRequested(
            nativeSessionId = 1L,
            sequence = 1L,
            sampleRate = 44100,
            bitDepth = 16,
            channels = 2,
            codec = "ALAC",
            clientIp = "192.168.2.50",
            aesKeyBase64 = "",
            aesIvBase64 = "",
            dacpId = "DACP1",
            activeRemoteToken = "TOKEN1",
            dacpPort = 3689
        )
        assertEquals(11L, ep)
        val setup = events.single().payload as StreamSetupEvent
        assertEquals(101L, events.single().sessionId)
        assertEquals(EventOrigin.AIRPLAY, events.single().origin)
        assertEquals(StreamProtocol.AIRPLAY_1, setup.protocol)
        assertEquals(DurationState.UNKNOWN, setup.initialMetadata?.durationState)

        dispatcher.onFirstDataPacket(1L, 2L, 11L, 44100, 16, 2, "ALAC")
        assertTrue(events.last().payload is FirstDataPacketEvent)

        dispatcher.onStreamPaused(1L, 3L, 11L, isImplicit = false)
        assertTrue(events.last().payload is ExplicitPauseEvent)

        dispatcher.onStreamResumed(1L, 4L, 11L)
        assertEquals(11L, (events.last().payload as StreamResumeEvent).resumedEpoch)

        dispatcher.onStreamPaused(1L, 5L, 11L, isImplicit = true)
        assertTrue(events.last().payload is ImplicitPauseEvent)

        dispatcher.onTrackSwitchFlushed(1L, 6L, 12L)
        assertEquals(12L, (events.last().payload as TrackSwitchEvent).newEpoch)

        dispatcher.onStreamStopped(1L, 7L, 13L)
        assertTrue(events.last().payload is StreamTeardownEvent)
    }

    @Test
    fun metadataWithoutDurationStaysUnknownToPreventLiveFlashAndProgressFiltersZeroZero() {
        val (dispatcher, events) = createDispatcher()
        dispatcher.onStreamRequested(1L, 1L, 44100, 16, 2, "ALAC", "10.0.0.2", "", "", "", "", 0)

        // 首曲无时长元数据：绝不能设为 LIVE
        dispatcher.onMetadataReceived(1L, 2L, "晴天", "周杰伦", "叶惠美", 0L, byteArrayOf(1, 2, 3))
        val patch1 = events.last().payload as MetadataPatchEvent
        assertEquals(DurationState.UNKNOWN, patch1.durationState)

        // 0/0/0 进度时间戳必须被丢弃
        val beforeCount = events.size
        dispatcher.onProgressUpdated(1L, 3L, 0L, 0L)
        assertEquals(beforeCount, events.size)

        // 收到有效 progress 含 durMs > 0 -> 补丁升级为 FIXED + SEEKABLE
        dispatcher.onProgressUpdated(1L, 4L, 12_000L, 269_000L)
        val patchesAfterProgress = events.drop(beforeCount).map { it.payload }
        assertTrue(patchesAfterProgress.any { it is MetadataPatchEvent && it.durationState == DurationState.FIXED && it.seekCapability == SeekCapability.SEEKABLE })
        assertTrue(patchesAfterProgress.any { it is ProgressUpdateEvent && it.positionMs == 12_000L && it.durationMs == 269_000L })

        // 切歌 DMAP 到达，先产生 TrackBoundaryEvent 递增 trackGeneration，且不清空 coverBytes 也不假设 LIVE
        dispatcher.onMetadataReceived(1L, 5L, "稻香", "周杰伦", "魔杰座", 0L, null)
        val boundary = events[events.size - 2].payload as TrackBoundaryEvent
        val patch2 = events.last().payload as MetadataPatchEvent
        assertEquals(DurationState.UNKNOWN, boundary.initialDurationState)
        assertEquals(patch2.trackGeneration, boundary.trackGeneration)
        assertNull(patch2.coverBytes)

        // 真实电台直播 (pos > 0, dur == 0) 才标为 LIVE
        dispatcher.onProgressUpdated(1L, 6L, 5000L, 0L)
        assertTrue(events.dropLast(1).last().payload is MetadataPatchEvent)
        val livePatch = events.firstOrNull { it.payload is MetadataPatchEvent && (it.payload as MetadataPatchEvent).durationState == DurationState.LIVE }
        assertTrue(livePatch != null)

        // 曲目已被标记为 LIVE 后，同曲目迟到且 durationMs == 0 的 DMAP 元数据绝不能将状态退回 UNKNOWN (#29)
        val beforeDelayedMeta = events.size
        dispatcher.onMetadataReceived(1L, 7L, "稻香", "周杰伦", "魔杰座专辑", 0L, null)
        val delayedPatch = events.drop(beforeDelayedMeta).first { it.payload is MetadataPatchEvent }.payload as MetadataPatchEvent
        assertNull("LIVE 状态确立后 duration=0 补丁不得覆写为 UNKNOWN", delayedPatch.durationState)
        assertNull("LIVE 状态确立后 duration=0 补丁不得覆写 SeekCapability", delayedPatch.seekCapability)
    }

    @Test
    fun `hasSession reflects active session ownership across preemption and teardown`() {
        val (dispatcher, events) = createDispatcher()
        dispatcher.onStreamRequested(10L, 1L, 44100, 16, 2, "ALAC", "10.0.0.1", "", "", "", "", 0)
        val firstGlobalSid = events.last { it.payload is StreamSetupEvent }.sessionId
        assertTrue(firstGlobalSid > 0L)
        assertTrue(dispatcher.hasSession(firstGlobalSid))

        // 切至新会话后旧 globalSessionId 不再属于活跃会话 (#13)
        dispatcher.onStreamRequested(11L, 1L, 48000, 24, 2, "ALAC", "10.0.0.2", "", "", "", "", 0)
        val secondGlobalSid = events.last { it.payload is StreamSetupEvent }.sessionId
        assertFalse(dispatcher.hasSession(firstGlobalSid))
        assertTrue(dispatcher.hasSession(secondGlobalSid))

        // 抢占 / 停止调用 resetSessions() 后活跃 globalSessionId 失效 (#13)
        dispatcher.resetSessions()
        assertFalse(dispatcher.hasSession(secondGlobalSid))
    }

    @Test
    fun staleNativeSessionEventsAreDroppedWithoutContaminatingActiveSession() {
        val (dispatcher, events) = createDispatcher()
        dispatcher.onStreamRequested(1L, 1L, 44100, 16, 2, "ALAC", "10.0.0.1", "", "", "", "", 0)
        dispatcher.onStreamRequested(2L, 1L, 44100, 16, 2, "ALAC", "10.0.0.2", "", "", "", "", 0)
        val countAfterSecondSetup = events.size

        // 旧会话 (nativeSessionId = 1) 迟到的 TEARDOWN / PAUSE 必须被静默丢弃
        dispatcher.onStreamPaused(1L, 99L, 11L, isImplicit = true)
        dispatcher.onStreamStopped(1L, 100L, 12L)
        assertEquals(countAfterSecondSetup, events.size)
    }
}
