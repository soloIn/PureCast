package com.solo.purecast.ui.lyrics

import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 💡 歌词渲染与动效数学模型/几何计算生产单元测试：
 * 全量直调生产代码 [LyricsRenderingMath] 与 [LyricsSyncClock]，彻底杜绝“自写公式断言自身”的假绿测试。
 * 覆盖 Finding 3、Finding 6、Finding 9、Finding 10、Finding 12 的生产纯函数与 StateFlow 响应流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRenderingTest {

    // ==========================================
    // Finding 12 & Finding 9: 词尾空白剔除与边界安全钳位
    // ==========================================

    @Test
    fun `finding 12 - word trailing whitespace is trimmed to prevent false line wrap`() {
        val fullText = "Never gonna give you up"
        val wordWithTrailingSpace = "gonna "
        val startChar = fullText.indexOf(wordWithTrailingSpace)
        assertEquals(6, startChar)

        // 直调生产函数 LyricsRenderingMath.clampSafeCharBounds
        val (safeStart, endChar) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = fullText,
            startChar = startChar,
            wordText = wordWithTrailingSpace
        )

        // 验证：trimEnd() 确保 endChar 指向有效字母 'a' (10)，而不是末尾空格 (11)
        assertEquals(6, safeStart)
        assertEquals(10, endChar)
        assertEquals('a', fullText[endChar])
        assertEquals(' ', fullText[11])

        // 验证多重连续尾随空格场景
        val (multiSpaceStart, multiSpaceEnd) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = "Never gonna   give you up",
            startChar = 6,
            wordText = "gonna   "
        )
        assertEquals(6, multiSpaceStart)
        assertEquals(10, multiSpaceEnd)
    }

    @Test
    fun `finding 9 - safeStart and endChar boundary clamping never throws IllegalArgumentException`() {
        // 1. 空文本边界：fullText.isEmpty() 必须安全返回 (0, 0)，绝不抛出越界异常
        val (emptyStart0, emptyEnd0) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = "",
            startChar = 0,
            wordText = ""
        )
        assertEquals(0, emptyStart0)
        assertEquals(0, emptyEnd0)

        val (emptyStart1, emptyEnd1) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = "",
            startChar = 10,
            wordText = "hello"
        )
        assertEquals(0, emptyStart1)
        assertEquals(0, emptyEnd1)

        val (emptyStart2, emptyEnd2) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = "",
            startChar = -5,
            wordText = "   "
        )
        assertEquals(0, emptyStart2)
        assertEquals(0, emptyEnd2)

        // 2. 超短文本越界 startChar 钳位
        val (shortStart, shortEnd) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = "Hi",
            startChar = 10,
            wordText = "Hello World"
        )
        assertEquals(1, shortStart)
        assertEquals(1, shortEnd)

        // 3. 负数 startChar 安全收敛到 0
        val (negStart, negEnd) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = "Hello",
            startChar = -3,
            wordText = "He"
        )
        assertEquals(0, negStart)
        assertEquals(1, negEnd)

        // 4. 全空格 wordText 边界安全
        val (spaceOnlyStart, spaceOnlyEnd) = LyricsRenderingMath.clampSafeCharBounds(
            fullText = "Hello",
            startChar = 2,
            wordText = "   "
        )
        assertEquals(2, spaceOnlyStart)
        assertEquals(2, spaceOnlyEnd)
    }

    // ==========================================
    // Finding 12: 动态扫光羽化区间锚定与平滑展开
    // ==========================================

    @Test
    fun `finding 12 - feather interval converges at word left when progress is zero`() {
        val feather = 30f
        val wordLeft = 100f

        // 1. 词首开唱瞬间 (wordProgress == 0f)：直调生产函数 calculateEffectiveFeather
        val (sweepStart0, effectiveFeather0) = LyricsRenderingMath.calculateEffectiveFeather(
            sweep = 100f,
            feather = feather,
            wordLeft = wordLeft,
            wordProgress = 0f
        )
        // 验证：在词首时羽化宽度收敛至 1px，sweepStart 精确停在 wordLeft，彻底杜绝提前点亮未唱字符
        assertEquals(100f, sweepStart0, 0.001f)
        assertEquals(1f, effectiveFeather0, 0.001f)

        // 2. 爬坡展开阶段 (wordProgress == 0.1f，达到 0.2f 阈值的一半)：
        // 展开系数 = 0.1 / 0.2 = 0.5f，半羽化 = 15f * 0.5f = 7.5f，羽化总宽 = 15f
        val (sweepStartMid, effectiveFeatherMid) = LyricsRenderingMath.calculateEffectiveFeather(
            sweep = 115f,
            feather = feather,
            wordLeft = wordLeft,
            wordProgress = 0.1f
        )
        assertEquals(107.5f, sweepStartMid, 0.001f)
        assertEquals(15f, effectiveFeatherMid, 0.001f)

        // 3. 稳态扫光阶段 (wordProgress >= 0.2f)：展开至满额对称 30f 标准羽化宽度
        val (sweepStartSteady, effectiveFeatherSteady) = LyricsRenderingMath.calculateEffectiveFeather(
            sweep = 175f,
            feather = feather,
            wordLeft = wordLeft,
            wordProgress = 0.5f
        )
        assertEquals(160f, sweepStartSteady, 0.001f)
        assertEquals(30f, effectiveFeatherSteady, 0.001f)

        // 4. 边界保护：负进度与超额进度安全测试
        val (sweepStartNeg, effectiveFeatherNeg) = LyricsRenderingMath.calculateEffectiveFeather(
            sweep = 100f,
            feather = feather,
            wordLeft = wordLeft,
            wordProgress = -0.5f
        )
        assertEquals(100f, sweepStartNeg, 0.001f)
        assertEquals(1f, effectiveFeatherNeg, 0.001f)
    }

    // ==========================================
    // Finding 6: 折行跨行单词视觉行片段划分与局部进度
    // ==========================================

    @Test
    fun `finding 6 - wrapped multi-line word sweeps uniformly across visual line segments`() {
        // 模拟折行单词：总计 10 个字符 (safeStart = 0, endChar = 9)
        // 字符 0..4 位于视觉第 0 行，字符 5..9 位于视觉第 1 行
        val lineMapping = { offset: Int -> if (offset < 5) 0 else 1 }

        // 1. 测试进度 0.25 (对应第 0 行 5 个字符的前半程)
        val seg0 = LyricsRenderingMath.calculateWrappedLineSegment(
            safeStart = 0,
            endChar = 9,
            progress = 0.25f,
            getLineForOffset = lineMapping
        )
        assertEquals(0, seg0.visualLine)
        assertEquals(0, seg0.segStartChar)
        assertEquals(4, seg0.segEndChar)
        assertEquals(0.5f, seg0.segProgress, 0.001f)

        // 2. 测试进度 0.75 (对应第 1 行 5 个字符的前半程)
        val seg1 = LyricsRenderingMath.calculateWrappedLineSegment(
            safeStart = 0,
            endChar = 9,
            progress = 0.75f,
            getLineForOffset = lineMapping
        )
        assertEquals(1, seg1.visualLine)
        assertEquals(5, seg1.segStartChar)
        assertEquals(9, seg1.segEndChar)
        assertEquals(0.5f, seg1.segProgress, 0.001f)

        // 3. 词首开唱 (progress = 0f) 与词尾唱毕 (progress = 1f) 边界测试
        val segStart = LyricsRenderingMath.calculateWrappedLineSegment(0, 9, 0f, lineMapping)
        assertEquals(0, segStart.visualLine)
        assertEquals(0f, segStart.segProgress, 0.001f)

        val segEnd = LyricsRenderingMath.calculateWrappedLineSegment(0, 9, 1.0f, lineMapping)
        assertEquals(1, segEnd.visualLine)
        assertEquals(1.0f, segEnd.segProgress, 0.001f)
    }

    // ==========================================
    // Finding 3: 38% 黄金视线分割点与滚动偏移量计算
    // ==========================================

    @Test
    fun `finding 3 - visibleItem offset is already relative to viewport container without adding topPaddingPx`() {
        val viewportHeightPx = 1080f
        val postItemOffset = 374.4f // 处于 38% 黄金分割基准处
        val postItemSize = 72f

        // 直调生产函数 LyricsRenderingMath.calculateViewportScrollDelta
        val delta = LyricsRenderingMath.calculateViewportScrollDelta(
            itemOffsetInViewport = postItemOffset,
            itemSize = postItemSize,
            viewportHeightPx = viewportHeightPx,
            targetRatio = 0.38f
        )
        // 验证：374.4 + 36 = 410.4f，正好等于 1080 * 0.38f，微调位移精确为 0f，完美居中
        assertEquals(0f, delta, 0.001f)
        assertTrue("直接使用 offset + size / 2f 时 fineDelta 为 0，完美居中 38% 黄金视线", abs(delta) < 0.001f)

        // 场景 B：超高歌词行 (高度 120px，中心偏移 60px)
        val tallDelta = LyricsRenderingMath.calculateViewportScrollDelta(
            itemOffsetInViewport = postItemOffset,
            itemSize = 120f,
            viewportHeightPx = viewportHeightPx,
            targetRatio = 0.38f
        )
        // (374.4 + 60) - 410.4 = 24.0f
        assertEquals(24f, tallDelta, 0.001f)

        // 场景 C：Compose LazyColumn 带 contentPadding(top = 374.4f) 时，viewportStartOffset == -374.4f，首行 offset == 0f
        val paddedItem0Delta = LyricsRenderingMath.calculateViewportScrollDelta(
            itemOffsetInViewport = 0f,
            itemSize = 72f,
            viewportHeightPx = viewportHeightPx,
            targetRatio = 0.38f,
            viewportStartOffset = -374.4f
        )
        assertEquals(0f, paddedItem0Delta, 0.001f)

        // 第 2 行相对内容区 offset = 100f -> 应向下滚动 100f 将其移至 38% 中心
        val paddedItem1Delta = LyricsRenderingMath.calculateViewportScrollDelta(
            itemOffsetInViewport = 100f,
            itemSize = 72f,
            viewportHeightPx = viewportHeightPx,
            targetRatio = 0.38f,
            viewportStartOffset = -374.4f
        )
        assertEquals(100f, paddedItem1Delta, 0.001f)
    }

    // ==========================================
    // Finding 10: 歌词行与翻译行层级透明度与字重排版保护
    // ==========================================

    @Test
    fun `finding 10 - word sync active line keeps outer alpha at 1 avoiding double dimming flash`() {
        // 直调生产函数 LyricsRenderingMath.calculateLineTargetAlpha
        val activeAlpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive = true, distanceFromActive = 0)
        assertEquals(1.0f, activeAlpha, 0.001f)

        // 非激活行按距离递减
        val dist1Alpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive = false, distanceFromActive = 1)
        assertEquals(0.36f, dist1Alpha, 0.001f)

        val dist2Alpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive = false, distanceFromActive = 2)
        assertEquals(0.30f, dist2Alpha, 0.001f)

        // 远距离钳位在 0.16f 下限
        val farAlpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive = false, distanceFromActive = 10)
        assertEquals(0.16f, farAlpha, 0.001f)
    }

    @Test
    fun `finding 10 - translation color alpha fixed to 0_78 without conditional double dip`() {
        // 直调生产常量与计算函数 LyricsRenderingMath.calculateEffectiveTranslationAlpha
        assertEquals(0.78f, LyricsRenderingMath.TRANSLATION_ALPHA, 0.001f)

        val activeTransAlpha = LyricsRenderingMath.calculateEffectiveTranslationAlpha(outerAlpha = 1.0f)
        assertEquals(0.78f, activeTransAlpha, 0.001f)

        val inactiveTransAlpha = LyricsRenderingMath.calculateEffectiveTranslationAlpha(outerAlpha = 0.42f)
        assertEquals(0.78f * 0.42f, inactiveTransAlpha, 0.001f)
    }

    // ==========================================
    // LyricsSyncClock: 生产能力集成验证
    // ==========================================

    @Test
    fun `lyricsSyncClock calculateActiveLineState tracks active line and syllable progress accurately`() {
        val clock = LyricsSyncClock(
            maxExtrapolationMs = 1000L,
            jumpThresholdMs = 350L,
            lineLeadInMs = 280L
        )

        val timeline = LyricTimeline(
            source = "test",
            format = LyricFormat.TTML_SYLLABLE,
            lines = listOf(
                LyricLine(
                    lineIndex = 0,
                    startMs = 1000L,
                    endMs = 3000L,
                    mainText = "Hello World",
                    words = listOf(
                        LyricWord(startMs = 1000L, endMs = 1800L, text = "Hello "),
                        LyricWord(startMs = 1800L, endMs = 3000L, text = "World")
                    )
                ),
                LyricLine(
                    lineIndex = 1,
                    startMs = 4000L,
                    endMs = 6000L,
                    mainText = "Second Line",
                    words = listOf(
                        LyricWord(startMs = 4000L, endMs = 6000L, text = "Second Line")
                    )
                )
            )
        )

        // 1. 空歌词时间轴：返回 lineIndex = -1
        val emptyState = clock.calculateActiveLineState(
            LyricTimeline(source = "empty", format = LyricFormat.LRC_LINE_SYNC, lines = emptyList()),
            1000L
        )
        assertEquals(-1, emptyState.lineIndex)
        assertEquals(0f, emptyState.lineProgressFraction, 0.001f)

        // 2. 前奏期 (currentTime < firstEffectiveStart = 1000 - 280 = 720ms)：返回 -1
        val introState = clock.calculateActiveLineState(timeline, 500L)
        assertEquals(-1, introState.lineIndex)

        // 3. 视线先导期 (720ms <= currentTime < 1000ms)：首行提前激活，lineIndex = 0，进度 0f
        val leadInState = clock.calculateActiveLineState(timeline, 800L)
        assertEquals(0, leadInState.lineIndex)
        assertEquals(0f, leadInState.lineProgressFraction, 0.001f)
        assertEquals(-1, leadInState.activeWordIndex)

        // 4. 正在演唱第一词 "Hello" (1400ms，在 1000..1800 区间中点)：
        val singingWord0State = clock.calculateActiveLineState(timeline, 1400L)
        assertEquals(0, singingWord0State.lineIndex)
        assertEquals(0, singingWord0State.activeWordIndex)
        assertEquals(0.5f, singingWord0State.wordProgressFraction, 0.001f)

        // 5. 正在演唱第二词 "World" (2400ms，在 1800..3000 区间中点)：
        val singingWord1State = clock.calculateActiveLineState(timeline, 2400L)
        assertEquals(0, singingWord1State.lineIndex)
        assertEquals(1, singingWord1State.activeWordIndex)
        assertEquals(0.5f, singingWord1State.wordProgressFraction, 0.001f)
    }

    @Test
    fun `lyricsSyncClock mutationVersionFlow strictly emits on hardAlign, seek, state change, and reset`() = runTest {
        val clock = LyricsSyncClock(
            maxExtrapolationMs = 1200L,
            jumpThresholdMs = 350L
        )

        val collectedVersions = mutableListOf<Long>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            clock.mutationVersionFlow.collect { collectedVersions.add(it) }
        }

        // 初始状态 StateFlow 默认发射 0L
        assertEquals(listOf(0L), collectedVersions)

        // 1. hardAlign 显式硬对齐：必须发射新版本 (1L)
        clock.hardAlign(targetPositionMs = 10_000L, playing = true, nowNanoTime = 1_000_000_000L)
        assertEquals(listOf(0L, 1L), collectedVersions)

        // 2. 稳态播放连续推进 (常规 500ms 离散上报，无跳变，playing 保持 true)：严禁发射任何版本！
        clock.updatePosition(newPositionMs = 10_500L, playing = true, nowNanoTime = 1_500_000_000L)
        assertEquals("稳态推进严禁发射新版本", listOf(0L, 1L), collectedVersions)

        clock.updatePosition(newPositionMs = 11_000L, playing = true, nowNanoTime = 2_000_000_000L)
        assertEquals("稳态推进严禁发射新版本", listOf(0L, 1L), collectedVersions)

        // 3. 状态切换：播放 -> 暂停，必须发射新版本 (2L)
        clock.updatePosition(newPositionMs = 11_200L, playing = false, nowNanoTime = 2_200_000_000L)
        assertEquals(listOf(0L, 1L, 2L), collectedVersions)

        // 暂停稳态持续心跳上报：无状态改变，无跳变，严禁发射
        clock.updatePosition(newPositionMs = 11_200L, playing = false, nowNanoTime = 2_700_000_000L)
        assertEquals(listOf(0L, 1L, 2L), collectedVersions)

        // 4. 暂停态下 Seek 进度大幅跳变 (|45000 - 11200| > 350ms)：必须发射新版本 (3L)
        clock.updatePosition(newPositionMs = 45_000L, playing = false, nowNanoTime = 2_800_000_000L)
        assertEquals(listOf(0L, 1L, 2L, 3L), collectedVersions)

        // 5. 状态切换：暂停 -> 播放，必须发射新版本 (4L)
        clock.updatePosition(newPositionMs = 45_000L, playing = true, nowNanoTime = 3_000_000_000L)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), collectedVersions)

        // 6. 播放态下 Seek 进度大幅跳变 (|80000 - 45000| > 350ms)：必须发射新版本 (5L)
        clock.updatePosition(newPositionMs = 80_000L, playing = true, nowNanoTime = 3_500_000_000L)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), collectedVersions)

        // 7. reset 重置时钟：必须发射新版本 (6L)
        clock.reset()
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L), collectedVersions)

        collectJob.cancel()
    }

    // ==========================================
    // AMLL / Apple Music 逐行弹簧波浪参数与长间奏静息判定
    // ==========================================

    @Test
    fun `calculateWaveSpringParams adapts stiffness and stagger by inter-line gap`() {
        // 1. 首行或空行兜底：返回标准临界阻尼参数
        val defaultParams = LyricsRenderingMath.calculateWaveSpringParams(prevLine = null, currentLine = null)
        assertEquals(300f, defaultParams.stiffness, 0.001f)
        assertEquals(1.0f, defaultParams.dampingRatio, 0.001f)
        assertEquals(34L, defaultParams.staggerStepMs)

        // 2. 快歌/密集连唱 (startGapMs < 1500ms)：更紧致弹簧与更短错峰
        val fastPrev = LyricLine(lineIndex = 0, startMs = 10_000L, endMs = 11_000L, mainText = "Fast 1")
        val fastCurr = LyricLine(lineIndex = 1, startMs = 11_000L, endMs = 12_000L, mainText = "Fast 2")
        val fastParams = LyricsRenderingMath.calculateWaveSpringParams(fastPrev, fastCurr)
        assertEquals(380f, fastParams.stiffness, 0.001f)
        assertEquals(22L, fastParams.staggerStepMs)

        // 3. 标准 LRC 常规节奏 (startGapMs = 2800ms, endMs == next.startMs 导致 vocalGapMs == 0)：
        //    严禁误判为快歌，必须落入常规档位
        val lrcPrev = LyricLine(lineIndex = 0, startMs = 10_000L, endMs = 12_800L, mainText = "LRC 1")
        val lrcCurr = LyricLine(lineIndex = 1, startMs = 12_800L, endMs = 15_600L, mainText = "LRC 2")
        val normalParams = LyricsRenderingMath.calculateWaveSpringParams(lrcPrev, lrcCurr)
        assertEquals(300f, normalParams.stiffness, 0.001f)
        assertEquals(34L, normalParams.staggerStepMs)

        // 4. 抒情慢歌 (startGapMs > 4500ms) 或明显人声停顿 (vocalGapMs >= 1500ms)：更舒缓弹性
        val balladPrev = LyricLine(
            lineIndex = 0,
            startMs = 20_000L,
            endMs = 22_000L,
            mainText = "Ballad 1",
            words = listOf(LyricWord(startMs = 20_000L, endMs = 21_500L, text = "Ballad 1"))
        )
        val balladCurr = LyricLine(lineIndex = 1, startMs = 23_200L, endMs = 26_000L, mainText = "Ballad 2")
        val balladParams = LyricsRenderingMath.calculateWaveSpringParams(balladPrev, balladCurr)
        assertEquals(240f, balladParams.stiffness, 0.001f)
        assertEquals(42L, balladParams.staggerStepMs)
    }

    @Test
    fun `calculateViewportEdgeFadeAlpha smoothly fades near viewport top and bottom edges`() {
        val viewportH = 1000f
        val topFade = 100f
        val bottomFade = 120f

        // 视口核心区域恒为 1.0f
        assertEquals(1f, LyricsRenderingMath.calculateViewportEdgeFadeAlpha(380f, viewportH, topFade, bottomFade), 0.001f)
        assertEquals(1f, LyricsRenderingMath.calculateViewportEdgeFadeAlpha(500f, viewportH, topFade, bottomFade), 0.001f)

        // 顶部中点 (t = 0.5 -> smoothstep = 0.5) 与越界 (<= 0 -> 0f)
        assertEquals(0.5f, LyricsRenderingMath.calculateViewportEdgeFadeAlpha(50f, viewportH, topFade, bottomFade), 0.001f)
        assertEquals(0f, LyricsRenderingMath.calculateViewportEdgeFadeAlpha(0f, viewportH, topFade, bottomFade), 0.001f)
        assertEquals(0f, LyricsRenderingMath.calculateViewportEdgeFadeAlpha(-20f, viewportH, topFade, bottomFade), 0.001f)

        // 底部中点 (1000 - 940 = 60 -> t = 0.5 -> smoothstep = 0.5) 与越界 (>= 1000 -> 0f)
        assertEquals(0.5f, LyricsRenderingMath.calculateViewportEdgeFadeAlpha(940f, viewportH, topFade, bottomFade), 0.001f)
        assertEquals(0f, LyricsRenderingMath.calculateViewportEdgeFadeAlpha(1000f, viewportH, topFade, bottomFade), 0.001f)
    }

    @Test
    fun `calculateLineTargetBlurDp scales progressively with distance from active line`() {
        assertEquals(0f, LyricsRenderingMath.calculateLineTargetBlurDp(isActive = true, distanceFromActive = 0), 0.001f)
        assertEquals(1.2f, LyricsRenderingMath.calculateLineTargetBlurDp(isActive = false, distanceFromActive = 1), 0.001f)
        assertEquals(2.0f, LyricsRenderingMath.calculateLineTargetBlurDp(isActive = false, distanceFromActive = 2), 0.001f)
        assertEquals(2.8f, LyricsRenderingMath.calculateLineTargetBlurDp(isActive = false, distanceFromActive = 5), 0.001f)
    }

    @Test
    fun `isInInterludeRest detects long interludes and outro without false positives on short gaps or standard LRC`() {
        val wordSyncTimeline = LyricTimeline(
            source = "test",
            format = LyricFormat.TTML_SYLLABLE,
            lines = listOf(
                LyricLine(
                    lineIndex = 0,
                    startMs = 1_000L,
                    endMs = 3_000L,
                    mainText = "Short gap line",
                    words = listOf(LyricWord(startMs = 1_000L, endMs = 2_800L, text = "Short gap line"))
                ),
                LyricLine(
                    lineIndex = 1,
                    startMs = 4_500L,
                    endMs = 7_000L,
                    mainText = "Before long interlude",
                    words = listOf(LyricWord(startMs = 4_500L, endMs = 6_500L, text = "Before long interlude"))
                ),
                LyricLine(
                    lineIndex = 2,
                    startMs = 12_000L,
                    endMs = 15_000L,
                    mainText = "Final outro line",
                    words = listOf(LyricWord(startMs = 12_000L, endMs = 14_500L, text = "Final outro line"))
                )
            )
        )

        // 越界索引（前奏 -1 / 超界）返回 false
        assertEquals(false, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, -1, 500L))
        assertEquals(false, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 99, 500L))

        // 短间隙与常规换气停顿 (1700ms / 3500ms < 5000ms)：始终 false
        assertEquals(false, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 0, 3_500L))

        // 长间奏 (12000 - 6500 = 5500ms >= 5000ms)：
        // 唱完后 800ms 内 (7200ms) 不静息；7300ms 起持续静息直至切入下一句 (index = 2)，绝不在 index = 1 期间提前退静息导致上一行回亮
        assertEquals(false, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 1, 7_200L))
        assertEquals(true, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 1, 7_300L))
        assertEquals(true, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 1, 11_500L))
        assertEquals(false, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 2, 11_800L))

        // 歌曲尾奏：末句唱完 1500ms 后进入静息 (14500 + 1500 = 16000ms)
        assertEquals(false, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 2, 15_900L))
        assertEquals(true, LyricsRenderingMath.isInInterludeRest(wordSyncTimeline, 2, 16_000L))

        // 标准 LRC (line.endMs == nextLine.startMs)：曲中间奏保守返回 false，不误熄灭长拖音
        val lrcTimeline = LyricTimeline(
            source = "lrc",
            format = LyricFormat.LRC_LINE_SYNC,
            lines = listOf(
                LyricLine(lineIndex = 0, startMs = 1_000L, endMs = 15_000L, mainText = "Held LRC line"),
                LyricLine(lineIndex = 1, startMs = 15_000L, endMs = 20_000L, mainText = "Next LRC line")
            )
        )
        assertEquals(false, LyricsRenderingMath.isInInterludeRest(lrcTimeline, 0, 10_000L))
    }

    // ==========================================
    // 两段式边界唤醒调度决策 (delay + VSYNC)
    // ==========================================

    @Test
    fun `calculateNextWakeupDelayMs transitions cleanly from coarse delay to VSYNC handoff and idle outro`() {
        // 1. 尾奏/空歌词无后续边界：返回 -1L 彻底挂起，0 CPU 空转
        assertEquals(-1L, LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 180_000L, nextBoundaryMs = Long.MAX_VALUE))

        // 2. 远距边界 (> 532ms)：钳位在单次粗睡眠上限 500ms，兼顾低唤醒与非 mutation 进度校时兜底
        assertEquals(
            LyricsRenderingMath.WAKEUP_MAX_COARSE_SLEEP_MS,
            LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_000L, nextBoundaryMs = 13_000L)
        )
        assertEquals(
            LyricsRenderingMath.WAKEUP_MAX_COARSE_SLEEP_MS,
            LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_000L, nextBoundaryMs = 10_532L)
        )

        // 3. 中距边界 (65ms..531ms)：提前 32ms (~2 帧) 唤醒交由 VSYNC 精控
        assertEquals(168L, LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_000L, nextBoundaryMs = 10_200L))
        assertEquals(33L, LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_000L, nextBoundaryMs = 10_065L))

        // 4. VSYNC 精控窗口 (<= 64ms) 以及已到达/越过边界 (<= 0ms)：返回 0L 走 withFrameNanos 精确对齐且防忙等
        assertEquals(0L, LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_000L, nextBoundaryMs = 10_064L))
        assertEquals(0L, LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_000L, nextBoundaryMs = 10_016L))
        assertEquals(0L, LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_000L, nextBoundaryMs = 10_000L))
        assertEquals(0L, LyricsRenderingMath.calculateNextWakeupDelayMs(nowMs = 10_050L, nextBoundaryMs = 10_000L))
    }
}
