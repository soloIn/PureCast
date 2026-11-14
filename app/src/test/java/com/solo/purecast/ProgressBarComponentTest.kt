package com.solo.purecast

import com.solo.purecast.model.DurationState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.ui.components.ProgressRightLabelMode
import com.solo.purecast.ui.components.calculateProgressFraction
import com.solo.purecast.ui.components.resolveProgressRightLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 💡 Unit U6：ProgressBarComponent 确定性时长/进度比例与右侧状态标签回归测试
 */
class ProgressBarComponentTest {

    @Test
    fun `idle standby state returns zero progress fraction and standby label`() {
        val fraction = calculateProgressFraction(
            currentPositionMs = 0L,
            totalDurationMs = 0L,
            durationState = DurationState.UNKNOWN,
            isIdle = true
        )
        assertEquals(0.0f, fraction, 0.001f)
        assertEquals(
            ProgressRightLabelMode.Standby,
            resolveProgressRightLabel(
                totalDurationMs = 0L,
                durationState = DurationState.UNKNOWN,
                playbackStatus = PlaybackStatus.IDLE
            )
        )
    }

    @Test
    fun `unknown duration while playing stays at zero fraction with placeholder label and never fakes LIVE`() {
        val fraction = calculateProgressFraction(
            currentPositionMs = 15_000L,
            totalDurationMs = 0L,
            durationState = DurationState.UNKNOWN,
            isIdle = false
        )
        assertEquals(0.0f, fraction, 0.001f)
        assertEquals(
            ProgressRightLabelMode.Placeholder("--:--"),
            resolveProgressRightLabel(
                totalDurationMs = 0L,
                durationState = DurationState.UNKNOWN,
                playbackStatus = PlaybackStatus.PLAYING
            )
        )
    }

    @Test
    fun `active explicit LIVE stream returns full fraction and LIVE badge`() {
        val fraction = calculateProgressFraction(
            currentPositionMs = 15_000L,
            totalDurationMs = 0L,
            durationState = DurationState.LIVE,
            isIdle = false
        )
        assertEquals(1.0f, fraction, 0.001f)
        assertEquals(
            ProgressRightLabelMode.LiveBadge,
            resolveProgressRightLabel(
                totalDurationMs = 0L,
                durationState = DurationState.LIVE,
                playbackStatus = PlaybackStatus.PLAYING
            )
        )
    }

    @Test
    fun `standard FIXED duration track calculates exact progress fraction and mm ss label`() {
        val fraction = calculateProgressFraction(
            currentPositionMs = 30_000L,
            totalDurationMs = 60_000L,
            durationState = DurationState.FIXED,
            isIdle = false
        )
        assertEquals(0.5f, fraction, 0.001f)
        assertEquals(
            ProgressRightLabelMode.FormattedTime("01:00"),
            resolveProgressRightLabel(
                totalDurationMs = 60_000L,
                durationState = DurationState.FIXED,
                playbackStatus = PlaybackStatus.PLAYING
            )
        )
    }

    @Test
    fun `idle state always returns zero fraction even with residual position`() {
        val fraction = calculateProgressFraction(
            currentPositionMs = 25_000L,
            totalDurationMs = 60_000L,
            durationState = DurationState.FIXED,
            isIdle = true
        )
        assertEquals(0.0f, fraction, 0.001f)
    }

    @Test
    fun `progress fraction clamps cleanly between 0 and 1`() {
        val belowZero = calculateProgressFraction(
            currentPositionMs = -5000L,
            totalDurationMs = 60_000L,
            durationState = DurationState.FIXED
        )
        assertEquals(0.0f, belowZero, 0.001f)

        val overflow = calculateProgressFraction(
            currentPositionMs = 90_000L,
            totalDurationMs = 60_000L,
            durationState = DurationState.FIXED
        )
        assertEquals(1.0f, overflow, 0.001f)
    }
}
