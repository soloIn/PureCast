package com.solo.purecast.core.session

import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.PlaybackError
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TransportPipeState
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 💡 USB 硬件打开成功结果回执
 */
data class HardwareOpenSuccess(
    val epoch: Long,
    val dacName: String
)

/**
 * 💡 协调器依赖的硬件生命周期桥接契约
 */
interface SessionHardwareBridge {
    fun openDeviceWithCurrentFd(sampleRate: Int, bitDepth: Int, channels: Int): Result<HardwareOpenSuccess>
    fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long
    fun releaseDevice()
    fun emergencyMuteAndFlush(reason: FlushReason): Long
    fun resumeNativeStream(): Int
    fun requestUsbPermission(deviceName: String)
}

/**
 * 💡 PureCast 核心播放会话状态协调器 (PlaybackSessionCoordinator)
 *
 * 职责边界：
 * 1. 维护全局唯一可信源快照 [snapshot] (`StateFlow<PlayerSessionSnapshot>`)；
 * 2. 强制鉴权 [SessionEventEnvelope]，按 `sessionId`、`deviceGeneration` 与单调 `sequence`
 *    过滤旧会话迟到事件及乱序状态分包；
 * 3. 调度毫秒级/分钟级状态定时器（220ms 显式防抖、800ms UI 保护窗口、5000ms DACP 单项超时回退、
 *    10s 硬件让渡与 10m 挂起回收）；
 * 4. 维护无界串行 DACP 遥控指令队列，确保快速连按全部按序送达发送端。
 */
class PlaybackSessionCoordinator(
    private val scope: CoroutineScope,
    private val hardwareBridge: SessionHardwareBridge,
    private val dacpCommandSender: suspend (RemoteCommandType, Long) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    private val onSessionTerminated: (StreamProtocol, Long) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val sessionIdGenerator = AtomicLong(0L)
    private val sequenceGenerator = AtomicLong(0L)
    private val commandIdGenerator = AtomicLong(0L)

    private val trackTransactionManager = TrackTransactionManager()
    private val eventChannel = Channel<SessionEventEnvelope<SessionEventPayload>>(Channel.UNLIMITED)
    private val dacpCommandChannel = Channel<Pair<RemoteCommandType, Long>>(Channel.UNLIMITED)

    private val _snapshot = MutableStateFlow(PlayerSessionSnapshot())
    val snapshot: StateFlow<PlayerSessionSnapshot> = _snapshot.asStateFlow()

    private var context = TransitionContext()
    private val lastAcceptedSequenceBySession = HashMap<Long, Long>()
    private var latestDeviceGeneration: Long = 0L
    private var lastAcceptedHardwareSequence: Long = 0L

    private var debouncePauseJob: Job? = null
    private var uiProtectionJob: Job? = null
    private var hardwareIdleTimeoutJob: Job? = null
    private var suspendTimeoutJob: Job? = null
    private var remoteCommandTimeoutJob: Job? = null

    init {
        startEventLoop()
        startDacpSerialLoop()
    }

    /** 分配全局单调会话身份 ID */
    fun allocateSessionId(): Long = sessionIdGenerator.incrementAndGet()

    /** 分配全局单调事件序号 */
    fun allocateSequence(): Long = sequenceGenerator.incrementAndGet()

    /** 分配遥控指令追踪 ID */
    fun allocateCommandId(): Long = commandIdGenerator.incrementAndGet()

    /** 非阻塞投递事件信封至协调器单线程事件循环 */
    fun dispatch(envelope: SessionEventEnvelope<SessionEventPayload>) {
        val result = eventChannel.trySend(envelope)
        if (result.isFailure) {
            Logger.service.w(TAG, "投递会话事件失败: payload=${envelope.payload::class.simpleName}")
        }
    }

    private fun startEventLoop() {
        scope.launch {
            for (envelope in eventChannel) {
                processEnvelope(envelope)
            }
        }
    }

    private fun startDacpSerialLoop() {
        scope.launch {
            for ((command, commandId) in dacpCommandChannel) {
                try {
                    val res = dacpCommandSender(command, commandId)
                    if (res.isFailure) {
                        val errMsg = res.exceptionOrNull()?.message ?: "DACP 指令执行失败"
                        Logger.service.w(TAG, "DACP 串行指令执行失败: cmd=$command, id=$commandId, err=$errMsg")
                        dispatch(
                            SessionEventEnvelope(
                                origin = EventOrigin.AIRPLAY,
                                sessionId = context.snapshot.sessionId,
                                deviceGeneration = latestDeviceGeneration,
                                sequence = allocateSequence(),
                                timestampElapsedMs = clockMs(),
                                payload = RemoteCommandFailedEvent(
                                    commandId = commandId,
                                    errorCode = ErrorCode.REMOTE_COMMAND_TIMEOUT,
                                    message = errMsg
                                )
                            )
                        )
                    }
                } catch (t: Throwable) {
                    Logger.service.e(TAG, "DACP 串行指令抛出异常: cmd=$command", t)
                }
            }
        }
    }

    private fun processEnvelope(envelope: SessionEventEnvelope<SessionEventPayload>) {
        if (!shouldAcceptEnvelope(envelope)) {
            return
        }

        val output = TriStateTransitionMatrix.reduce(
            ctx = context,
            envelope = envelope,
            trackManager = trackTransactionManager,
            nowMs = clockMs()
        )
        context = output.nextContext
        _snapshot.value = context.snapshot

        for (effect in output.effects) {
            executeEffect(effect)
        }
    }

    private fun shouldAcceptEnvelope(envelope: SessionEventEnvelope<SessionEventPayload>): Boolean {
        return when (envelope.origin) {
            EventOrigin.USB_HARDWARE -> {
                if (envelope.deviceGeneration < latestDeviceGeneration) {
                    Logger.usb.d(
                        TAG,
                        "丢弃过期硬件代数事件: gen=${envelope.deviceGeneration} < $latestDeviceGeneration, payload=${envelope.payload::class.simpleName}"
                    )
                    false
                } else if (envelope.deviceGeneration == latestDeviceGeneration &&
                    envelope.sequence <= lastAcceptedHardwareSequence
                ) {
                    Logger.usb.d(TAG, "丢弃乱序硬件事件: seq=${envelope.sequence}")
                    false
                } else {
                    latestDeviceGeneration = envelope.deviceGeneration
                    lastAcceptedHardwareSequence = envelope.sequence
                    true
                }
            }

            EventOrigin.AIRPLAY, EventOrigin.UPNP -> {
                val activeSid = context.snapshot.sessionId
                if (envelope.payload is StreamSetupEvent) {
                    if (envelope.sessionId < activeSid) {
                        Logger.service.d(TAG, "丢弃旧会话 StreamSetupEvent: sid=${envelope.sessionId} < $activeSid")
                        false
                    } else {
                        lastAcceptedSequenceBySession[envelope.sessionId] = envelope.sequence
                        true
                    }
                } else if (envelope.sessionId == 0L || envelope.sessionId != activeSid) {
                    Logger.service.d(
                        TAG,
                        "丢弃非活跃会话协议事件: origin=${envelope.origin}, sid=${envelope.sessionId}, active=$activeSid, payload=${envelope.payload::class.simpleName}"
                    )
                    false
                } else {
                    val lastSeq = lastAcceptedSequenceBySession[envelope.sessionId] ?: 0L
                    if (envelope.sequence <= lastSeq) {
                        Logger.service.d(
                            TAG,
                            "丢弃乱序/旧序号事件: sid=${envelope.sessionId}, seq=${envelope.sequence} <= $lastSeq"
                        )
                        false
                    } else {
                        lastAcceptedSequenceBySession[envelope.sessionId] = envelope.sequence
                        true
                    }
                }
            }

            EventOrigin.SERVICE, EventOrigin.USER_ACTION -> {
                val activeSid = context.snapshot.sessionId
                val isSessionBoundControl = envelope.payload is RemoteSkipNextEvent ||
                    envelope.payload is RemoteCommandTimeoutEvent ||
                    envelope.payload is DebouncePauseTimeoutEvent ||
                    envelope.payload is SuspendTimeoutEvent
                if (isSessionBoundControl && envelope.sessionId != 0L && envelope.sessionId != activeSid) {
                    Logger.service.d(
                        TAG,
                        "丢弃失效会话的定时/交互事件: sid=${envelope.sessionId}, active=$activeSid"
                    )
                    false
                } else {
                    true
                }
            }
        }
    }

    private fun executeEffect(effect: TransitionEffect) {
        when (effect) {
            is TransitionEffect.ReleaseHardwareDevice -> {
                hardwareBridge.releaseDevice()
            }

            is TransitionEffect.OpenHardwareDevice -> {
                val openRes = hardwareBridge.openDeviceWithCurrentFd(
                    effect.sampleRate,
                    effect.bitDepth,
                    effect.channels
                )
                if (openRes.isSuccess) {
                    val ok = openRes.getOrThrow()
                    context = context.copy(
                        snapshot = context.snapshot.copy(
                            hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                            currentEpoch = ok.epoch,
                            audioPath = context.snapshot.audioPath.copy(
                                dacName = ok.dacName,
                                isExclusive = true
                            ),
                            errors = context.snapshot.errors - ErrorCode.USB_OPEN_FAILED
                        )
                    )
                    _snapshot.value = context.snapshot
                } else {
                    val err = PlaybackError(
                        code = ErrorCode.USB_OPEN_FAILED,
                        message = openRes.exceptionOrNull()?.message ?: "无法独占打开 USB DAC",
                        recoverable = true,
                        timestampMs = clockMs()
                    )
                    context = context.copy(
                        snapshot = context.snapshot.copy(
                            hardwareState = HardwarePipelineState.RELEASED,
                            errors = context.snapshot.errors + (ErrorCode.USB_OPEN_FAILED to err)
                        )
                    )
                    _snapshot.value = context.snapshot
                }
            }

            is TransitionEffect.OpenDeviceOnResume -> {
                val openRes = hardwareBridge.openDeviceWithCurrentFd(
                    effect.sampleRate,
                    effect.bitDepth,
                    effect.channels
                )
                if (openRes.isSuccess) {
                    val ok = openRes.getOrThrow()
                    hardwareBridge.resumeNativeStream()
                    context = context.copy(
                        snapshot = context.snapshot.copy(
                            transportState = TransportPipeState.STREAMING,
                            businessState = BusinessSessionState.PLAYING,
                            hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE,
                            currentEpoch = ok.epoch,
                            audioPath = context.snapshot.audioPath.copy(
                                dacName = ok.dacName,
                                isExclusive = true
                            ),
                            errors = context.snapshot.errors - ErrorCode.USB_OPEN_FAILED,
                            uiProtectionActive = false
                        )
                    )
                    _snapshot.value = context.snapshot
                } else {
                    val err = PlaybackError(
                        code = ErrorCode.USB_OPEN_FAILED,
                        message = openRes.exceptionOrNull()?.message ?: "恢复播放时重开 USB DAC 失败",
                        recoverable = true,
                        timestampMs = clockMs()
                    )
                    context = context.copy(
                        snapshot = context.snapshot.copy(
                            businessState = BusinessSessionState.PAUSED,
                            hardwareState = HardwarePipelineState.RELEASED,
                            currentEpoch = effect.fallbackPausedEpoch,
                            errors = context.snapshot.errors + (ErrorCode.USB_OPEN_FAILED to err)
                        )
                    )
                    _snapshot.value = context.snapshot
                }
            }

            is TransitionEffect.ResumeNativeOutput -> {
                hardwareBridge.resumeNativeStream()
            }

            is TransitionEffect.RequestUsbPermission -> {
                hardwareBridge.requestUsbPermission(effect.deviceName)
            }

            is TransitionEffect.NotifyPreemptedProtocol -> {
                onSessionTerminated(effect.protocol, effect.sessionId)
            }

            is TransitionEffect.EnqueueDacpCommand -> {
                dacpCommandChannel.trySend(effect.command to effect.commandId)
            }

            is TransitionEffect.ScheduleDebouncePause -> {
                debouncePauseJob?.cancel()
                val sid = context.snapshot.sessionId
                debouncePauseJob = scope.launch {
                    delay(DEBOUNCE_PAUSE_MS)
                    dispatch(
                        SessionEventEnvelope(
                            origin = EventOrigin.SERVICE,
                            sessionId = sid,
                            deviceGeneration = latestDeviceGeneration,
                            sequence = allocateSequence(),
                            timestampElapsedMs = clockMs(),
                            payload = DebouncePauseTimeoutEvent(effect.token)
                        )
                    )
                }
            }

            is TransitionEffect.CancelDebouncePause -> {
                debouncePauseJob?.cancel()
                debouncePauseJob = null
            }

            is TransitionEffect.ScheduleUiProtection -> {
                uiProtectionJob?.cancel()
                val sid = context.snapshot.sessionId
                uiProtectionJob = scope.launch {
                    delay(UI_PROTECTION_WINDOW_MS)
                    dispatch(
                        SessionEventEnvelope(
                            origin = EventOrigin.SERVICE,
                            sessionId = sid,
                            deviceGeneration = latestDeviceGeneration,
                            sequence = allocateSequence(),
                            timestampElapsedMs = clockMs(),
                            payload = UiProtectionTimeoutEvent(effect.token)
                        )
                    )
                }
            }

            is TransitionEffect.ScheduleHardwareIdleTimeout -> {
                hardwareIdleTimeoutJob?.cancel()
                hardwareIdleTimeoutJob = scope.launch {
                    delay(HARDWARE_IDLE_RELEASE_MS)
                    dispatch(
                        SessionEventEnvelope(
                            origin = EventOrigin.SERVICE,
                            sessionId = 0L,
                            deviceGeneration = latestDeviceGeneration,
                            sequence = allocateSequence(),
                            timestampElapsedMs = clockMs(),
                            payload = HardwareIdleTimeoutEvent
                        )
                    )
                }
            }

            is TransitionEffect.CancelHardwareIdleTimeout -> {
                hardwareIdleTimeoutJob?.cancel()
                hardwareIdleTimeoutJob = null
            }

            is TransitionEffect.ScheduleSuspendTimeout -> {
                suspendTimeoutJob?.cancel()
                suspendTimeoutJob = scope.launch {
                    delay(SUSPEND_TIMEOUT_MS)
                    dispatch(
                        SessionEventEnvelope(
                            origin = EventOrigin.SERVICE,
                            sessionId = effect.sessionId,
                            deviceGeneration = latestDeviceGeneration,
                            sequence = allocateSequence(),
                            timestampElapsedMs = clockMs(),
                            payload = SuspendTimeoutEvent
                        )
                    )
                }
            }

            is TransitionEffect.CancelSuspendTimeout -> {
                suspendTimeoutJob?.cancel()
                suspendTimeoutJob = null
            }

            is TransitionEffect.ScheduleRemoteCommandTimeout -> {
                remoteCommandTimeoutJob?.cancel()
                val sid = context.snapshot.sessionId
                remoteCommandTimeoutJob = scope.launch {
                    delay(REMOTE_COMMAND_TIMEOUT_MS)
                    dispatch(
                        SessionEventEnvelope(
                            origin = EventOrigin.SERVICE,
                            sessionId = sid,
                            deviceGeneration = latestDeviceGeneration,
                            sequence = allocateSequence(),
                            timestampElapsedMs = clockMs(),
                            payload = RemoteCommandTimeoutEvent(effect.commandId)
                        )
                    )
                }
            }

            is TransitionEffect.CancelRemoteCommandTimeout -> {
                remoteCommandTimeoutJob?.cancel()
                remoteCommandTimeoutJob = null
            }
        }
    }

    companion object {
        private const val TAG = "SessionCoord"

        /** 💡 被动断流/显式暂停防抖窗口：吸收切歌前导静音与缓冲切换瞬态 */
        const val DEBOUNCE_PAUSE_MS = 220L

        /** 💡 遥控切歌 UI 保护窗口：连续快速切歌保持焦点及界面视觉连贯 */
        const val UI_PROTECTION_WINDOW_MS = 800L

        /** 💡 DACP 单项请求超时期限：防止发端无响应导致 UI 卡死于 TRANSITIONING */
        const val REMOTE_COMMAND_TIMEOUT_MS = 5_000L

        /** 💡 停止后硬件让渡延迟：释放独占声卡重绑内核驱动 */
        const val HARDWARE_IDLE_RELEASE_MS = 10_000L

        /** 💡 暂停挂起回收延迟：10 分钟无恢复后彻底清空会话、凭据及低延迟锁 */
        const val SUSPEND_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
