package com.solo.purecast.core.lyrics

import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 💡 U5 单调插值时钟与回弹抑制单元测试
 */
class LyricsSyncClockTest {

    private lateinit var syncClock: LyricsSyncClock

    @Before
    fun setUp() {
        syncClock = LyricsSyncClock(
            maxExtrapolationMs = 1200L,
            seekThresholdMs = 1500L,
            seekDeadbandDurationMs = 250L
        )
    }

    @Test
    fun `linear frame extrapolation accurately advances time between discrete ticks`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 16ms 帧 (60fps 帧 1)
        val t1 = syncClock.getCurrentInterpolatedTimeMs(baseNano + 16_000_000L)
        assertEquals(10_016L, t1)

        // 33ms 帧 (60fps 帧 2)
        val t2 = syncClock.getCurrentInterpolatedTimeMs(baseNano + 33_000_000L)
        assertEquals(10_033L, t2)

        // 50ms 帧 (60fps 帧 3)
        val t3 = syncClock.getCurrentInterpolatedTimeMs(baseNano + 50_000_000L)
        assertEquals(10_050L, t3)
    }

    @Test
    fun `extrapolation is clamped to 1200ms when player freezes or delays tick`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 延迟 2000ms 未收到新进度推送
        val tOut = syncClock.getCurrentInterpolatedTimeMs(baseNano + 2_000_000_000L)
        // 应该被硬限制在 10000 + 1200 = 11200ms
        assertEquals(11_200L, tOut)
    }

    @Test
    fun `paused state freezes interpolated clock immediately`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(10_000L, playing = false, nowNanoTime = baseNano)

        // 推进 500ms，时间依然冻结在 10000ms
        val t = syncClock.getCurrentInterpolatedTimeMs(baseNano + 500_000_000L)
        assertEquals(10_000L, t)
    }

    @Test
    fun `seek triggers deadband suppressing backward jitter packets within 250ms`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 发生 Seek: 10000ms -> 45000ms (|delta| = 35000ms > 1500ms)
        val seekNano = baseNano + 500_000_000L
        syncClock.updatePosition(45_000L, playing = true, nowNanoTime = seekNano)

        // 50ms 后到达一个由于网络 Jitter 导致的细微回跳包 44950ms (< 45000ms, 位于 250ms 死区内)
        val jitterNano = seekNano + 50_000_000L
        syncClock.updatePosition(44_950L, playing = true, nowNanoTime = jitterNano)

        // 验证回跳包被死区严格过滤，当前推演时钟保持在 >= 45000ms
        val current = syncClock.getCurrentInterpolatedTimeMs(jitterNano)
        assertTrue("当前时间不能小于 Seek 目标值: current=$current", current >= 45_000L)
    }

    @Test
    fun `calculateActiveLineState evaluates line index and word progress correctly`() {
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.TTML_SYLLABLE,
            lines = listOf(
                LyricLine(
                    lineIndex = 0,
                    startMs = 0L,
                    endMs = 10_000L,
                    mainText = "First Line"
                ),
                LyricLine(
                    lineIndex = 1,
                    startMs = 10_000L,
                    endMs = 20_000L,
                    mainText = "Second Line",
                    words = listOf(
                        LyricWord("Second", 10_000L, 15_000L),
                        LyricWord("Line", 15_000L, 20_000L)
                    )
                )
            )
        )

        // 测试第 1 行前半段 (5000ms)
        val state1 = syncClock.calculateActiveLineState(timeline, 5_000L)
        assertEquals(0, state1.lineIndex)
        assertEquals(0.5f, state1.lineProgressFraction, 0.01f)

        // 测试第 2 行第一个词正在点亮 (12500ms)
        val state2 = syncClock.calculateActiveLineState(timeline, 12_500L)
        assertEquals(1, state2.lineIndex)
        assertEquals(0.25f, state2.lineProgressFraction, 0.01f)
        assertEquals(0, state2.activeWordIndex)
        assertEquals(0.5f, state2.wordProgressFraction, 0.01f) // 10s~15s 之间的中间点

        // 测试第 2 行第二个词点亮 (17500ms)
        val state3 = syncClock.calculateActiveLineState(timeline, 17_500L)
        assertEquals(1, state3.lineIndex)
        assertEquals(1, state3.activeWordIndex)
        assertEquals(0.5f, state3.wordProgressFraction, 0.01f)
    }
}
