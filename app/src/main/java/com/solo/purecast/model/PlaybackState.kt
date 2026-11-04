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
 * 💡 当前播放曲目元数据
 */
data class TrackMetadata(
    val title: String = "等待音频推送",
    val artist: String = "PureCast TV",
    val album: String = "",
    val coverUri: String? = null,
    val durationMs: Long = 0L,
    val isLiveStream: Boolean = false
)

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
    val bufferAvailablePercent: Int = 100
)
