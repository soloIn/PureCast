package com.solo.purecast.model

/**
 * 💡 派生播放展示状态枚举（供 TV UI 与 MediaSession 只读展示）
 * 严格由 [BusinessSessionState.toPlaybackStatus] 纯函数单向映射，禁止外部手工篡改。
 */
enum class PlaybackStatus {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED
}

/**
 * 💡 传输管道态（正交状态域 1/3）
 * 仅反映 AirPlay / UPnP 网络协议流接收通道的连接与数据活性。
 */
enum class TransportPipeState {
    DISCONNECTED,
    PREPARING,
    STREAMING,
    INTERRUPTED
}

/**
 * 💡 硬件管道态（完整 7 态，正交状态域 2/3）
 * 独立刻画 USB DAC 物理插拔、权限审批、内核驱动重绑让渡以及 libusb 等时传输锁相阶段。
 */
enum class HardwarePipelineState {
    DISCONNECTED,
    ATTACHED,
    PERMISSION_PENDING,
    RELEASED,
    EXCLUSIVE_MUTED,
    EXCLUSIVE_ACTIVE,
    AUDIO_RECONFIGURING
}

/**
 * 💡 业务会话态（正交状态域 3/3）
 * 控制用户体验层的稳定会话阶段，吸收毫秒级切歌抖动并仲裁超时回退。
 */
enum class BusinessSessionState {
    IDLE,
    PLAYING,
    TRANSITIONING,
    PAUSED,
    STOPPED;

    /**
     * 💡 纯函数映射：TRANSITIONING 过渡期间对外映射为 PLAYING，避免大屏进度与控制栏闪烁
     */
    fun toPlaybackStatus(): PlaybackStatus = when (this) {
        IDLE -> PlaybackStatus.IDLE
        PLAYING, TRANSITIONING -> PlaybackStatus.PLAYING
        PAUSED -> PlaybackStatus.PAUSED
        STOPPED -> PlaybackStatus.STOPPED
    }
}

/**
 * 💡 硬件快路径紧急静音与世代刷新原因枚举
 * [nativeCode] 严格对齐 C++ `enum class FlushReason : int` 数值约定。
 */
enum class FlushReason(val nativeCode: Int) {
    /** 显式暂停（如 UPnP SOAP Pause）：仅切全 0 保活，不动 buffer_epoch_ 与环形缓冲 */
    EXPLICIT_PAUSE(0),

    /** 切歌/FLUSH：递增 epoch，由 URB 消费端安全清空旧代残余并通过 100ms 预充自动解锁静音 */
    FLUSH_SWITCH(1),

    /** 停止播放/RTSP TEARDOWN：递增 epoch 排空旧流并保持静音待机 */
    STOP_OR_TEARDOWN(2),

    /** 跨协议抢占（如 AirPlay <-> UPnP）：立即阻断旧代写入口并清理消费端残余 */
    PREEMPT(3)
}

/**
 * 💡 流传输协议类型
 */
enum class StreamProtocol {
    NONE,
    UPNP_DLNA,
    AIRPLAY_1
}

/**
 * 💡 曲目时长确定性状态（显式区分未知时长、固定点播时长与明确实时直播）
 */
enum class DurationState {
    UNKNOWN,
    FIXED,
    LIVE
}

/**
 * 💡 曲目寻道能力契约（驱动 MediaSession ACTION_SEEK_TO 与进度条可交互态）
 */
enum class SeekCapability {
    UNKNOWN,
    SEEKABLE,
    UNSEEKABLE
}

/**
 * 💡 结构化播放器错误码
 */
enum class ErrorCode {
    USB_DEVICE_DETACHED,
    USB_PERMISSION_DENIED,
    USB_RECONFIGURE_FAILED,
    USB_OPEN_FAILED,
    DECODE_ERROR,
    NETWORK_STREAM_ERROR,
    REMOTE_COMMAND_UNSUPPORTED,
    REMOTE_COMMAND_TIMEOUT,
    SERVER_ERROR
}

/**
 * 💡 结构化错误实体
 */
data class PlaybackError(
    val code: ErrorCode,
    val message: String,
    val recoverable: Boolean = true,
    val timestampMs: Long = 0L
)

/**
 * 💡 当前播放曲目元数据
 * 在构造、增量合并与 `.copy()` 边界对外部传入的 [coverBytes] 实施防御性拷贝，同时在同一个不可变快照实例上
 * 保持稳定的引用与 [coverHash] 指纹，兼顾跨线程防篡改与 Compose 重组零冗余分配。
 */
class TrackMetadata private constructor(
    val title: String,
    val artist: String,
    val album: String,
    val coverUri: String?,
    private val internalCoverBytes: ByteArray?,
    val durationMs: Long,
    val durationState: DurationState,
    val seekCapability: SeekCapability,
    val trackUri: String?,
    @Suppress("UNUSED_PARAMETER") alreadyCopied: Boolean
) {
    constructor(
        title: String = DEFAULT_TITLE,
        artist: String = DEFAULT_ARTIST,
        album: String = "",
        coverUri: String? = null,
        coverBytes: ByteArray? = null,
        durationMs: Long = 0L,
        durationState: DurationState = DurationState.UNKNOWN,
        seekCapability: SeekCapability = SeekCapability.UNKNOWN,
        trackUri: String? = null
    ) : this(
        title = title,
        artist = artist,
        album = album,
        coverUri = coverUri,
        internalCoverBytes = coverBytes?.copyOf(),
        durationMs = durationMs,
        durationState = durationState,
        seekCapability = seekCapability,
        trackUri = trackUri,
        alreadyCopied = true
    )

    /** 💡 稳定的只读封面字节引用（已于构造时独立拷贝隔离外部缓冲区；Compose 可稳定复用） */
    val coverBytes: ByteArray?
        get() = internalCoverBytes

    /** 💡 轻量级封面内容指纹，供 Compose `LaunchedEffect` / 缓存键精确对比是否真正换图 */
    val coverHash: Int = internalCoverBytes?.contentHashCode() ?: 0

    /** 💡 为需要可写操作或 JNI 跨边界传递提供显式防御副本 */
    fun copyCoverBytes(): ByteArray? = internalCoverBytes?.copyOf()

    fun copy(
        title: String = this.title,
        artist: String = this.artist,
        album: String = this.album,
        coverUri: String? = this.coverUri,
        coverBytes: ByteArray? = this.internalCoverBytes,
        durationMs: Long = this.durationMs,
        durationState: DurationState = this.durationState,
        seekCapability: SeekCapability = this.seekCapability,
        trackUri: String? = this.trackUri
    ): TrackMetadata = TrackMetadata(
        title = title,
        artist = artist,
        album = album,
        coverUri = coverUri,
        internalCoverBytes = if (coverBytes === this.internalCoverBytes) {
            this.internalCoverBytes
        } else {
            coverBytes?.copyOf()
        },
        durationMs = durationMs,
        durationState = durationState,
        seekCapability = seekCapability,
        trackUri = trackUri,
        alreadyCopied = true
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrackMetadata

        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (coverUri != other.coverUri) return false
        if (internalCoverBytes != null) {
            if (other.internalCoverBytes == null) return false
            if (!internalCoverBytes.contentEquals(other.internalCoverBytes)) return false
        } else if (other.internalCoverBytes != null) return false
        if (durationMs != other.durationMs) return false
        if (durationState != other.durationState) return false
        if (seekCapability != other.seekCapability) return false
        if (trackUri != other.trackUri) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + (coverUri?.hashCode() ?: 0)
        result = 31 * result + coverHash
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + durationState.hashCode()
        result = 31 * result + seekCapability.hashCode()
        result = 31 * result + (trackUri?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "TrackMetadata(title='$title', artist='$artist', album='$album', " +
            "coverUri=$coverUri, coverBytesSize=${internalCoverBytes?.size ?: 0}, " +
            "durationMs=$durationMs, durationState=$durationState, " +
            "seekCapability=$seekCapability, trackUri=$trackUri)"
    }

    companion object {
        const val DEFAULT_TITLE = "等待音频推送"
        const val DEFAULT_ARTIST = "PureCast TV"
    }
}

/**
 * 💡 物理音频链路与硬件时钟状态
 */
data class AudioPathState(
    val isDacConnected: Boolean = false,
    val dacName: String = DEFAULT_DAC_NAME,
    val isExclusive: Boolean = false,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 2,
    val bufferAvailablePercent: Int = 100,
    val protocol: StreamProtocol = StreamProtocol.NONE,
    val codec: String = "PCM"
) {
    companion object {
        const val DEFAULT_DAC_NAME = "未检测到 USB DAC"
    }
}

/**
 * 💡 全局唯一可信播放会话快照（Single Source of Truth）
 * 由 `PlaybackSessionCoordinator` 原子发布；UI（`MainTvScreen`）与服务集成层仅只读订阅。
 */
data class PlayerSessionSnapshot(
    val sessionId: Long = 0L,
    val deviceGeneration: Long = 0L,
    val transportState: TransportPipeState = TransportPipeState.DISCONNECTED,
    val businessState: BusinessSessionState = BusinessSessionState.IDLE,
    val hardwareState: HardwarePipelineState = HardwarePipelineState.DISCONNECTED,
    val activeProtocol: StreamProtocol = StreamProtocol.NONE,
    val currentEpoch: Long = 1L,
    val trackGeneration: Long = 0L,
    val metadata: TrackMetadata = TrackMetadata(),
    val audioPath: AudioPathState = AudioPathState(),
    val progressPositionMs: Long = 0L,
    val progressDurationMs: Long = 0L,
    val progressRevision: Long = 0L,
    val lastProgressFromUserSeek: Boolean = false,
    val errors: Map<ErrorCode, PlaybackError> = emptyMap(),
    val uiProtectionActive: Boolean = false,
    val volumePercent: Int = 100,
    val transitionOriginState: BusinessSessionState? = null
) {
    /** 💡 唯一派生的 UI/MediaSession 状态，严格杜绝外部覆写；若从 PAUSED 切歌进入 TRANSITIONING 则对外保持 PAUSED 避免跳变 */
    val playbackStatus: PlaybackStatus
        get() = if (businessState == BusinessSessionState.TRANSITIONING && transitionOriginState == BusinessSessionState.PAUSED) {
            PlaybackStatus.PAUSED
        } else {
            businessState.toPlaybackStatus()
        }

    /** 💡 按固定优先级提取当前最高优先级错误（与 ErrorCode 枚举声明序一致） */
    val highestPriorityError: PlaybackError?
        get() = errors.values.minByOrNull { it.code.ordinal }
}
