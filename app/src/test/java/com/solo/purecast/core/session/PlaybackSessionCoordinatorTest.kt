package com.solo.purecast.core.session

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
    fun `ImplicitPauseEvent transitions to PAUSED in 0ms immediately`() = runTest(StandardTestDispatcher()) {
        val c = createCoordinator(this)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("DAC", hasPermission = true)))
        val s1 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        runCurrent()

        c.dispatch(env(c, EventOrigin.AIRPLAY, s1, ImplicitPauseEvent(10L)))
        runCurrent()
        assertEquals(TransportPipeState.INTERRUPTED, c.snapshot.value.transportState)
        assertEquals(BusinessSessionState.PAUSED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
    }

    @Test
    fun `TrackComplete transitions to STOPPED and 10s HardwareIdleTimeout releases device into IDLE`() = runTest(StandardTestDispatcher()) {
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
        assertEquals(BusinessSessionState.STOPPED, c.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_MUTED, c.snapshot.value.hardwareState)
        assertEquals(0, hw.releaseDeviceCalls)

        // 9.9 秒尚未释放
        advanceTimeBy(9_900)
        runCurrent()
        assertEquals(BusinessSessionState.STOPPED, c.snapshot.value.businessState)
        assertEquals(0, hw.releaseDeviceCalls)

        // 10.0 秒触发 HardwareIdleTimeoutEvent -> IDLE + RELEASED + releaseDevice()
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
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, ImplicitPauseEvent(2L)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 3L, isPreempted = true)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s0, StreamTeardownEvent(hardStop = true)))
        runCurrent()
        assertEquals(BusinessSessionState.STOPPED, c.snapshot.value.businessState)

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
        c.dispatch(env(c, EventOrigin.AIRPLAY, sAp, ImplicitPauseEvent(31L)))
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
        // 在 activeRemoteCommandId == 300L 时经 ImplicitPauseEvent 离开 TRANSITIONING，再收到匹配 id=300L 超时事件 (branch: id == active && !TRANSITIONING)
        c.dispatch(env(c, EventOrigin.AIRPLAY, sRe, ImplicitPauseEvent(1L), deviceGen = 6L))
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

        // 配置 0s (永久独占) -> 停播 30s 仍保持 EXCLUSIVE_MUTED 且不触发 RELEASED
        c.setIdleTimeoutSeconds(0)
        val s2 = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, StreamSetupEvent(StreamProtocol.AIRPLAY_1)))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, 10L, 44100, 16, 2, "ALAC")))
        c.dispatch(env(c, EventOrigin.AIRPLAY, s2, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 11L)))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
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

        // 2. 验证协调器在 STOPPED（停播但保留最后快照）状态下收到同会话迟到 StreamResumeEvent 时仍保持 STOPPED (#23)
        val hw = FakeSessionHardwareBridge()
        val c = createCoordinator(this, hwBridge = hw)
        c.dispatch(env(c, EventOrigin.USB_HARDWARE, 0L, HardwareAttachedEvent("ZX300A", hasPermission = true)))
        val sid = c.allocateSessionId()
        c.dispatch(env(c, EventOrigin.UPNP, sid, StreamSetupEvent(StreamProtocol.UPNP_DLNA)))
        c.dispatch(env(c, EventOrigin.UPNP, sid, FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 10L, 48000, 24, 2, "WAV")))
        c.dispatch(env(c, EventOrigin.UPNP, sid, StreamStopEvent(FlushReason.STOP_OR_TEARDOWN, newEpoch = 11L)))
        runCurrent()
        assertEquals(BusinessSessionState.STOPPED, c.snapshot.value.businessState)

        // 直接在 STOPPED 状态下投递迟到 StreamResumeEvent，确认绝不重开 DAC、恢复推流或变为 PLAYING
        val opensBefore = hw.openDeviceCalls
        val resumesBefore = hw.fastPath.resumeCalls
        c.dispatch(env(c, EventOrigin.UPNP, sid, StreamResumeEvent(StreamProtocol.UPNP_DLNA, resumedEpoch = 12L)))
        runCurrent()
        assertEquals(BusinessSessionState.STOPPED, c.snapshot.value.businessState)
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
        c.dispatch(env(c, EventOrigin.UPNP, s1, ImplicitPauseEvent(10L), deviceGen = gen))
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
        c.dispatch(env(c, EventOrigin.UPNP, s1, ImplicitPauseEvent(21L), deviceGen = gen))
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
        c.dispatch(env(c, EventOrigin.UPNP, s1, ImplicitPauseEvent(22L), deviceGen = gen))
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
}
