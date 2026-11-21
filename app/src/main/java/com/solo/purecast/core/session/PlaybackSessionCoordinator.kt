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
    fun openDeviceWithCurrentFd(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        forceNewEpoch: Boolean = false
    ): Result<HardwareOpenSuccess>
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
    private val onProtocolResumeAfterReopen: (StreamProtocol, Long, Long) -> ProtocolResumeReopenResult =
        { _, _, _ -> ProtocolResumeReopenResult.STREAMING_READY },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class DacpCommandItem(
        val command: RemoteCommandType,
        val commandId: Long,
        val sessionId: Long
    )

    private val sessionIdGenerator = AtomicLong(0L)
    private val sequenceGenerator = AtomicLong(0L)
    private val commandIdGenerator = AtomicLong(0L)

    private val trackTransactionManager = TrackTransactionManager()
    private val eventChannel = Channel<SessionEventEnvelope<SessionEventPayload>>(Channel.UNLIMITED)
    private val dacpCommandChannel = Channel<DacpCommandItem>(Channel.UNLIMITED)

    private val _snapshot = MutableStateFlow(PlayerSessionSnapshot())
    val snapshot: StateFlow<PlayerSessionSnapshot> = _snapshot.asStateFlow()

    private var context = TransitionContext()
    private val lastAcceptedSequenceBySession = HashMap<Long, Long>()
    private var latestDeviceGeneration: Long = 0L
    private var lastAcceptedHardwareSequence: Long = 0L

    @Volatile
    private var idleTimeoutMillis: Long = HARDWARE_IDLE_RELEASE_MS

    private val timerLock = Any()
    private var debouncePauseJob: Job? = null
    private var uiProtectionJob: Job? = null
    private var hardwareIdleTimeoutJob: Job? = null
    private var suspendTimeoutJob: Job? = null
    private var remoteCommandTimeoutJob: Job? = null
    private var coverGraceJob: Job? = null
    private var transitionTimeoutJob: Job? = null
    private val transientErrorDismissJobs = java.util.concurrent.ConcurrentHashMap<ErrorCode, Job>()

    init {
        startEventLoop()
        startDacpSerialLoop()
    }

    /** 释放协调器定时器与通道，停止事件循环（线程安全，可从主线程 onDestroy 调用） */
    fun shutdown() {
        synchronized(timerLock) {
            debouncePauseJob?.cancel()
            debouncePauseJob = null
            uiProtectionJob?.cancel()
            uiProtectionJob = null
            hardwareIdleTimeoutJob?.cancel()
            hardwareIdleTimeoutJob = null
            suspendTimeoutJob?.cancel()
            suspendTimeoutJob = null
            remoteCommandTimeoutJob?.cancel()
            remoteCommandTimeoutJob = null
            coverGraceJob?.cancel()
            coverGraceJob = null
            transitionTimeoutJob?.cancel()
            transitionTimeoutJob = null
            transientErrorDismissJobs.values.forEach { it.cancel() }
            transientErrorDismissJobs.clear()
        }
        eventChannel.close()
        dacpCommandChannel.close()
    }

    /** 分配全局单调会话身份 ID */
    fun allocateSessionId(): Long = sessionIdGenerator.incrementAndGet()

    /** 分配全局单调事件序号 */
    fun allocateSequence(): Long = sequenceGenerator.incrementAndGet()

    /** 分配遥控指令追踪 ID */
    fun allocateCommandId(): Long = commandIdGenerator.incrementAndGet()

    /**
     * 💡 配置停止播放后的 USB DAC 空闲让渡延迟（0 秒表示常驻独占不自动归还）
     */
    fun setIdleTimeoutSeconds(seconds: Int) {
        idleTimeoutMillis = if (seconds <= 0) 0L else seconds.coerceAtLeast(3) * 1000L
        Logger.service.i(TAG, "更新空闲声卡让渡时间: ${idleTimeoutMillis}ms (raw=${seconds}s)")
    }

    /**
     * 💡 入队不改变业务状态机的轻量 DACP 串行指令（如本机主动 Pause/Resume 通知 iOS）
     */
    fun sendDacpFireAndForget(command: RemoteCommandType) {
        dacpCommandChannel.trySend(DacpCommandItem(command, 0L, context.snapshot.sessionId))
    }

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
                // 💡 保护单线程事件循环：任何单个事件归约或副作用抛出非致命异常时绝不终止消费协程
                try {
                    processEnvelope(envelope)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Logger.service.e(
                        TAG,
                        "协调器事件循环处理异常 (sid=${envelope.sessionId}, payload=${envelope.payload::class.simpleName})",
                        t
                    )
                }
            }
        }
    }

    private fun startDacpSerialLoop() {
        scope.launch {
            for (item in dacpCommandChannel) {
                val (command, commandId, capturedSessionId) = item
                try {
                    val res = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                        dacpCommandSender(command, commandId)
                    } ?: Result.failure(java.util.concurrent.TimeoutException("DACP 指令等待网络响应超时 (5000ms)"))
                    if (res.isFailure) {
                        val errMsg = res.exceptionOrNull()?.message ?: "DACP 指令执行失败"
                        Logger.service.w(TAG, "DACP 串行指令执行失败: cmd=$command, id=$commandId, err=$errMsg")
                        if (commandId > 0L) {
                            dispatch(
                                SessionEventEnvelope(
                                    origin = EventOrigin.AIRPLAY,
                                    sessionId = capturedSessionId,
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
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Logger.service.e(TAG, "DACP 串行指令抛出异常: cmd=$command", t)
                    if (commandId > 0L) {
                        dispatch(
                            SessionEventEnvelope(
                                origin = EventOrigin.AIRPLAY,
                                sessionId = capturedSessionId,
                                deviceGeneration = latestDeviceGeneration,
                                sequence = allocateSequence(),
                                timestampElapsedMs = clockMs(),
                                payload = RemoteCommandFailedEvent(
                                    commandId = commandId,
                                    errorCode = ErrorCode.REMOTE_COMMAND_TIMEOUT,
                                    message = t.message ?: "DACP 指令异常"
                                )
                            )
                        )
                    }
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
        if (context.snapshot.sessionId == 0L) {
            lastAcceptedSequenceBySession.clear()
        }
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
                        lastAcceptedSequenceBySession.keys.removeIf { it != envelope.sessionId }
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
                // 💡 修复 Finding 2: SERVICE 与 USER_ACTION 事件携带具体会话 ID 时必须严格绑定当前活跃会话，
                // 若 sessionId != 0L 且不等于当前 activeSid，说明属于已抢占、已销毁或前序旧会话，坚决丢弃，杜绝跨会话状态污染！
                if (envelope.sessionId != 0L && envelope.sessionId != activeSid) {
                    Logger.service.d(
                        TAG,
                        "丢弃非活跃/已过期会话的系统与交互事件: origin=${envelope.origin}, sid=${envelope.sessionId}, active=$activeSid, payload=${envelope.payload::class.simpleName}"
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
                openDeviceAndUpdateSnapshot(
                    sampleRate = effect.sampleRate,
                    bitDepth = effect.bitDepth,
                    channels = effect.channels,
                    fallbackErrorMessage = "无法独占打开 USB DAC",
                    onSuccess = { snap, ok ->
                        snap.copy(
                            hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                            currentEpoch = ok.epoch,
                            audioPath = snap.audioPath.copy(
                                dacName = ok.dacName,
                                isExclusive = true
                            ),
                            errors = snap.errors - ErrorCode.USB_OPEN_FAILED
                        )
                    },
                    onFailure = { snap, err ->
                        snap.copy(
                            hardwareState = HardwarePipelineState.RELEASED,
                            errors = snap.errors + (ErrorCode.USB_OPEN_FAILED to err)
                        )
                    }
                )
            }

            is TransitionEffect.OpenDeviceOnResume -> {
                openDeviceAndUpdateSnapshot(
                    sampleRate = effect.sampleRate,
                    bitDepth = effect.bitDepth,
                    channels = effect.channels,
                    fallbackErrorMessage = "恢复播放时重开 USB DAC 失败",
                    onSuccess = { snap, ok ->
                        val resumeStatus = onProtocolResumeAfterReopen(effect.protocol, effect.sessionId, ok.epoch)
                        when (resumeStatus) {
                            ProtocolResumeReopenResult.STREAMING_READY -> {
                                hardwareBridge.resumeNativeStream()
                                snap.copy(
                                    transportState = TransportPipeState.STREAMING,
                                    businessState = BusinessSessionState.PLAYING,
                                    hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE,
                                    currentEpoch = ok.epoch,
                                    audioPath = snap.audioPath.copy(
                                        dacName = ok.dacName,
                                        isExclusive = true
                                    ),
                                    errors = snap.errors - ErrorCode.USB_OPEN_FAILED,
                                    uiProtectionActive = false
                                )
                            }

                            ProtocolResumeReopenResult.RESTARTING -> {
                                // 💡 P1-4 修复：UPnP 重新拉流进入 TRANSITIONING，挂载 5s 过渡态看门狗防止无数据卡死
                                synchronized(timerLock) {
                                    transitionTimeoutJob = rescheduleServiceTimer(
                                        previous = transitionTimeoutJob,
                                        delayMs = TRANSITION_TIMEOUT_MS,
                                        sessionId = snap.sessionId,
                                        payload = TransitionTimeoutEvent(snap.sessionId)
                                    )
                                }
                                snap.copy(
                                    transportState = TransportPipeState.PREPARING,
                                    businessState = BusinessSessionState.TRANSITIONING,
                                    hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                                    currentEpoch = ok.epoch,
                                    audioPath = snap.audioPath.copy(
                                        dacName = ok.dacName,
                                        isExclusive = true
                                    ),
                                    errors = snap.errors - ErrorCode.USB_OPEN_FAILED,
                                    uiProtectionActive = false
                                )
                            }

                            ProtocolResumeReopenResult.PRODUCER_DEAD -> {
                                synchronized(timerLock) {
                                    suspendTimeoutJob = rescheduleServiceTimer(
                                        previous = suspendTimeoutJob,
                                        delayMs = SUSPEND_TIMEOUT_MS,
                                        sessionId = snap.sessionId,
                                        payload = SuspendTimeoutEvent
                                    )
                                }
                                snap.copy(
                                    transportState = TransportPipeState.INTERRUPTED,
                                    businessState = BusinessSessionState.PAUSED,
                                    hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
                                    currentEpoch = ok.epoch,
                                    audioPath = snap.audioPath.copy(
                                        dacName = ok.dacName,
                                        isExclusive = true
                                    ),
                                    errors = snap.errors - ErrorCode.USB_OPEN_FAILED,
                                    uiProtectionActive = false
                                )
                            }
                        }
                    },
                    onFailure = { snap, err ->
                        // 💡 P1-4 修复：恢复打开硬件失败回退 PAUSED，重新挂载 10 分钟挂起超时定时器
                        synchronized(timerLock) {
                            suspendTimeoutJob = rescheduleServiceTimer(
                                previous = suspendTimeoutJob,
                                delayMs = SUSPEND_TIMEOUT_MS,
                                sessionId = snap.sessionId,
                                payload = SuspendTimeoutEvent
                            )
                        }
                        snap.copy(
                            businessState = BusinessSessionState.PAUSED,
                            hardwareState = HardwarePipelineState.RELEASED,
                            currentEpoch = effect.fallbackPausedEpoch,
                            errors = snap.errors + (ErrorCode.USB_OPEN_FAILED to err)
                        )
                    }
                )
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
                dacpCommandChannel.trySend(DacpCommandItem(effect.command, effect.commandId, context.snapshot.sessionId))
            }

            is TransitionEffect.ScheduleDebouncePause -> {
                synchronized(timerLock) {
                    debouncePauseJob = rescheduleServiceTimer(
                        previous = debouncePauseJob,
                        delayMs = DEBOUNCE_PAUSE_MS,
                        sessionId = context.snapshot.sessionId,
                        payload = DebouncePauseTimeoutEvent(effect.token)
                    )
                }
            }

            is TransitionEffect.CancelDebouncePause -> {
                synchronized(timerLock) {
                    debouncePauseJob?.cancel()
                    debouncePauseJob = null
                }
            }

            is TransitionEffect.ScheduleUiProtection -> {
                synchronized(timerLock) {
                    uiProtectionJob = rescheduleServiceTimer(
                        previous = uiProtectionJob,
                        delayMs = UI_PROTECTION_WINDOW_MS,
                        sessionId = context.snapshot.sessionId,
                        payload = UiProtectionTimeoutEvent(effect.token)
                    )
                }
            }

            is TransitionEffect.ScheduleHardwareIdleTimeout -> {
                val timeoutMs = idleTimeoutMillis
                synchronized(timerLock) {
                    hardwareIdleTimeoutJob = if (timeoutMs <= 0L) {
                        hardwareIdleTimeoutJob?.cancel()
                        null
                    } else {
                        rescheduleServiceTimer(
                            previous = hardwareIdleTimeoutJob,
                            delayMs = timeoutMs,
                            sessionId = 0L,
                            payload = HardwareIdleTimeoutEvent
                        )
                    }
                }
            }

            is TransitionEffect.CancelHardwareIdleTimeout -> {
                synchronized(timerLock) {
                    hardwareIdleTimeoutJob?.cancel()
                    hardwareIdleTimeoutJob = null
                }
            }

            is TransitionEffect.ScheduleSuspendTimeout -> {
                synchronized(timerLock) {
                    suspendTimeoutJob = rescheduleServiceTimer(
                        previous = suspendTimeoutJob,
                        delayMs = SUSPEND_TIMEOUT_MS,
                        sessionId = effect.sessionId,
                        payload = SuspendTimeoutEvent
                    )
                }
            }

            is TransitionEffect.CancelSuspendTimeout -> {
                synchronized(timerLock) {
                    suspendTimeoutJob?.cancel()
                    suspendTimeoutJob = null
                }
            }

            is TransitionEffect.ScheduleRemoteCommandTimeout -> {
                synchronized(timerLock) {
                    remoteCommandTimeoutJob = rescheduleServiceTimer(
                        previous = remoteCommandTimeoutJob,
                        delayMs = REMOTE_COMMAND_TIMEOUT_MS,
                        sessionId = context.snapshot.sessionId,
                        payload = RemoteCommandTimeoutEvent(effect.commandId)
                    )
                }
            }

            is TransitionEffect.CancelRemoteCommandTimeout -> {
                synchronized(timerLock) {
                    remoteCommandTimeoutJob?.cancel()
                    remoteCommandTimeoutJob = null
                }
            }

            is TransitionEffect.ScheduleCoverGraceTimeout -> {
                synchronized(timerLock) {
                    coverGraceJob?.cancel()
                    coverGraceJob = scope.launch {
                        delay(1200L)
                        dispatch(
                            SessionEventEnvelope(
                                origin = EventOrigin.SERVICE,
                                sessionId = context.snapshot.sessionId,
                                deviceGeneration = latestDeviceGeneration,
                                sequence = allocateSequence(),
                                timestampElapsedMs = clockMs(),
                                payload = CoverGraceTimeoutEvent(effect.trackGeneration)
                            )
                        )
                    }
                }
            }

            is TransitionEffect.CancelCoverGraceTimeout -> {
                synchronized(timerLock) {
                    coverGraceJob?.cancel()
                    coverGraceJob = null
                }
            }

            is TransitionEffect.ScheduleTransientErrorDismiss -> {
                synchronized(timerLock) {
                    transientErrorDismissJobs[effect.code]?.cancel()
                    transientErrorDismissJobs[effect.code] = scope.launch {
                        delay(3500L)
                        dispatch(
                            SessionEventEnvelope(
                                origin = EventOrigin.SERVICE,
                                sessionId = context.snapshot.sessionId,
                                deviceGeneration = latestDeviceGeneration,
                                sequence = allocateSequence(),
                                timestampElapsedMs = clockMs(),
                                payload = ErrorClearedEvent(effect.code)
                            )
                        )
                    }
                }
            }

            is TransitionEffect.CancelTransientErrorDismiss -> {
                synchronized(timerLock) {
                    transientErrorDismissJobs.remove(effect.code)?.cancel()
                }
            }

            is TransitionEffect.ScheduleTransitionTimeout -> {
                synchronized(timerLock) {
                    transitionTimeoutJob = rescheduleServiceTimer(
                        previous = transitionTimeoutJob,
                        delayMs = TRANSITION_TIMEOUT_MS,
                        sessionId = effect.sessionId,
                        payload = TransitionTimeoutEvent(effect.sessionId)
                    )
                }
            }

            is TransitionEffect.CancelTransitionTimeout -> {
                synchronized(timerLock) {
                    transitionTimeoutJob?.cancel()
                    transitionTimeoutJob = null
                }
            }
        }
    }

    private fun openDeviceAndUpdateSnapshot(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        fallbackErrorMessage: String,
        onSuccess: (PlayerSessionSnapshot, HardwareOpenSuccess) -> PlayerSessionSnapshot,
        onFailure: (PlayerSessionSnapshot, PlaybackError) -> PlayerSessionSnapshot
    ) {
        val openRes = hardwareBridge.openDeviceWithCurrentFd(sampleRate, bitDepth, channels)
        val nextSnapshot = if (openRes.isSuccess) {
            onSuccess(context.snapshot, openRes.getOrThrow())
        } else {
            val err = PlaybackError(
                code = ErrorCode.USB_OPEN_FAILED,
                message = openRes.exceptionOrNull()?.message ?: fallbackErrorMessage,
                recoverable = true,
                timestampMs = clockMs()
            )
            onFailure(context.snapshot, err)
        }
        context = context.copy(snapshot = nextSnapshot)
        _snapshot.value = nextSnapshot
    }

    private fun rescheduleServiceTimer(
        previous: Job?,
        delayMs: Long,
        sessionId: Long,
        payload: SessionEventPayload
    ): Job {
        previous?.cancel()
        return scope.launch {
            delay(delayMs)
            dispatch(
                SessionEventEnvelope(
                    origin = EventOrigin.SERVICE,
                    sessionId = sessionId,
                    deviceGeneration = latestDeviceGeneration,
                    sequence = allocateSequence(),
                    timestampElapsedMs = clockMs(),
                    payload = payload
                )
            )
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

        /** 💡 过渡态看门狗超时期限：防止 SETUP/FLUSH 后无数据流入导致 UI 卡死于 TRANSITIONING (R4) */
        const val TRANSITION_TIMEOUT_MS = 5_000L

        /** 💡 停止后硬件让渡延迟：释放独占声卡重绑内核驱动 */
        const val HARDWARE_IDLE_RELEASE_MS = 10_000L

        /** 💡 暂停挂起回收延迟：10 分钟无恢复后彻底清空会话、凭据及低延迟锁 */
        const val SUSPEND_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
