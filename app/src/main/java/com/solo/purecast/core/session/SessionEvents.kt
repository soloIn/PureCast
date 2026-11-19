package com.solo.purecast.core.session

import com.solo.purecast.model.DurationState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.PlaybackError
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata

/**
 * 💡 领域事件来源域标识
 * 用于会话身份仲裁与跨协议事件溯源日志追踪。
 */
enum class EventOrigin {
    AIRPLAY,
    UPNP,
    USB_HARDWARE,
    SERVICE,
    USER_ACTION
}

/**
 * 💡 遥控器/MediaSession 交互指令枚举
 */
enum class RemoteCommandType {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS
}

/**
 * 💡 统一会话事件信封
 * 所有跨线程、跨协议发往 `PlaybackSessionCoordinator` 的事件均须封装此信封。
 * 协调器按 `origin`、`sessionId`、`deviceGeneration` 与单调 `sequence` 仲裁，拒绝旧代迟到污染。
 */
data class SessionEventEnvelope<out T : SessionEventPayload>(
    val origin: EventOrigin,
    val sessionId: Long,
    val deviceGeneration: Long,
    val sequence: Long,
    val timestampElapsedMs: Long,
    val payload: T
)

/**
 * 💡 领域事件载荷密封契约
 */
sealed interface SessionEventPayload

// ==========================================
// 1. 流生命周期与传输管道事件
// ==========================================

/**
 * 新流初始化 / 跨协议抢占建流事件
 * 若协调器当前活跃会话非同一流，触发新 sessionId 分配并抢占旧会话。
 */
data class StreamSetupEvent(
    val protocol: StreamProtocol,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 2,
    val codec: String = "PCM",
    val initialEpoch: Long = 1L,
    val trackUri: String? = null,
    val initialMetadata: TrackMetadata? = null,
    val trackGeneration: Long? = null
) : SessionEventPayload

/**
 * 协议解复用完成三元组切频并成功向 Native 写入首帧有效 PCM 后发射
 */
data class FirstDataPacketEvent(
    val protocol: StreamProtocol,
    val epoch: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val codec: String
) : SessionEventPayload

/**
 * 切歌瞬态 FLUSH（AirPlay FLUSH 或 UPnP 下一首接管）
 */
data class TrackSwitchEvent(
    val flushReason: FlushReason = FlushReason.FLUSH_SWITCH,
    val newEpoch: Long,
    val trackUri: String? = null,
    val isSeekFlush: Boolean = false
) : SessionEventPayload

/**
 * 发送端显式暂停（如 UPnP SOAP Pause / AirPlay SET_PARAMETER pause）
 * 硬件快路径维持 URB 全 0 保活且不动 epoch 与缓冲，协调器保持 220ms 防抖窗口。
 */
data class ExplicitPauseEvent(
    val flushReason: FlushReason = FlushReason.EXPLICIT_PAUSE,
    val epoch: Long
) : SessionEventPayload

/**
 * Native 底层检测到隐式静音断流并确认进入保活后上报，触发协调层 0ms 立即切换 PAUSED
 */
data class ImplicitPauseEvent(
    val epoch: Long,
    val reason: String = "native_implicit_silence"
) : SessionEventPayload

/**
 * 恢复推流或暂停续播事件
 * 若当前硬件态为 RELEASED（例如 USB 拔出插回授权后的待命态），恢复前须对新 fd 重新 open_device。
 */
data class StreamResumeEvent(
    val protocol: StreamProtocol,
    val resumedEpoch: Long
) : SessionEventPayload

/**
 * 点播曲目自然播放完毕（EOF / 缓冲自然耗尽）
 */
data class TrackCompleteEvent(
    val newEpoch: Long = 0L,
    val reason: String = "eof"
) : SessionEventPayload

/**
 * 推流停止或连接关闭（如 UPnP Stop / AirPlay 硬停止）
 */
data class StreamStopEvent(
    val flushReason: FlushReason = FlushReason.STOP_OR_TEARDOWN,
    val newEpoch: Long = 0L,
    val isPreempted: Boolean = false
) : SessionEventPayload

/**
 * AirPlay RTSP TEARDOWN 事件
 * @property hardStop true 表示远端明确放弃会话不再恢复；false 表示进入 PAUSED 等待 10 分钟挂起超时
 */
data class StreamTeardownEvent(
    val hardStop: Boolean = false
) : SessionEventPayload

// ==========================================
// 2. 曲目边界、增量元数据与播放进度事件
// ==========================================

/**
 * 显式曲目边界事件：通知 TrackTransactionManager 递增曲目代数 [trackGeneration]
 * @property initialPositionMs 💡 新曲目起始播放进度（如切歌开播携带的非零进度或播客断点），<=0L 表示从头播放
 * @property initialDurationMs 💡 新曲目初始已知时长，<=0L 表示尚未解析
 */
data class TrackBoundaryEvent(
    val trackKey: String,
    val trackGeneration: Long,
    val initialDurationState: DurationState = DurationState.UNKNOWN,
    val seekCapability: SeekCapability = SeekCapability.UNKNOWN,
    val trackUri: String? = null,
    val initialPositionMs: Long = 0L,
    val initialDurationMs: Long = 0L
) : SessionEventPayload

/**
 * 同曲目代数内的增量元数据补丁（仅允许修补非 null 字段，旧代数分包一律丢弃）
 */
class MetadataPatchEvent(
    val trackGeneration: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val coverUri: String? = null,
    coverBytes: ByteArray? = null,
    val durationMs: Long? = null,
    val durationState: DurationState? = null,
    val seekCapability: SeekCapability? = null
) : SessionEventPayload {
    private val safeCoverBytes: ByteArray? = coverBytes?.copyOf()

    /** 💡 返回独立拷贝防止外部修改 */
    val coverBytes: ByteArray?
        get() = safeCoverBytes?.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MetadataPatchEvent

        if (trackGeneration != other.trackGeneration) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (coverUri != other.coverUri) return false
        if (safeCoverBytes != null) {
            if (other.safeCoverBytes == null) return false
            if (!safeCoverBytes.contentEquals(other.safeCoverBytes)) return false
        } else if (other.safeCoverBytes != null) return false
        if (durationMs != other.durationMs) return false
        if (durationState != other.durationState) return false
        if (seekCapability != other.seekCapability) return false

        return true
    }

    override fun hashCode(): Int {
        var result = trackGeneration.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (coverUri?.hashCode() ?: 0)
        result = 31 * result + (safeCoverBytes?.contentHashCode() ?: 0)
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + (durationState?.hashCode() ?: 0)
        result = 31 * result + (seekCapability?.hashCode() ?: 0)
        return result
    }
}

/**
 * 播放进度与时长更新事件
 * 💡 P1-1 契约：携带 [trackGeneration] 曲目代数，拒绝旧曲目切歌在途进度包覆盖新歌进度与时长
 */
data class ProgressUpdateEvent(
    val positionMs: Long,
    val durationMs: Long,
    val trackGeneration: Long = 0L
) : SessionEventPayload

/**
 * 💡 1200ms 切歌封面宽限期到期事件：若新曲目仍未提供封面，触发清理旧封面
 */
data class CoverGraceTimeoutEvent(
    val trackGeneration: Long
) : SessionEventPayload

// ==========================================
// 3. 遥控交互与定时器超时事件
// ==========================================

/**
 * TV 遥控器 / 硬件媒体键触发切歌或播控
 */
data class RemoteSkipNextEvent(
    val commandType: RemoteCommandType,
    val commandId: Long = 0L
) : SessionEventPayload

/**
 * 遥控指令执行结果（如 DACP 发送失败、UPnP 不支持切歌拦截提示等）
 */
data class RemoteCommandFailedEvent(
    val commandId: Long,
    val errorCode: ErrorCode,
    val message: String
) : SessionEventPayload

/**
 * 5000ms DACP 单项请求超时回退事件，防止 UI 永久卡死在 TRANSITIONING
 */
data class RemoteCommandTimeoutEvent(
    val commandId: Long
) : SessionEventPayload

/**
 * 💡 5000ms 过渡态 (TRANSITIONING) 看门狗超时事件，防止切歌或建连异常导致 UI 永久卡死
 */
data class TransitionTimeoutEvent(
    val sessionId: Long
) : SessionEventPayload

/**
 * 10 秒硬件空闲让渡到期：STOPPED -> IDLE，释放 USB DAC 并重绑内核驱动
 */
data object HardwareIdleTimeoutEvent : SessionEventPayload

/**
 * 10 分钟暂停挂起超时：PAUSED -> IDLE，彻底回收会话资源与低延迟锁
 */
data object SuspendTimeoutEvent : SessionEventPayload

/**
 * 220ms 被动断流 / 显式暂停防抖计时器到期
 */
data class DebouncePauseTimeoutEvent(
    val debounceToken: Long
) : SessionEventPayload

/**
 * 800ms 切歌 UI 保护窗口到期
 */
data class UiProtectionTimeoutEvent(
    val windowToken: Long
) : SessionEventPayload

// ==========================================
// 4. USB 硬件热插拔、授权与重配事件
// ==========================================

/**
 * 检测到兼容的 USB DAC 物理接入
 */
data class HardwareAttachedEvent(
    val deviceName: String,
    val hasPermission: Boolean
) : SessionEventPayload

/**
 * 正在弹出系统 USB 访问授权弹窗（ATTACHED -> PERMISSION_PENDING）
 */
data class UsbPermissionRequestedEvent(
    val deviceName: String
) : SessionEventPayload

/**
 * USB 权限审批结果（授权通过统一停留在 RELEASED 待机，拒绝注入错误，绝不抢播或变更业务稳态）
 */
data class UsbPermissionGrantedEvent(
    val deviceName: String,
    val granted: Boolean
) : SessionEventPayload

/**
 * USB DAC 物理拔出（仅 PLAYING / TRANSITIONING 转 PAUSED；IDLE / STOPPED / PAUSED 保持原态）
 */
data class HardwareDetachedEvent(
    val reason: String = "usb_detached"
) : SessionEventPayload

/**
 * UAC2 三元组切频完成，更新硬件态与音频路径规格
 */
data class HardwareReconfiguredEvent(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val epoch: Long
) : SessionEventPayload

/**
 * UAC2 三元组切频失败并安全回滚
 */
data class HardwareReconfigureFailedEvent(
    val errorCode: ErrorCode,
    val message: String,
    val fallbackState: HardwarePipelineState
) : SessionEventPayload

/**
 * 硬件执行器状态与缓冲区水位同步事件
 */
data class HardwareStateSyncedEvent(
    val newHardwareState: HardwarePipelineState,
    val dacName: String? = null,
    val bufferAvailablePercent: Int? = null
) : SessionEventPayload

// ==========================================
// 5. 结构化错误注入与定向清除事件
// ==========================================

data class ErrorReportedEvent(
    val error: PlaybackError
) : SessionEventPayload

data class ErrorClearedEvent(
    val code: ErrorCode
) : SessionEventPayload
