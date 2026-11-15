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
import org.junit.Assert.assertArrayEquals
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
        val teardown = events.last().payload as StreamTeardownEvent
        assertFalse("TEARDOWN 事件严禁作为 hardStop 硬停止", teardown.hardStop)
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

        // 💡 R7 契约验收：无时长进度 (pos > 0, dur == 0) 不得误标为 LIVE，必须保持 UNKNOWN，杜绝切歌误切 LIVE 与红点闪烁
        val countBeforeUnknownProgress = events.size
        dispatcher.onProgressUpdated(1L, 6L, 5000L, 0L)
        val newEvents = events.drop(countBeforeUnknownProgress)
        assertTrue(newEvents.any { it.payload is ProgressUpdateEvent && (it.payload as ProgressUpdateEvent).positionMs == 5000L })
        assertFalse("无时长流切歌时绝不得发射 LIVE 补丁", newEvents.any { it.payload is MetadataPatchEvent && (it.payload as MetadataPatchEvent).durationState == DurationState.LIVE })
    }

    @Test
    fun trackSwitchFlushThenProgressThenDmapInheritsDurationCorrectly() {
        var mockClock = 1000L
        val events = mutableListOf<SessionEventEnvelope<SessionEventPayload>>()
        val globalSessionGen = AtomicLong(100L)
        val seqGen = AtomicLong(0L)
        val dispatcher = AirPlayNativeEventDispatcher(
            dispatchEvent = { events += it },
            allocateSessionId = { globalSessionGen.incrementAndGet() },
            allocateSequence = { seqGen.incrementAndGet() },
            onAcquireHardwareForStream = { _, _, _, _ -> 11L },
            onDecryptAndInjectCryptoKey = { _, _ -> },
            onUpdateDacpTarget = { _, _, _, _ -> },
            onSolveChallenge = { "mock-resp" },
            clockMs = { mockClock }
        )

        dispatcher.onStreamRequested(1L, 1L, 44100, 16, 2, "ALAC", "10.0.0.2", "", "", "", "", 0)
        dispatcher.onMetadataReceived(1L, 2L, "晴天", "周杰伦", "叶惠美", 260_000L, null)

        // 💡 模拟 iOS 切歌分包乱序时序：FLUSH -> progress: -> dmap:
        mockClock = 2000L
        dispatcher.onTrackSwitchFlushed(1L, 3L, 15L)

        mockClock = 2100L
        dispatcher.onProgressUpdated(1L, 4L, 0L, 240_000L)

        mockClock = 2200L
        dispatcher.onMetadataReceived(1L, 5L, "夜曲", "周杰伦", "十一月的萧邦", 0L, null)

        val boundary = events[events.size - 2].payload as TrackBoundaryEvent
        val patch = events.last().payload as MetadataPatchEvent

        // 验证：新歌成功继承换代后先到达的 240 秒时长，状态为 FIXED + SEEKABLE
        assertEquals(DurationState.FIXED, boundary.initialDurationState)
        assertEquals(SeekCapability.SEEKABLE, boundary.seekCapability)
        assertEquals(240_000L, patch.durationMs)
        assertEquals(DurationState.FIXED, patch.durationState)
        assertEquals(SeekCapability.SEEKABLE, patch.seekCapability)
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

    @Test
    fun markPendingSeekSetsIsSeekFlushOnTrackSwitchFlushed() {
        val (dispatcher, events) = createDispatcher()
        dispatcher.onStreamRequested(1L, 1L, 44100, 16, 2, "ALAC", "10.0.0.1", "", "", "", "", 0)

        // 默认情况下切歌 FLUSH 的 isSeekFlush 为 false (重置进度)
        dispatcher.onTrackSwitchFlushed(1L, 2L, 12L)
        val normalSwitch = events.last().payload as TrackSwitchEvent
        assertEquals(12L, normalSwitch.newEpoch)
        assertFalse(normalSwitch.isSeekFlush)

        // 标记触发 TV 端寻道后，4000ms 窗口内的 FLUSH 被标记为 isSeekFlush = true (保留进度)
        dispatcher.markPendingSeek()
        dispatcher.onTrackSwitchFlushed(1L, 3L, 13L)
        val seekSwitch = events.last().payload as TrackSwitchEvent
        assertEquals(13L, seekSwitch.newEpoch)
        assertTrue(seekSwitch.isSeekFlush)

        // 再次到达的 FLUSH 标记已被消费，恢复为 isSeekFlush = false
        dispatcher.onTrackSwitchFlushed(1L, 4L, 14L)
        val subsequentSwitch = events.last().payload as TrackSwitchEvent
        assertEquals(14L, subsequentSwitch.newEpoch)
        assertFalse(subsequentSwitch.isSeekFlush)
    }

    @Test
    fun orphanCoverArrivingBeforeDmapIsInheritedByNewTrackWithoutDisappearing() {
        var mockClock = 1000L
        val events = mutableListOf<SessionEventEnvelope<SessionEventPayload>>()
        val globalSessionGen = AtomicLong(100L)
        val seqGen = AtomicLong(0L)
        val dispatcher = AirPlayNativeEventDispatcher(
            dispatchEvent = { events += it },
            allocateSessionId = { globalSessionGen.incrementAndGet() },
            allocateSequence = { seqGen.incrementAndGet() },
            onAcquireHardwareForStream = { _, _, _, _ -> 11L },
            onDecryptAndInjectCryptoKey = { _, _ -> },
            onUpdateDacpTarget = { _, _, _, _ -> },
            onSolveChallenge = { "mock-resp" },
            clockMs = { mockClock }
        )

        // 1. 初始化建连，生成初始代数
        dispatcher.onStreamRequested(1L, 1L, 44100, 16, 2, "ALAC", "10.0.0.2", "", "", "", "", 0)
        val setupEvent = events.last().payload as StreamSetupEvent
        val initialGen = setupEvent.trackGeneration ?: 1L

        // 2. 模拟 iOS CSeq 11: 孤立封面先于文本报文到达 (image/jpeg 报文无 title/artist)
        val testCover = byteArrayOf(11, 22, 33, 44)
        mockClock = 1010L
        dispatcher.onMetadataReceived(1L, 2L, "", "", "", 0L, testCover)

        val patchOrphan = events.last().payload as MetadataPatchEvent
        assertEquals(initialGen, patchOrphan.trackGeneration)
        assertArrayEquals(testCover, patchOrphan.coverBytes)

        // 3. 模拟 25ms 后 iOS CSeq 13: DMAP 文本元数据到达，无封面
        mockClock = 1035L
        dispatcher.onMetadataReceived(1L, 3L, "离别的车站", "赵薇", "z", 357_253L, null)

        val boundary = events[events.size - 2].payload as TrackBoundaryEvent
        val patchTrack = events.last().payload as MetadataPatchEvent

        // 验证：新一代曲目必须完整继承前序 25ms 到达的孤立封面，绝不能为 null
        assertTrue(boundary.trackGeneration > initialGen)
        assertEquals(boundary.trackGeneration, patchTrack.trackGeneration)
        assertEquals("离别的车站", patchTrack.title)
        assertEquals("赵薇", patchTrack.artist)
        assertArrayEquals(testCover, patchTrack.coverBytes)

        // 4. 验证后续切歌到真正无封面歌曲时，孤立封面已在消费后被清空，新歌不会误继承
        mockClock = 5000L
        dispatcher.onMetadataReceived(1L, 4L, "无封面新歌", "佚名", "", 120_000L, null)
        val patchNoCover = events.last().payload as MetadataPatchEvent
        assertEquals("无封面新歌", patchNoCover.title)
        assertNull("孤立封面消费后必须清空，后续无封面歌曲严禁误继承旧封面", patchNoCover.coverBytes)
    }
}
