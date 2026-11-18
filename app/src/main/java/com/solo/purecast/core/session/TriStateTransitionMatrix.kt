package com.solo.purecast.core.session

import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.PlaybackError
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TransportPipeState

/**
 * 💡 矩阵内部运行时可变辅助上下文（仅由 `PlaybackSessionCoordinator` 单线程消费协程持有）
 */
internal data class TransitionContext(
    val snapshot: PlayerSessionSnapshot = PlayerSessionSnapshot(),
    val pendingDebounceToken: Long = 0L,
    val activeUiProtectionToken: Long = 0L,
    val activeRemoteCommandId: Long = 0L,
    val preCommandBusinessState: BusinessSessionState = BusinessSessionState.PLAYING
)

/**
 * 💡 纯状态转换产出的副作用意图（由 `PlaybackSessionCoordinator` 异步/外部执行，与核心矩阵解耦）
 */
internal sealed interface TransitionEffect {
    data object ReleaseHardwareDevice : TransitionEffect
    data class OpenHardwareDevice(val sampleRate: Int, val bitDepth: Int, val channels: Int) : TransitionEffect
    data object ResumeNativeOutput : TransitionEffect
    data class RequestUsbPermission(val deviceName: String) : TransitionEffect
    data class NotifyPreemptedProtocol(val protocol: StreamProtocol, val sessionId: Long) : TransitionEffect
    data class EnqueueDacpCommand(val command: RemoteCommandType, val commandId: Long) : TransitionEffect
    data class ScheduleDebouncePause(val token: Long) : TransitionEffect
    data object CancelDebouncePause : TransitionEffect
    data class ScheduleUiProtection(val token: Long) : TransitionEffect
    data object ScheduleHardwareIdleTimeout : TransitionEffect
    data object CancelHardwareIdleTimeout : TransitionEffect
    data class ScheduleSuspendTimeout(val sessionId: Long) : TransitionEffect
    data object CancelSuspendTimeout : TransitionEffect
    data class ScheduleRemoteCommandTimeout(val commandId: Long) : TransitionEffect
    data object CancelRemoteCommandTimeout : TransitionEffect
    data class ScheduleCoverGraceTimeout(val trackGeneration: Long) : TransitionEffect
    data object CancelCoverGraceTimeout : TransitionEffect
    data class ScheduleTransitionTimeout(val sessionId: Long) : TransitionEffect
    data object CancelTransitionTimeout : TransitionEffect
    data class ScheduleTransientErrorDismiss(val code: ErrorCode, val timestampMs: Long) : TransitionEffect
    data class CancelTransientErrorDismiss(val code: ErrorCode) : TransitionEffect
    data class OpenDeviceOnResume(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val fallbackPausedEpoch: Long,
        val protocol: StreamProtocol = StreamProtocol.NONE,
        val sessionId: Long = 0L
    ) : TransitionEffect
}

internal data class TransitionOutput(
    val nextContext: TransitionContext,
    val effects: List<TransitionEffect>
)

/**
 * 💡 三态正交纯状态转换矩阵，将状态归约与副作用意图从调度循环解耦。
 */
internal object TriStateTransitionMatrix {

    fun reduce(
        ctx: TransitionContext,
        envelope: SessionEventEnvelope<SessionEventPayload>,
        trackManager: TrackTransactionManager,
        nowMs: Long
    ): TransitionOutput {
        val snap = ctx.snapshot
        val effects = mutableListOf<TransitionEffect>()

        when (val ev = envelope.payload) {
            is StreamSetupEvent -> {
                // 💡 仅在真正存在不同协议且活跃的会话时方触发跨协议抢占通知；同协议新会话严禁自相残杀误关自身网络连接
                val isCrossProtocolPreempting = snap.sessionId != 0L &&
                    snap.sessionId != envelope.sessionId &&
                    snap.activeProtocol != StreamProtocol.NONE &&
                    snap.activeProtocol != ev.protocol
                val oldProtocol = snap.activeProtocol
                val oldSessionId = snap.sessionId

                val initialMeta = ev.initialMetadata
                val hasCustomInitial = initialMeta != null &&
                    initialMeta.title.isNotEmpty() &&
                    initialMeta.title != "AirPlay 音频直通"

                // 💡 修复 Finding 9: 续播状态继承仅限同协议、处于 PAUSED 态且未明确指定不同新歌名的情况
                // 1) 若传入了明确的不同新歌名 (hasCustomInitial && initialMeta.title != snap.metadata.title)，判定为换歌，不继承进度与元数据；
                // 2) 若为占位/空元数据或相同歌名，判定为同曲目续播，继承元数据与播放进度。
                val isSameTrack = if (hasCustomInitial) {
                    initialMeta.title == snap.metadata.title
                } else {
                    true
                }
                val isResumingPausedSession = snap.activeProtocol == ev.protocol &&
                    snap.sessionId != 0L &&
                    snap.businessState == BusinessSessionState.PAUSED &&
                    isSameTrack

                val resolvedInitialMeta = if (hasCustomInitial) {
                    initialMeta
                } else if (isResumingPausedSession && snap.metadata.title.isNotEmpty() && snap.metadata.title != "AirPlay 音频直通") {
                    snap.metadata
                } else {
                    initialMeta
                }

                val txGen = ev.trackGeneration ?: (trackManager.currentTrackGeneration + 1L)
                val nextMeta = trackManager.resetForNewStream(txGen, resolvedInitialMeta)

                val nextHwState: HardwarePipelineState
                if (snap.hardwareState == HardwarePipelineState.RELEASED) {
                    effects += TransitionEffect.OpenHardwareDevice(ev.sampleRate, ev.bitDepth, ev.channels)
                    nextHwState = HardwarePipelineState.AUDIO_RECONFIGURING
                } else {
                    nextHwState = muteIfExclusive(snap.hardwareState)
                }

                if (isCrossProtocolPreempting) {
                    effects += TransitionEffect.NotifyPreemptedProtocol(oldProtocol, oldSessionId)
                }
                addCancelAllTimers(effects)
                // 💡 R4 契约：为 StreamSetup 进入 TRANSITIONING 注册 5000ms 过渡态看门狗超时
                effects += TransitionEffect.ScheduleTransitionTimeout(envelope.sessionId)

                val nextSnap = snap.copy(
                    sessionId = envelope.sessionId,
                    deviceGeneration = envelope.deviceGeneration,
                    transportState = TransportPipeState.PREPARING,
                    businessState = BusinessSessionState.TRANSITIONING,
                    hardwareState = nextHwState,
                    activeProtocol = ev.protocol,
                    currentEpoch = ev.initialEpoch,
                    trackGeneration = txGen,
                    metadata = nextMeta,
                    audioPath = snap.audioPath.copy(
                        sampleRate = ev.sampleRate,
                        bitDepth = ev.bitDepth,
                        channels = ev.channels,
                        protocol = ev.protocol,
                        codec = ev.codec,
                        isExclusive = isExclusiveActiveOrReconfiguring(nextHwState)
                    ),
                    progressPositionMs = if (isResumingPausedSession) snap.progressPositionMs else 0L,
                    progressDurationMs = nextMeta.durationMs,
                    errors = clearTransientCommandErrors(snap.errors),
                    uiProtectionActive = false
                )
                return TransitionOutput(
                    ctx.copy(
                        snapshot = nextSnap,
                        pendingDebounceToken = 0L,
                        activeRemoteCommandId = 0L
                    ),
                    effects
                )
            }

            is FirstDataPacketEvent -> {
                addCancelAllTimers(effects)
                // 💡 修复 Finding 2: 显式暂停契约捍卫。
                // 若当前处于显式 PAUSED 状态（无论是用户在 TV 端按暂停、或远端协议触发暂停、或暂停状态下的 Seek/换曲推流首包到达），
                // 业务态必须严格保持 PAUSED，硬件态维持 EXCLUSIVE_MUTED 持续泵 0 保活，绝不提前调用 ResumeNativeOutput 偷跑发声！
                // 真正的播放恢复统一交由显式信令触发的 StreamResumeEvent 处理。
                val isAlreadyPaused = snap.businessState == BusinessSessionState.PAUSED
                val nextBiz = if (isAlreadyPaused) BusinessSessionState.PAUSED else BusinessSessionState.PLAYING
                if (isAlreadyPaused) {
                    // 💡 P2 修复：解耦挂起定时器。只要处于 PAUSED 态，首包到达（包含 DAC 处于断开或等待权限时）均必须注册 10 分钟挂起超时
                    effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                }
                val nextHw = if (isDetachedOrPendingPermission(snap.hardwareState)) {
                    snap.hardwareState
                } else if (isAlreadyPaused) {
                    // 💡 若当前处于稳定 PAUSED 态（如 AirPlay / UPnP 暂停态下执行寻道或换曲预充首包写入），
                    // 硬件维持 EXCLUSIVE_MUTED 持续泵 0 保活，绝不提前调用 ResumeNativeOutput，彻底杜绝声音偷跑与界面跳闪！
                    HardwarePipelineState.EXCLUSIVE_MUTED
                } else {
                    effects += TransitionEffect.ResumeNativeOutput
                    HardwarePipelineState.EXCLUSIVE_ACTIVE
                }

                val nextSnap = snap.copy(
                    transportState = TransportPipeState.STREAMING,
                    businessState = nextBiz,
                    hardwareState = nextHw,
                    activeProtocol = ev.protocol,
                    currentEpoch = ev.epoch,
                    audioPath = snap.audioPath.copy(
                        sampleRate = ev.sampleRate,
                        bitDepth = ev.bitDepth,
                        channels = ev.channels,
                        codec = ev.codec,
                        protocol = ev.protocol,
                        isExclusive = isExclusiveActiveOrReconfiguring(nextHw)
                    ),
                    errors = clearTransientCommandErrors(snap.errors),
                    uiProtectionActive = false
                )
                return TransitionOutput(
                    ctx.copy(
                        snapshot = nextSnap,
                        pendingDebounceToken = 0L,
                        activeUiProtectionToken = 0L,
                        activeRemoteCommandId = 0L
                    ),
                    effects
                )
            }

            is TrackSwitchEvent -> {
                effects += TransitionEffect.CancelDebouncePause
                effects += TransitionEffect.CancelHardwareIdleTimeout
                effects += TransitionEffect.CancelSuspendTimeout

                // 💡 修复 Finding 1: 在 AirPlay 中，FLUSH 既用于切歌，也用于暂停和 Seek。
                // 收到 FLUSH 时始终保留当前已播进度，仅在真正收到新曲目的 TrackBoundaryEvent 时才归零进度，杜绝暂停与换代误清零
                val nextPositionMs = snap.progressPositionMs

                // 💡 P0-4 修复：若当前已处于稳定 PAUSED 状态（如 iOS 暂停后发 FLUSH 或暂停态拖动进度条），
                // 必须保持 PAUSED 状态，严禁进入 TRANSITIONING（避免大屏跳回播放中 5 秒），维持挂起定时器
                if (snap.businessState == BusinessSessionState.PAUSED) {
                    effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                    val pausedSnap = snap.copy(
                        transportState = TransportPipeState.INTERRUPTED,
                        hardwareState = muteIfExclusive(snap.hardwareState),
                        currentEpoch = ev.newEpoch,
                        progressPositionMs = nextPositionMs
                    )
                    return TransitionOutput(ctx.copy(snapshot = pausedSnap, pendingDebounceToken = 0L), effects)
                }

                // 💡 R4 契约：为切歌/换代进入 TRANSITIONING 注册 5000ms 过渡态看门狗超时
                effects += TransitionEffect.ScheduleTransitionTimeout(snap.sessionId)

                val nextSnap = snap.copy(
                    transportState = TransportPipeState.INTERRUPTED,
                    businessState = BusinessSessionState.TRANSITIONING,
                    hardwareState = muteIfExclusive(snap.hardwareState),
                    currentEpoch = ev.newEpoch,
                    progressPositionMs = nextPositionMs
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
            }

            is ExplicitPauseEvent -> {
                // 💡 用户在 TV 遥控器/UI 主动点击暂停时立即进入 PAUSED（0ms 响应）；远端协议事件保留 220ms 防抖避免切歌时闪烁
                val isUserAction = envelope.origin == EventOrigin.USER_ACTION
                if (isUserAction) {
                    effects += TransitionEffect.CancelDebouncePause
                    effects += TransitionEffect.CancelRemoteCommandTimeout
                    effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                    val nextSnap = snap.copy(
                        transportState = TransportPipeState.INTERRUPTED,
                        businessState = BusinessSessionState.PAUSED,
                        hardwareState = muteIfExclusive(snap.hardwareState),
                        uiProtectionActive = false
                    )
                    return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
                } else {
                    val token = envelope.sequence
                    effects += TransitionEffect.ScheduleDebouncePause(token)
                    val nextSnap = snap.copy(
                        transportState = TransportPipeState.INTERRUPTED,
                        hardwareState = muteIfExclusive(snap.hardwareState)
                    )
                    return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = token), effects)
                }
            }

            is DebouncePauseTimeoutEvent -> {
                if (ev.debounceToken != ctx.pendingDebounceToken) {
                    return TransitionOutput(ctx, emptyList())
                }
                effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                val nextSnap = snap.copy(
                    businessState = BusinessSessionState.PAUSED,
                    uiProtectionActive = false
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
            }

            is ImplicitPauseEvent -> {
                effects += TransitionEffect.CancelDebouncePause
                effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                val nextSnap = snap.copy(
                    transportState = TransportPipeState.INTERRUPTED,
                    businessState = BusinessSessionState.PAUSED,
                    hardwareState = muteIfExclusive(snap.hardwareState),
                    uiProtectionActive = false,
                    transitionOriginState = null
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
            }

            is TrackCompleteEvent ->
                return transitionToStopped(ctx, effects, ev.newEpoch, scheduleIdleRelease = true)

            is StreamStopEvent ->
                return transitionToStopped(ctx, effects, ev.newEpoch, scheduleIdleRelease = !ev.isPreempted)

            is StreamTeardownEvent -> {
                effects += TransitionEffect.CancelDebouncePause
                effects += TransitionEffect.CancelRemoteCommandTimeout
                val nextHw = muteIfExclusive(snap.hardwareState)
                // 💡 契约修复：若当前业务态已经处于 PAUSED（例如用户或系统触发了显式暂停），即使收到底层 hardStop 信号，
                // 也绝不能退化为 STOPPED 并启动 10s 让渡，必须严格保持 PAUSED 业务态并维持 10 分钟挂起倒计时！
                val shouldKeepPaused = !ev.hardStop || snap.businessState == BusinessSessionState.PAUSED
                return if (!shouldKeepPaused) {
                    effects += TransitionEffect.ScheduleHardwareIdleTimeout
                    val nextSnap = snap.copy(
                        transportState = TransportPipeState.DISCONNECTED,
                        businessState = BusinessSessionState.STOPPED,
                        hardwareState = nextHw,
                        uiProtectionActive = false
                    )
                    TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
                } else {
                    effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                    val nextSnap = snap.copy(
                        transportState = TransportPipeState.DISCONNECTED,
                        businessState = BusinessSessionState.PAUSED,
                        hardwareState = nextHw,
                        uiProtectionActive = false
                    )
                    TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
                }
            }

            is HardwareIdleTimeoutEvent -> {
                if (snap.businessState != BusinessSessionState.STOPPED) {
                    return TransitionOutput(ctx, emptyList())
                }
                return resetSessionToIdle(ctx, trackManager, effects)
            }

            is SuspendTimeoutEvent -> {
                if (snap.businessState != BusinessSessionState.PAUSED) {
                    return TransitionOutput(ctx, emptyList())
                }
                effects += TransitionEffect.NotifyPreemptedProtocol(snap.activeProtocol, snap.sessionId)
                return resetSessionToIdle(ctx, trackManager, effects)
            }

            is TransitionTimeoutEvent -> {
                // 💡 R4 契约：仅在会话仍处于 TRANSITIONING 过渡态且 sessionId 匹配时安全回退稳态
                if (snap.sessionId != ev.sessionId || snap.businessState != BusinessSessionState.TRANSITIONING) {
                    return TransitionOutput(ctx, emptyList())
                }
                effects += TransitionEffect.CancelTransitionTimeout
                // 💡 B-3 防御：区分初始建流超时 (PREPARING) 与 切歌换代超时 (INTERRUPTED)
                // 若在 PREPARING 阶段超时（从未收到过首包有效数据），直接安全停止并启动 10s 硬件空闲让渡释放声卡，杜绝伪暂停占住声卡 10 分钟！
                if (snap.transportState == TransportPipeState.PREPARING) {
                    return transitionToStopped(ctx, effects, snap.currentEpoch, scheduleIdleRelease = true)
                }
                effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                val nextSnap = snap.copy(
                    transportState = TransportPipeState.INTERRUPTED,
                    businessState = BusinessSessionState.PAUSED,
                    hardwareState = muteIfExclusive(snap.hardwareState),
                    uiProtectionActive = false,
                    transitionOriginState = null
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
            }

            is RemoteSkipNextEvent -> {
                if (snap.activeProtocol == StreamProtocol.UPNP_DLNA &&
                    isTrackSkipCommand(ev.commandType)
                ) {
                    val err = PlaybackError(
                        code = ErrorCode.REMOTE_COMMAND_UNSUPPORTED,
                        message = "UPnP 推流由发送端控制曲目列表，不支持电视遥控器切歌",
                        recoverable = true,
                        timestampMs = nowMs
                    )
                    val errEffects = listOf(TransitionEffect.ScheduleTransientErrorDismiss(ErrorCode.REMOTE_COMMAND_UNSUPPORTED, nowMs))
                    return TransitionOutput(
                        ctx.copy(snapshot = snap.copy(errors = snap.errors + (ErrorCode.REMOTE_COMMAND_UNSUPPORTED to err))),
                        errEffects
                    )
                }
                if (snap.activeProtocol != StreamProtocol.AIRPLAY_1) {
                    return TransitionOutput(ctx, emptyList())
                }

                val preState = if (snap.businessState != BusinessSessionState.TRANSITIONING) {
                    snap.businessState
                } else {
                    ctx.preCommandBusinessState
                }
                val cmdId = ev.commandId
                val windowToken = envelope.sequence
                effects += TransitionEffect.EnqueueDacpCommand(ev.commandType, cmdId)
                effects += TransitionEffect.ScheduleUiProtection(windowToken)
                effects += TransitionEffect.ScheduleRemoteCommandTimeout(cmdId)

                val nextSnap = snap.copy(
                    businessState = BusinessSessionState.TRANSITIONING,
                    hardwareState = muteIfExclusive(snap.hardwareState),
                    uiProtectionActive = true,
                    errors = clearTransientCommandErrors(snap.errors),
                    transitionOriginState = preState
                )
                return TransitionOutput(
                    ctx.copy(
                        snapshot = nextSnap,
                        activeUiProtectionToken = windowToken,
                        activeRemoteCommandId = cmdId,
                        preCommandBusinessState = preState
                    ),
                    effects
                )
            }

            is UiProtectionTimeoutEvent -> {
                if (ev.windowToken != ctx.activeUiProtectionToken) {
                    return TransitionOutput(ctx, emptyList())
                }
                return TransitionOutput(
                    ctx.copy(
                        snapshot = snap.copy(uiProtectionActive = false),
                        activeUiProtectionToken = 0L
                    ),
                    emptyList()
                )
            }

            is RemoteCommandTimeoutEvent -> return rollbackRemoteCommandFailure(
                ctx = ctx,
                commandId = ev.commandId,
                errorCode = ErrorCode.REMOTE_COMMAND_TIMEOUT,
                message = "远端设备未响应切歌指令",
                nowMs = nowMs,
                cancelTimeout = false
            )

            is RemoteCommandFailedEvent -> return rollbackRemoteCommandFailure(
                ctx = ctx,
                commandId = ev.commandId,
                errorCode = ev.errorCode,
                message = ev.message,
                nowMs = nowMs,
                cancelTimeout = true
            )

            is StreamResumeEvent -> {
                if (snap.businessState == BusinessSessionState.IDLE ||
                    snap.businessState == BusinessSessionState.STOPPED
                ) {
                    return TransitionOutput(ctx, emptyList())
                }
                addCancelAllTimers(effects)
                return when {
                    isDetachedOrPendingPermission(snap.hardwareState) -> {
                        val nextSnap = snap.copy(businessState = BusinessSessionState.PAUSED)
                        TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
                    }
                    snap.hardwareState == HardwarePipelineState.RELEASED -> {
                        effects += TransitionEffect.OpenDeviceOnResume(
                            sampleRate = snap.audioPath.sampleRate,
                            bitDepth = snap.audioPath.bitDepth,
                            channels = snap.audioPath.channels,
                            fallbackPausedEpoch = ev.resumedEpoch,
                            protocol = snap.activeProtocol,
                            sessionId = snap.sessionId
                        )
                        TransitionOutput(ctx.copy(pendingDebounceToken = 0L), effects)
                    }
                    else -> {
                        effects += TransitionEffect.ResumeNativeOutput
                        val nextSnap = snap.copy(
                            transportState = TransportPipeState.STREAMING,
                            businessState = BusinessSessionState.PLAYING,
                            hardwareState = HardwarePipelineState.EXCLUSIVE_ACTIVE,
                            currentEpoch = ev.resumedEpoch,
                            audioPath = snap.audioPath.copy(isExclusive = true, protocol = ev.protocol),
                            uiProtectionActive = false,
                            transitionOriginState = null
                        )
                        TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
                    }
                }
            }

            is HardwareAttachedEvent -> {
                val nextHw = if (ev.hasPermission) {
                    HardwarePipelineState.RELEASED
                } else {
                    effects += TransitionEffect.RequestUsbPermission(ev.deviceName)
                    HardwarePipelineState.ATTACHED
                }
                val nextErrors = if (ev.hasPermission) {
                    clearUsbErrors(snap.errors)
                } else {
                    snap.errors - ErrorCode.USB_DEVICE_DETACHED
                }
                val nextSnap = snap.copy(
                    deviceGeneration = envelope.deviceGeneration,
                    hardwareState = nextHw,
                    audioPath = snap.audioPath.copy(
                        isDacConnected = true,
                        dacName = ev.deviceName,
                        isExclusive = false
                    ),
                    errors = nextErrors
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), effects)
            }

            is UsbPermissionRequestedEvent -> {
                return TransitionOutput(
                    ctx.copy(snapshot = snap.copy(hardwareState = HardwarePipelineState.PERMISSION_PENDING)),
                    emptyList()
                )
            }

            is UsbPermissionGrantedEvent -> {
                val wasExclusive = isExclusiveActiveOrReconfiguring(snap.hardwareState)
                val nextHw = if (!ev.granted) {
                    HardwarePipelineState.PERMISSION_PENDING
                } else if (wasExclusive) {
                    snap.hardwareState // 💡 防御：当前已处于独占或切频态时，授权回执保持独占态，严防误降级为 RELEASED
                } else {
                    HardwarePipelineState.RELEASED
                }
                val nextExclusive = ev.granted && wasExclusive
                val nextErrors = if (ev.granted) {
                    clearUsbErrors(snap.errors)
                } else {
                    snap.errors + (
                        ErrorCode.USB_PERMISSION_DENIED to PlaybackError(
                            code = ErrorCode.USB_PERMISSION_DENIED,
                            message = "USB 音频设备权限未授予: ${ev.deviceName}",
                            recoverable = true,
                            timestampMs = nowMs
                        )
                    )
                }
                val nextSnap = snap.copy(
                    deviceGeneration = envelope.deviceGeneration,
                    hardwareState = nextHw,
                    audioPath = snap.audioPath.copy(
                        isDacConnected = true,
                        dacName = ev.deviceName,
                        isExclusive = nextExclusive
                    ),
                    errors = nextErrors
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
            }

            is HardwareDetachedEvent -> {
                effects += TransitionEffect.ReleaseHardwareDevice
                val nextBiz = if (snap.businessState == BusinessSessionState.PLAYING ||
                    snap.businessState == BusinessSessionState.TRANSITIONING
                ) {
                    effects += TransitionEffect.CancelDebouncePause
                    effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                    BusinessSessionState.PAUSED
                } else {
                    snap.businessState
                }
                val detachErr = PlaybackError(
                    code = ErrorCode.USB_DEVICE_DETACHED,
                    message = "外置 USB DAC 已断开 (${ev.reason})",
                    recoverable = true,
                    timestampMs = nowMs
                )
                val nextSnap = snap.copy(
                    deviceGeneration = envelope.deviceGeneration,
                    businessState = nextBiz,
                    hardwareState = HardwarePipelineState.DISCONNECTED,
                    audioPath = snap.audioPath.copy(
                        isDacConnected = false,
                        isExclusive = false,
                        dacName = AudioPathState.DEFAULT_DAC_NAME
                    ),
                    errors = snap.errors + (ErrorCode.USB_DEVICE_DETACHED to detachErr),
                    uiProtectionActive = false
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
            }

            is HardwareReconfiguredEvent -> {
                val nextHw = if (snap.hardwareState == HardwarePipelineState.EXCLUSIVE_ACTIVE) {
                    HardwarePipelineState.EXCLUSIVE_ACTIVE
                } else {
                    HardwarePipelineState.EXCLUSIVE_MUTED
                }
                val nextSnap = snap.copy(
                    hardwareState = nextHw,
                    currentEpoch = ev.epoch,
                    audioPath = snap.audioPath.copy(
                        sampleRate = ev.sampleRate,
                        bitDepth = ev.bitDepth,
                        channels = ev.channels,
                        isExclusive = true
                    ),
                    errors = snap.errors - ErrorCode.USB_RECONFIGURE_FAILED - ErrorCode.USB_OPEN_FAILED
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
            }

            is HardwareReconfigureFailedEvent -> {
                val err = PlaybackError(
                    code = ev.errorCode,
                    message = ev.message,
                    recoverable = true,
                    timestampMs = nowMs
                )
                val nextSnap = snap.copy(
                    hardwareState = ev.fallbackState,
                    audioPath = snap.audioPath.copy(
                        isExclusive = isExclusiveActiveOrReconfiguring(ev.fallbackState)
                    ),
                    errors = snap.errors + (ev.errorCode to err)
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
            }

            is HardwareStateSyncedEvent -> {
                // 💡 修复 Finding 11: 允许原位重配切频状态 (AUDIO_RECONFIGURING) 正常透传，通知 UI 与上层状态机，绝不无条件覆写回 EXCLUSIVE_ACTIVE
                val targetHwState = ev.newHardwareState
                val nextSnap = snap.copy(
                    hardwareState = targetHwState,
                    audioPath = snap.audioPath.copy(
                        dacName = ev.dacName ?: snap.audioPath.dacName,
                        bufferAvailablePercent = ev.bufferAvailablePercent ?: snap.audioPath.bufferAvailablePercent,
                        isExclusive = isExclusiveActiveOrReconfiguring(targetHwState)
                    )
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
            }

            is TrackBoundaryEvent -> {
                val result = trackManager.onTrackBoundary(ev)
                // 💡 修复 Finding 8: 跨曲目边界进度严格归零。
                // 仅在首曲开播冷启动（snap.trackGeneration == 0L）且音频已先于元数据走字 (1L..3000L) 时才保留当前微小进度；
                // 只要此前已有活跃曲目（snap.trackGeneration > 0L）发生切歌换代，新曲目进度必须坚决归零 (0L)，彻底杜绝前序曲目残存早期进度继承给新歌！
                val nextPosMs = if (result.accepted) {
                    val pos = snap.progressPositionMs
                    if (snap.trackGeneration == 0L && pos in 1L..3000L) pos else 0L
                } else {
                    snap.progressPositionMs
                }
                val nextSnap = snap.copy(
                    trackGeneration = result.activeGeneration,
                    metadata = result.metadata,
                    progressPositionMs = nextPosMs,
                    progressDurationMs = if (result.accepted) 0L else snap.progressDurationMs
                )
                if (result.accepted) {
                    effects += TransitionEffect.ScheduleCoverGraceTimeout(ev.trackGeneration)
                }
                return TransitionOutput(ctx.copy(snapshot = nextSnap), effects)
            }

            is CoverGraceTimeoutEvent -> {
                val cleared = trackManager.clearStaleCoverIfUnclaimed(ev.trackGeneration)
                if (cleared != null) {
                    val nextSnap = snap.copy(metadata = cleared)
                    return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
                }
                return TransitionOutput(ctx, emptyList())
            }

            is MetadataPatchEvent -> {
                val updated = trackManager.applyMetadataPatch(ev) ?: return TransitionOutput(ctx, emptyList())
                val nextSnap = snap.copy(
                    metadata = updated,
                    progressDurationMs = if (updated.durationMs > 0L) updated.durationMs else snap.progressDurationMs
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
            }

            is ProgressUpdateEvent -> {
                // 💡 P1-1 防御：若事件携带了曲目代数且不等于当前代数，说明是切歌在途旧包，直接丢弃避免污染新歌
                if (ev.trackGeneration > 0L && ev.trackGeneration != snap.trackGeneration) {
                    return TransitionOutput(ctx, emptyList())
                }
                // 💡 防倒流保护：若处于同代开播初始 3.5 秒内且协议上报了滞后的小值 (<= 1500ms)，杜绝将已走字进度逆向倒拉
                if (snap.businessState == BusinessSessionState.PLAYING &&
                    snap.progressPositionMs in 1L..3500L &&
                    ev.positionMs in 0L..1500L &&
                    ev.positionMs < snap.progressPositionMs
                ) {
                    return TransitionOutput(ctx, emptyList())
                }
                val clampedDuration = if (ev.durationMs > 0L) ev.durationMs else snap.progressDurationMs
                val clampedPosition = if (clampedDuration > 0L) {
                    ev.positionMs.coerceIn(0L, clampedDuration)
                } else {
                    ev.positionMs.coerceAtLeast(0L)
                }
                return TransitionOutput(
                    ctx.copy(
                        snapshot = snap.copy(
                            progressPositionMs = clampedPosition,
                            progressDurationMs = clampedDuration
                        )
                    ),
                    emptyList()
                )
            }

            is ErrorReportedEvent -> {
                return TransitionOutput(
                    ctx.copy(snapshot = snap.copy(errors = snap.errors + (ev.error.code to ev.error))),
                    emptyList()
                )
            }

            is ErrorClearedEvent -> {
                val clearedEffects = listOf(TransitionEffect.CancelTransientErrorDismiss(ev.code))
                return TransitionOutput(
                    ctx.copy(snapshot = snap.copy(errors = snap.errors - ev.code)),
                    clearedEffects
                )
            }
        }
    }

    private fun transitionToStopped(
        ctx: TransitionContext,
        effects: MutableList<TransitionEffect>,
        newEpoch: Long,
        scheduleIdleRelease: Boolean
    ): TransitionOutput {
        effects += TransitionEffect.CancelDebouncePause
        effects += TransitionEffect.CancelSuspendTimeout
        effects += TransitionEffect.CancelRemoteCommandTimeout
        effects += TransitionEffect.CancelCoverGraceTimeout
        effects += TransitionEffect.CancelTransitionTimeout
        if (scheduleIdleRelease) {
            effects += TransitionEffect.ScheduleHardwareIdleTimeout
        }
        val nextSnap = ctx.snapshot.copy(
            transportState = TransportPipeState.DISCONNECTED,
            businessState = BusinessSessionState.STOPPED,
            hardwareState = muteIfExclusive(ctx.snapshot.hardwareState),
            currentEpoch = newEpoch,
            errors = clearTransientCommandErrors(ctx.snapshot.errors),
            uiProtectionActive = false,
            transitionOriginState = null
        )
        return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
    }

    private fun resetSessionToIdle(
        ctx: TransitionContext,
        trackManager: TrackTransactionManager,
        effects: MutableList<TransitionEffect>
    ): TransitionOutput {
        effects += TransitionEffect.ReleaseHardwareDevice
        effects += TransitionEffect.CancelCoverGraceTimeout
        effects += TransitionEffect.CancelTransitionTimeout
        trackManager.reset()
        val snap = ctx.snapshot
        val nextSnap = snap.copy(
            sessionId = 0L,
            transportState = TransportPipeState.DISCONNECTED,
            businessState = BusinessSessionState.IDLE,
            hardwareState = releaseUnlessDisconnected(snap.hardwareState),
            activeProtocol = StreamProtocol.NONE,
            metadata = trackManager.currentMetadata,
            audioPath = snap.audioPath.copy(isExclusive = false, protocol = StreamProtocol.NONE),
            progressPositionMs = 0L,
            progressDurationMs = 0L,
            errors = clearTransientCommandErrors(snap.errors),
            uiProtectionActive = false,
            transitionOriginState = null
        )
        return TransitionOutput(ctx.copy(snapshot = nextSnap), effects)
    }

    /**
     * 💡 统一处理 DACP 切歌失败/超时回滚，校验 commandId 与 TRANSITIONING 态防止旧响应污染新指令，并同步恢复硬件声卡输出
     */
    private fun rollbackRemoteCommandFailure(
        ctx: TransitionContext,
        commandId: Long,
        errorCode: ErrorCode,
        message: String,
        nowMs: Long,
        cancelTimeout: Boolean
    ): TransitionOutput {
        val snap = ctx.snapshot
        if (commandId != ctx.activeRemoteCommandId || snap.businessState != BusinessSessionState.TRANSITIONING) {
            return TransitionOutput(ctx, emptyList())
        }
        val effects = mutableListOf<TransitionEffect>()
        if (cancelTimeout) {
            effects += TransitionEffect.CancelRemoteCommandTimeout
        }
        effects += TransitionEffect.CancelTransitionTimeout
        val fallbackState = ctx.preCommandBusinessState
        val nextHw = if (fallbackState == BusinessSessionState.PLAYING &&
            isExclusiveActiveOrReconfiguring(snap.hardwareState)
        ) {
            effects += TransitionEffect.ResumeNativeOutput
            HardwarePipelineState.EXCLUSIVE_ACTIVE
        } else {
            snap.hardwareState
        }
        val err = PlaybackError(
            code = errorCode,
            message = message,
            recoverable = true,
            timestampMs = nowMs
        )
        effects += TransitionEffect.ScheduleTransientErrorDismiss(errorCode, nowMs)
        val nextSnap = snap.copy(
            businessState = fallbackState,
            hardwareState = nextHw,
            uiProtectionActive = false,
            errors = snap.errors + (errorCode to err),
            transitionOriginState = null
        )
        return TransitionOutput(
            ctx.copy(
                snapshot = nextSnap,
                activeRemoteCommandId = 0L,
                activeUiProtectionToken = 0L
            ),
            effects
        )
    }

    private fun isExclusiveActiveOrReconfiguring(hw: HardwarePipelineState): Boolean =
        hw == HardwarePipelineState.EXCLUSIVE_ACTIVE ||
            hw == HardwarePipelineState.EXCLUSIVE_MUTED ||
            hw == HardwarePipelineState.AUDIO_RECONFIGURING

    private fun isDetachedOrPendingPermission(hw: HardwarePipelineState): Boolean =
        hw == HardwarePipelineState.DISCONNECTED ||
            hw == HardwarePipelineState.ATTACHED ||
            hw == HardwarePipelineState.PERMISSION_PENDING

    private fun muteIfExclusive(hw: HardwarePipelineState): HardwarePipelineState =
        if (isExclusiveActiveOrReconfiguring(hw)) HardwarePipelineState.EXCLUSIVE_MUTED else hw

    private fun releaseUnlessDisconnected(hw: HardwarePipelineState): HardwarePipelineState =
        if (hw == HardwarePipelineState.DISCONNECTED) HardwarePipelineState.DISCONNECTED else HardwarePipelineState.RELEASED

    private fun isTrackSkipCommand(cmd: RemoteCommandType): Boolean =
        cmd == RemoteCommandType.NEXT || cmd == RemoteCommandType.PREVIOUS

    private fun addCancelAllTimers(effects: MutableList<TransitionEffect>) {
        effects += TransitionEffect.CancelDebouncePause
        effects += TransitionEffect.CancelHardwareIdleTimeout
        effects += TransitionEffect.CancelSuspendTimeout
        effects += TransitionEffect.CancelRemoteCommandTimeout
        effects += TransitionEffect.CancelTransitionTimeout
    }

    private fun clearTransientCommandErrors(errors: Map<ErrorCode, PlaybackError>): Map<ErrorCode, PlaybackError> =
        errors - ErrorCode.REMOTE_COMMAND_TIMEOUT - ErrorCode.REMOTE_COMMAND_UNSUPPORTED

    private fun clearUsbErrors(errors: Map<ErrorCode, PlaybackError>): Map<ErrorCode, PlaybackError> =
        errors - ErrorCode.USB_DEVICE_DETACHED - ErrorCode.USB_PERMISSION_DENIED - ErrorCode.USB_OPEN_FAILED
}
