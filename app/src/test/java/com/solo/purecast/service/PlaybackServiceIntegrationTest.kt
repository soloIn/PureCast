package com.solo.purecast.service

import android.media.session.PlaybackState
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.HardwareAttachedEvent
import com.solo.purecast.core.session.HardwareDetachedEvent
import com.solo.purecast.core.session.HardwareOpenSuccess
import com.solo.purecast.core.session.MetadataPatchEvent
import com.solo.purecast.core.session.PlaybackSessionCoordinator
import com.solo.purecast.core.session.ProtocolResumeReopenResult
import com.solo.purecast.core.session.RemoteCommandType
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionHardwareBridge
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamSetupEvent
import com.solo.purecast.core.session.StreamStopEvent
import com.solo.purecast.core.session.StreamTeardownEvent
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 💡 Unit U5 集成测试：验证 [PlaybackServiceSessionHost] 对 Coordinator 快照订阅、
 * `SeekCapability -> ACTION_SEEK_TO` 映射、锁与焦点管理、跨协议抢占及快速遥控串口行为
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceIntegrationTest {

    private class FakeHardwareBridge : SessionHardwareBridge {
        var nextEpoch = 10L
        var openShouldFail = false
        var releaseCalls = 0
        var resumeCalls = 0
        val muteCalls = CopyOnWriteArrayList<FlushReason>()

        var lastForceNewEpoch = false
        var eventLog: MutableList<String>? = null

        override fun openDeviceWithCurrentFd(
            sampleRate: Int,
            bitDepth: Int,
            channels: Int,
            forceNewEpoch: Boolean
        ): Result<HardwareOpenSuccess> {
            eventLog?.add("openDevice(forceNewEpoch=$forceNewEpoch)")
            lastForceNewEpoch = forceNewEpoch
            if (openShouldFail) return Result.failure(IllegalStateException("usb open fail"))
            return Result.success(HardwareOpenSuccess(++nextEpoch, "Sony NW-ZX300A"))
        }

        override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long = ++nextEpoch

        override fun releaseDevice() {
            releaseCalls++
        }

        override fun emergencyMuteAndFlush(reason: FlushReason): Long {
            muteCalls += reason
            if (reason != FlushReason.EXPLICIT_PAUSE) nextEpoch++
            return nextEpoch
        }

        override fun resumeNativeStream(): Int {
            resumeCalls++
            return 0
        }

        override fun requestUsbPermission(deviceName: String) = Unit
    }

    private class FakeSystemEffects : ServiceSystemEffects {
        var lastActions: Long = 0L
        var lastStatus: PlaybackStatus? = null
        var lastMetadata: TrackMetadata? = null
        val notifiedSnapshots = CopyOnWriteArrayList<PlayerSessionSnapshot>()
        var wifiLockHeld = false
        var audioFocusGranted = true
        var audioFocusHeld = false
        var wakeUpCount = 0
        val upnpStates = CopyOnWriteArrayList<String>()
        val preempted = CopyOnWriteArrayList<Pair<StreamProtocol, Long>>()
        val soughtPositions = CopyOnWriteArrayList<Pair<StreamProtocol, Long>>()
        val dacpSent = CopyOnWriteArrayList<String>()
        var reopenResult = ProtocolResumeReopenResult.STREAMING_READY
        var eventLog: MutableList<String>? = null

        override fun syncMediaSessionState(status: PlaybackStatus, positionMs: Long, seekCapability: SeekCapability) {
            lastStatus = status
            lastActions = computeMediaSessionActions(seekCapability)
        }

        override fun syncMediaSessionMetadata(metadata: TrackMetadata) {
            lastMetadata = metadata
        }

        override fun syncNotification(snapshot: PlayerSessionSnapshot) {
            notifiedSnapshots += snapshot
        }

        override fun acquireWifiLowLatencyLock() {
            wifiLockHeld = true
        }

        override fun releaseWifiLowLatencyLock() {
            wifiLockHeld = false
        }

        override fun requestAudioFocus(): Boolean {
            audioFocusHeld = audioFocusGranted
            return audioFocusGranted
        }

        override fun abandonAudioFocus() {
            audioFocusHeld = false
        }

        override fun wakeUpUiIfInBackground() {
            wakeUpCount++
        }

        override fun notifyUpnpTransportState(state: String) {
            upnpStates += state
        }

        override fun preemptProtocol(protocol: StreamProtocol, sessionId: Long) {
            eventLog?.add("preempt($protocol,$sessionId)")
            preempted += protocol to sessionId
        }

        override fun seekProtocolStream(protocol: StreamProtocol, positionMs: Long) {
            eventLog?.add("seek($protocol,$positionMs)")
            soughtPositions += protocol to positionMs
        }

        override fun resumeProtocolAfterDeviceReopen(
            protocol: StreamProtocol,
            sessionId: Long,
            newEpoch: Long
        ): ProtocolResumeReopenResult {
            eventLog?.add("reopenResume($protocol,$sessionId,$newEpoch)")
            return reopenResult
        }
    }

    private fun TestScope.createHost(
        hw: FakeHardwareBridge = FakeHardwareBridge(),
        fx: FakeSystemEffects = FakeSystemEffects(),
        dacpSender: suspend (RemoteCommandType, Long) -> Result<Unit> = { cmd, _ ->
            fx.dacpSent += cmd.name
            Result.success(Unit)
        }
    ): Pair<PlaybackServiceSessionHost, PlaybackSessionCoordinator> {
        var preemptHook: (StreamProtocol, Long) -> Unit = { _, _ -> }
        val coordinator = PlaybackSessionCoordinator(
            scope = this,
            hardwareBridge = hw,
            dacpCommandSender = dacpSender,
            onSessionTerminated = { proto, sid -> preemptHook(proto, sid) },
            onProtocolResumeAfterReopen = { proto, sid, ep -> fx.resumeProtocolAfterDeviceReopen(proto, sid, ep) },
            clockMs = { testScheduler.currentTime }
        )
        val host = PlaybackServiceSessionHost(
            scope = this,
            coordinator = coordinator,
            hardwareBridge = hw,
            systemEffects = fx,
            clockMs = { testScheduler.currentTime }
        )
        preemptHook = { proto, sid -> fx.preemptProtocol(proto, sid) }
        host.startObserving()
        return host to coordinator
    }

    @Test
    fun snapshotDrivesMediaSessionSeekCapabilityAndLockLifecycle() = runTest {
        val hw = FakeHardwareBridge()
        val fx = FakeSystemEffects()
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (host, coord) = testScope.createHost(hw, fx)

        // 1. 建流但初始 UNKNOWN 时长 -> 不含 ACTION_SEEK_TO
        val ep = host.acquireHardwareForStream(96000, 24, 2)
        assertTrue(ep > 0L)
        assertEquals(1, fx.wakeUpCount)
        assertTrue(fx.audioFocusHeld)

        val sid = coord.allocateSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(
                    protocol = StreamProtocol.UPNP_DLNA,
                    sampleRate = 96000,
                    bitDepth = 24,
                    channels = 2,
                    codec = "FLAC",
                    initialEpoch = ep,
                    trackGeneration = 1L,
                    initialMetadata = TrackMetadata(
                        title = "Track 1",
                        durationState = DurationState.UNKNOWN,
                        seekCapability = SeekCapability.UNKNOWN
                    )
                )
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, ep, 96000, 24, 2, "FLAC")
            )
        )
        testScheduler.runCurrent()

        assertEquals(PlaybackStatus.PLAYING, fx.lastStatus)
        assertTrue(fx.wifiLockHeld)
        assertEquals(0L, fx.lastActions and PlaybackState.ACTION_SEEK_TO)

        // 2. 元数据补全解析出 FIXED + SEEKABLE -> ACTION_SEEK_TO 开启
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 3L,
                payload = MetadataPatchEvent(
                    trackGeneration = 1L,
                    durationMs = 180_000L,
                    durationState = DurationState.FIXED,
                    seekCapability = SeekCapability.SEEKABLE
                )
            )
        )
        testScheduler.runCurrent()
        assertTrue((fx.lastActions and PlaybackState.ACTION_SEEK_TO) != 0L)
        assertEquals("Track 1", fx.lastMetadata?.title)

        // 3. 暂停 -> 释放 Wi-Fi 低延迟锁，保留音频焦点便于恢复
        host.pausePlayback()
        testScheduler.advanceTimeBy(250L)
        testScheduler.runCurrent()
        assertEquals(PlaybackStatus.PAUSED, fx.lastStatus)
        assertFalse(fx.wifiLockHeld)
        assertTrue(fx.audioFocusHeld)

        // 4. 停止 -> 立即断开会话归 IDLE，放弃音频焦点并在 10s 后独立归还硬件
        host.stopPlayback()
        testScheduler.runCurrent()
        assertEquals(PlaybackStatus.IDLE, fx.lastStatus)
        assertFalse(fx.wifiLockHeld)
        assertFalse(fx.audioFocusHeld)
        assertEquals(0, hw.releaseCalls)

        testScheduler.advanceTimeBy(10_500L)
        testScheduler.runCurrent()
        assertEquals(PlaybackStatus.IDLE, fx.lastStatus)
        assertEquals(1, hw.releaseCalls)
        host.stop()
    }

    @Test
    fun crossProtocolPreemptionAndTenMinuteSuspendTimeoutTerminateStaleSession() = runTest {
        val hw = FakeHardwareBridge()
        val fx = FakeSystemEffects()
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (host, coord) = testScope.createHost(hw, fx)

        // 1. UPnP 会话活跃
        val upnpSid = coord.allocateSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = upnpSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.UPNP_DLNA, 44100, 16, 2, "WAV", 10L)
            )
        )
        testScheduler.runCurrent()

        // 2. AirPlay 抢占 -> 终止旧 UPnP 会话
        val airSid = coord.allocateSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = airSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 11L)
            )
        )
        testScheduler.runCurrent()
        assertTrue(fx.preempted.contains(StreamProtocol.UPNP_DLNA to upnpSid))

        // 3. AirPlay 暂停后 TEARDOWN 软挂起 -> 10 分钟后触发彻底清理释放硬件并终止 AirPlay DACP
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USER_ACTION,
                sessionId = airSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 3L,
                payload = com.solo.purecast.core.session.ExplicitPauseEvent(epoch = 11L)
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = airSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 4L,
                payload = StreamTeardownEvent(hardStop = false)
            )
        )
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PAUSED, coord.snapshot.value.businessState)

        testScheduler.advanceTimeBy(10 * 60 * 1000L + 100L)
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.IDLE, coord.snapshot.value.businessState)
        assertTrue(fx.preempted.contains(StreamProtocol.AIRPLAY_1 to airSid))
        assertTrue(hw.releaseCalls >= 1)
        host.stop()
    }

    @Test
    fun rapidNextCommandsSerializeOnAirPlayAndRejectOnUpnpWithErrorClearanceOnDetachAttach() = runTest {
        val hw = FakeHardwareBridge()
        val fx = FakeSystemEffects()
        val releaseFirst = CompletableDeferred<Unit>()
        val orderLog = CopyOnWriteArrayList<String>()
        var cmdNum = 0
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        val (host, coord) = testScope.createHost(
            hw = hw,
            fx = fx,
            dacpSender = { cmd, _ ->
                val idx = ++cmdNum
                orderLog += "start:$idx:${cmd.name}"
                if (idx == 1) {
                    releaseFirst.await()
                }
                orderLog += "done:$idx:${cmd.name}"
                Result.success(Unit)
            }
        )

        val airSid = coord.allocateSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = airSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 2L)
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = airSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 2L, 44100, 16, 2, "ALAC")
            )
        )
        testScheduler.runCurrent()

        // 快速连按 3 次下一曲：第 1 次未完成前，第 2/3 次绝不并发发送
        host.handleNextTrack()
        host.handleNextTrack()
        host.handleNextTrack()
        testScheduler.runCurrent()
        assertEquals(listOf("start:1:NEXT"), orderLog.toList())
        assertEquals(BusinessSessionState.TRANSITIONING, coord.snapshot.value.businessState)

        releaseFirst.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(
            listOf("start:1:NEXT", "done:1:NEXT", "start:2:NEXT", "done:2:NEXT", "start:3:NEXT", "done:3:NEXT"),
            orderLog.toList()
        )

        // 5000ms DACP 未收到远端响应回退至 PLAYING 并注入超时错
        testScheduler.advanceTimeBy(5100L)
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PLAYING, coord.snapshot.value.businessState)
        assertTrue(coord.snapshot.value.errors.containsKey(ErrorCode.REMOTE_COMMAND_TIMEOUT))

        // 切换至 UPnP 后按下一曲 -> 注入 REMOTE_COMMAND_UNSUPPORTED，不发 DACP
        val upnpSid = coord.allocateSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = upnpSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 6000L,
                payload = StreamSetupEvent(StreamProtocol.UPNP_DLNA, 44100, 16, 2, "WAV", 3L)
            )
        )
        testScheduler.runCurrent()
        host.handleNextTrack()
        testScheduler.runCurrent()
        assertTrue(coord.snapshot.value.errors.containsKey(ErrorCode.REMOTE_COMMAND_UNSUPPORTED))
        assertEquals(6, orderLog.size)

        // 设备拔出 inject USB_DEVICE_DETACHED，再带权限插回定向清除
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USB_HARDWARE,
                sessionId = 0L,
                deviceGeneration = 2L,
                sequence = 10L,
                timestampElapsedMs = 7000L,
                payload = HardwareDetachedEvent()
            )
        )
        testScheduler.runCurrent()
        assertTrue(coord.snapshot.value.errors.containsKey(ErrorCode.USB_DEVICE_DETACHED))

        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USB_HARDWARE,
                sessionId = 0L,
                deviceGeneration = 3L,
                sequence = 11L,
                timestampElapsedMs = 8000L,
                payload = HardwareAttachedEvent("Sony ZX300A", hasPermission = true)
            )
        )
        testScheduler.runCurrent()
        assertFalse(coord.snapshot.value.errors.containsKey(ErrorCode.USB_DEVICE_DETACHED))
        assertEquals(HardwarePipelineState.RELEASED, coord.snapshot.value.hardwareState)

        host.stop()
    }

    @Test
    fun `synchronous cross-protocol preemption precedes forceNewEpoch hardware open and resumePlayback guards IDLE STOPPED`() = runTest {
        val log = mutableListOf<String>()
        val hw = FakeHardwareBridge().apply { eventLog = log }
        val fx = FakeSystemEffects().apply { eventLog = log }
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (host, coord) = testScope.createHost(hw, fx)

        // 1. IDLE 下触发 resumePlayback() 必须安全阻断，不重开设备或不发送 DACP (#23)
        host.resumePlayback()
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.IDLE, coord.snapshot.value.businessState)
        assertTrue(fx.dacpSent.isEmpty())

        // 2. 启动 AirPlay 会话 -> PLAYING
        val airEpoch = host.acquireHardwareForStream(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC")
        val airSid = host.nextSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = airSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", airEpoch)
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = airSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, airEpoch, 44100, 16, 2, "ALAC")
            )
        )
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PLAYING, coord.snapshot.value.businessState)
        log.clear()

        // 3. UPnP 抢占式请求硬件：必须同步先调 preemptProtocol(AIRPLAY_1, airSid)，再调 openDeviceWithCurrentFd(forceNewEpoch=true) (#1 #13)
        val upnpEpoch = host.acquireHardwareForStream(StreamProtocol.UPNP_DLNA, 44100, 16, 2, "WAV")
        assertTrue(upnpEpoch > airEpoch)
        assertTrue(hw.lastForceNewEpoch)
        assertEquals(
            listOf("preempt(AIRPLAY_1,$airSid)", "openDevice(forceNewEpoch=true)"),
            log.take(2)
        )

        // 4. 若 UPnP 停播至 STOPPED，调用 host.resumePlayback() 也必须安全阻断 (#23)
        val upnpSid = host.nextSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = upnpSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 3L,
                payload = StreamSetupEvent(StreamProtocol.UPNP_DLNA, 44100, 16, 2, "WAV", upnpEpoch)
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = upnpSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 4L,
                payload = FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, upnpEpoch, 44100, 16, 2, "WAV")
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = upnpSid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 5L,
                payload = StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = upnpEpoch + 1L)
            )
        )
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.IDLE, coord.snapshot.value.businessState)

        val resBefore = hw.resumeCalls
        host.resumePlayback()
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.IDLE, coord.snapshot.value.businessState)
        assertEquals(resBefore, hw.resumeCalls)

        host.stop()
    }

    @Test
    fun `resumeProtocolAfterDeviceReopen coordinates MediaSession across STREAMING_READY RESTARTING and PRODUCER_DEAD`() = runTest {
        val hw = FakeHardwareBridge()
        val fx = FakeSystemEffects()
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (host, coord) = testScope.createHost(hw, fx)

        val ep1 = host.acquireHardwareForStream(StreamProtocol.UPNP_DLNA, 96000, 24, 2, "FLAC")
        val sid = host.nextSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.UPNP_DLNA, 96000, 24, 2, "FLAC", ep1)
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, ep1, 96000, 24, 2, "FLAC")
            )
        )
        // USB 拔出 -> PAUSED + DISCONNECTED；插回 -> PAUSED + RELEASED
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USB_HARDWARE,
                sessionId = 0L,
                deviceGeneration = 1L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 3L,
                payload = HardwareDetachedEvent("pull")
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USB_HARDWARE,
                sessionId = 0L,
                deviceGeneration = 2L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 4L,
                payload = HardwareAttachedEvent("Sony ZX300A", hasPermission = true)
            )
        )
        testScheduler.runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, coord.snapshot.value.hardwareState)

        // 配置 STREAMING_READY 后从宿主恢复播放，MediaSession 必须进入 PLAYING (#2)
        fx.reopenResult = ProtocolResumeReopenResult.STREAMING_READY
        host.resumePlayback()
        testScheduler.runCurrent()
        assertEquals(PlaybackStatus.PLAYING, fx.lastStatus)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, coord.snapshot.value.hardwareState)

        // 再经历 USB 重插并用 PRODUCER_DEAD 恢复 -> 回落为 PAUSED，不进入 PLAYING (#2)
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USB_HARDWARE,
                sessionId = 0L,
                deviceGeneration = 3L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 5L,
                payload = HardwareDetachedEvent("pull2")
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.USB_HARDWARE,
                sessionId = 0L,
                deviceGeneration = 4L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 6L,
                payload = HardwareAttachedEvent("Sony ZX300A", hasPermission = true)
            )
        )
        testScheduler.runCurrent()

        fx.reopenResult = ProtocolResumeReopenResult.PRODUCER_DEAD
        host.resumePlayback()
        testScheduler.runCurrent()
        assertEquals(PlaybackStatus.PAUSED, fx.lastStatus)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, coord.snapshot.value.hardwareState)

        host.stop()
    }

    @Test
    fun `AirPlay resumePlayback ignores PLAYING state and routes PAUSED via tracked DACP PLAY`() = runTest {
        val hw = FakeHardwareBridge()
        val fx = FakeSystemEffects()
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val (host, coord) = testScope.createHost(hw, fx)

        val ep = host.acquireHardwareForStream(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC")
        val sid = host.nextSessionId()
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", ep)
            )
        )
        coord.dispatch(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coord.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, ep, 44100, 16, 2, "ALAC")
            )
        )
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PLAYING, coord.snapshot.value.businessState)

        // PLAYING 态误按播放键或音频焦点恢复 -> 直接忽略，不切 TRANSITIONING、不发 DACP
        fx.dacpSent.clear()
        host.resumePlayback()
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PLAYING, coord.snapshot.value.businessState)
        assertTrue(fx.dacpSent.isEmpty())

        // 暂停进入 PAUSED 后再调用 resumePlayback() -> 走受追踪 DACP PLAY 进入 TRANSITIONING
        host.pausePlayback()
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PAUSED, coord.snapshot.value.businessState)

        fx.dacpSent.clear()
        host.resumePlayback()
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, coord.snapshot.value.businessState)
        assertEquals(listOf("PLAY"), fx.dacpSent.toList())

        host.stop()
    }
}
