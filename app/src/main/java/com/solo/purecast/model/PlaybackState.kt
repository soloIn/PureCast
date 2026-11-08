package com.solo.purecast.model

/**
 * 💡 播放状态枚举
 */
enum class PlaybackStatus {
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED
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
 * 💡 当前播放曲目元数据
 */
data class TrackMetadata(
    val title: String = "等待音频推送",
    val artist: String = "PureCast TV",
    val album: String = "",
    val coverUri: String? = null,
    val coverBytes: ByteArray? = null,
    val durationMs: Long = 0L,
    val isLiveStream: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrackMetadata

        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (coverUri != other.coverUri) return false
        if (coverBytes != null) {
            if (other.coverBytes == null) return false
            if (!coverBytes.contentEquals(other.coverBytes)) return false
        } else if (other.coverBytes != null) return false
        if (durationMs != other.durationMs) return false
        if (isLiveStream != other.isLiveStream) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + (coverUri?.hashCode() ?: 0)
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + isLiveStream.hashCode()
        return result
    }
}

/**
 * 💡 物理音频链路与硬件时钟状态
 */
data class AudioPathState(
    val isDacConnected: Boolean = false,
    val dacName: String = "未检测到 USB DAC",
    val isExclusive: Boolean = false,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val channels: Int = 2,
    val bufferAvailablePercent: Int = 100,
    val protocol: StreamProtocol = StreamProtocol.NONE,
    val codec: String = "PCM"
)
