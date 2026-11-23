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
            jumpThresholdMs = 350L
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
    fun `seek jumps immediately reset clock anchor whether forward or backward`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 发生向前 Seek (快进): 10000ms -> 45000ms (|delta| = 35000ms > 350ms)
        val seekForwardNano = baseNano + 500_000_000L
        syncClock.updatePosition(45_000L, playing = true, nowNanoTime = seekForwardNano)

        val tForward = syncClock.getCurrentInterpolatedTimeMs(seekForwardNano)
        assertEquals(45_000L, tForward)

        // 发生向后 Seek (快退): 45000ms -> 12000ms (|delta| = 33000ms > 350ms)
        val seekBackwardNano = seekForwardNano + 200_000_000L
        syncClock.updatePosition(12_000L, playing = true, nowNanoTime = seekBackwardNano)

        val tBackward = syncClock.getCurrentInterpolatedTimeMs(seekBackwardNano)
        assertEquals(12_000L, tBackward)
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

    @Test
    fun `intro period returns lineIndex minus 1 and does not prematurely highlight first line`() {
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.LRC_LINE_SYNC,
            lines = listOf(
                LyricLine(lineIndex = 0, startMs = 15_000L, endMs = 25_000L, mainText = "First Line"),
                LyricLine(lineIndex = 1, startMs = 25_000L, endMs = 35_000L, mainText = "Second Line")
            )
        )

        // 歌曲刚播放到第 5 秒，第一句在 15 秒（前奏期）
        val state = syncClock.calculateActiveLineState(timeline, 5_000L)
        assertEquals(-1, state.lineIndex)
        assertEquals(0f, state.lineProgressFraction, 0.001f)
    }

    @Test
    fun `pausing does not jump backward even if reported position is behind interpolated time`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 外推推进到 10_300ms
        val interpolatedTime = syncClock.getCurrentInterpolatedTimeMs(baseNano + 300_000_000L)
        assertEquals(10_300L, interpolatedTime)

        // 暂停瞬间底层上报 10_100ms（比已外推时间略小）
        syncClock.updatePosition(10_100L, playing = false, nowNanoTime = baseNano + 301_000_000L)

        // 验证暂停后时间原地冻结在 >= 10_300ms，绝不倒退至 10_100ms
        val frozenTime = syncClock.getCurrentInterpolatedTimeMs(baseNano + 500_000_000L)
        assertTrue("暂停时间不能倒退: frozen=$frozenTime", frozenTime >= 10_300L)
    }

    @Test
    fun `continuous rewind seek is not blocked by deadband`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(50_000L, playing = true, nowNanoTime = baseNano)

        // 第一次快退 Seek: 50s -> 30s (|delta| = 20s > 1500ms, backward)
        val seek1Nano = baseNano + 100_000_000L
        syncClock.updatePosition(30_000L, playing = true, nowNanoTime = seek1Nano)

        // 100ms 内再次快退 Seek: 30s -> 10s (|delta| = 20s > 1500ms, 合法连续快退)
        val seek2Nano = seek1Nano + 100_000_000L
        syncClock.updatePosition(10_000L, playing = true, nowNanoTime = seek2Nano)

        // 验证连续快退成功应用，没有被 250ms 死区错误吃掉
        val current = syncClock.getCurrentInterpolatedTimeMs(seek2Nano)
        assertEquals(10_000L, current)
    }

    @Test
    fun `hardAlign instantly resets clock anchor and clears interpolation history`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(240_000L, playing = true, nowNanoTime = baseNano)

        // 切歌换曲：上一首歌残留时间 240s，新歌从 0s 开始硬对齐
        syncClock.hardAlign(targetPositionMs = 0L, playing = true, nowNanoTime = baseNano + 100_000_000L)
        val cur = syncClock.getCurrentInterpolatedTimeMs(baseNano + 100_000_000L)
        assertEquals(0L, cur)
    }

    @Test
    fun `forward rewind seek during buffering transitioning is not eaten by maxOf`() {
        val baseNano = 1_000_000_000L
        syncClock.updatePosition(90_000L, playing = true, nowNanoTime = baseNano)

        // 推进到 90_300ms
        val interpolatedTime = syncClock.getCurrentInterpolatedTimeMs(baseNano + 300_000_000L)
        assertEquals(90_300L, interpolatedTime)

        // iPhone 向前拖动到 30_000ms，同时触发缓冲 (playing = false)
        syncClock.updatePosition(30_000L, playing = false, nowNanoTime = baseNano + 301_000_000L)

        // 验证向前拖动被立刻采纳，绝不会被 maxOf 强制限制为 90_300ms
        val frozenTime = syncClock.getCurrentInterpolatedTimeMs(baseNano + 500_000_000L)
        assertEquals(30_000L, frozenTime)
    }

    @Test
    fun `initial warmup ignores spurious 0ms packet but accepts real non-zero protocol progress`() {
        val baseNano = 1_000_000_000L
        // 模拟自然切歌 DMAP 提前 ~2s 到达：先硬对齐至 0ms 开始推演
        syncClock.hardAlign(targetPositionMs = 0L, playing = true, nowNanoTime = baseNano)

        // 走字 1000ms (预充 100ms 扣除后推演到达 900ms)
        val t1 = syncClock.getCurrentInterpolatedTimeMs(baseNano + 1_000_000_000L)
        assertEquals(900L, t1)

        // 1. 协议层在开播前 3.5 秒内补发未就绪 0ms 占位空包仍应拦截，不拉偏时钟
        syncClock.updatePosition(0L, playing = true, nowNanoTime = baseNano + 1_100_000_000L, isUserSeek = false)
        assertTrue(syncClock.getCurrentInterpolatedTimeMs(baseNano + 1_100_000_000L) >= 1000L)

        // 2. 切歌 2.04s 后 AirPlay 下发真实非零进度 268ms（扬声器刚刚开始出声）：必须立即放行对齐，绝不能吞掉导致 +2.8s 跑偏！
        val prog1Nano = baseNano + 2_040_000_000L
        syncClock.updatePosition(268L, playing = true, nowNanoTime = prog1Nano, isUserSeek = false, allowSeekJump = true)
        assertEquals(268L, syncClock.getCurrentInterpolatedTimeMs(prog1Nano))

        // 3. 切歌 3.14s 后 AirPlay 再次下发校准进度 360ms：同样立即对齐，之后 52s 内时钟与真声误差 < 10ms
        val prog2Nano = baseNano + 3_140_000_000L
        syncClock.updatePosition(360L, playing = true, nowNanoTime = prog2Nano, isUserSeek = false, allowSeekJump = true)
        assertEquals(360L, syncClock.getCurrentInterpolatedTimeMs(prog2Nano))

        // 4. 若用户在前 3.5 秒内显式拖动回开头 0ms (isUserSeek = true)，必须放行硬对齐至 0ms
        val userSeekNano = baseNano + 3_300_000_000L
        syncClock.updatePosition(0L, playing = true, nowNanoTime = userSeekNano, isUserSeek = true)
        assertEquals(0L, syncClock.getCurrentInterpolatedTimeMs(userSeekNano))
    }

    @Test
    fun `ringBufferDelayMsProvider accurately subtracts hardware latency from interpolated clock`() {
        var mockDelay = 200L
        val clockWithDelay = LyricsSyncClock(
            maxExtrapolationMs = 1200L,
            jumpThresholdMs = 350L,
            ringBufferDelayMsProvider = { mockDelay }
        )

        val baseNano = 1_000_000_000L
        clockWithDelay.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 经过 500ms，原始推演时间为 10500ms，扣除 200ms 硬件延迟后应为 10300ms
        val t = clockWithDelay.getCurrentInterpolatedTimeMs(baseNano + 500_000_000L)
        assertEquals(10_300L, t)
    }

    @Test
    fun `intro period activates first line at startMs minus leadInMs`() {
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.LRC_LINE_SYNC,
            lines = listOf(
                LyricLine(lineIndex = 0, startMs = 15_000L, endMs = 25_000L, mainText = "First Line"),
                LyricLine(lineIndex = 1, startMs = 25_000L, endMs = 35_000L, mainText = "Second Line")
            )
        )

        // 前奏期提前量门限为 15_000L - 280L = 14_720L
        // 14_700ms 处于前奏未就绪状态
        val preState = syncClock.calculateActiveLineState(timeline, 14_700L)
        assertEquals(-1, preState.lineIndex)

        // 14_720ms 到达提前先导时刻，第 1 行提前激活并启动平滑滚动与居中放大
        val leadInState = syncClock.calculateActiveLineState(timeline, 14_720L)
        assertEquals(0, leadInState.lineIndex)
        assertEquals(0f, leadInState.lineProgressFraction, 0.001f)
    }

    @Test
    fun `standard LRC line switches slightly early within leadInMs window`() {
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.LRC_LINE_SYNC,
            lines = listOf(
                LyricLine(lineIndex = 0, startMs = 5_000L, endMs = 10_000L, mainText = "Line 0"),
                LyricLine(lineIndex = 1, startMs = 10_000L, endMs = 15_000L, mainText = "Line 1")
            )
        )

        // 第 2 行在 10_000L - 280L = 9_720L 前为第 0 行激活
        val state0 = syncClock.calculateActiveLineState(timeline, 9_700L)
        assertEquals(0, state0.lineIndex)

        // 9_720L 进入第 1 行提前先导窗口，第 1 行提前激活
        val state1 = syncClock.calculateActiveLineState(timeline, 9_720L)
        assertEquals(1, state1.lineIndex)
        assertEquals(0f, state1.lineProgressFraction, 0.001f)

        // 10_000L 人声正式响起，已完成滚动与居中，进度开始推进
        val stateAtVocal = syncClock.calculateActiveLineState(timeline, 10_000L)
        assertEquals(1, stateAtVocal.lineIndex)
        assertEquals(0f, stateAtVocal.lineProgressFraction, 0.001f)

        val stateMiddle = syncClock.calculateActiveLineState(timeline, 12_500L)
        assertEquals(1, stateMiddle.lineIndex)
        assertEquals(0.5f, stateMiddle.lineProgressFraction, 0.01f)
    }

    @Test
    fun `word-sync karaoke line with tight gap waits for previous line vocals before activating`() {
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.TTML_SYLLABLE,
            lines = listOf(
                LyricLine(
                    lineIndex = 0,
                    startMs = 5_000L,
                    endMs = 10_000L,
                    mainText = "Line 0",
                    words = listOf(LyricWord("Hello", 5_000L, 9_900L))
                ),
                LyricLine(
                    lineIndex = 1,
                    startMs = 10_000L,
                    endMs = 15_000L,
                    mainText = "Line 1",
                    words = listOf(LyricWord("World", 10_000L, 14_000L))
                )
            )
        )

        // 9_800ms：上一行人声仍在唱（结束于 9_900ms），绝不因提前量截断上一行
        val stateHolding = syncClock.calculateActiveLineState(timeline, 9_800L)
        assertEquals(0, stateHolding.lineIndex)

        // 9_900ms：上一行唱完，立即激活下一行 (提前 100ms 预先准备)
        val stateSwitched = syncClock.calculateActiveLineState(timeline, 9_900L)
        assertEquals(1, stateSwitched.lineIndex)
        assertEquals(0f, stateSwitched.lineProgressFraction, 0.001f)
        assertEquals(-1, stateSwitched.activeWordIndex) // 下一行音节尚未开唱，处于未点亮暗色就绪态

        // 10_000ms：下一行正式开唱
        val stateSinging = syncClock.calculateActiveLineState(timeline, 10_000L)
        assertEquals(1, stateSinging.lineIndex)
        assertEquals(0, stateSinging.activeWordIndex)
    }

    @Test
    fun `tight line spacing preserves previous line when effective start would invert order`() {
        // 测试紧凑间距行（例如第 0 行 5000ms 开唱，第 1 行 5100ms 开唱）
        // 验证 getEffectiveStartMs 不会倒挂早于上一行 5000ms，导致第 0 行被直接跳过
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.LRC_LINE_SYNC,
            lines = listOf(
                LyricLine(
                    lineIndex = 0,
                    startMs = 5_000L,
                    endMs = 5_100L,
                    mainText = "Quick line 0"
                ),
                LyricLine(
                    lineIndex = 1,
                    startMs = 5_100L,
                    endMs = 8_000L,
                    mainText = "Line 1"
                )
            )
        )

        // 4_900ms：处于前奏期，第 0 行因 leadIn (280ms) 提前激活
        val state4900 = syncClock.calculateActiveLineState(timeline, 4_900L)
        assertEquals(0, state4900.lineIndex)

        // 5_020ms：第 0 行正在唱（5000ms~5100ms），第 1 行的 effectiveStart 不能早于 5000ms
        // 第 0 行必须能够获得焦点展示，不能被下一行倒挂提前抢占
        val state5020 = syncClock.calculateActiveLineState(timeline, 5_020L)
        assertEquals(0, state5020.lineIndex)

        // 5_100ms：第 0 行唱完，切换至第 1 行
        val state5100 = syncClock.calculateActiveLineState(timeline, 5_100L)
        assertEquals(1, state5100.lineIndex)
    }

    @Test
    fun `ringBufferDelayMsProvider caches within 16ms frame window reducing redundant JNI calls`() {
        var callCount = 0
        val clock = LyricsSyncClock(
            ringBufferDelayMsProvider = {
                callCount++
                200L
            }
        )
        val baseNano = 1_000_000_000L
        clock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)
        // updatePosition 内部触发 hardAlign，执行了首次采样
        val initialCalls = callCount
        assertTrue("初始化至少采样 1 次: callCount=$initialCalls", initialCalls >= 1)

        // 在同一 16ms 帧内进行 15 次连续高频读取 (模拟 Compose TV 多组件重绘)
        for (offsetMs in 1..15) {
            val t = clock.getCurrentInterpolatedTimeMs(baseNano + offsetMs * 1_000_000L)
            assertTrue(t >= 9800L)
        }
        // 验证 16ms 窗口内全部命中缓存，底层 Provider 未发生额外调用
        assertEquals("16ms 帧内应复用缓存，不产生额外 JNI 调用", initialCalls, callCount)

        // 超过 16ms 跨帧（17ms）后读取，触发新的一帧重新采样
        clock.getCurrentInterpolatedTimeMs(baseNano + 17_000_000L)
        assertEquals("跨帧后应触发第 2 次采样", initialCalls + 1, callCount)
    }

    @Test
    fun `ringBufferDelayMsProvider applies exponential moving average to filter high frequency sawtooth`() {
        var delayValue = 200L
        val clock = LyricsSyncClock(
            ringBufferDelayMsProvider = { delayValue }
        )
        val baseNano = 1_000_000_000L
        clock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 经过 500ms，当前基准延迟已初始化为 200ms
        // 此时声卡 URB 发生突发读取排队，延迟瞬时突变为 300ms (+100ms 锯齿阶跃)
        delayValue = 300L
        val nowNano = baseNano + 500_000_000L
        val t = clock.getCurrentInterpolatedTimeMs(nowNano)

        // rawTimeMs = 10_000 + 500 = 10_500ms
        // 若无 EMA，直接扣减 300ms 将得到 10_200ms (导致倒退或剧烈顿挫)
        // 经 EMA (alpha = 0.15) 滤波后：filteredDelay = 0.15 * 300 + 0.85 * 200 = 215ms
        // hardwareAdjustedTime = 10_500 - 215 = 10_285ms
        assertEquals(10_285L, t)
    }

    @Test
    fun `pausing maintains exact last interpolated time without jumping forward by ringDelay`() {
        val clock = LyricsSyncClock(
            ringBufferDelayMsProvider = { 200L }
        )
        val baseNano = 1_000_000_000L
        clock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)

        // 推进 500ms，推演时间应为 10_000 + 500 - 200 = 10_300ms
        val playingTime = clock.getCurrentInterpolatedTimeMs(baseNano + 500_000_000L)
        assertEquals(10_300L, playingTime)

        // 此时暂停，播放器上报当前音频流位置 10_500ms (扬声器实际才播到 10_300ms)
        clock.updatePosition(10_500L, playing = false, nowNanoTime = baseNano + 500_000_000L)

        // 验证暂停态冻结值严格保持为暂停瞬间的 10_300ms，绝不向前跳变 +200ms 到 10_500ms
        val pausedTime = clock.getCurrentInterpolatedTimeMs(baseNano + 800_000_000L)
        assertEquals(10_300L, pausedTime)
    }

    @Test
    fun `resuming from pause smoothly continues from paused position without forward jump`() {
        val clock = LyricsSyncClock(
            ringBufferDelayMsProvider = { 200L }
        )
        val baseNano = 1_000_000_000L
        clock.updatePosition(10_000L, playing = true, nowNanoTime = baseNano)
        val tBeforePause = clock.getCurrentInterpolatedTimeMs(baseNano + 500_000_000L)
        assertEquals(10_300L, tBeforePause)

        // 暂停：时间冻结在 10_300ms
        clock.updatePosition(10_500L, playing = false, nowNanoTime = baseNano + 500_000_000L)
        assertEquals(10_300L, clock.getCurrentInterpolatedTimeMs(baseNano + 600_000_000L))

        // 暂停 1 秒后恢复播放：播放器继续从 10_500ms 推流
        val resumeNano = baseNano + 1_500_000_000L
        clock.updatePosition(10_500L, playing = true, nowNanoTime = resumeNano)

        // 恢复瞬间 (elapsed 0ms)：扣除 200ms 环形延迟后恰好对齐 10_300ms
        val tResume = clock.getCurrentInterpolatedTimeMs(resumeNano)
        assertEquals(10_300L, tResume)

        // 经过 16ms 渲染帧：平滑单调推演至 10_316ms，无跳变无冻结断层
        val tFrame1 = clock.getCurrentInterpolatedTimeMs(resumeNano + 16_000_000L)
        assertEquals(10_316L, tFrame1)
    }

    @Test
    fun `seek while paused immediately updates frozen clock`() {
        val clock = LyricsSyncClock(
            ringBufferDelayMsProvider = { 200L }
        )
        val baseNano = 1_000_000_000L
        clock.updatePosition(10_000L, playing = false, nowNanoTime = baseNano)
        // 初始暂停冻结在 10_000 - 200 = 9_800ms
        assertEquals(9_800L, clock.getCurrentInterpolatedTimeMs(baseNano))

        // 暂停状态下拖动进度条寻道到 45_000ms (|45000 - 10000| > 350ms)
        clock.updatePosition(45_000L, playing = false, nowNanoTime = baseNano + 100_000_000L)

        // 验证暂停态寻道立即刷新冻结时钟基准为 45_000 - 200 = 44_800ms
        val seekTime = clock.getCurrentInterpolatedTimeMs(baseNano + 200_000_000L)
        assertEquals(44_800L, seekTime)
    }

    @Test
    fun `mutationVersion increments on hardAlign, seek jump, state change, and reset, but NOT on steady state ticks`() {
        val clock = LyricsSyncClock(
            maxExtrapolationMs = 1200L,
            jumpThresholdMs = 350L
        )
        val baseNano = 1_000_000_000L
        assertEquals(0L, clock.mutationVersion)

        // 1. hardAlign 显式硬对齐：自增 mutationVersion
        clock.hardAlign(targetPositionMs = 10_000L, playing = true, nowNanoTime = baseNano)
        assertEquals(1L, clock.mutationVersion)

        // 2. 稳态播放连续推进 (常规 500ms 离散上报，无跳变，playing 保持 true)：严禁自增 mutationVersion！
        clock.updatePosition(newPositionMs = 10_500L, playing = true, nowNanoTime = baseNano + 500_000_000L)
        assertEquals("稳态 500ms updatePosition 绝不能递增 mutationVersion (杜绝 2Hz 重组与动画重启)", 1L, clock.mutationVersion)

        clock.updatePosition(newPositionMs = 11_000L, playing = true, nowNanoTime = baseNano + 1_000_000_000L)
        assertEquals("稳态下版本号保持稳定", 1L, clock.mutationVersion)

        // 3. 状态切换：播放 -> 暂停，应该自增 mutationVersion
        clock.updatePosition(newPositionMs = 11_200L, playing = false, nowNanoTime = baseNano + 1_200_000_000L)
        assertEquals(2L, clock.mutationVersion)

        // 暂停稳态持续上报微小波动 (playing 保持 false，无大幅跳变)：不自增
        clock.updatePosition(newPositionMs = 11_200L, playing = false, nowNanoTime = baseNano + 1_700_000_000L)
        assertEquals(2L, clock.mutationVersion)

        // 4. 暂停态下 Seek 进度大幅跳变 (|45000 - 11200| > 350ms)：应该自增 mutationVersion
        clock.updatePosition(newPositionMs = 45_000L, playing = false, nowNanoTime = baseNano + 1_800_000_000L)
        assertEquals(3L, clock.mutationVersion)

        // 5. 状态切换：暂停 -> 播放，应该自增 mutationVersion
        clock.updatePosition(newPositionMs = 45_000L, playing = true, nowNanoTime = baseNano + 2_000_000_000L)
        assertEquals(4L, clock.mutationVersion)

        // 6. 播放态下 Seek 进度大幅跳变 (|80000 - 45000| > 350ms)：应该自增 mutationVersion
        clock.updatePosition(newPositionMs = 80_000L, playing = true, nowNanoTime = baseNano + 2_500_000_000L)
        assertEquals(5L, clock.mutationVersion)

        // 7. reset 必须补上自增 mutationVersion
        clock.reset()
        assertEquals(6L, clock.mutationVersion)
    }

    @Test
    fun `tight word-sync gap scrolls next line 450ms early while keeping active vocal line intact`() {
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.TTML_SYLLABLE,
            lines = listOf(
                LyricLine(
                    lineIndex = 0,
                    startMs = 5_000L,
                    endMs = 10_000L,
                    mainText = "Line 0",
                    words = listOf(LyricWord("Hello", 5_000L, 9_950L))
                ),
                LyricLine(
                    lineIndex = 1,
                    startMs = 10_000L,
                    endMs = 15_000L,
                    mainText = "Line 1",
                    words = listOf(LyricWord("World", 10_000L, 14_000L))
                )
            )
        )

        // 9_500ms：距离下一句开口 500ms (> 450ms)，仍在第 0 行
        val stateBefore = syncClock.calculateActiveLineState(timeline, 9_500L)
        assertEquals(0, stateBefore.lineIndex)
        assertEquals(0, stateBefore.scrollLineIndex)

        // 9_600ms：距离下一句开口 400ms (<= 450ms)，滚动锚点已提前切到第 1 行启动弹簧上滑，
        // 同时第 0 行最后一个字尚未唱完 (9_950ms)，lineIndex 严格保持 0 不截断扫光！
        val statePreScroll = syncClock.calculateActiveLineState(timeline, 9_600L)
        assertEquals(0, statePreScroll.lineIndex)
        assertEquals(1, statePreScroll.scrollLineIndex)

        // 9_950ms：上一行唱完，高亮也切入第 1 行准备开口
        val stateSwitched = syncClock.calculateActiveLineState(timeline, 9_950L)
        assertEquals(1, stateSwitched.lineIndex)
        assertEquals(1, stateSwitched.scrollLineIndex)
    }

    @Test
    fun `overlapping previous line endMs never bounces activeLineIndex backward after current line finishes`() {
        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.TTML_SYLLABLE,
            lines = listOf(
                // 模拟上一行带有长和声拖尾，其 endMs 覆盖并超过下一行
                LyricLine(
                    lineIndex = 0,
                    startMs = 10_000L,
                    endMs = 16_000L,
                    mainText = "Previous line with long tail",
                    words = listOf(LyricWord("Previous", 10_000L, 12_500L))
                ),
                LyricLine(
                    lineIndex = 1,
                    startMs = 13_000L,
                    endMs = 14_500L,
                    mainText = "Current short line",
                    words = listOf(LyricWord("Current", 13_000L, 14_500L))
                ),
                LyricLine(
                    lineIndex = 2,
                    startMs = 15_500L,
                    endMs = 18_000L,
                    mainText = "Next line",
                    words = listOf(LyricWord("Next", 15_500L, 18_000L))
                )
            )
        )

        // 14_501ms：第 1 行刚唱完，尚未到第 2 行激活点；绝不能因第 0 行 endMs=16_000ms 而倒跳回 0 引发闪暗回跳
        assertEquals(1, syncClock.calculateActiveLineIndex(timeline, 14_501L))
        assertEquals(1, syncClock.calculateActiveLineIndex(timeline, 15_000L))
    }

    @Test
    fun `line-sync lead-in constants provide settle gap between scroll start and highlight`() {
        val timeline = LyricTimeline(
            source = "lrc",
            format = LyricFormat.LRC_LINE_SYNC,
            lines = listOf(
                LyricLine(lineIndex = 0, startMs = 5_000L, endMs = 10_000L, mainText = "Line 0"),
                LyricLine(lineIndex = 1, startMs = 10_000L, endMs = 15_000L, mainText = "Line 1")
            )
        )

        // 9_450ms (startMs - 550ms)：非逐字行开始上滑，但此时尚未高亮（仍保持第 0 行高亮）
        assertEquals(1, syncClock.calculateScrollLineIndex(timeline, 9_450L, LyricsSyncClock.LINE_SYNC_SCROLL_LEAD_IN_MS))
        assertEquals(0, syncClock.calculateActiveLineIndex(timeline, 9_450L, LyricsSyncClock.LINE_SYNC_HIGHLIGHT_LEAD_IN_MS))

        // 9_850ms：弹簧已基本落位停稳，仍留有短暂静止间隙
        assertEquals(0, syncClock.calculateActiveLineIndex(timeline, 9_850L, LyricsSyncClock.LINE_SYNC_HIGHLIGHT_LEAD_IN_MS))

        // 9_900ms (startMs - 100ms)：落位后干脆点亮第 1 行
        assertEquals(1, syncClock.calculateActiveLineIndex(timeline, 9_900L, LyricsSyncClock.LINE_SYNC_HIGHLIGHT_LEAD_IN_MS))
    }

    @Test
    fun `queue anchorDelayMs holds clock at 0ms until speaker playback starts without jumping`() {
        val baseNano = 1_000_000_000L
        // 1. 新曲目 DMAP 先行到达并开始走表
        syncClock.hardAlign(targetPositionMs = 0L, playing = true, nowNanoTime = baseNano)
        assertEquals(400L, syncClock.getCurrentInterpolatedTimeMs(baseNano + 500_000_000L))

        // 2. 500ms 后收到协议上报精确排队延迟 1200ms (ref_ts 仍早于 start_ts 1200ms)
        val anchorNano = baseNano + 500_000_000L
        syncClock.updatePosition(
            newPositionMs = 0L,
            playing = true,
            nowNanoTime = anchorNano,
            allowSeekJump = true,
            anchorDelayMs = 1_200L
        )

        // 3. 在 1200ms 排队等待期间，时钟严格保持 0ms 不抢跑
        assertEquals(0L, syncClock.getCurrentInterpolatedTimeMs(anchorNano + 600_000_000L))
        assertEquals(0L, syncClock.getCurrentInterpolatedTimeMs(anchorNano + 1_199_000_000L))

        // 4. 超过 1200ms 到达扬声器出声时刻后无缝推进
        assertEquals(250L, syncClock.getCurrentInterpolatedTimeMs(anchorNano + 1_450_000_000L))
    }
}
