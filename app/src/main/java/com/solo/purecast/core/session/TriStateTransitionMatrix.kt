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
    val activeRemoteCommandType: RemoteCommandType? = null,
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
                    progressAnchorDelayMs = 0L,
                    progressRevision = snap.progressRevision + 1L,
                    lastProgressFromUserSeek = false,
                    errors = clearTransientCommandErrors(snap.errors),
                    uiProtectionActive = false
                )
                return TransitionOutput(
                    ctx.copy(
                        snapshot = nextSnap,
                        pendingDebounceToken = 0L,
                        activeRemoteCommandId = 0L,
                        activeRemoteCommandType = null
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
                    progressAnchorDelayMs = 0L,
                    errors = clearTransientCommandErrors(snap.errors),
                    uiProtectionActive = false
                )
                return TransitionOutput(
                    ctx.copy(
                        snapshot = nextSnap,
                        pendingDebounceToken = 0L,
                        activeUiProtectionToken = 0L,
                        activeRemoteCommandId = 0L,
                        activeRemoteCommandType = null
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
                        progressPositionMs = nextPositionMs,
                        progressAnchorDelayMs = 0L
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
                    progressPositionMs = nextPositionMs,
                    progressAnchorDelayMs = 0L
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
                        progressAnchorDelayMs = 0L,
                        uiProtectionActive = false
                    )
                    return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
                } else {
                    val token = envelope.sequence
                    effects += TransitionEffect.ScheduleDebouncePause(token)
                    val nextSnap = snap.copy(
                        transportState = TransportPipeState.INTERRUPTED,
                        hardwareState = muteIfExclusive(snap.hardwareState),
                        progressAnchorDelayMs = 0L
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

            is TrackCompleteEvent ->
                return disconnectSessionToIdle(
                    ctx = ctx,
                    trackManager = trackManager,
                    effects = effects,
                    newEpoch = ev.newEpoch,
                    scheduleIdleRelease = true,
                    notifyProtocol = false
                )

            is StreamStopEvent ->
                return disconnectSessionToIdle(
                    ctx = ctx,
                    trackManager = trackManager,
                    effects = effects,
                    newEpoch = ev.newEpoch,
                    scheduleIdleRelease = !ev.isPreempted,
                    notifyProtocol = false
                )

            is StreamTeardownEvent -> {
                effects += TransitionEffect.CancelDebouncePause
                effects += TransitionEffect.CancelRemoteCommandTimeout
                effects += TransitionEffect.CancelTransitionTimeout
                // 💡 由 DACP 探活裁决器统一裁定：
                // 1) hardStop == true（停止/对端已断）-> 不管停止前是什么状态（PLAYING / TRANSITIONING / PAUSED），直接断开会话归 IDLE；
                // 2) hardStop == false（DACP 仍存活，仅暂停休眠）-> 统一转入/保持 PAUSED 并启动 10 分钟会话保持超时。
                return if (ev.hardStop) {
                    disconnectSessionToIdle(
                        ctx = ctx,
                        trackManager = trackManager,
                        effects = effects,
                        newEpoch = snap.currentEpoch,
                        scheduleIdleRelease = true,
                        notifyProtocol = true
                    )
                } else {
                    effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
                    val nextSnap = snap.copy(
                        transportState = TransportPipeState.DISCONNECTED,
                        businessState = BusinessSessionState.PAUSED,
                        hardwareState = muteIfExclusive(snap.hardwareState),
                        progressAnchorDelayMs = 0L,
                        uiProtectionActive = false,
                        transitionOriginState = null
                    )
                    TransitionOutput(
                        ctx.copy(
                            snapshot = nextSnap,
                            pendingDebounceToken = 0L,
                            activeRemoteCommandId = 0L,
                            activeRemoteCommandType = null
                        ),
                        effects
                    )
                }
            }

            is HardwareIdleTimeoutEvent -> {
                // 💡 硬件空闲让渡与业务会话解耦：仅在会话已归 IDLE（或兼容 STOPPED）时释放声卡硬件，不影响会话字段
                if (snap.businessState != BusinessSessionState.IDLE &&
                    snap.businessState != BusinessSessionState.STOPPED
                ) {
                    return TransitionOutput(ctx, emptyList())
                }
                effects += TransitionEffect.ReleaseHardwareDevice
                val nextSnap = snap.copy(
                    hardwareState = releaseUnlessDisconnected(snap.hardwareState),
                    audioPath = snap.audioPath.copy(isExclusive = false)
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), effects)
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
                // 若在 PREPARING 阶段超时（从未收到过首包有效数据），直接断开会话归 IDLE 并启动硬件空闲让渡，杜绝伪暂停占住声卡 10 分钟！
                if (snap.transportState == TransportPipeState.PREPARING) {
                    return disconnectSessionToIdle(
                        ctx = ctx,
                        trackManager = trackManager,
                        effects = effects,
                        newEpoch = snap.currentEpoch,
                        scheduleIdleRelease = true,
                        notifyProtocol = true
                    )
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

                val isPlayResumeCommand = ev.commandType == RemoteCommandType.PLAY
                if (isPlayResumeCommand && snap.businessState == BusinessSessionState.PLAYING) {
                    return TransitionOutput(ctx, emptyList())
                }
                val preState = if (snap.businessState != BusinessSessionState.TRANSITIONING) {
                    snap.businessState
                } else {
                    ctx.preCommandBusinessState
                }
                val cmdId = ev.commandId
                val windowToken = envelope.sequence
                if (isPlayResumeCommand) {
                    effects += TransitionEffect.CancelSuspendTimeout
                    if (snap.transportState != TransportPipeState.DISCONNECTED && !isDetachedOrPendingPermission(snap.hardwareState)) {
                        if (snap.hardwareState == HardwarePipelineState.RELEASED) {
                            effects += TransitionEffect.OpenDeviceOnResume(
                                sampleRate = snap.audioPath.sampleRate,
                                bitDepth = snap.audioPath.bitDepth,
                                channels = snap.audioPath.channels,
                                fallbackPausedEpoch = snap.currentEpoch,
                                protocol = snap.activeProtocol,
                                sessionId = snap.sessionId
                            )
                        } else if (snap.hardwareState == HardwarePipelineState.EXCLUSIVE_MUTED) {
                            // 💡 未 TEARDOWN 的暂停恢复：先解除 Native 显式暂停标记以放行新到音频帧进 Sync 门控，
                            // 但业务态停留在 TRANSITIONING，等真实有声帧过 Sync 门控触发 FirstDataPacketEvent 再切 PLAYING！
                            effects += TransitionEffect.ResumeNativeOutput
                        }
                    }
                }
                effects += TransitionEffect.EnqueueDacpCommand(ev.commandType, cmdId)
                effects += TransitionEffect.ScheduleUiProtection(windowToken)
                effects += TransitionEffect.ScheduleRemoteCommandTimeout(cmdId)

                val nextSnap = snap.copy(
                    businessState = BusinessSessionState.TRANSITIONING,
                    hardwareState = if (isPlayResumeCommand) snap.hardwareState else muteIfExclusive(snap.hardwareState),
                    uiProtectionActive = true,
                    errors = clearTransientCommandErrors(snap.errors),
                    transitionOriginState = preState
                )
                return TransitionOutput(
                    ctx.copy(
                        snapshot = nextSnap,
                        activeUiProtectionToken = windowToken,
                        activeRemoteCommandId = cmdId,
                        activeRemoteCommandType = ev.commandType,
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
                trackManager = trackManager,
                commandId = ev.commandId,
                errorCode = ErrorCode.REMOTE_COMMAND_TIMEOUT,
                message = if (ctx.activeRemoteCommandType == RemoteCommandType.PLAY) "远端设备未响应播放指令" else "远端设备未响应切歌指令",
                nowMs = nowMs,
                cancelTimeout = false
            )

            is RemoteCommandFailedEvent -> return rollbackRemoteCommandFailure(
                ctx = ctx,
                trackManager = trackManager,
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
                // 💡 严禁在 AirPlay 传输通道已断开 (TEARDOWN DISCONNECTED) 时直接强切 STREAMING/PLAYING 引发虚空播放
                if (snap.activeProtocol == StreamProtocol.AIRPLAY_1 &&
                    snap.transportState == TransportPipeState.DISCONNECTED
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
                            progressAnchorDelayMs = 0L,
                            uiProtectionActive = false,
                            transitionOriginState = null
                        )
                        TransitionOutput(
                            ctx.copy(
                                snapshot = nextSnap,
                                pendingDebounceToken = 0L,
                                activeRemoteCommandId = 0L,
                                activeRemoteCommandType = null
                            ),
                            effects
                        )
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
                // 💡 修复：任意位置投屏、自动切歌与跨曲目残存隔离：
                // 1. 若当前曲目边界显式携带了真实初始进度 (ev.initialPositionMs > 0L)，优先采纳；
                // 2. 若上一状态为 AirPlay 冷启动占位标题 ("AirPlay 音频直通") 且已有先到的有效进度，保留该进度，杜绝歌曲中途投屏被 DMAP 归零；
                // 3. 否则，仅在首曲冷启动 (snap.trackGeneration == 0L) 且音频已先于元数据走字 (1L..3000L) 时保留微小进度；
                // 4. 真实跨曲目切歌换代且未显式指定初始进度时坚决归零 (0L)，彻底杜绝前序曲目残存旧进度泄漏给新歌。
                val isAirPlayColdStartPlaceholder = snap.metadata.title == "AirPlay 音频直通"
                val nextPosMs = if (result.accepted) {
                    if (ev.initialPositionMs > 0L) {
                        ev.initialPositionMs
                    } else if (isAirPlayColdStartPlaceholder && snap.progressPositionMs > 0L) {
                        snap.progressPositionMs
                    } else {
                        val pos = snap.progressPositionMs
                        if (snap.trackGeneration == 0L && pos in 1L..3000L) pos else 0L
                    }
                } else {
                    snap.progressPositionMs
                }
                val nextDurMs = if (result.accepted) {
                    if (ev.initialDurationMs > 0L) ev.initialDurationMs else if (isAirPlayColdStartPlaceholder && snap.progressDurationMs > 0L) snap.progressDurationMs else 0L
                } else snap.progressDurationMs

                // 💡 修复 TV 遥控器切歌卡在 TRANSITIONING 5 秒的致命缺陷：
                // iOS Apple Music 响应 DACP nextitem/previtem 时通常无 FLUSH 无缝切曲，不会再次触发 FirstDataPacketEvent；
                // 一旦确立新曲目边界 (result.accepted)，立即结束遥控切歌过渡态、取消 5 秒超时并恢复声卡输出与播放时钟！
                val isCompletingRemoteSkip = result.accepted &&
                    snap.businessState == BusinessSessionState.TRANSITIONING &&
                    ctx.activeRemoteCommandType != RemoteCommandType.PLAY &&
                    (ctx.activeRemoteCommandId != 0L || snap.transitionOriginState != null) &&
                    snap.transportState != TransportPipeState.PREPARING

                val resolvedBiz = if (isCompletingRemoteSkip) {
                    if (ctx.preCommandBusinessState == BusinessSessionState.PAUSED) {
                        BusinessSessionState.PAUSED
                    } else {
                        BusinessSessionState.PLAYING
                    }
                } else {
                    snap.businessState
                }
                val resolvedHw = if (isCompletingRemoteSkip &&
                    resolvedBiz == BusinessSessionState.PLAYING &&
                    isExclusiveActiveOrReconfiguring(snap.hardwareState)
                ) {
                    effects += TransitionEffect.ResumeNativeOutput
                    HardwarePipelineState.EXCLUSIVE_ACTIVE
                } else {
                    snap.hardwareState
                }
                if (isCompletingRemoteSkip) {
                    effects += TransitionEffect.CancelRemoteCommandTimeout
                    effects += TransitionEffect.CancelTransitionTimeout
                }

                val nextSnap = snap.copy(
                    businessState = resolvedBiz,
                    hardwareState = resolvedHw,
                    trackGeneration = result.activeGeneration,
                    metadata = result.metadata,
                    progressPositionMs = nextPosMs,
                    progressDurationMs = nextDurMs,
                    progressAnchorDelayMs = if (result.accepted) ev.initialAnchorDelayMs.coerceAtLeast(0L) else snap.progressAnchorDelayMs,
                    progressRevision = if (result.accepted) snap.progressRevision + 1L else snap.progressRevision,
                    lastProgressFromUserSeek = false,
                    uiProtectionActive = if (isCompletingRemoteSkip) false else snap.uiProtectionActive,
                    transitionOriginState = if (isCompletingRemoteSkip) null else snap.transitionOriginState,
                    errors = if (isCompletingRemoteSkip) clearTransientCommandErrors(snap.errors) else snap.errors
                )
                if (result.accepted) {
                    effects += TransitionEffect.ScheduleCoverGraceTimeout(ev.trackGeneration)
                }
                return TransitionOutput(
                    ctx.copy(
                        snapshot = nextSnap,
                        activeRemoteCommandId = if (isCompletingRemoteSkip) 0L else ctx.activeRemoteCommandId,
                        activeRemoteCommandType = if (isCompletingRemoteSkip) null else ctx.activeRemoteCommandType,
                        activeUiProtectionToken = if (isCompletingRemoteSkip) 0L else ctx.activeUiProtectionToken
                    ),
                    effects
                )
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
                // 💡 根治 Ticker 竞态覆盖 Seek：内部轮询包必须校验 expectedProgressRevision，若期间已有新协议进度/Seek 到达则直接丢弃
                if (envelope.origin == EventOrigin.SERVICE &&
                    ev.expectedProgressRevision >= 0L &&
                    ev.expectedProgressRevision != snap.progressRevision
                ) {
                    return TransitionOutput(ctx, emptyList())
                }
                // 💡 防倒流保护：仅拦截开播前 3.5 秒内非用户显式寻道且无排队延迟的 0ms 未就绪占位空包；
                // 携带明确 anchorDelayMs > 0L 的排队延迟事件与真实非零协议进度必须放行，精准消除自然切歌抢跑。
                if (envelope.origin != EventOrigin.USER_ACTION &&
                    ev.anchorDelayMs <= 0L &&
                    snap.businessState == BusinessSessionState.PLAYING &&
                    snap.progressPositionMs in 1L..3500L &&
                    ev.positionMs == 0L
                ) {
                    return TransitionOutput(ctx, emptyList())
                }
                val clampedDuration = if (ev.durationMs > 0L) ev.durationMs else snap.progressDurationMs
                val clampedPosition = if (clampedDuration > 0L) {
                    ev.positionMs.coerceIn(0L, clampedDuration)
                } else {
                    ev.positionMs.coerceAtLeast(0L)
                }
                val isAuthoritativeProgress = envelope.origin != EventOrigin.SERVICE
                val nextAnchorDelay = if (isAuthoritativeProgress) ev.anchorDelayMs.coerceAtLeast(0L) else 0L
                return TransitionOutput(
                    ctx.copy(
                        snapshot = snap.copy(
                            progressPositionMs = clampedPosition,
                            progressDurationMs = clampedDuration,
                            progressAnchorDelayMs = nextAnchorDelay,
                            progressRevision = if (isAuthoritativeProgress) snap.progressRevision + 1L else snap.progressRevision,
                            lastProgressFromUserSeek = envelope.origin == EventOrigin.USER_ACTION
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

    /**
     * 💡 停止播放 / 确认断联的统一收敛入口：
     * 不管停止前处于何种状态（PLAYING / TRANSITIONING / PAUSED），立即断开会话直达 IDLE，
     * 清理元数据与进度并通知协议层回收凭据；USB 声卡保持 EXCLUSIVE_MUTED，独立按空闲让渡定时器释放。
     */
    private fun disconnectSessionToIdle(
        ctx: TransitionContext,
        trackManager: TrackTransactionManager,
        effects: MutableList<TransitionEffect>,
        newEpoch: Long,
        scheduleIdleRelease: Boolean,
        notifyProtocol: Boolean,
        extraError: Pair<ErrorCode, PlaybackError>? = null
    ): TransitionOutput {
        val snap = ctx.snapshot
        effects += TransitionEffect.CancelDebouncePause
        effects += TransitionEffect.CancelSuspendTimeout
        effects += TransitionEffect.CancelRemoteCommandTimeout
        effects += TransitionEffect.CancelCoverGraceTimeout
        effects += TransitionEffect.CancelTransitionTimeout
        if (notifyProtocol && snap.sessionId != 0L && snap.activeProtocol != StreamProtocol.NONE) {
            effects += TransitionEffect.NotifyPreemptedProtocol(snap.activeProtocol, snap.sessionId)
        }
        if (scheduleIdleRelease) {
            effects += TransitionEffect.ScheduleHardwareIdleTimeout
        }
        trackManager.reset()
        val nextHw = muteIfExclusive(snap.hardwareState)
        val baseErrors = clearTransientCommandErrors(snap.errors)
        val nextErrors = if (extraError != null) baseErrors + extraError else baseErrors
        val nextSnap = snap.copy(
            sessionId = 0L,
            transportState = TransportPipeState.DISCONNECTED,
            businessState = BusinessSessionState.IDLE,
            hardwareState = nextHw,
            activeProtocol = StreamProtocol.NONE,
            currentEpoch = newEpoch,
            metadata = trackManager.currentMetadata,
            audioPath = snap.audioPath.copy(
                isExclusive = isExclusiveActiveOrReconfiguring(nextHw),
                protocol = StreamProtocol.NONE
            ),
            progressPositionMs = 0L,
            progressDurationMs = 0L,
            progressAnchorDelayMs = 0L,
            progressRevision = snap.progressRevision + 1L,
            lastProgressFromUserSeek = false,
            errors = nextErrors,
            uiProtectionActive = false,
            transitionOriginState = null
        )
        return TransitionOutput(
            ctx.copy(
                snapshot = nextSnap,
                pendingDebounceToken = 0L,
                activeRemoteCommandId = 0L,
                activeRemoteCommandType = null,
                activeUiProtectionToken = 0L
            ),
            effects
        )
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
            progressAnchorDelayMs = 0L,
            progressRevision = snap.progressRevision + 1L,
            lastProgressFromUserSeek = false,
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
        trackManager: TrackTransactionManager,
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
        // 💡 若在 TEARDOWN 断开挂起态点击播放 (RemoteCommandType.PLAY) 但 DACP 失败或超时，
        // 说明 iPhone 已彻底断开，直接断开会话归 IDLE 避免僵尸挂起与虚空播放！
        if (ctx.activeRemoteCommandType == RemoteCommandType.PLAY &&
            snap.transportState == TransportPipeState.DISCONNECTED
        ) {
            val err = PlaybackError(
                code = errorCode,
                message = "发送端已断开连接",
                recoverable = true,
                timestampMs = nowMs
            )
            effects += TransitionEffect.ScheduleTransientErrorDismiss(errorCode, nowMs)
            return disconnectSessionToIdle(
                ctx = ctx,
                trackManager = trackManager,
                effects = effects,
                newEpoch = snap.currentEpoch,
                scheduleIdleRelease = true,
                notifyProtocol = true,
                extraError = errorCode to err
            )
        }
        val fallbackState = ctx.preCommandBusinessState
        if (fallbackState == BusinessSessionState.PAUSED) {
            effects += TransitionEffect.ScheduleSuspendTimeout(snap.sessionId)
        }
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
                activeRemoteCommandType = null,
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
