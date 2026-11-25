package com.solo.purecast.core.usb

import android.app.Activity
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.PlaybackSessionCoordinator
import com.solo.purecast.core.session.RemoteCommandType
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamSetupEvent
import com.solo.purecast.core.session.StreamStopEvent
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.StreamProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 💡 Unit U4 单元测试：验证单线程 HardwareExecutor、热插拔状态隔离、RELEASED 落态恢复重开与切频回滚
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsbDeviceManagerTest {

    private class FakeOpenFdHandle(override val fileDescriptor: Int) : UsbOpenFdHandle {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeUsbSystemBridge : UsbSystemBridge {
        var scannedDevice: UsbDeviceDescriptor? = null
        var permissionGranted: Boolean = false
        var nextFd: Int = 100
        val openedHandles = CopyOnWriteArrayList<FakeOpenFdHandle>()
        val requestedPermissions = CopyOnWriteArrayList<String>()
        var selfHealCalls = 0
        var openReturnsNull = false

        override fun scanAudioDevice(): UsbDeviceDescriptor? = scannedDevice

        override fun hasPermission(device: UsbDeviceDescriptor): Boolean = permissionGranted

        override fun openConnection(device: UsbDeviceDescriptor): UsbOpenFdHandle? {
            if (openReturnsNull) return null
            val handle = FakeOpenFdHandle(nextFd++)
            openedHandles += handle
            return handle
        }

        override fun requestPermission(device: UsbDeviceDescriptor, activity: Activity?) {
            requestedPermissions += device.productName
        }

        override fun triggerAudioSelfHealing() {
            selfHealCalls++
        }
    }

    private class FakeUsbNativeAudioBridge : UsbNativeAudioBridge {
        var openDeviceResult: Int = 0
        var reconfigureResult: Long = 2L
        var epoch: Long = 1L
        val openCalls = CopyOnWriteArrayList<String>()
        val reconfigureCalls = CopyOnWriteArrayList<String>()
        val muteCalls = CopyOnWriteArrayList<FlushReason>()
        var releaseCalls = 0
        var resumeCalls = 0

        override fun openDevice(fd: Int, sampleRate: Int, bitDepth: Int, channels: Int): Int {
            openCalls += "$fd:$sampleRate/$bitDepth/$channels"
            if (openDeviceResult == 0) {
                epoch++
            }
            return openDeviceResult
        }

        override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long {
            reconfigureCalls += "$sampleRate/$bitDepth/$channels"
            if (reconfigureResult > 0L) {
                epoch = reconfigureResult
            }
            return reconfigureResult
        }

        override fun releaseDevice() {
            releaseCalls++
        }

        override fun emergencyMuteAndFlush(reason: FlushReason): Long {
            muteCalls += reason
            if (reason != FlushReason.EXPLICIT_PAUSE) {
                epoch++
            }
            return epoch
        }

        override fun getCurrentBufferEpoch(): Long = epoch

        override fun resumeStream(): Int {
            resumeCalls++
            return 0
        }
    }

    private fun directExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "test-hw-exec").apply { isDaemon = true }
    }

    private fun flushExecutor(executor: ExecutorService) {
        executor.submit { }.get(2, TimeUnit.SECONDS)
    }

    private fun sonyDac() = UsbDeviceDescriptor(
        deviceId = 1001,
        deviceName = "/dev/bus/usb/001/002",
        productName = "Sony NW-ZX300A",
        vendorId = UsbDeviceManager.SONY_VENDOR_ID,
        productId = 0x0c1e
    )

    @Test
    fun attachAndDetachUnderIdleOrStoppedDoesNotPolluteBusinessStateOrSpinUrbs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val systemBridge = FakeUsbSystemBridge().apply {
            scannedDevice = sonyDac()
            permissionGranted = true
        }
        val nativeBridge = FakeUsbNativeAudioBridge()
        val dispatched = CopyOnWriteArrayList<SessionEventEnvelope<SessionEventPayload>>()
        var coordinator: PlaybackSessionCoordinator? = null

        val hwExecutor = directExecutor()
        val usbManager = UsbDeviceManager(
            systemBridge = systemBridge,
            nativeBridge = nativeBridge,
            hardwareExecutor = hwExecutor,
            dispatchEvent = { env ->
                dispatched += env
                coordinator?.dispatch(env)
            }
        )
        coordinator = PlaybackSessionCoordinator(
            scope = TestScope(dispatcher),
            hardwareBridge = usbManager
        )

        // 1. IDLE 下热插拔附着已授权设备：仅落到 RELEASED，不提前 openDevice 空转 URB
        assertTrue(usbManager.scanAndAttachDevice())
        testScheduler.runCurrent()
        var snap = coordinator.snapshot.value
        assertEquals(BusinessSessionState.IDLE, snap.businessState)
        assertEquals(PlaybackStatus.IDLE, snap.playbackStatus)
        assertEquals(HardwarePipelineState.RELEASED, snap.hardwareState)
        assertTrue(nativeBridge.openCalls.isEmpty())

        // 2. IDLE 下拔出：businessState 保持 IDLE，绝不误切 PAUSED
        assertTrue(usbManager.handleDeviceDetached(null))
        testScheduler.runCurrent()
        snap = coordinator.snapshot.value
        assertEquals(BusinessSessionState.IDLE, snap.businessState)
        assertEquals(HardwarePipelineState.DISCONNECTED, snap.hardwareState)
        assertTrue(snap.errors.containsKey(ErrorCode.USB_DEVICE_DETACHED))

        // 3. 重新插回有权限：再次落于 RELEASED，清除 USB 断开错误，businessState 保持 IDLE
        assertTrue(usbManager.scanAndAttachDevice())
        testScheduler.runCurrent()
        snap = coordinator.snapshot.value
        assertEquals(BusinessSessionState.IDLE, snap.businessState)
        assertEquals(HardwarePipelineState.RELEASED, snap.hardwareState)
        assertFalse(snap.errors.containsKey(ErrorCode.USB_DEVICE_DETACHED))
        assertTrue(nativeBridge.openCalls.isEmpty())

        // 4. STOPPED 业务态下拔出：严格维持 STOPPED 不变
        val sid = coordinator.allocateSessionId()
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.UPNP_DLNA, 44100, 16, 2, "WAV", 2L)
            )
        )
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.UPNP_DLNA, 2L, 44100, 16, 2, "WAV")
            )
        )
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.UPNP,
                sessionId = sid,
                deviceGeneration = 0L,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 3L,
                payload = StreamStopEvent()
            )
        )
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.IDLE, coordinator.snapshot.value.businessState)

        assertTrue(usbManager.handleDeviceDetached(null))
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.IDLE, coordinator.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.DISCONNECTED, coordinator.snapshot.value.hardwareState)

        usbManager.destroy()
        hwExecutor.shutdownNow()
        hwExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun playingDetachEntersPausedAndReattachPermissionToReleasedThenResumeReopensNewFd() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val systemBridge = FakeUsbSystemBridge().apply {
            scannedDevice = sonyDac()
            permissionGranted = true
            nextFd = 10
        }
        val nativeBridge = FakeUsbNativeAudioBridge()
        var coordinator: PlaybackSessionCoordinator? = null

        val hwExecutor = directExecutor()
        val usbManager = UsbDeviceManager(
            systemBridge = systemBridge,
            nativeBridge = nativeBridge,
            hardwareExecutor = hwExecutor,
            dispatchEvent = { env -> coordinator?.dispatch(env) }
        )
        coordinator = PlaybackSessionCoordinator(
            scope = TestScope(dispatcher),
            hardwareBridge = usbManager
        )

        // 先扫描并模拟进入 PLAYING
        assertTrue(usbManager.scanAndAttachDevice())
        assertTrue(usbManager.claimUsbExclusive(44100, 16, 2))
        val sid = coordinator.allocateSessionId()
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.AIRPLAY,
                sessionId = sid,
                deviceGeneration = 1L,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", nativeBridge.epoch)
            )
        )
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.AIRPLAY,
                sessionId = sid,
                deviceGeneration = 1L,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, nativeBridge.epoch, 44100, 16, 2, "ALAC")
            )
        )
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PLAYING, coordinator.snapshot.value.businessState)
        assertEquals(1, nativeBridge.openCalls.size)

        // 播放中物理拔出：立即静音停机并关闭旧 fd，协调器进入 PAUSED + DISCONNECTED
        assertTrue(usbManager.handleDeviceDetached(null))
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PAUSED, coordinator.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.DISCONNECTED, coordinator.snapshot.value.hardwareState)
        assertTrue(systemBridge.openedHandles[0].closed)
        assertTrue(nativeBridge.releaseCalls >= 1)

        // 插回已有权限：进入 RELEASED 待命，保持 PAUSED 不自动抢播
        assertTrue(usbManager.scanAndAttachDevice())
        testScheduler.runCurrent()
        assertEquals(BusinessSessionState.PAUSED, coordinator.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.RELEASED, coordinator.snapshot.value.hardwareState)
        assertEquals("插回授权时不得提前 openDevice", 1, nativeBridge.openCalls.size)

        // 用户按播放恢复（StreamResumeEvent）：Coordinator 驱动对新 fd 重开 openDevice 后切 PLAYING
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.AIRPLAY,
                sessionId = sid,
                deviceGeneration = 2L,
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 3L,
                payload = StreamResumeEvent(StreamProtocol.AIRPLAY_1, nativeBridge.epoch)
            )
        )
        testScheduler.runCurrent()
        assertEquals(2, nativeBridge.openCalls.size)
        assertTrue(nativeBridge.openCalls[1].startsWith("11:"))
        assertEquals(BusinessSessionState.PLAYING, coordinator.snapshot.value.businessState)
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, coordinator.snapshot.value.hardwareState)

        usbManager.destroy()
        hwExecutor.shutdownNow()
        hwExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun attachWithoutPermissionStaysPendingAndDoesNotCallOpenDeviceUntilGranted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dac = sonyDac()
        val systemBridge = FakeUsbSystemBridge().apply {
            scannedDevice = dac
            permissionGranted = false
        }
        val nativeBridge = FakeUsbNativeAudioBridge()
        var coordinator: PlaybackSessionCoordinator? = null

        val hwExecutor = directExecutor()
        val usbManager = UsbDeviceManager(
            systemBridge = systemBridge,
            nativeBridge = nativeBridge,
            hardwareExecutor = hwExecutor,
            dispatchEvent = { env -> coordinator?.dispatch(env) }
        )
        coordinator = PlaybackSessionCoordinator(
            scope = TestScope(dispatcher),
            hardwareBridge = usbManager
        )

        // 1. 纯后台冷启动（permissionActivity == null）扫描到未授权设备：严禁主动弹窗，保持 ATTACHED 且禁止 openDevice
        assertFalse(usbManager.scanAndAttachDevice())
        testScheduler.runCurrent()
        flushExecutor(hwExecutor)
        testScheduler.runCurrent()
        assertEquals(HardwarePipelineState.ATTACHED, coordinator.snapshot.value.hardwareState)
        assertTrue(nativeBridge.openCalls.isEmpty())
        assertTrue(
            "纯后台无前台 Activity 时不得调用 systemBridge.requestPermission 叠加开机弹窗",
            systemBridge.requestedPermissions.isEmpty()
        )

        // 2. 前台 Activity 绑定后触发扫描授权 -> 进入 PERMISSION_PENDING 并弹出一次授权框
        val fakeActivity = Activity()
        usbManager.scanAndRequestPermissionAsync(fakeActivity)
        flushExecutor(hwExecutor)
        testScheduler.runCurrent()
        assertEquals(HardwarePipelineState.PERMISSION_PENDING, coordinator.snapshot.value.hardwareState)
        assertEquals(1, systemBridge.requestedPermissions.size)

        // 3. 用户点拒绝 -> USB_PERMISSION_DENIED 错误注入，状态停留在 PERMISSION_PENDING
        usbManager.onPermissionResult(dac, granted = false)
        testScheduler.runCurrent()
        assertEquals(HardwarePipelineState.PERMISSION_PENDING, coordinator.snapshot.value.hardwareState)
        assertTrue(coordinator.snapshot.value.errors.containsKey(ErrorCode.USB_PERMISSION_DENIED))
        assertTrue(nativeBridge.openCalls.isEmpty())

        // 4. 拒绝后返回 MainActivity.onResume() 再次自动检查：同一连接周期内不得无限重弹
        usbManager.scanAndRequestPermissionAsync(fakeActivity, forceRetry = false)
        flushExecutor(hwExecutor)
        testScheduler.runCurrent()
        assertEquals(
            "用户拒绝后同一次物理连接内 onResume 自动检查不得重复弹窗",
            1,
            systemBridge.requestedPermissions.size
        )

        // 5. 设置页手动点击“独占接管”(forceRetry = true) 允许重新弹窗
        usbManager.scanAndRequestPermissionAsync(fakeActivity, forceRetry = true)
        flushExecutor(hwExecutor)
        testScheduler.runCurrent()
        assertEquals(
            "显式 forceRetry=true 应允许重新发起 USB 授权弹窗",
            2,
            systemBridge.requestedPermissions.size
        )

        // 6. 用户授权通过 -> 统一停留在 RELEASED 且清除错误，仍不空转 openDevice
        systemBridge.permissionGranted = true
        usbManager.onPermissionResult(dac, granted = true)
        testScheduler.runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, coordinator.snapshot.value.hardwareState)
        assertFalse(coordinator.snapshot.value.errors.containsKey(ErrorCode.USB_PERMISSION_DENIED))
        assertTrue(nativeBridge.openCalls.isEmpty())

        usbManager.destroy()
        hwExecutor.shutdownNow()
        hwExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun reconfigureFailureTriggersSafeRollbackAndFallbackReopenWithoutCrash() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val systemBridge = FakeUsbSystemBridge().apply {
            scannedDevice = sonyDac()
            permissionGranted = true
            nextFd = 50
        }
        val nativeBridge = FakeUsbNativeAudioBridge()
        var coordinator: PlaybackSessionCoordinator? = null

        val hwExecutor = directExecutor()
        val usbManager = UsbDeviceManager(
            systemBridge = systemBridge,
            nativeBridge = nativeBridge,
            hardwareExecutor = hwExecutor,
            dispatchEvent = { env -> coordinator?.dispatch(env) }
        )
        coordinator = PlaybackSessionCoordinator(
            scope = TestScope(dispatcher),
            hardwareBridge = usbManager
        )

        // 1. 先按 44.1kHz/16bit 建流独占
        assertTrue(usbManager.scanAndAttachDevice())
        val firstOpen = usbManager.openDeviceWithCurrentFd(44100, 16, 2)
        assertTrue(firstOpen.isSuccess)
        testScheduler.runCurrent()
        assertEquals(1, nativeBridge.openCalls.size)

        // 2. 相同规格直接幂等命中，不重开也不切频
        val sameSpecOpen = usbManager.openDeviceWithCurrentFd(44100, 16, 2)
        assertTrue(sameSpecOpen.isSuccess)
        assertEquals(1, nativeBridge.openCalls.size)
        assertTrue(nativeBridge.reconfigureCalls.isEmpty())

        // 3. 切频至 96kHz/24bit 但模拟原位切频失败 (-5L)，触发释放+关闭旧 fd+回滚重开
        nativeBridge.reconfigureResult = -5L
        val reconfigOpen = usbManager.openDeviceWithCurrentFd(96000, 24, 2)
        assertTrue("切频失败回滚重开应成功", reconfigOpen.isSuccess)
        testScheduler.runCurrent()
        assertEquals(1, nativeBridge.reconfigureCalls.size)
        assertEquals(1, nativeBridge.releaseCalls)
        assertTrue(systemBridge.openedHandles[0].closed)
        assertEquals("51:96000/24/2", nativeBridge.openCalls[1])
        assertEquals(96000, coordinator.snapshot.value.audioPath.sampleRate)
        assertEquals(24, coordinator.snapshot.value.audioPath.bitDepth)

        // 4. 模拟切频失败且回滚重开也失败 -> 安全回落到 RELEASED 并上报 USB_RECONFIGURE_FAILED
        nativeBridge.openDeviceResult = -19
        val doubleFail = usbManager.openDeviceWithCurrentFd(192000, 24, 2)
        assertTrue(doubleFail.isFailure)
        testScheduler.runCurrent()
        assertEquals(HardwarePipelineState.RELEASED, coordinator.snapshot.value.hardwareState)
        assertTrue(coordinator.snapshot.value.errors.containsKey(ErrorCode.USB_RECONFIGURE_FAILED))

        usbManager.destroy()
        hwExecutor.shutdownNow()
        hwExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun externalAudioFocusLossReleasesExclusiveAndSyncsHardwareState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val systemBridge = FakeUsbSystemBridge().apply {
            scannedDevice = sonyDac()
            permissionGranted = true
            nextFd = 77
        }
        val nativeBridge = FakeUsbNativeAudioBridge()
        var coordinator: PlaybackSessionCoordinator? = null

        val hwExecutor = directExecutor()
        val usbManager = UsbDeviceManager(
            systemBridge = systemBridge,
            nativeBridge = nativeBridge,
            hardwareExecutor = hwExecutor,
            dispatchEvent = { env -> coordinator?.dispatch(env) }
        )
        coordinator = PlaybackSessionCoordinator(
            scope = TestScope(dispatcher),
            hardwareBridge = usbManager
        )

        assertTrue(usbManager.scanAndAttachDevice())
        assertTrue(usbManager.claimUsbExclusive(44100, 16, 2))
        val sid = coordinator.allocateSessionId()
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.AIRPLAY,
                sessionId = sid,
                deviceGeneration = usbManager.currentDeviceGeneration(),
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 1L,
                payload = StreamSetupEvent(StreamProtocol.AIRPLAY_1, 44100, 16, 2, "ALAC", nativeBridge.epoch)
            )
        )
        coordinator.dispatch(
            SessionEventEnvelope(
                origin = com.solo.purecast.core.session.EventOrigin.AIRPLAY,
                sessionId = sid,
                deviceGeneration = usbManager.currentDeviceGeneration(),
                sequence = coordinator.allocateSequence(),
                timestampElapsedMs = 2L,
                payload = FirstDataPacketEvent(StreamProtocol.AIRPLAY_1, nativeBridge.epoch, 44100, 16, 2, "ALAC")
            )
        )
        testScheduler.runCurrent()
        assertEquals(HardwarePipelineState.EXCLUSIVE_ACTIVE, coordinator.snapshot.value.hardwareState)

        usbManager.releaseForExternalFocusLoss()
        flushExecutor(hwExecutor)
        testScheduler.runCurrent()

        assertFalse(usbManager.isExclusive())
        assertEquals(1, nativeBridge.releaseCalls)
        assertTrue(systemBridge.openedHandles[0].closed)
        assertTrue(systemBridge.selfHealCalls >= 1)
        assertEquals(HardwarePipelineState.RELEASED, coordinator.snapshot.value.hardwareState)

        usbManager.destroy()
        hwExecutor.shutdownNow()
        hwExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Test
    fun `bounded syncTimeoutMs returns failure fast when hardwareExecutor blocks and destroy is non-blocking`() = runTest {
        val systemBridge = FakeUsbSystemBridge().apply {
            scannedDevice = sonyDac()
            permissionGranted = true
        }
        val nativeBridge = FakeUsbNativeAudioBridge()
        val hwExecutor = directExecutor()
        val blockStarted = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val usbManager = UsbDeviceManager(
            systemBridge = systemBridge,
            nativeBridge = nativeBridge,
            hardwareExecutor = hwExecutor,
            ownsExecutor = true,
            dispatchEvent = {},
            clockMs = { testScheduler.currentTime },
            syncTimeoutMs = 50L
        )

        try {
            // 占用单线程 hardwareExecutor 模拟 JNI / 内核卡顿 (#8)
            hwExecutor.submit {
                blockStarted.countDown()
                releaseBlock.await(2, TimeUnit.SECONDS)
            }
            assertTrue(blockStarted.await(1, TimeUnit.SECONDS))

            val t0 = System.nanoTime()
            val attached = usbManager.scanAndAttachDevice()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            assertFalse("同步调用超时必须返回失败而不是无限等待", attached)
            assertTrue("同步调用应在约 50ms 超时边界附近返回 (实测 ${elapsedMs}ms)", elapsedMs < 800L)

            val openRes = usbManager.openDeviceWithCurrentFd(44100, 16, 2)
            assertTrue("openDeviceWithCurrentFd 超时应返回 Result.failure", openRes.isFailure)
        } finally {
            releaseBlock.countDown()
            usbManager.destroy()
        }
    }

    @Test
    fun `async attach detach dispatch cleanly and forceNewEpoch flushes PREEMPT on identical spec`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val systemBridge = FakeUsbSystemBridge().apply {
            scannedDevice = sonyDac()
            permissionGranted = true
        }
        val nativeBridge = FakeUsbNativeAudioBridge()
        val dispatched = CopyOnWriteArrayList<SessionEventEnvelope<SessionEventPayload>>()
        var coordinator: PlaybackSessionCoordinator? = null
        val hwExecutor = directExecutor()
        val usbManager = UsbDeviceManager(
            systemBridge = systemBridge,
            nativeBridge = nativeBridge,
            hardwareExecutor = hwExecutor,
            dispatchEvent = { env ->
                dispatched += env
                coordinator?.dispatch(env)
            },
            clockMs = { testScheduler.currentTime }
        )
        coordinator = PlaybackSessionCoordinator(
            scope = this.backgroundScope,
            hardwareBridge = usbManager,
            clockMs = { testScheduler.currentTime }
        )

        // 1. 异步扫描入队，不阻塞调用方且发回附着事件 (#8)
        usbManager.scanAndAttachDeviceAsync()
        flushExecutor(hwExecutor)
        testScheduler.runCurrent()
        assertTrue(dispatched.any { it.payload is com.solo.purecast.core.session.HardwareAttachedEvent })

        // 2. 首次打开 44.1kHz/16bit 成功并产生 HardwareReconfiguredEvent (#14)
        val firstOpen = usbManager.openDeviceWithCurrentFd(44100, 16, 2)
        assertTrue(firstOpen.isSuccess)
        assertEquals(1, dispatched.count { it.payload is com.solo.purecast.core.session.HardwareReconfiguredEvent })

        // 3. 相同规格但 forceNewEpoch=true (跨协议抢占) 必须强制触发 FlushReason.PREEMPT 并推进 epoch (#1)
        val epochBeforePreempt = firstOpen.getOrThrow().epoch
        val preemptOpen = usbManager.openDeviceWithCurrentFd(44100, 16, 2, forceNewEpoch = true)
        assertTrue(preemptOpen.isSuccess)
        assertTrue(nativeBridge.muteCalls.contains(FlushReason.PREEMPT))
        assertTrue(
            "forceNewEpoch=true 在同规格下也必须推进 epoch 拦截旧推流写入",
            preemptOpen.getOrThrow().epoch > epochBeforePreempt
        )

        // 4. 释放后以完全相同规格再次 freshOpen 时，仍必须发射 HardwareReconfiguredEvent 同步快照 (#14)
        usbManager.releaseDevice()
        val reopensBefore = dispatched.count { it.payload is com.solo.purecast.core.session.HardwareReconfiguredEvent }
        val reopenSameSpec = usbManager.openDeviceWithCurrentFd(44100, 16, 2)
        assertTrue(reopenSameSpec.isSuccess)
        assertEquals(
            "freshOpen 每次成功必须发射 HardwareReconfiguredEvent",
            reopensBefore + 1,
            dispatched.count { it.payload is com.solo.purecast.core.session.HardwareReconfiguredEvent }
        )

        // 4. 异步拔出同样走单线程排队并清除独占状态 (#8)
        usbManager.handleDeviceDetachedAsync(null)
        flushExecutor(hwExecutor)
        testScheduler.runCurrent()
        assertFalse(usbManager.isExclusive())
        assertTrue(dispatched.any { it.payload is com.solo.purecast.core.session.HardwareDetachedEvent })

        usbManager.destroy()
        hwExecutor.shutdownNow()
        hwExecutor.awaitTermination(1, TimeUnit.SECONDS)
    }
}
