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
                val isPreempting = snap.sessionId != 0L && snap.sessionId != envelope.sessionId
                val oldProtocol = snap.activeProtocol
                val oldSessionId = snap.sessionId

                val txGen = ev.trackGeneration ?: (trackManager.currentTrackGeneration + 1L)
                val nextMeta = trackManager.resetForNewStream(txGen, ev.initialMetadata)

                val nextHwState: HardwarePipelineState
                if (snap.hardwareState == HardwarePipelineState.RELEASED) {
                    effects += TransitionEffect.OpenHardwareDevice(ev.sampleRate, ev.bitDepth, ev.channels)
                    nextHwState = HardwarePipelineState.AUDIO_RECONFIGURING
                } else {
                    nextHwState = muteIfExclusive(snap.hardwareState)
                }

                if (isPreempting) {
                    effects += TransitionEffect.NotifyPreemptedProtocol(oldProtocol, oldSessionId)
                }
                addCancelAllTimers(effects)

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
                    progressPositionMs = 0L,
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
                val nextHw = if (isDetachedOrPendingPermission(snap.hardwareState)) {
                    snap.hardwareState
                } else {
                    HardwarePipelineState.EXCLUSIVE_ACTIVE
                }

                val nextSnap = snap.copy(
                    transportState = TransportPipeState.STREAMING,
                    businessState = BusinessSessionState.PLAYING,
                    hardwareState = nextHw,
                    activeProtocol = ev.protocol,
                    currentEpoch = ev.epoch,
                    audioPath = snap.audioPath.copy(
                        sampleRate = ev.sampleRate,
                        bitDepth = ev.bitDepth,
                        channels = ev.channels,
                        codec = ev.codec,
                        protocol = ev.protocol,
                        isExclusive = nextHw == HardwarePipelineState.EXCLUSIVE_ACTIVE
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

                val nextSnap = snap.copy(
                    transportState = TransportPipeState.INTERRUPTED,
                    businessState = BusinessSessionState.TRANSITIONING,
                    hardwareState = muteIfExclusive(snap.hardwareState),
                    currentEpoch = ev.newEpoch,
                    progressPositionMs = 0L
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
            }

            is ExplicitPauseEvent -> {
                val token = envelope.sequence
                effects += TransitionEffect.ScheduleDebouncePause(token)
                val nextSnap = snap.copy(
                    transportState = TransportPipeState.INTERRUPTED,
                    hardwareState = muteIfExclusive(snap.hardwareState)
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = token), effects)
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
                    uiProtectionActive = false
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
                return if (ev.hardStop) {
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
                        transportState = TransportPipeState.INTERRUPTED,
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
                    return TransitionOutput(
                        ctx.copy(snapshot = snap.copy(errors = snap.errors + (ErrorCode.REMOTE_COMMAND_UNSUPPORTED to err))),
                        emptyList()
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
                    errors = clearTransientCommandErrors(snap.errors)
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
                            uiProtectionActive = false
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
                val nextHw = if (ev.granted) {
                    HardwarePipelineState.RELEASED
                } else {
                    HardwarePipelineState.PERMISSION_PENDING
                }
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
                        isExclusive = false
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
                val nextHw = if (isExclusiveActiveOrReconfiguring(snap.hardwareState)) {
                    snap.hardwareState
                } else {
                    HardwarePipelineState.EXCLUSIVE_MUTED
                }
                val nextSnap = snap.copy(
                    hardwareState = nextHw,
                    currentEpoch = ev.epoch,
                    audioPath = snap.audioPath.copy(
                        sampleRate = ev.sampleRate,
                        bitDepth = ev.bitDepth,
                        channels = ev.channels
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
                        isExclusive = ev.fallbackState == HardwarePipelineState.EXCLUSIVE_ACTIVE ||
                            ev.fallbackState == HardwarePipelineState.EXCLUSIVE_MUTED
                    ),
                    errors = snap.errors + (ev.errorCode to err)
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
            }

            is HardwareStateSyncedEvent -> {
                val nextSnap = snap.copy(
                    hardwareState = ev.newHardwareState,
                    audioPath = snap.audioPath.copy(
                        dacName = ev.dacName ?: snap.audioPath.dacName,
                        bufferAvailablePercent = ev.bufferAvailablePercent ?: snap.audioPath.bufferAvailablePercent,
                        isExclusive = ev.newHardwareState == HardwarePipelineState.EXCLUSIVE_ACTIVE ||
                            ev.newHardwareState == HardwarePipelineState.EXCLUSIVE_MUTED
                    )
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
            }

            is TrackBoundaryEvent -> {
                val result = trackManager.onTrackBoundary(ev)
                val nextSnap = snap.copy(
                    trackGeneration = result.activeGeneration,
                    metadata = result.metadata,
                    progressPositionMs = if (result.accepted) 0L else snap.progressPositionMs,
                    progressDurationMs = if (result.accepted) 0L else snap.progressDurationMs
                )
                return TransitionOutput(ctx.copy(snapshot = nextSnap), emptyList())
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
                return TransitionOutput(
                    ctx.copy(snapshot = snap.copy(errors = snap.errors - ev.code)),
                    emptyList()
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
        if (scheduleIdleRelease) {
            effects += TransitionEffect.ScheduleHardwareIdleTimeout
        }
        val nextSnap = ctx.snapshot.copy(
            transportState = TransportPipeState.DISCONNECTED,
            businessState = BusinessSessionState.STOPPED,
            hardwareState = muteIfExclusive(ctx.snapshot.hardwareState),
            currentEpoch = newEpoch,
            errors = clearTransientCommandErrors(ctx.snapshot.errors),
            uiProtectionActive = false
        )
        return TransitionOutput(ctx.copy(snapshot = nextSnap, pendingDebounceToken = 0L), effects)
    }

    private fun resetSessionToIdle(
        ctx: TransitionContext,
        trackManager: TrackTransactionManager,
        effects: MutableList<TransitionEffect>
    ): TransitionOutput {
        effects += TransitionEffect.ReleaseHardwareDevice
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
            uiProtectionActive = false
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
        val nextSnap = snap.copy(
            businessState = fallbackState,
            hardwareState = nextHw,
            uiProtectionActive = false,
            errors = snap.errors + (errorCode to err)
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
    }

    private fun clearTransientCommandErrors(errors: Map<ErrorCode, PlaybackError>): Map<ErrorCode, PlaybackError> =
        errors - ErrorCode.REMOTE_COMMAND_TIMEOUT - ErrorCode.REMOTE_COMMAND_UNSUPPORTED

    private fun clearUsbErrors(errors: Map<ErrorCode, PlaybackError>): Map<ErrorCode, PlaybackError> =
        errors - ErrorCode.USB_DEVICE_DETACHED - ErrorCode.USB_PERMISSION_DENIED - ErrorCode.USB_OPEN_FAILED
}
