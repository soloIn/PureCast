package com.solo.purecast

import com.solo.purecast.ui.components.calculateProgressFraction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 💡 ProgressBarComponent 播放进度比例算法回归测试
 */
class ProgressBarComponentTest {

    @Test
    fun `idle standby state returns zero progress fraction`() {
        // 未推流/待机初始状态：时长与当前进度均为 0，进度条必须完全归零 (0.0f) 而非错误撑满
        val fraction = calculateProgressFraction(
            currentPositionMs = 0L,
            totalDurationMs = 0L,
            isLiveStream = false
        )
        assertEquals(0.0f, fraction, 0.001f)
    }

    @Test
    fun `idle standby with default metadata returns zero progress fraction`() {
        // 初始元数据 durationMs 为 0，此时即便是 liveStream 判定，但当前位置仍为 0 且无播放，归零为 0.0f
        val fraction = calculateProgressFraction(
            currentPositionMs = 0L,
            totalDurationMs = 0L,
            isLiveStream = true
        )
        assertEquals(0.0f, fraction, 0.001f)
    }

    @Test
    fun `active live stream with advancing position returns full progress fraction`() {
        // 正在播放的实时直播广播流 (RAOP / 直播流，时长未知或为 0，但有播放进度) -> 返回 1.0f
        val fraction = calculateProgressFraction(
            currentPositionMs = 15_000L,
            totalDurationMs = 0L,
            isLiveStream = true
        )
        assertEquals(1.0f, fraction, 0.001f)
    }

    @Test
    fun `standard VOD track calculates exact progress fraction`() {
        // 正常点播流：当前 30 秒，总长 60 秒 -> 0.5f
        val fraction = calculateProgressFraction(
            currentPositionMs = 30_000L,
            totalDurationMs = 60_000L,
            isLiveStream = false
        )
        assertEquals(0.5f, fraction, 0.001f)
    }

    @Test
    fun `idle state always returns zero fraction even with residual position`() {
        // 待机空闲态强制归零
        val fraction = calculateProgressFraction(
            currentPositionMs = 25_000L,
            totalDurationMs = 60_000L,
            isLiveStream = false,
            isIdle = true
        )
        assertEquals(0.0f, fraction, 0.001f)
    }

    @Test
    fun `progress fraction clamps cleanly between 0 and 1`() {
        val belowZero = calculateProgressFraction(
            currentPositionMs = -5000L,
            totalDurationMs = 60_000L,
            isLiveStream = false
        )
        assertEquals(0.0f, belowZero, 0.001f)

        val overflow = calculateProgressFraction(
            currentPositionMs = 90_000L,
            totalDurationMs = 60_000L,
            isLiveStream = false
        )
        assertEquals(1.0f, overflow, 0.001f)
    }
}
