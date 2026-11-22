package com.solo.purecast.core.session

import com.solo.purecast.model.AudioPathState
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
import com.solo.purecast.model.TransportPipeState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💡 U2 核心纯逻辑状态机、快速控制器与事务元数据全分支单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionCoordinatorTest {

    private class FakeFastPathBridge : FastPathNativeBridge {
        var currentEpoch = 1L
        var paused = false
        var flushing = false
        var streaming = true
        val recordedFlushes = mutableListOf<FlushReason>()
        var reconfigureResultEpoch: Long? = null
        var resumeResult = 0
        var resumeCalls = 0

        override fun emergencyMuteAndFlush(reason: FlushReason): Long {
            recordedFlushes += reason
            return when (reason) {
                FlushReason.EXPLICIT_PAUSE -> {
                    paused = true
                    currentEpoch
                }
                FlushReason.FLUSH_SWITCH -> {
                    flushing = true
                    ++currentEpoch
                }
                FlushReason.STOP_OR_TEARDOWN, FlushReason.PREEMPT -> {
                    paused = true
                    ++currentEpoch
                }
            }
        }

        override fun getCurrentBufferEpoch(): Long = currentEpoch

        override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long {
            return reconfigureResultEpoch ?: ++currentEpoch
        }

        override fun resumeStream(): Int {
            resumeCalls++
            paused = false
            return resumeResult
        }

        override fun isStreaming(): Boolean = streaming && !paused && !flushing
    }

    private class FakeSessionHardwareBridge(
        val fastPath: FakeFastPathBridge = FakeFastPathBridge()
    ) : SessionHardwareBridge {
        var openDeviceCalls = 0
        var lastOpenSpec: Triple<Int, Int, Int>? = null
        var openDeviceShouldFail = false
        var throwOnOpenAttempt: RuntimeException? = null
        var openEpochToReturn = 10L
        var openDacName = "Sony ZX300A"
        var releaseDeviceCalls = 0
        val permissionRequests = mutableListOf<String>()

        override fun openDeviceWithCurrentFd(
            sampleRate: Int,
            bitDepth: Int,
            channels: Int,
            forceNewEpoch: Boolean
        ): Result<HardwareOpenSuccess> {
            throwOnOpenAttempt?.let { ex ->
                throwOnOpenAttempt = null
                throw ex
            }
            openDeviceCalls++
            lastOpenSpec = Triple(sampleRate, bitDepth, channels)
            return if (openDeviceShouldFail) {
                Result.failure(IllegalStateException("usb open failed"))
            } else {
                fastPath.currentEpoch = openEpochToReturn
                Result.success(HardwareOpenSuccess(epoch = openEpochToReturn, dacName = openDacName))
            }
        }

        override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long {
            return fastPath.reconfigureAudioFormat(sampleRate, bitDepth, channels)
        }

        override fun releaseDevice() {
            releaseDeviceCalls++
        }

        override fun emergencyMuteAndFlush(reason: FlushReason): Long {
            return fastPath.emergencyMuteAndFlush(reason)
        }

        override fun resumeNativeStream(): Int {
            return fastPath.resumeStream()
        }

        override fun requestUsbPermission(deviceName: String) {
            permissionRequests += deviceName
        }
    }

    private fun createCoordinator(
        testScope: TestScope,
        hwBridge: FakeSessionHardwareBridge = FakeSessionHardwareBridge(),
        dacpSender: suspend (RemoteCommandType, Long) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        onPreempted: (StreamProtocol, Long) -> Unit = { _, _ -> },
        onProtocolResumeAfterReopen: (StreamProtocol, Long, Long) -> ProtocolResumeReopenResult =
            { _, _, _ -> ProtocolResumeReopenResult.STREAMING_READY }
    ): PlaybackSessionCoordinator {
        return PlaybackSessionCoordinator(
            scope = testScope.backgroundScope,
            hardwareBridge = hwBridge,
            dacpCommandSender = dacpSender,
            onSessionTerminated = onPreempted,
            onProtocolResumeAfterReopen = onProtocolResumeAfterReopen,
            clockMs = { testScope.testScheduler.currentTime }
        )
    }

    private fun env(
        coordinator: PlaybackSessionCoordinator,
        origin: EventOrigin,
        sessionId: Long,
        payload: SessionEventPayload,
        deviceGen: Long = 1L,
        seq: Long = coordinator.allocateSequence(),
        ts: Long = 100L
    ): SessionEventEnvelope<SessionEventPayload> = SessionEventEnvelope(
        origin = origin,
        sessionId = sessionId,
        deviceGeneration = deviceGen,
        sequence = seq,
        timestampElapsedMs = ts,
        payload = payload
    )

    @Test
    fun `HardwareFastPathController delegates FlushReason directly without coordinator`() {
        val fakeBridge = FakeFastPathBridge()
        val controller = HardwareFastPathController(fakeBridge)

        val ep0 = controller.getCurrentBufferEpoch()
        assertEquals(1L, ep0)

        // EXPLICIT_PAUSE 保持 epoch 不递增
        val pauseEp = controller.emergencyMuteAndFlush(FlushReason.EXPLICIT_PAUSE)
        assertEquals(1L, pauseEp)
        assertFalse(controller.isStreaming())

        // FLUSH_SWITCH 与 PREEMPT 递增 epoch
        assertEquals(2L, controller.emergencyMuteAndFlush(FlushReason.FLUSH_SWITCH))
        assertEquals(3L, controller.emergencyMuteAndFlush(FlushReason.PREEMPT))
        assertEquals(4L, controller.reconfigureAudioFormat(96000, 24, 2))
        assertEquals(0, controller.resumeStream())
        assertEquals(
            listOf(FlushReason.EXPLICIT_PAUSE, FlushReason.FLUSH_SWITCH, FlushReason.PREEMPT),
            fakeBridge.recordedFlushes
        )
    }

    @Test
    fun `StreamSetup to FirstDataPacket transitions RELEASED device cleanly to PLAYING`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        // 先挂载有权限 USB DAC -> RELEASED
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony WALKMAN", hasPermission = true), deviceGen = 1L))
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)

        // 新流建立: RELEASED 必须先 openDevice -> EXCLUSIVE_MUTED + TRANSITIONING
        val s1 = c.allocateSessionId()
        c.dispatch(
            env(
                c, EventOrigin.UPNP, s1,
                StreamSetupEvent(
                    protocol = StreamProtocol.UPNP_DLNA,
                    sampleRate = 96000,
                    bitDepth = 24,
                    channels = 2,
                    codec = "FLAC",
                    initialEpoch = 10L
                )
            )
        )
        runCurrent()
        assertEquals(1, hw.openDeviceCalls)
        assertEquals(Triple(96000, 24, 2), hw.lastOpenSpec)
        assertEquals(TransportPipeState.PREPARING, c.snapshot.value.transportState)
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)
        assertEquals(PlaybackStatus.PLAYING, c.snapshot.value.playbackStatus)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)

        // 首帧到达 -> STREAMING + PLAYING + EXCLUSIVE_ACTIVE
        c.dispatch(
            env(
                c, EventOrigin.UPNP, s1,
                FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 96000, 24, 2, "FLAC")
            )
        )
        runCurrent()
        val snap = c.snapshot.value
        assertEquals(TransportPipeState.STREAMING, snap.transportState)
        assertEquals(BusinessSessionState.PLAYING, snap.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, snap.hardwareState)
        assertEquals(96000, snap.audioPath.sampleRate)
        assertEquals(24, snap.audioPath.bitDepth)
        assertTrue(snap.audioPath.isExclusive)
    }

    @Test
    fun `ExplicitPause 220ms debounce cancels when TrackSwitch and FirstDataPacket arrive within window`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1, codec = "ALAC")))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()

        // 发送端显式暂停 -> 硬件立切 EXCLUSIVE_MUTED，但业务态仍在防抖等待不跳 PAUSED
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, ExplicitPauseEvent(FlushReason.EXPLICIT_PAUSE, 10L)))
        runCurrent()
        assertEquals(TransportPipeState.INTERRUPTED, c.snapshot.value.transportState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 100ms 时到来 FLUSH 切歌，应维持 TRANSITIONING / PLAYING
        advanceTimeBy(100)
        runCurrent()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 11L)))
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)
        assertEquals(PlaybackStatus.PLAYING, c.snapshot.value.playbackStatus)

        // 超过 220ms 防抖点但因为被 FLUSH+FirstDataPacket 取消，仍稳居 PLAYING
        advanceTimeBy(150)
        runCurrent()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 11L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 真实单独 ExplicitPause 无后续切歌 -> 220ms 到期切为 PAUSED
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, ExplicitPauseEvent(FlushReason.EXPLICIT_PAUSE, 11L)))
        runCurrent()
        advanceTimeBy(219)
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(PlaybackStatus.PAUSED, c.snapshot.value.playbackStatus)
    }

    @Test
    fun `User ExplicitPauseEvent transitions to PAUSED in 0ms immediately`() = runTest(StandardTestDispatcher()) {
        val c = createCoordinator(this)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()

        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 10L)))
        runCurrent()
        assertEquals(TransportPipeState.INTERRUPTED, c.snapshot.value.transportState)
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
    }

    @Test
    fun `TrackComplete immediately disconnects session to IDLE while 10s HardwareIdleTimeout releases device independently`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamSetupEvent(StreamProtocol.UPNP_DLNA)))
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 44100, 16, 2, "WAV")))
        runCurrent()

        c.dispatch(env(c, EventOrigin.UPNP, s1, TrackCompleteEvent(newEpoch = 11L)))
        runCurrent()
        assertEquals(TransportPipeState.DISCONNECTED, c.snapshot.value.transportState)
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(0L, c.snapshot.value.sessionId)
        assertEquals(StreamProtocol.NONE, c.snapshot.value.activeProtocol)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(0, hw.releaseDeviceCalls)

        // 9.9 秒会话已归 IDLE，但 USB 声卡尚未释放
        advanceTimeBy(9_900)
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(0, hw.releaseDeviceCalls)

        // 10.0 秒触发 HardwareIdleTimeoutEvent -> RELEASED + releaseDevice()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertEquals(StreamProtocol.NONE, c.snapshot.value.activeProtocol)
        assertEquals(1, hw.releaseDeviceCalls)
    }

    @Test
    fun `10 minute SuspendTimeout releases device and clears session from PAUSED`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val terminated = mutableListOf<Pair<StreamProtocol, Long>>()
        val c = createCoordinator(this, hw, onPreempted = { p, sid -> terminated += p to sid })

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        // 先暂停进入 PAUSED，再收到 iOS 暂停 8s 后的 TEARDOWN -> 保持 PAUSED 挂起 10 分钟
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamTeardownEvent(hardStop = false)))
        runCurrent()

        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 推进 10 分钟 (600_000 ms)
        advanceTimeBy(600_000L)
        runCurrent()

        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertEquals(1, hw.releaseDeviceCalls)
        assertTrue(terminated.any { it.first == StreamProtocol.AIRPLAY_1 && it.second == s1 })
    }

    @Test
    fun `Three rapid RemoteSkipNext presses queue serially and 5000ms timeout recovers safely`() = runTest(StandardTestDispatcher()) {
        val sentOrder = mutableListOf<Pair<RemoteCommandType, Long>>()
        var gateDelayMs = 0L
        val c = createCoordinator(
            this,
            dacpSender = { cmd, id ->
                if (gateDelayMs > 0) delay(gateDelayMs)
                sentOrder += cmd to id
                Result.success(Unit)
            }
        )

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()

        // 在 500ms 内连按 3 次下一曲（每次网络耗时 100ms 验证严格串行排队而非合并丢弃）
        gateDelayMs = 100L
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, RemoteSkipNextEvent(RemoteCommandType.NEXT, 1L)))
        advanceTimeBy(50)
        runCurrent()
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, RemoteSkipNextEvent(RemoteCommandType.NEXT, 2L)))
        advanceTimeBy(50)
        runCurrent()
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, RemoteSkipNextEvent(RemoteCommandType.NEXT, 3L)))
        runCurrent()

        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)
        assertTrue(c.snapshot.value.uiProtectionActive)

        // 推进 350ms 使 3 个串行任务全部完成
        advanceTimeBy(350)
        runCurrent()
        assertEquals(
            listOf(
                RemoteCommandType.NEXT to 1L,
                RemoteCommandType.NEXT to 2L,
                RemoteCommandType.NEXT to 3L
            ),
            sentOrder
        )

        // 800ms UI 保护窗口到期取消 uiProtectionActive，但 TRANSITIONING 仍等待远端或 5000ms 超时
        advanceTimeBy(850)
        runCurrent()
        assertFalse(c.snapshot.value.uiProtectionActive)
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)

        // 发端（处于歌单末尾）始终未下发新流，到达 5000ms 超时安全回退至触发前 PLAYING 稳态
        advanceTimeBy(4_200)
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.REMOTE_COMMAND_TIMEOUT))
    }

    @Test
    fun `UPnP intercepts remote track skip cleanly with error banner without altering state`() = runTest(StandardTestDispatcher()) {
        val c = createCoordinator(this)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamSetupEvent(StreamProtocol.UPNP_DLNA)))
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 48000, 24, 2, "WAV")))
        runCurrent()

        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, RemoteSkipNextEvent(RemoteCommandType.NEXT, 99L)))
        runCurrent()

        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(TransportPipeState.STREAMING, c.snapshot.value.transportState)
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.REMOTE_COMMAND_UNSUPPORTED))
    }

    @Test
    fun `USB hotplug non-playing guard, permission RELEASED landing, and resume reopen new fd`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        // 1. IDLE 下拔插 USB 绝不误入 PAUSED
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = false), deviceGen = 1L))
        runCurrent()
        assertEquals(HardwarePipelineState.ATTACHED, c.snapshot.value.hardwareState)
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionRequestedEvent("ZX300A"), deviceGen = 1L))
        runCurrent()
        assertEquals(HardwarePipelineState.PERMISSION_PENDING, c.snapshot.value.hardwareState)
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionGrantedEvent("ZX300A", granted = true), deviceGen = 1L))
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertEquals(0, hw.openDeviceCalls) // 不提前空转 URB

        // 2. 启动播放后拔出 USB -> 强制切 PAUSED + DISCONNECTED + 错误注入
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamSetupEvent(StreamProtocol.UPNP_DLNA, 96000, 24, 2)))
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 96000, 24, 2, "FLAC")))
        runCurrent()
        assertEquals(1, hw.openDeviceCalls)
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("unplugged"), deviceGen = 2L))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.DISCONNECTED, c.snapshot.value.hardwareState)
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.USB_DEVICE_DETACHED))

        // 在 PAUSED 下再触发拔出事件也不改状态; 插回无权限停留在 PERMISSION_PENDING
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = false), deviceGen = 3L))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.ATTACHED, c.snapshot.value.hardwareState)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionRequestedEvent("ZX300A"), deviceGen = 3L))
        runCurrent()
        assertEquals(HardwarePipelineState.PERMISSION_PENDING, c.snapshot.value.hardwareState)

        // 未授权时尝试恢复播放必须被硬件就绪守卫阻断
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamResumeEvent(StreamProtocol.UPNP_DLNA, 10L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 授权通过 -> 统一停留在 RELEASED 且清除断开错, 绝不抢播
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionGrantedEvent("ZX300A", granted = true), deviceGen = 3L))
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertFalse(c.snapshot.value.errors.containsKey(ErrorCode.USB_DEVICE_DETACHED))
        assertEquals(1, hw.openDeviceCalls)

        // 3. 用户恢复播放 -> RELEASED 先对新 fd 执行 open_device 再进入 PLAYING + EXCLUSIVE_ACTIVE
        hw.openEpochToReturn = 25L
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamResumeEvent(StreamProtocol.UPNP_DLNA, 10L)))
        runCurrent()
        assertEquals(2, hw.openDeviceCalls)
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
        assertEquals(25L, c.snapshot.value.currentEpoch)
    }

    @Test
    fun `Stale sessionId, stale deviceGeneration, and out-of-order sequence envelopes are dropped`() = runTest(StandardTestDispatcher()) {
        val terminated = mutableListOf<Pair<StreamProtocol, Long>>()
        val c = createCoordinator(this, onPreempted = { p, sid -> terminated += p to sid })

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true), deviceGen = 2L))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1), deviceGen = 2L, seq = 10L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC"), deviceGen = 2L, seq = 11L))
        runCurrent()

        // 乱序旧序号事件必须丢弃
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN), deviceGen = 2L, seq = 9L))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 跨协议新会话 UPnP s2 抢占 AirPlay s1
        val s2 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, s2, StreamSetupEvent(StreamProtocol.UPNP_DLNA), deviceGen = 2L, seq = 12L))
        c.dispatch(env(c, EventOrigin.UPNP, s2, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 12L, 96000, 24, 2, "FLAC"), deviceGen = 2L, seq = 13L))
        runCurrent()
        assertEquals(StreamProtocol.UPNP_DLNA, c.snapshot.value.activeProtocol)
        assertTrue(terminated.contains(StreamProtocol.AIRPLAY_1 to s1))

        // 旧会话 s1 迟到 TEARDOWN / EOF 不得篡改 s2
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamTeardownEvent(hardStop = true), deviceGen = 2L, seq = 99L))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(StreamProtocol.UPNP_DLNA, c.snapshot.value.activeProtocol)

        // 旧硬件代数 deviceGen=1 迟到 HardwareDetachedEvent 必须丢弃
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("stale_detach"), deviceGen = 1L, seq = 100L))
        runCurrent()
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
    }

    @Test
    fun `TrackTransactionManager merges partial metadata within same generation and drops stale generation`() = runTest(StandardTestDispatcher()) {
        val tm = TrackTransactionManager()
        val initialCover = byteArrayOf(1, 2, 3, 4)

        val b1 = tm.onTrackBoundary(
            TrackBoundaryEvent(
                trackKey = "track-1",
                trackGeneration = 1L,
                initialDurationState = DurationState.UNKNOWN,
                seekCapability = SeekCapability.UNKNOWN,
                trackUri = "http://nas/1.flac"
            )
        )
        assertTrue(b1.accepted)
        tm.applyMetadataPatch(
            MetadataPatchEvent(
                trackGeneration = 1L,
                title = "Hotel California",
                coverBytes = initialCover,
                durationMs = 391_000L,
                durationState = DurationState.FIXED,
                seekCapability = SeekCapability.SEEKABLE
            )
        )

        // 进入第 2 代切歌边界：绝不能把现有 coverBytes 预清为 null
        val b2 = tm.onTrackBoundary(
            TrackBoundaryEvent(
                trackKey = "track-2",
                trackGeneration = 2L,
                initialDurationState = DurationState.UNKNOWN,
                seekCapability = SeekCapability.UNKNOWN
            )
        )
        assertTrue(b2.accepted)
        assertArrayEquals(initialCover, tm.currentMetadata.coverBytes!!)

        // Packet 1 仅标题
        val m1 = tm.applyMetadataPatch(MetadataPatchEvent(trackGeneration = 2L, title = "Desperado"))!!
        assertEquals("Desperado", m1.title)
        assertEquals(2L, tm.currentTrackGeneration)

        // Packet 2 仅歌手与时长状态 -> 同代数合并、title 保留
        val m2 = tm.applyMetadataPatch(
            MetadataPatchEvent(
                trackGeneration = 2L,
                artist = "Eagles",
                durationState = DurationState.LIVE,
                seekCapability = SeekCapability.UNSEEKABLE
            )
        )!!
        assertEquals("Desperado", m2.title)
        assertEquals("Eagles", m2.artist)
        assertEquals(DurationState.LIVE, m2.durationState)
        assertEquals(SeekCapability.UNSEEKABLE, m2.seekCapability)
        assertEquals(2L, tm.currentTrackGeneration)

        // 旧代数 trackGeneration=1 延迟补丁一律丢弃
        assertNull(tm.applyMetadataPatch(MetadataPatchEvent(trackGeneration = 1L, title = "Stale Title")))
        assertEquals("Desperado", tm.currentMetadata.title)
    }

    @Test
    fun `HardwareReconfigure failure and UsbPermissionDenied record structured errors`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = false), deviceGen = 1L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionGrantedEvent("ZX300A", granted = false), deviceGen = 1L))
        runCurrent()
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.USB_PERMISSION_DENIED))

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionGrantedEvent("ZX300A", granted = true), deviceGen = 1L))
        c.dispatch(
            env(
                c, EventOrigin.USB_HARDWARE, 0L,
                HardwareReconfigureFailedEvent(
                    errorCode = ErrorCode.USB_RECONFIGURE_FAILED,
                    message = "DAC clock rejected 384kHz",
                    fallbackState = HardwarePipelineState.RELEASED
                ),
                deviceGen = 1L
            )
        )
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.USB_RECONFIGURE_FAILED))

        c.dispatch(env(c, EventOrigin.SERVICE, 0L, ErrorClearedEvent(ErrorCode.USB_RECONFIGURE_FAILED)))
        runCurrent()
        assertFalse(c.snapshot.value.errors.containsKey(ErrorCode.USB_RECONFIGURE_FAILED))
    }

    @Test
    fun `Exhaustive branch coverage for metadata patches, progress, stops, detach, open errors, and DACP failures`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        var dacpFailureMode = 0 // 0=ok, 1=failure, 2=throw
        val c = createCoordinator(
            this,
            hwBridge = hw,
            dacpSender = { _, _ ->
                when (dacpFailureMode) {
                    1 -> Result.failure(RuntimeException("dacp rejected"))
                    2 -> throw IllegalStateException("socket broken")
                    else -> Result.success(Unit)
                }
            }
        )

        // 1. 测试未连接 USB 下的各种事件与 TrackTransactionManager 全分支
        val tmStandalone = TrackTransactionManager()
        val presetMeta = TrackMetadata(title = "Preset", artist = "A", album = "B", trackUri = "uri://preset")
        tmStandalone.resetForNewStream(1L, presetMeta)
        assertFalse(tmStandalone.onTrackBoundary(TrackBoundaryEvent("k", 1L)).accepted) // 重复旧代被拒
        tmStandalone.onTrackBoundary(TrackBoundaryEvent("k2", 2L, DurationState.FIXED, SeekCapability.SEEKABLE))
        assertNull(tmStandalone.applyMetadataPatch(MetadataPatchEvent(trackGeneration = 1L, title = "old")))
        val patchedAll = tmStandalone.applyMetadataPatch(
            MetadataPatchEvent(
                trackGeneration = 2L,
                title = "T2",
                artist = "Art2",
                album = "Alb2",
                coverUri = "http://img",
                coverBytes = byteArrayOf(7, 8),
                durationMs = 123_000L,
                durationState = DurationState.FIXED,
                seekCapability = SeekCapability.SEEKABLE
            )
        )!!
        assertEquals("Alb2", patchedAll.album)
        assertEquals("http://img", patchedAll.coverUri)
        assertEquals(123_000L, patchedAll.durationMs)
        // 空字节封面补丁保留原封面
        val patchedEmptyCover = tmStandalone.applyMetadataPatch(
            MetadataPatchEvent(trackGeneration = 2L, coverBytes = byteArrayOf())
        )!!
        assertArrayEquals(byteArrayOf(7, 8), patchedEmptyCover.coverBytes!!)

        // 2. 无 DAC (DISCONNECTED) 下流初始化、首帧、切歌、暂停、停播、空闲让渡与无效定时器过滤
        val s0 = c.allocateSessionId()
        // 非活跃会话拦截
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0 + 99, ExplicitPauseEvent(FlushReason.EXPLICIT_PAUSE, 1L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, StreamSetupEvent(StreamProtocol.AIRPLAY_1, initialMetadata = presetMeta, trackGeneration = 1L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 1L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(HardwarePipelineState.DISCONNECTED, c.snapshot.value.hardwareState)

        // TrackBoundary & MetadataPatch & ProgressUpdate 经协调器分发
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, TrackBoundaryEvent("tk2", 2L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, TrackBoundaryEvent("tk2", 2L))) // 重复被拒 branch
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, MetadataPatchEvent(trackGeneration = 1L, title = "ignored"))) // stale patch
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, MetadataPatchEvent(trackGeneration = 2L, title = "Song 2", durationMs = 200_000L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, ProgressUpdateEvent(positionMs = -50L, durationMs = 0L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, ProgressUpdateEvent(positionMs = 250_000L, durationMs = 200_000L)))
        runCurrent()
        assertEquals(200_000L, c.snapshot.value.progressPositionMs)

        // 在 IDLE/PLAYING 下测试 UPnP PREVIOUS & NONE 下 RemoteSkip
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, ExplicitPauseEvent(FlushReason.EXPLICIT_PAUSE, 1L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 2L)))
        c.dispatch(env(c, EventOrigin.USER_ACTION, s0, ExplicitPauseEvent(epoch = 2L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 3L, isPreempted = true)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, StreamTeardownEvent(hardStop = true)))
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)

        // STOPPED 下硬件离线且在 PLAYING 之外拔出不影响 PAUSED，10s 让渡保留 DISCONNECTED
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("offline"), deviceGen = 1L))
        advanceTimeBy(10_500)
        runCurrent()
        assertEquals(HardwarePipelineState.DISCONNECTED, c.snapshot.value.hardwareState)
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)

        // IDLE 下遥控指令无效果
        c.dispatch(env(c, EventOrigin.USER_ACTION, 0L, RemoteSkipNextEvent(RemoteCommandType.NEXT, 1L)))
        // 不匹配状态的超时事件安全忽略
        c.dispatch(env(c, EventOrigin.SERVICE, 0L, HardwareIdleTimeoutEvent))
        c.dispatch(env(c, EventOrigin.SERVICE, 0L, SuspendTimeoutEvent))
        c.dispatch(env(c, EventOrigin.SERVICE, 0L, DebouncePauseTimeoutEvent(9999L)))
        c.dispatch(env(c, EventOrigin.SERVICE, 0L, UiProtectionTimeoutEvent(9999L)))
        c.dispatch(env(c, EventOrigin.SERVICE, 0L, RemoteCommandTimeoutEvent(9999L)))
        // Stale deviceGen & stale hardware sequence
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("old", true), deviceGen = 0L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("dup", true), deviceGen = 1L, seq = 1L))
        runCurrent()

        // 3. 挂载 DAC 后测试 openDevice 失败分支 (StreamSetup & StreamResume)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true), deviceGen = 2L))
        runCurrent()
        hw.openDeviceShouldFail = true
        val sOpenFail = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, sOpenFail, StreamSetupEvent(StreamProtocol.UPNP_DLNA)))
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.USB_OPEN_FAILED))

        // RELEASED 下 Resume 时 openDevice 失败分支
        c.dispatch(env(c, EventOrigin.UPNP, sOpenFail, StreamResumeEvent(StreamProtocol.UPNP_DLNA, 10L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)

        // 修复 openDevice 成功并在 RELEASED 下 Resume 成功
        hw.openDeviceShouldFail = false
        c.dispatch(env(c, EventOrigin.UPNP, sOpenFail, StreamResumeEvent(StreamProtocol.UPNP_DLNA, 10L)))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)

        // UPnP 发起 PREVIOUS 拦截 vs PLAY_PAUSE 允许分支
        c.dispatch(env(c, EventOrigin.USER_ACTION, sOpenFail, RemoteSkipNextEvent(RemoteCommandType.PREVIOUS, 10L)))
        c.dispatch(env(c, EventOrigin.USER_ACTION, sOpenFail, RemoteSkipNextEvent(RemoteCommandType.PLAY_PAUSE, 11L)))
        runCurrent()
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.REMOTE_COMMAND_UNSUPPORTED))

        // HardwareReconfiguredEvent & HardwareStateSyncedEvent & ErrorReportedEvent
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareReconfiguredEvent(192000, 24, 2, 30L), deviceGen = 2L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareStateSyncedEvent(HardwarePipelineState.EXCLUSIVE_MUTED, dacName = null, bufferAvailablePercent = null), deviceGen = 2L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareStateSyncedEvent(HardwarePipelineState.EXCLUSIVE_ACTIVE, dacName = "ZX300A", bufferAvailablePercent = 85), deviceGen = 2L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareReconfiguredEvent(44100, 16, 2, 31L), deviceGen = 2L))
        c.dispatch(env(c, EventOrigin.SERVICE, sOpenFail, ErrorReportedEvent(com.solo.purecast.model.PlaybackError(ErrorCode.NETWORK_STREAM_ERROR, "drop"))))
        runCurrent()
        assertEquals(44100, c.snapshot.value.audioPath.sampleRate)
        assertEquals(85, c.snapshot.value.audioPath.bufferAvailablePercent)
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.NETWORK_STREAM_ERROR))

        // 在 EXCLUSIVE_MUTED 下正常 StreamResumeEvent 直接切 EXCLUSIVE_ACTIVE
        c.dispatch(env(c, EventOrigin.UPNP, sOpenFail, ExplicitPauseEvent(FlushReason.EXPLICIT_PAUSE, 31L)))
        advanceTimeBy(250)
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        c.dispatch(env(c, EventOrigin.UPNP, sOpenFail, StreamResumeEvent(StreamProtocol.UPNP_DLNA, 31L)))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 4. AirPlay PAUSED 态发 DACP 指令、失败回滚与异常日志、StreamStopEvent 非抢占
        val sAp = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, sAp, StreamSetupEvent(StreamProtocol.AIRPLAY_1), seq = c.allocateSequence()))
        // 验证旧会话 sAp 传错 seq <= lastAccepted 丢弃与旧 Setup sid < activeSid 丢弃
        c.dispatch(env(c, EventOrigin.AIRPLAY, sOpenFail, StreamSetupEvent(StreamProtocol.UPNP_DLNA), seq = c.allocateSequence()))
        c.dispatch(env(c, EventOrigin.USER_ACTION, sAp, ExplicitPauseEvent(epoch = 31L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 从 PAUSED 发 DACP 切歌触发 Result.failure 自动回退 PAUSED（不强制 ResumeNative）
        dacpFailureMode = 1
        c.dispatch(env(c, EventOrigin.USER_ACTION, sAp, RemoteSkipNextEvent(RemoteCommandType.NEXT, 77L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertTrue(c.snapshot.value.errors.containsKey(ErrorCode.REMOTE_COMMAND_TIMEOUT))

        // 再从 PAUSED 发一条触发异常 catch + 5000ms 超时兜底回退
        dacpFailureMode = 2
        c.dispatch(env(c, EventOrigin.USER_ACTION, sAp, RemoteSkipNextEvent(RemoteCommandType.PREVIOUS, 78L)))
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 非活跃会话的 USER_ACTION 控制事件丢弃
        c.dispatch(env(c, EventOrigin.USER_ACTION, sOpenFail, RemoteSkipNextEvent(RemoteCommandType.NEXT, 79L)))
        // 非抢占 StreamStopEvent -> 10s idle release -> 触发 HardwareIdleTimeoutEvent 恢复 IDLE
        c.dispatch(env(c, EventOrigin.AIRPLAY, sAp, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 0L, isPreempted = false)))
        // 也覆盖 ProgressUpdate 零 duration 分支（当 progressDurationMs == 0 且 ev.durationMs == 0）
        advanceTimeBy(10_500)
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)

        // 覆盖 TRANSITIONING 下 USB 拔出转 PAUSED 与 HardwareReconfigureFailed fallback EXCLUSIVE_MUTED
        val sTrans = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, sTrans, StreamSetupEvent(StreamProtocol.UPNP_DLNA)))
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareReconfigureFailedEvent(ErrorCode.USB_RECONFIGURE_FAILED, "err", HardwarePipelineState.EXCLUSIVE_MUTED), deviceGen = 3L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("pull_during_transition"), deviceGen = 4L))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.DISCONNECTED, c.snapshot.value.hardwareState)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("pull_again_when_paused"), deviceGen = 5L))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 5. 收尾精准覆盖剩余分支 (re-setup 同 sessionId, durationMs=0 补丁/进度, RELEASED 下 reconfig, PERMISSION_PENDING 阻断等)
        val noUriMeta = TrackMetadata(title = "NoUriOnly", durationMs = 0L)
        tmStandalone.resetForNewStream(3L, noUriMeta)
        assertEquals("stream-3", tmStandalone.currentTrackKey)

        // ATTACHED 与 PERMISSION_PENDING 下触发 StreamResumeEvent 阻断 + FirstDataPacketEvent 非独占分支
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = false), deviceGen = 6L))
        val sRe = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, StreamSetupEvent(StreamProtocol.AIRPLAY_1), deviceGen = 6L))
        // 同 sessionId 重进 StreamSetupEvent (isPreempting = false)
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, StreamSetupEvent(StreamProtocol.AIRPLAY_1), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, ProgressUpdateEvent(positionMs = 1500L, durationMs = 0L), deviceGen = 6L))
        assertEquals(0L, c.snapshot.value.progressDurationMs)
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, MetadataPatchEvent(trackGeneration = tmStandalone.currentTrackGeneration + 99), deviceGen = 6L))
        // ATTACHED 时 resume 阻断
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, StreamResumeEvent(StreamProtocol.AIRPLAY_1, 1L), deviceGen = 6L))
        runCurrent()
        assertEquals(HardwarePipelineState.ATTACHED, c.snapshot.value.hardwareState)
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 授权到 PERMISSION_PENDING 再 resume 阻断
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionRequestedEvent("DAC"), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, StreamResumeEvent(StreamProtocol.AIRPLAY_1, 1L), deviceGen = 6L))
        // 发出 300L 后测试：在 TRANSITIONING 下收到不匹配 id 的 RemoteCommandFailedEvent(999L) (branch: TRANSITIONING && id != active)
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 1L, 44100, 16, 2, "ALAC"), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.USER_ACTION, sRe, RemoteSkipNextEvent(RemoteCommandType.NEXT, 300L), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, RemoteCommandFailedEvent(999L, ErrorCode.REMOTE_COMMAND_TIMEOUT, "wrong id fail"), deviceGen = 6L))
        // 在 activeRemoteCommandId == 300L 时经用户显式暂停离开 TRANSITIONING，再收到匹配 id=300L 超时事件 (branch: id == active && !TRANSITIONING)
        c.dispatch(env(c, EventOrigin.USER_ACTION, sRe, ExplicitPauseEvent(epoch = 1L), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.SERVICE, sRe, RemoteCommandTimeoutEvent(300L), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 1L, 44100, 16, 2, "ALAC"), deviceGen = 6L))
        // 重新触发 TRANSITIONING 并在 PERMISSION_PENDING(非独占)下触发 RemoteCommandTimeout(301L)
        c.dispatch(env(c, EventOrigin.USER_ACTION, sRe, RemoteSkipNextEvent(RemoteCommandType.NEXT, 301L), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.SERVICE, sRe, RemoteCommandTimeoutEvent(301L), deviceGen = 6L))
        // 测试 RemoteCommandFailedEvent 不在 TRANSITIONING 下的分支
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, RemoteCommandFailedEvent(999L, ErrorCode.REMOTE_COMMAND_TIMEOUT, "stale fail"), deviceGen = 6L))
        // 测试 RELEASED 下接收 HardwareReconfiguredEvent / ReconfigureFailed(EXCLUSIVE_ACTIVE) / StateSynced(RELEASED) / MetadataPatch(durationMs=0)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, UsbPermissionGrantedEvent("DAC", granted = true), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareReconfiguredEvent(48000, 24, 2, 50L), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareReconfigureFailedEvent(ErrorCode.USB_RECONFIGURE_FAILED, "x", HardwarePipelineState.EXCLUSIVE_ACTIVE), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareStateSyncedEvent(HardwarePipelineState.RELEASED), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, TrackBoundaryEvent("no-dur", 500L), deviceGen = 6L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, MetadataPatchEvent(trackGeneration = 500L, title = "ZeroDur", durationMs = 0L), deviceGen = 6L))
        runCurrent()
        assertEquals("ZeroDur", c.snapshot.value.metadata.title)
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
    }

    @Test
    fun `custom idleTimeoutSeconds and sendDacpFireAndForget honor configured behavior`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val sentCommands = mutableListOf<Pair<RemoteCommandType, Long>>()
        val c = createCoordinator(
            this,
            hwBridge = hw,
            dacpSender = { cmd, id ->
                sentCommands += cmd to id
                Result.failure(IllegalStateException("dacp transient error"))
            }
        )

        // 1. 验证 sendDacpFireAndForget 触发 dacpSender(id=0L) 且失败时不发射 RemoteCommandFailedEvent / 不卡在 TRANSITIONING
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()

        c.sendDacpFireAndForget(RemoteCommandType.PAUSE)
        runCurrent()
        assertEquals(listOf(RemoteCommandType.PAUSE to 0L), sentCommands)
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 2. 验证 setIdleTimeoutSeconds(5) 按 5s 释放独占；setIdleTimeoutSeconds(0) 永久保持独占不释放
        c.setIdleTimeoutSeconds(5)
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 11L)))
        runCurrent()
        advanceTimeBy(4_900)
        runCurrent()
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(0, hw.releaseDeviceCalls)

        advanceTimeBy(200)
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)
        assertEquals(1, hw.releaseDeviceCalls)

        // 配置 0s (永久独占) -> 停播后会话立即归 IDLE，同时声卡 30s 后仍保持 EXCLUSIVE_MUTED 不释放
        c.setIdleTimeoutSeconds(0)
        val s2 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 11L)))
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(0L, c.snapshot.value.sessionId)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(1, hw.releaseDeviceCalls)
    }

    @Test
    fun `TriStateTransitionMatrix ignores StreamResumeEvent in IDLE and STOPPED states`() = runTest(StandardTestDispatcher()) {
        // 1. 直接验证归约器在 IDLE 下收到 StreamResumeEvent 直接返回且不产生副作用 (#23)
        val idleCtx = TransitionContext()
        val idleResumeEnv = SessionEventEnvelope<SessionEventPayload>(
            origin = EventOrigin.UPNP,
            sessionId = 1L,
            deviceGeneration = 1L,
            sequence = 1L,
            timestampElapsedMs = 100L,
            payload = StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 2L)
        )
        val idleOut = TriStateTransitionMatrix.reduce(idleCtx, idleResumeEnv, TrackTransactionManager(), 100L)
        assertEquals(idleCtx, idleOut.nextContext)
        assertTrue(idleOut.effects.isEmpty())

        // 2. 验证协调器在停止归 IDLE 状态下收到同会话迟到 StreamResumeEvent 时仍保持 IDLE (#23)
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hwBridge = hw)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true)))
        val sid = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, sid, StreamSetupEvent(StreamProtocol.UPNP_DLNA)))
        c.dispatch(env(c, EventOrigin.UPNP, sid, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 48000, 24, 2, "WAV")))
        c.dispatch(env(c, EventOrigin.UPNP, sid, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 11L)))
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)

        // 直接在 IDLE 状态下投递迟到 StreamResumeEvent，确认绝不重开 DAC、恢复推流或变为 PLAYING
        val opensBefore = hw.openDeviceCalls
        val resumesBefore = hw.fastPath.resumeCalls
        c.dispatch(env(c, EventOrigin.UPNP, sid, StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 12L)))
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(opensBefore, hw.openDeviceCalls)
        assertEquals(resumesBefore, hw.fastPath.resumeCalls)
    }

    @Test
    fun `RemoteCommandFailedEvent rolls back active TRANSITIONING state and clears transient UI state`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hwBridge = hw)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true)))
        val sid = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, sid, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sid, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)

        // 切歌指令先进入 TRANSITIONING + EXCLUSIVE_MUTED，再由发送端异步回报 RemoteCommandFailedEvent (#22)
        val cmdId = c.allocateCommandId()
        c.dispatch(env(c, EventOrigin.USER_ACTION, sid, RemoteSkipNextEvent(RemoteCommandType.NEXT, cmdId)))
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertTrue(c.snapshot.value.uiProtectionActive)

        val resumesBefore = hw.fastPath.resumeCalls
        c.dispatch(env(c, EventOrigin.AIRPLAY, sid, RemoteCommandFailedEvent(cmdId, ErrorCode.REMOTE_COMMAND_UNSUPPORTED, "http 404")))
        runCurrent()

        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
        assertFalse(c.snapshot.value.uiProtectionActive)
        assertEquals(resumesBefore + 1, hw.fastPath.resumeCalls)
        assertEquals("http 404", c.snapshot.value.errors[ErrorCode.REMOTE_COMMAND_UNSUPPORTED]?.message)

        // 用户关闭或自动消除错误提示 (#31)
        c.dispatch(env(c, EventOrigin.USER_ACTION, sid, ErrorClearedEvent(ErrorCode.REMOTE_COMMAND_UNSUPPORTED)))
        runCurrent()
        assertFalse(c.snapshot.value.errors.containsKey(ErrorCode.REMOTE_COMMAND_UNSUPPORTED))
    }

    @Test
    fun `RemoteSkipNext in PAUSED state keeps playbackStatus PAUSED during TRANSITIONING and after rollback`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hwBridge = hw)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true)))
        val sid = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, sid, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sid, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 1L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(PlaybackStatus.PLAYING, c.snapshot.value.playbackStatus)

        // 用户主动显式暂停
        c.dispatch(env(c, EventOrigin.USER_ACTION, sid, ExplicitPauseEvent(epoch = 1L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(PlaybackStatus.PAUSED, c.snapshot.value.playbackStatus)

        // 💡 暂停状态下按遥控器切歌：businessState 进入 TRANSITIONING，但对外 playbackStatus 严格保持 PAUSED 杜绝跳变！
        val cmdId = c.allocateCommandId()
        c.dispatch(env(c, EventOrigin.USER_ACTION, sid, RemoteSkipNextEvent(RemoteCommandType.NEXT, cmdId)))
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.transitionOriginState)
        assertEquals(PlaybackStatus.PAUSED, c.snapshot.value.playbackStatus)

        // 收到切歌失败并回滚至 PAUSED
        c.dispatch(env(c, EventOrigin.AIRPLAY, sid, RemoteCommandFailedEvent(cmdId, ErrorCode.REMOTE_COMMAND_TIMEOUT, "timeout")))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertNull(c.snapshot.value.transitionOriginState)
        assertEquals(PlaybackStatus.PAUSED, c.snapshot.value.playbackStatus)
    }

    @Test
    fun `OpenDeviceOnResume handles STREAMING_READY RESTARTING and PRODUCER_DEAD uniformly`() = runTest(StandardTestDispatcher()) {
        var resumeBehavior = ProtocolResumeReopenResult.STREAMING_READY
        val resumeCallbacks = mutableListOf<Triple<StreamProtocol, Long, Long>>()
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(
            this,
            hwBridge = hw,
            onProtocolResumeAfterReopen = { proto, sid, epoch ->
                resumeCallbacks += Triple(proto, sid, epoch)
                resumeBehavior
            }
        )
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true)))

        // Case 1: STREAMING_READY -> 模拟暂停期间 USB 热插拔后进入 RELEASED，恢复时重开 DAC 并直接进入 PLAYING + EXCLUSIVE_ACTIVE (#2)
        val s1 = c.allocateSessionId()
        var gen = 1L
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamSetupEvent(StreamProtocol.UPNP_DLNA), deviceGen = gen))
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 96000, 24, 2, "WAV"), deviceGen = gen))
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 10L), deviceGen = gen))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("unplug-1"), deviceGen = ++gen))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true), deviceGen = ++gen))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)

        hw.openEpochToReturn = 21L
        resumeBehavior = ProtocolResumeReopenResult.STREAMING_READY
        val resumesBeforeReady = hw.fastPath.resumeCalls
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 20L), deviceGen = gen))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
        assertEquals(21L, c.snapshot.value.currentEpoch)
        assertEquals(resumesBeforeReady + 1, hw.fastPath.resumeCalls)
        assertEquals(Triple(StreamProtocol.UPNP_DLNA, s1, 21L), resumeCallbacks.last())

        // Case 2: RESTARTING -> 重开 DAC 后管线重连中，停留在 TRANSITIONING + PREPARING + EXCLUSIVE_MUTED 等首包 (#2)
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 21L), deviceGen = gen))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("unplug-2"), deviceGen = ++gen))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true), deviceGen = ++gen))
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)

        hw.openEpochToReturn = 22L
        resumeBehavior = ProtocolResumeReopenResult.RESTARTING
        val resumesBeforeRestart = hw.fastPath.resumeCalls
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 21L), deviceGen = gen))
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)
        assertEquals(TransportPipeState.PREPARING, c.snapshot.value.transportState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(resumesBeforeRestart, hw.fastPath.resumeCalls)

        // 首包到达后顺利进入 PLAYING
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 22L, 96000, 24, 2, "WAV"), deviceGen = gen))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)

        // Case 3: PRODUCER_DEAD -> 标记 INTERRUPTED + PAUSED 并触发 10 分钟挂起回收 (#2)
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 22L), deviceGen = gen))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareDetachedEvent("unplug-3"), deviceGen = ++gen))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true), deviceGen = ++gen))
        runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, c.snapshot.value.hardwareState)

        hw.openEpochToReturn = 23L
        resumeBehavior = ProtocolResumeReopenResult.PRODUCER_DEAD
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 22L), deviceGen = gen))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(TransportPipeState.INTERRUPTED, c.snapshot.value.transportState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        advanceTimeBy(PlaybackSessionCoordinator.SUSPEND_TIMEOUT_MS + 100L)
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
    }

    @Test
    fun `startEventLoop survives transient non-cancellation RuntimeException and processes next events`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hwBridge = hw)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true)))

        // 让首次 openDeviceWithCurrentFd 直接抛未捕获 RuntimeException (#21)
        hw.throwOnOpenAttempt = IllegalStateException("transient jni failure")
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamSetupEvent(StreamProtocol.UPNP_DLNA)))
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 48000, 16, 2, "WAV")))
        runCurrent()

        // 异常被事件循环兜底拦截后，事件队列消费协程依然存活，能正常处理后续新会话
        val s2 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 11L, 44100, 16, 2, "ALAC")))
        runCurrent()

        assertEquals(s2, c.snapshot.value.sessionId)
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
    }

    @Test
    fun `CoverGraceTimeoutEvent clears stale cover if unclaimed after 1200ms grace period`() = runTest(StandardTestDispatcher()) {
        val tm = TrackTransactionManager()
        val initialCover = byteArrayOf(1, 2, 3)
        tm.resetForNewStream(1L, TrackMetadata(title = "Song 1", coverBytes = initialCover))
        assertEquals(1L, tm.coverTrackGeneration)

        // Track 2 arrives with no cover
        val ctx = TransitionContext(
            snapshot = PlayerSessionSnapshot(
                sessionId = 1L,
                businessState = BusinessSessionState.PLAYING,
                metadata = tm.currentMetadata
            )
        )
        val boundaryEnv = SessionEventEnvelope(
            origin = EventOrigin.UPNP,
            sessionId = 1L,
            deviceGeneration = 1L,
            sequence = 1L,
            timestampElapsedMs = 100L,
            payload = TrackBoundaryEvent("track-2", 2L)
        )
        val out1 = TriStateTransitionMatrix.reduce(ctx, boundaryEnv, tm, 100L)
        assertTrue(out1.effects.any { it is TransitionEffect.ScheduleCoverGraceTimeout })
        assertNotNull(out1.nextContext.snapshot.metadata.coverBytes)

        // Case A: CoverGraceTimeoutEvent fires when cover is STILL unclaimed (coverTrackGeneration < 2L)
        val timeoutEnv = SessionEventEnvelope(
            origin = EventOrigin.SERVICE,
            sessionId = 1L,
            deviceGeneration = 1L,
            sequence = 2L,
            timestampElapsedMs = 1300L,
            payload = CoverGraceTimeoutEvent(2L)
        )
        val out2 = TriStateTransitionMatrix.reduce(out1.nextContext, timeoutEnv, tm, 1300L)
        assertNull(out2.nextContext.snapshot.metadata.coverBytes)
        assertNull(out2.nextContext.snapshot.metadata.coverUri)

        // Case B: CoverGraceTimeoutEvent fires when cover was ALREADY claimed (cleared == null branch)
        val tm2 = TrackTransactionManager()
        tm2.resetForNewStream(1L, TrackMetadata(title = "Song 1", coverBytes = initialCover))
        val outB1 = TriStateTransitionMatrix.reduce(ctx, boundaryEnv, tm2, 100L)
        tm2.applyMetadataPatch(MetadataPatchEvent(trackGeneration = 2L, coverBytes = byteArrayOf(4, 5, 6)))
        val outB2 = TriStateTransitionMatrix.reduce(outB1.nextContext, timeoutEnv, tm2, 1300L)
        assertEquals(outB1.nextContext, outB2.nextContext)

        // Case C: Duplicate TrackBoundaryEvent (result.accepted == false branch)
        val duplicateBoundary = SessionEventEnvelope(
            origin = EventOrigin.UPNP,
            sessionId = 1L,
            deviceGeneration = 1L,
            sequence = 3L,
            timestampElapsedMs = 1400L,
            payload = TrackBoundaryEvent("track-2", 2L)
        )
        val outC = TriStateTransitionMatrix.reduce(outB2.nextContext, duplicateBoundary, tm2, 1400L)
        assertFalse(outC.effects.any { it is TransitionEffect.ScheduleCoverGraceTimeout })

        // 验证 TrackTransactionManager 全分支覆盖：
        // 1. resetForNewStream 分支：null、uri-only、cover-less
        val tm3 = TrackTransactionManager()
        tm3.resetForNewStream(1L, null)
        assertEquals(0L, tm3.coverTrackGeneration)
        assertEquals("stream-1", tm3.currentTrackKey)

        val tm4 = TrackTransactionManager()
        tm4.resetForNewStream(1L, TrackMetadata(title = "NoCover"))
        assertEquals(0L, tm4.coverTrackGeneration)

        val tm5 = TrackTransactionManager()
        tm5.resetForNewStream(1L, TrackMetadata(coverUri = "http://uri-only"))
        assertEquals(1L, tm5.coverTrackGeneration)

        // 2. applyMetadataPatch 分支：
        // a. patch with bytes only -> clears uri
        tm5.onTrackBoundary(TrackBoundaryEvent("k2", 2L))
        val patchBytesOnly = tm5.applyMetadataPatch(MetadataPatchEvent(trackGeneration = 2L, coverBytes = byteArrayOf(9)))!!
        assertNotNull(patchBytesOnly.coverBytes)
        assertNull(patchBytesOnly.coverUri)
        assertEquals(2L, tm5.coverTrackGeneration)

        // b. patch with uri only -> clears bytes
        tm5.onTrackBoundary(TrackBoundaryEvent("k3", 3L))
        val patchUriOnly = tm5.applyMetadataPatch(MetadataPatchEvent(trackGeneration = 3L, coverUri = "http://new-uri"))!!
        assertNotNull(patchUriOnly.coverUri)
        assertNull(patchUriOnly.coverBytes)
        assertEquals(3L, tm5.coverTrackGeneration)

        // c. patch with empty uri string -> hasNewUri == false
        val patchEmptyUri = tm5.applyMetadataPatch(MetadataPatchEvent(trackGeneration = 3L, coverUri = ""))!!
        assertEquals("http://new-uri", patchEmptyUri.coverUri)

        // 3. clearStaleCoverIfUnclaimed 分支：
        // a. targetGeneration != currentTrackGeneration
        assertNull(tm5.clearStaleCoverIfUnclaimed(99L))
        // b. coverTrackGeneration >= currentTrackGeneration
        assertNull(tm5.clearStaleCoverIfUnclaimed(3L))

        // c. hasUri == true && hasBytes == false
        tm5.onTrackBoundary(TrackBoundaryEvent("k4", 4L))
        val clearedUri = tm5.clearStaleCoverIfUnclaimed(4L)
        assertNotNull(clearedUri)
        assertNull(clearedUri!!.coverUri)
        assertNull(clearedUri.coverBytes)

        // d. hasUri == false && hasBytes == false
        assertNull(tm5.clearStaleCoverIfUnclaimed(4L))
    }

    @Test
    fun `StreamSetupEvent times out to STOPPED after 5000ms transition timeout (R4 and B-3)`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony WALKMAN", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(
            env(
                c, EventOrigin.AIRPLAY, s1,
                StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)
            )
        )
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)

        // 4999ms 未超时
        advanceTimeBy(4999L)
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)

        // 5000ms 超时看门狗触发: 初始建流超时直接断开归 IDLE，杜绝伪暂停占住声卡 10 分钟 (B-3)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
    }

    @Test
    fun `TrackSwitchEvent times out to PAUSED after 5000ms transition timeout (R4)`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony WALKMAN", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 切歌 / FLUSH 进入 TRANSITIONING
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 11L, null)))
        runCurrent()
        assertEquals(BusinessSessionState.TRANSITIONING, c.snapshot.value.businessState)

        // 5000ms 内无音频包或恢复事件，超时回退至 PAUSED
        advanceTimeBy(5001L)
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
    }

    @Test
    fun `TrackSwitchEvent preserves progress position when isSeekFlush true and clears on song switch (R11 and B-3)`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony WALKMAN", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        // 推进进度至 45000ms
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, ProgressUpdateEvent(45000L, 180000L)))
        runCurrent()
        assertEquals(45000L, c.snapshot.value.progressPositionMs)

        // 💡 寻道引起的 FLUSH (isSeekFlush = true)，保留进度避免闪烁回弹 (R11)
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 11L, null, isSeekFlush = true)))
        runCurrent()
        assertEquals("Seek 触发的 FLUSH 必须保留 progressPositionMs (R11)", 45000L, c.snapshot.value.progressPositionMs)

        // 💡 FLUSH 无论是寻道、切歌还是暂停均保留当前进度，杜绝 iPhone 暂停时被误清零
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 12L, null, isSeekFlush = false)))
        runCurrent()
        assertEquals("FLUSH 阶段保留进度杜绝暂停误抹零", 45000L, c.snapshot.value.progressPositionMs)

        // 💡 随后收到新曲目的 TrackBoundaryEvent 时，进度才正式重置为 0L
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackBoundaryEvent(
            trackKey = "song-2",
            trackGeneration = 2L
        )))
        runCurrent()
        assertEquals("新曲目边界到达后 progressPositionMs 归零", 0L, c.snapshot.value.progressPositionMs)
    }

    @Test
    fun `TrackSwitchEvent in PAUSED state keeps PAUSED and does not jump to TRANSITIONING (P0-4)`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony WALKMAN", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 显式暂停（本机操作，立即进入 PAUSED）
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 11L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 💡 暂停态下收到 RTSP FLUSH（例如暂停态拖动进度条或手机暂停时附带发 FLUSH）
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 12L, null, isSeekFlush = false)))
        runCurrent()

        // 验证：P0-4 契约生效，必须保持 PAUSED，严禁误跳回 TRANSITIONING / PLAYING
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(12L, c.snapshot.value.currentEpoch)
    }

    @Test
    fun `StreamTeardownEvent distinguishes pause sleep from active disconnect and blocks zombie resume`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val terminated = mutableListOf<Pair<StreamProtocol, Long>>()
        var dacpShouldFail = false
        val c = createCoordinator(
            this,
            hwBridge = hw,
            dacpSender = { _, _ ->
                if (dacpShouldFail) Result.failure(IllegalStateException("connection refused"))
                else Result.success(Unit)
            },
            onPreempted = { p, sid -> terminated += p to sid }
        )

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true), deviceGen = 1L))
        runCurrent()

        // 场景 1: 播放中收到 DACP 探活确认断开 (hardStop=true) -> 不管前置状态直接断开会话归 IDLE
        val sPlayDisconnect = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, sPlayDisconnect, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sPlayDisconnect, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        c.dispatch(env(c, EventOrigin.AIRPLAY, sPlayDisconnect, StreamTeardownEvent(hardStop = true)))
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(0L, c.snapshot.value.sessionId)
        assertEquals(TransportPipeState.DISCONNECTED, c.snapshot.value.transportState)
        assertTrue(terminated.contains(StreamProtocol.AIRPLAY_1 to sPlayDisconnect))

        // 场景 2: DACP 探活确认存活 (hardStop=false) -> 无论此前是 PLAYING 还是 PAUSED，均保留 PAUSED 挂起态，且阻断直接 StreamResumeEvent
        val sPauseSleep = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, sPauseSleep, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 11L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sPauseSleep, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 11L, 44100, 16, 2, "ALAC")))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sPauseSleep, StreamTeardownEvent(hardStop = false)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(TransportPipeState.DISCONNECTED, c.snapshot.value.transportState)

        // 阻断直接 StreamResumeEvent 防止虚空播放
        c.dispatch(env(c, EventOrigin.USER_ACTION, sPauseSleep, StreamResumeEvent(StreamProtocol.AIRPLAY_1, 11L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(TransportPipeState.DISCONNECTED, c.snapshot.value.transportState)

        // 场景 3: 暂停挂起后 DACP 探活确认断开 (hardStop=true) -> 立即断开会话归 IDLE
        c.dispatch(env(c, EventOrigin.AIRPLAY, sPauseSleep, StreamTeardownEvent(hardStop = true)))
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(0L, c.snapshot.value.sessionId)
        assertTrue(terminated.contains(StreamProtocol.AIRPLAY_1 to sPauseSleep))

        // 场景 4: 暂停挂起后点击播放 (RemoteCommandType.PLAY) 但 DACP 失败 -> 立即断开会话归 IDLE 而非虚空播放
        val sDeadPlay = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, sDeadPlay, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 12L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sDeadPlay, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 12L, 44100, 16, 2, "ALAC")))
        c.dispatch(env(c, EventOrigin.USER_ACTION, sDeadPlay, ExplicitPauseEvent(epoch = 12L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, sDeadPlay, StreamTeardownEvent(hardStop = false)))
        runCurrent()
        dacpShouldFail = true
        c.dispatch(env(c, EventOrigin.USER_ACTION, sDeadPlay, RemoteSkipNextEvent(RemoteCommandType.PLAY, c.allocateCommandId())))
        runCurrent()
        assertEquals(BusinessSessionState.IDLE, c.snapshot.value.businessState)
        assertEquals(0L, c.snapshot.value.sessionId)
        assertTrue(terminated.contains(StreamProtocol.AIRPLAY_1 to sDeadPlay))
    }

    @Test
    fun `FirstDataPacketEvent in PAUSED state preserves PAUSED and EXCLUSIVE_MUTED without resuming native output`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony ZX300A", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, s1, StreamSetupEvent(StreamProtocol.UPNP_DLNA, 44100, 16, 2, "WAV", 10L)))
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 44100, 16, 2, "WAV")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
        val initialResumes = hw.fastPath.resumeCalls

        // 显式暂停 -> 业务态进入 PAUSED，硬件态 EXCLUSIVE_MUTED
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 10L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)

        // 暂停态下寻道换代 (TrackSwitchEvent isSeekFlush=true)
        c.dispatch(env(c, EventOrigin.UPNP, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 11L, null, isSeekFlush = true)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 寻道后首包数据写入完成派发 FirstDataPacketEvent
        c.dispatch(env(c, EventOrigin.UPNP, s1, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 11L, 44100, 16, 2, "WAV")))
        runCurrent()

        // 💡 验证：必须严格保持 PAUSED 与 EXCLUSIVE_MUTED，绝不调用 resumeStream 偷跑声音！
        assertEquals("暂停态寻道首包必须保持 PAUSED 业务态", BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals("暂停态寻道首包必须保持 EXCLUSIVE_MUTED 硬件态", HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals("传输状态应更新为就绪 STREAMING", TransportPipeState.STREAMING, c.snapshot.value.transportState)
        assertEquals("绝不能触发 native resumeCalls 解除静音", initialResumes, hw.fastPath.resumeCalls)

        // 验证 10 分钟挂起定时器正常生效
        advanceTimeBy(600_001L)
        runCurrent()
        assertEquals("10 分钟挂起超时后应安全退回 IDLE", BusinessSessionState.IDLE, c.snapshot.value.businessState)
    }

    @Test
    fun `FirstDataPacketEvent in PAUSED state with AIRPLAY_1 preserves PAUSED and EXCLUSIVE_MUTED without resuming native output`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony ZX300A", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, c.snapshot.value.hardwareState)
        val initialResumes = hw.fastPath.resumeCalls

        // 用户在电视端或 iPhone 按暂停 -> 业务态进入 PAUSED，硬件态 EXCLUSIVE_MUTED
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 10L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)

        // 暂停态下执行寻道或切曲 (TrackSwitchEvent FLUSH)
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 11L, null, isSeekFlush = true)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)

        // 寻道后首包数据写入完成派发 FirstDataPacketEvent
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 11L, 44100, 16, 2, "ALAC")))
        runCurrent()

        // 💡 验证 Finding 2: AirPlay 首包在 PAUSED 态下绝不能误判 resume 唤醒播放与偷跑声音！
        assertEquals("AirPlay 暂停态寻道首包必须保持 PAUSED 业务态", BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals("AirPlay 暂停态寻道首包必须保持 EXCLUSIVE_MUTED 硬件态", HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals("绝不能触发 native resumeCalls 解除静音", initialResumes, hw.fastPath.resumeCalls)
    }

    @Test
    fun `TrackBoundaryEvent resets progress to 0L on track generation change when snap trackGeneration is greater than 0`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony ZX300A", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackBoundaryEvent(trackGeneration = 1L, trackKey = "Song1", initialDurationState = DurationState.FIXED, seekCapability = SeekCapability.SEEKABLE, trackUri = null)))
        // 模拟已播放 2000ms
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, ProgressUpdateEvent(positionMs = 2000L, durationMs = 180000L)))
        runCurrent()
        assertEquals(2000L, c.snapshot.value.progressPositionMs)
        assertEquals(1L, c.snapshot.value.trackGeneration)

        // 切歌 (TrackSwitchEvent FLUSH -> 进度保留为 2000ms)
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 11L, null, isSeekFlush = false)))
        runCurrent()
        assertEquals(2000L, c.snapshot.value.progressPositionMs)

        // 新歌边界到来 (TrackBoundaryEvent generation = 2L)
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackBoundaryEvent(trackGeneration = 2L, trackKey = "Song2", initialDurationState = DurationState.FIXED, seekCapability = SeekCapability.SEEKABLE, trackUri = null)))
        runCurrent()

        // 💡 验证 Finding 8: 切歌换代时，上一首歌残留的 2000ms 进度必须被坚决归零，绝不继承给新歌！
        assertEquals("新曲目边界到来必须将进度归零", 0L, c.snapshot.value.progressPositionMs)
        assertEquals("曲目代数应推进为 2", 2L, c.snapshot.value.trackGeneration)
    }

    @Test
    fun `TrackBoundaryEvent adopts initialPositionMs when non-zero on track generation change`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("Sony ZX300A", hasPermission = true), deviceGen = 1L))
        runCurrent()

        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackBoundaryEvent(trackGeneration = 1L, trackKey = "Song1", initialDurationState = DurationState.FIXED, seekCapability = SeekCapability.SEEKABLE, trackUri = null)))
        // 模拟已播放 2000ms
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, ProgressUpdateEvent(positionMs = 2000L, durationMs = 180000L)))
        runCurrent()
        assertEquals(2000L, c.snapshot.value.progressPositionMs)

        // 切歌 (TrackSwitchEvent FLUSH)
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackSwitchEvent(FlushReason.FLUSH_SWITCH, 11L, null, isSeekFlush = false)))
        runCurrent()

        // 新歌边界到来，带有非零初始进度 (例如播客或非开头播放 160800ms / 209722ms)
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, TrackBoundaryEvent(
            trackGeneration = 2L,
            trackKey = "Song2",
            initialDurationState = DurationState.FIXED,
            seekCapability = SeekCapability.SEEKABLE,
            trackUri = null,
            initialPositionMs = 160_800L,
            initialDurationMs = 209_722L
        )))
        runCurrent()

        // 验证：新曲目必须精准采纳 160800ms 作为起始进度与 209722ms 作为时长，不得被抹零
        assertEquals("新曲目显式初始进度必须被精准采纳", 160_800L, c.snapshot.value.progressPositionMs)
        assertEquals("新曲目显式初始时长必须被精准采纳", 209_722L, c.snapshot.value.progressDurationMs)
        assertEquals("曲目代数应推进为 2", 2L, c.snapshot.value.trackGeneration)
    }

    private fun <T : SessionEventPayload> matrixEnv(
        payload: T,
        sessionId: Long = 1L,
        origin: EventOrigin = EventOrigin.UPNP,
        sequence: Long = 1L
    ) = SessionEventEnvelope(
        origin = origin,
        sessionId = sessionId,
        deviceGeneration = 1L,
        sequence = sequence,
        timestampElapsedMs = 100L,
        payload = payload
    )

    @Test
    fun `TriStateTransitionMatrix covers all branches of isCrossProtocolPreempting in StreamSetupEvent`() {
        val tm = TrackTransactionManager()
        val baseEnvelope = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 2L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)
        )

        // 1. snap.sessionId == 0L：空闲态建流，不触发抢占
        val snapZero = PlayerSessionSnapshot(
            sessionId = 0L,
            activeProtocol = StreamProtocol.NONE,
            hardwareState = HardwarePipelineState.RELEASED
        )
        val outZero = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapZero), baseEnvelope, tm, 100L)
        assertFalse(outZero.effects.any { it is TransitionEffect.NotifyPreemptedProtocol })

        // 2. snap.sessionId == envelope.sessionId：同会话重入建流，不触发抢占
        val snapSameSession = PlayerSessionSnapshot(
            sessionId = 2L,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            hardwareState = HardwarePipelineState.RELEASED
        )
        val outSameSession = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapSameSession), baseEnvelope, tm, 100L)
        assertFalse(outSameSession.effects.any { it is TransitionEffect.NotifyPreemptedProtocol })

        // 3. activeProtocol 为 NONE：虽然有旧 sessionId，但 activeProtocol == NONE，isCrossProtocolPreempting 为 false
        val snapNone = PlayerSessionSnapshot(
            sessionId = 1L,
            activeProtocol = StreamProtocol.NONE,
            hardwareState = HardwarePipelineState.RELEASED
        )
        val outNone = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapNone), baseEnvelope, tm, 100L)
        assertFalse(outNone.effects.any { it is TransitionEffect.NotifyPreemptedProtocol })

        // 4. 相同协议：activeProtocol == ev.protocol，isCrossProtocolPreempting 为 false
        val snapSameProto = PlayerSessionSnapshot(
            sessionId = 1L,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            hardwareState = HardwarePipelineState.RELEASED
        )
        val outSameProto = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapSameProto), baseEnvelope, tm, 100L)
        assertFalse(outSameProto.effects.any { it is TransitionEffect.NotifyPreemptedProtocol })

        // 5. 跨协议抢占：activeProtocol != NONE 且 activeProtocol != ev.protocol，isCrossProtocolPreempting 为 true
        val snapCrossProto = PlayerSessionSnapshot(
            sessionId = 1L,
            activeProtocol = StreamProtocol.UPNP_DLNA,
            hardwareState = HardwarePipelineState.RELEASED
        )
        val outCrossProto = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapCrossProto), baseEnvelope, tm, 100L)
        val preemptEffect = outCrossProto.effects.filterIsInstance<TransitionEffect.NotifyPreemptedProtocol>().firstOrNull()
        assertNotNull("跨协议抢占必须派发 NotifyPreemptedProtocol", preemptEffect)
        assertEquals(StreamProtocol.UPNP_DLNA, preemptEffect?.protocol)
        assertEquals(1L, preemptEffect?.sessionId)
    }

    @Test
    fun `TriStateTransitionMatrix covers all branches of TransitionTimeoutEvent`() {
        val tm = TrackTransactionManager()

        // 1. sessionId 匹配但 businessState != TRANSITIONING (例如处于 PLAYING)，直接忽略
        val snapPlaying = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.PLAYING
        )
        val envMatchSessionNotTransitioning = matrixEnv(
            origin = EventOrigin.SERVICE,
            sessionId = 1L,
            payload = TransitionTimeoutEvent(sessionId = 1L)
        )
        val outPlaying = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPlaying), envMatchSessionNotTransitioning, tm, 100L)
        assertEquals(snapPlaying, outPlaying.nextContext.snapshot)
        assertTrue(outPlaying.effects.isEmpty())

        // 2. sessionId 不匹配即使处于 TRANSITIONING 也直接忽略
        val snapTransitioning = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.TRANSITIONING
        )
        val envMismatchSession = matrixEnv(
            origin = EventOrigin.SERVICE,
            sessionId = 2L,
            sequence = 2L,
            payload = TransitionTimeoutEvent(sessionId = 2L)
        )
        val outMismatch = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapTransitioning), envMismatchSession, tm, 100L)
        assertEquals(snapTransitioning, outMismatch.nextContext.snapshot)
        assertTrue(outMismatch.effects.isEmpty())

        // 3. PREPARING 阶段超时（从未收到过有效首包），回退至 STOPPED 并触发 10s 硬件空闲让渡释放
        val snapPreparing = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.TRANSITIONING,
            transportState = TransportPipeState.PREPARING,
            hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE
        )
        val envPreparingTimeout = matrixEnv(
            origin = EventOrigin.SERVICE,
            sessionId = 1L,
            sequence = 3L,
            payload = TransitionTimeoutEvent(sessionId = 1L)
        )
        val outPreparing = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPreparing), envPreparingTimeout, tm, 100L)
        assertEquals("PREPARING 超时必须直接断开归 IDLE", BusinessSessionState.IDLE, outPreparing.nextContext.snapshot.businessState)
        assertEquals("PREPARING 超时硬件态静音", HardwarePipelineState.EXCLUSIVE_MUTED, outPreparing.nextContext.snapshot.hardwareState)
        assertTrue("PREPARING 超时必须调度 10s 空闲释放定时器", outPreparing.effects.any { it is TransitionEffect.ScheduleHardwareIdleTimeout })

        // 4. INTERRUPTED 阶段超时（切歌换代中），回退至 PAUSED 并启动 10 分钟挂起定时器
        val snapInterrupted = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.TRANSITIONING,
            transportState = TransportPipeState.INTERRUPTED,
            hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE
        )
        val envInterruptedTimeout = matrixEnv(
            origin = EventOrigin.SERVICE,
            sessionId = 1L,
            sequence = 4L,
            payload = TransitionTimeoutEvent(sessionId = 1L)
        )
        val outInterrupted = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapInterrupted), envInterruptedTimeout, tm, 100L)
        assertEquals("INTERRUPTED 超时必须回退至 PAUSED", BusinessSessionState.PAUSED, outInterrupted.nextContext.snapshot.businessState)
        assertEquals("INTERRUPTED 超时硬件态维持静音", HardwarePipelineState.EXCLUSIVE_MUTED, outInterrupted.nextContext.snapshot.hardwareState)
        assertTrue("INTERRUPTED 超时必须调度 10 分钟挂起定时器", outInterrupted.effects.any { it is TransitionEffect.ScheduleSuspendTimeout })
    }

    @Test
    fun `FirstDataPacketEvent in PAUSED state with detached DAC still schedules suspend timeout`() {
        val tm = TrackTransactionManager()
        val snapPausedDetached = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.PAUSED,
            transportState = TransportPipeState.INTERRUPTED,
            hardwareState = HardwarePipelineState.ATTACHED
        )
        val envFirstPacket = matrixEnv(
            origin = EventOrigin.UPNP,
            sessionId = 1L,
            payload = FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 44100, 16, 2, "WAV")
        )
        val out = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedDetached), envFirstPacket, tm, 100L)
        assertEquals(BusinessSessionState.PAUSED, out.nextContext.snapshot.businessState)
        assertEquals(HardwarePipelineState.ATTACHED, out.nextContext.snapshot.hardwareState)
        assertTrue("声卡未就绪时暂停首包仍须调度 10 分钟挂起定时器", out.effects.any { it is TransitionEffect.ScheduleSuspendTimeout })
        assertFalse("严禁触发 ResumeNativeOutput", out.effects.any { it is TransitionEffect.ResumeNativeOutput })
    }

    @Test
    fun `TriStateTransitionMatrix covers all branches of ProgressUpdateEvent`() {
        val tm = TrackTransactionManager()
        val snap = PlayerSessionSnapshot(
            sessionId = 1L,
            trackGeneration = 2L,
            progressPositionMs = 1000L,
            progressDurationMs = 5000L
        )

        // 1. trackGeneration == 0L: 兼容无代数信息进度包，正常更新
        val envGenZero = matrixEnv(
            origin = EventOrigin.UPNP,
            sessionId = 1L,
            payload = ProgressUpdateEvent(positionMs = 2000L, durationMs = 5000L, trackGeneration = 0L)
        )
        val outGenZero = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snap), envGenZero, tm, 100L)
        assertEquals(2000L, outGenZero.nextContext.snapshot.progressPositionMs)

        // 2. trackGeneration > 0L 但不匹配当前代数 (如在途旧代数包 1L != 2L)，丢弃不更新
        val envGenMismatch = matrixEnv(
            origin = EventOrigin.UPNP,
            sessionId = 1L,
            sequence = 2L,
            payload = ProgressUpdateEvent(positionMs = 3000L, durationMs = 5000L, trackGeneration = 1L)
        )
        val outGenMismatch = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snap), envGenMismatch, tm, 100L)
        assertEquals("代数不匹配的进度包必须被丢弃", 1000L, outGenMismatch.nextContext.snapshot.progressPositionMs)

        // 3. trackGeneration > 0L 且匹配当前代数 (2L == 2L)，正常更新
        val envGenMatch = matrixEnv(
            origin = EventOrigin.UPNP,
            sessionId = 1L,
            sequence = 3L,
            payload = ProgressUpdateEvent(positionMs = 4000L, durationMs = 5000L, trackGeneration = 2L)
        )
        val outGenMatch = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snap), envGenMatch, tm, 100L)
        assertEquals(4000L, outGenMatch.nextContext.snapshot.progressPositionMs)
    }

    @Test
    fun `TriStateTransitionMatrix covers UsbPermissionGrantedEvent and HardwareStateSyncedEvent branches`() {
        val tm = TrackTransactionManager()

        // --- 1. UsbPermissionGrantedEvent 分支覆盖 ---
        // 1.1 独占态下收到授权成功，保持独占态且 isExclusive 为 true
        val snapExclusive = PlayerSessionSnapshot(
            hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE,
            audioPath = AudioPathState(isDacConnected = true, isExclusive = true, dacName = "DAC1")
        )
        val envPermGrantedExclusive = matrixEnv(
            origin = EventOrigin.USB_HARDWARE,
            sessionId = 1L,
            sequence = 1L,
            payload = UsbPermissionGrantedEvent(deviceName = "DAC1", granted = true)
        )
        val outPermExclusive = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapExclusive), envPermGrantedExclusive, tm, 100L)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, outPermExclusive.nextContext.snapshot.hardwareState)
        assertTrue(outPermExclusive.nextContext.snapshot.audioPath.isExclusive)

        // 1.2 非独占态下收到授权成功，状态转换为 RELEASED 且 isExclusive 为 false
        val snapPending = PlayerSessionSnapshot(
            hardwareState = HardwarePipelineState.PERMISSION_PENDING,
            audioPath = AudioPathState(isDacConnected = true, isExclusive = false, dacName = "DAC1")
        )
        val outPermPending = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPending), envPermGrantedExclusive, tm, 100L)
        assertEquals(HardwarePipelineState.RELEASED, outPermPending.nextContext.snapshot.hardwareState)
        assertFalse(outPermPending.nextContext.snapshot.audioPath.isExclusive)

        // 1.3 授权被拒绝 (granted = false)
        val envPermDenied = matrixEnv(
            origin = EventOrigin.USB_HARDWARE,
            sessionId = 1L,
            sequence = 2L,
            payload = UsbPermissionGrantedEvent(deviceName = "DAC1", granted = false)
        )
        val outPermDenied = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapExclusive), envPermDenied, tm, 100L)
        assertEquals(HardwarePipelineState.PERMISSION_PENDING, outPermDenied.nextContext.snapshot.hardwareState)
        assertFalse(outPermDenied.nextContext.snapshot.audioPath.isExclusive)

        // --- 2. HardwareStateSyncedEvent 分支覆盖 ---
        val envSyncReconfig = matrixEnv(
            origin = EventOrigin.USB_HARDWARE,
            sessionId = 1L,
            sequence = 3L,
            payload = HardwareStateSyncedEvent(newHardwareState = HardwarePipelineState.AUDIO_RECONFIGURING)
        )

        // 2.1 修复 Finding 11: 原位重配切频状态 (AUDIO_RECONFIGURING) 正常透传同步，通知 UI，不被强行覆盖
        val snapPlaying = PlayerSessionSnapshot(
            hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE,
            businessState = BusinessSessionState.PLAYING,
            transportState = TransportPipeState.STREAMING
        )
        val outSyncPlaying = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPlaying), envSyncReconfig, tm, 100L)
        assertEquals(HardwarePipelineState.AUDIO_RECONFIGURING, outSyncPlaying.nextContext.snapshot.hardwareState)
        assertTrue(outSyncPlaying.nextContext.snapshot.audioPath.isExclusive)

        // 2.3 EXCLUSIVE_ACTIVE 但非 PLAYING 且非 STREAMING (例如 PAUSED/DISCONNECTED)：正常同步为 AUDIO_RECONFIGURING
        val snapPaused = PlayerSessionSnapshot(
            hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE,
            businessState = BusinessSessionState.PAUSED,
            transportState = TransportPipeState.DISCONNECTED
        )
        val outSyncPaused = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPaused), envSyncReconfig, tm, 100L)
        assertEquals(HardwarePipelineState.AUDIO_RECONFIGURING, outSyncPaused.nextContext.snapshot.hardwareState)

        // 2.4 非 EXCLUSIVE_ACTIVE (例如 RELEASED) 且 PLAYING：正常同步为 AUDIO_RECONFIGURING
        val snapReleasedPlaying = PlayerSessionSnapshot(
            hardwareState = HardwarePipelineState.RELEASED,
            businessState = BusinessSessionState.PLAYING,
            transportState = TransportPipeState.STREAMING
        )
        val outSyncReleased = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapReleasedPlaying), envSyncReconfig, tm, 100L)
        assertEquals(HardwarePipelineState.AUDIO_RECONFIGURING, outSyncReleased.nextContext.snapshot.hardwareState)

        // 2.5 同步到非 AUDIO_RECONFIGURING 状态 (例如 EXCLUSIVE_MUTED)
        val envSyncMuted = matrixEnv(
            origin = EventOrigin.USB_HARDWARE,
            sessionId = 1L,
            sequence = 4L,
            payload = HardwareStateSyncedEvent(newHardwareState = HardwarePipelineState.EXCLUSIVE_MUTED)
        )
        val outSyncMuted = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPlaying), envSyncMuted, tm, 100L)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, outSyncMuted.nextContext.snapshot.hardwareState)
    }

    @Test
    fun `TriStateTransitionMatrix covers pause resume metadata preservation in StreamSetupEvent`() {
        val tm = TrackTransactionManager()

        // 1. hasCustomInitial == true：传入明确歌名，直接采用传入的元数据
        val customMeta = TrackMetadata(title = "Custom Song", artist = "Artist")
        val envCustom = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 2L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L, initialMetadata = customMeta)
        )
        val snapIdle = PlayerSessionSnapshot(sessionId = 0L, activeProtocol = StreamProtocol.NONE, hardwareState = HardwarePipelineState.RELEASED)
        val outCustom = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapIdle), envCustom, tm, 100L)
        assertEquals("Custom Song", outCustom.nextContext.snapshot.metadata.title)

        // 2. isResumingPausedSession == true 且 snap 包含非默认歌名：无缝继承
        val snapPausedWithSong = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.PAUSED,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
            metadata = TrackMetadata(title = "Preserved Song", artist = "Faye Wong"),
            progressPositionMs = 25000L
        )
        val defaultMeta = TrackMetadata(title = "AirPlay 音频直通", artist = "AirPlay 设备")
        val envResumeDefault = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 2L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L, initialMetadata = defaultMeta)
        )
        val outResume = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedWithSong), envResumeDefault, tm, 100L)
        assertEquals("Preserved Song", outResume.nextContext.snapshot.metadata.title)
        assertEquals(25000L, outResume.nextContext.snapshot.progressPositionMs)

        // 3. isResumingPausedSession 但 snap 歌名为 "AirPlay 音频直通"：回退到 initialMeta
        val snapPausedDefaultSong = snapPausedWithSong.copy(
            metadata = TrackMetadata(title = "AirPlay 音频直通")
        )
        val outResumeDefault = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedDefaultSong), envResumeDefault, tm, 100L)
        assertEquals("AirPlay 音频直通", outResumeDefault.nextContext.snapshot.metadata.title)

        // 4. isResumingPausedSession 但 snap 歌名为空：回退到 initialMeta
        val snapPausedEmptySong = snapPausedWithSong.copy(
            metadata = TrackMetadata(title = "")
        )
        val outResumeEmpty = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedEmptySong), envResumeDefault, tm, 100L)
        assertEquals("AirPlay 音频直通", outResumeEmpty.nextContext.snapshot.metadata.title)

        // 5. snap 处于 PAUSED 但不同协议 (如 UPNP -> AIRPLAY)：isResumingPausedSession 为 false
        val snapPausedDifferentProto = snapPausedWithSong.copy(
            activeProtocol = StreamProtocol.UPNP_DLNA
        )
        val outDiffProto = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedDifferentProto), envResumeDefault, tm, 100L)
        assertEquals("AirPlay 音频直通", outDiffProto.nextContext.snapshot.metadata.title)
        assertEquals(0L, outDiffProto.nextContext.snapshot.progressPositionMs)

        // 6. initialMetadata 存在但 title 为空字符串：hasCustomInitial 为 false
        val envEmptyTitle = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 3L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L, initialMetadata = TrackMetadata(title = ""))
        )
        val outEmptyTitle = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapIdle), envEmptyTitle, tm, 100L)
        assertEquals("", outEmptyTitle.nextContext.snapshot.metadata.title)

        // 7. initialMetadata 为 null：hasCustomInitial 为 false，但命中 isResumingPausedSession
        val envNullMeta = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 4L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L, initialMetadata = null)
        )
        val outNullMeta = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedWithSong), envNullMeta, tm, 100L)
        assertEquals("Preserved Song", outNullMeta.nextContext.snapshot.metadata.title)
        assertEquals(25000L, outNullMeta.nextContext.snapshot.progressPositionMs)

        // 8. hasCustomInitial == true 且 initialMeta.title == snap.metadata.title：同曲名恢复
        val sameSongMeta = TrackMetadata(title = "Preserved Song", artist = "Faye Wong")
        val envSameSong = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 5L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L, initialMetadata = sameSongMeta)
        )
        val outSameSong = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedWithSong), envSameSong, tm, 100L)
        assertEquals("Preserved Song", outSameSong.nextContext.snapshot.metadata.title)
        assertEquals(25000L, outSameSong.nextContext.snapshot.progressPositionMs)

        // 9. hasCustomInitial == true 且 initialMeta.title != snap.metadata.title：切歌换曲目，isResumingPausedSession 为 false
        val differentSongMeta = TrackMetadata(title = "Different Song", artist = "Different Artist")
        val envDiffSong = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 6L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L, initialMetadata = differentSongMeta)
        )
        val outDiffSong = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapPausedWithSong), envDiffSong, tm, 100L)
        assertEquals("Different Song", outDiffSong.nextContext.snapshot.metadata.title)
        assertEquals(0L, outDiffSong.nextContext.snapshot.progressPositionMs)
    }

    @Test
    fun `TriStateTransitionMatrix covers early progress preservation in TrackBoundaryEvent`() {
        val tm = TrackTransactionManager()

        // 1. accepted && progressPositionMs in 1L..3000L (例如已播 1500ms)：保留进度
        val snapEarlyPlay = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.PLAYING,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            progressPositionMs = 1500L,
            progressDurationMs = 200000L
        )
        val envBoundary1 = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            payload = TrackBoundaryEvent(trackGeneration = 1L, trackKey = "Song1|Artist1")
        )
        val outEarly = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapEarlyPlay), envBoundary1, tm, 100L)
        assertEquals(1500L, outEarly.nextContext.snapshot.progressPositionMs)

        // 2. accepted && progressPositionMs > 3000L (例如残留上一首 180000ms)：归零
        val snapOldSongPlay = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.PLAYING,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            progressPositionMs = 180000L,
            progressDurationMs = 200000L
        )
        val envBoundary2 = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            payload = TrackBoundaryEvent(trackGeneration = 2L, trackKey = "Song2|Artist2")
        )
        val outOldSong = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapOldSongPlay), envBoundary2, tm, 100L)
        assertEquals(0L, outOldSong.nextContext.snapshot.progressPositionMs)

        // 3. accepted && progressPositionMs == 0L：保持 0L
        val snapZero = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.PLAYING,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            progressPositionMs = 0L,
            progressDurationMs = 200000L
        )
        val envBoundary3 = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            payload = TrackBoundaryEvent(trackGeneration = 3L, trackKey = "Song3|Artist3")
        )
        val outZero = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapZero), envBoundary3, tm, 100L)
        assertEquals(0L, outZero.nextContext.snapshot.progressPositionMs)

        // 4. rejected (trackGeneration <= current)：保持原进度
        val envRejected = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            payload = TrackBoundaryEvent(trackGeneration = 1L, trackKey = "SongOld|ArtistOld")
        )
        val outRejected = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapEarlyPlay), envRejected, tm, 100L)
        assertEquals(1500L, outRejected.nextContext.snapshot.progressPositionMs)
    }

    @Test
    fun `StreamSetupEvent when activeProtocol matches but sessionId is 0 does not resume session`() {
        val tm = TrackTransactionManager()
        val baseSnap = PlayerSessionSnapshot(
            sessionId = 0L,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            progressPositionMs = 25000L
        )
        val env = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", 10L)
        )
        val output = TriStateTransitionMatrix.reduce(
            TransitionContext(snapshot = baseSnap),
            env,
            tm,
            nowMs = 1000L
        )
        assertEquals(0L, output.nextContext.snapshot.progressPositionMs)
    }

    @Test
    fun `SERVICE and USER_ACTION events with inactive sessionId are dropped and do not pollute active session`() = runTest(StandardTestDispatcher()) {
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hw)

        // 1. 建立会话 101 并进入 PLAYING
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", true), deviceGen = 1L))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(s1, c.snapshot.value.sessionId)

        // 2. 模拟过期会话 99 的 USER_ACTION 和 SERVICE 事件 (Pause, Stop, Progress, Timeout)
        val deadSid = 99L
        c.dispatch(env(c, EventOrigin.USER_ACTION, deadSid, ExplicitPauseEvent(epoch = 1L)))
        c.dispatch(env(c, EventOrigin.USER_ACTION, deadSid, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, 1L, false)))
        c.dispatch(env(c, EventOrigin.SERVICE, deadSid, ProgressUpdateEvent(99999L, 99999L, 1L)))
        c.dispatch(env(c, EventOrigin.SERVICE, deadSid, TransitionTimeoutEvent(deadSid)))
        c.dispatch(env(c, EventOrigin.SERVICE, deadSid, CoverGraceTimeoutEvent(1L)))
        runCurrent()

        // 验证：当前活跃会话 101 完全不受污染，依然保持 PLAYING 且进度未被篡改
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)
        assertEquals(s1, c.snapshot.value.sessionId)

        // 3. 全局 sessionId == 0L 的合法服务事件（如全局错误消除）应正常受理
        c.dispatch(env(c, EventOrigin.SERVICE, 0L, ErrorClearedEvent(ErrorCode.USB_DEVICE_DETACHED)))
        runCurrent()
        assertEquals(BusinessSessionState.PLAYING, c.snapshot.value.businessState)

        // 4. 当前活跃会话自身的 USER_ACTION 事件正常受理
        c.dispatch(env(c, EventOrigin.USER_ACTION, s1, ExplicitPauseEvent(epoch = 10L)))
        runCurrent()
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
    }

    @Test
    fun `early non-zero protocol progress after track switch is accepted while 0ms placeholder is ignored`() {
        val tm = TrackTransactionManager()
        val snap = PlayerSessionSnapshot(
            sessionId = 1L,
            businessState = BusinessSessionState.PLAYING,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            trackGeneration = 16L,
            progressPositionMs = 1250L,
            progressDurationMs = 182_765L,
            progressRevision = 5L
        )

        // 1. 0ms 协议未就绪空包仍应拦截
        val zeroEnv = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            sequence = 1L,
            payload = ProgressUpdateEvent(positionMs = 0L, durationMs = 182_765L, trackGeneration = 16L)
        )
        val outZero = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snap), zeroEnv, tm, 100L)
        assertEquals(1250L, outZero.nextContext.snapshot.progressPositionMs)

        // 2. 复现 4ever in U：切歌后 1.25s 收到真实非零进度 7ms，必须放行校准并递增 progressRevision
        val realEnv1 = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            sequence = 2L,
            payload = ProgressUpdateEvent(positionMs = 7L, durationMs = 182_765L, trackGeneration = 16L)
        )
        val out1 = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snap), realEnv1, tm, 200L)
        assertEquals(7L, out1.nextContext.snapshot.progressPositionMs)
        assertEquals(6L, out1.nextContext.snapshot.progressRevision)

        // 3. 1s 后 Ticker 推至 1100ms，协议补发 104ms：同样必须放行校准
        val snapAfterTick = out1.nextContext.snapshot.copy(progressPositionMs = 1100L)
        val realEnv2 = matrixEnv(
            origin = EventOrigin.AIRPLAY,
            sessionId = 1L,
            sequence = 3L,
            payload = ProgressUpdateEvent(positionMs = 104L, durationMs = 182_765L, trackGeneration = 16L)
        )
        val out2 = TriStateTransitionMatrix.reduce(TransitionContext(snapshot = snapAfterTick), realEnv2, tm, 300L)
        assertEquals(104L, out2.nextContext.snapshot.progressPositionMs)
        assertEquals(7L, out2.nextContext.snapshot.progressRevision)
    }
}
