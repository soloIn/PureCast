package com.solo.purecast.ui.lyrics

import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline

/**
 * 💡 歌词渲染与动态扫光几何数学计算工具类
 * 抽取纯函数便于生产复用与单元测试覆盖，杜绝越界异常与异常扫光闪烁。
 */
object LyricsRenderingMath {

    /** 简体中文/次级翻译固定基础透明度 */
    const val TRANSLATION_ALPHA: Float = 0.78f

    /** 长前奏三点呼吸指示器最小前奏时长 (ms) */
    const val INTRO_DOTS_MIN_START_MS: Long = 4500L

    /** 曲中长间奏最小人声空拍时长 (ms)：过滤 2.5s~4.5s 普通乐句换气停顿，仅在真正间奏时展示 ··· */
    const val INTERLUDE_MIN_GAP_MS: Long = 5000L

    /** 曲中长间奏进入静息延迟 (ms)：上一句唱完后等待 800ms 再温和过渡至间奏呼吸态 */
    const val INTERLUDE_ENTER_DELAY_MS: Long = 800L

    /** 歌曲尾奏进入静息延迟 (ms) */
    const val OUTRO_ENTER_DELAY_MS: Long = 1500L

    /** 距下一歌词边界 <= 64ms 时切回逐帧 VSYNC 精确对齐 60Hz 帧边界 */
    const val WAKEUP_VSYNC_HANDOFF_MS: Long = 64L

    /** 粗睡眠提前 ~2 帧唤醒，吸收系统调度抖动后交由 VSYNC 精控 */
    const val WAKEUP_VSYNC_LEAD_MARGIN_MS: Long = 32L

    /** 单次粗睡眠上限 (ms)，兜底非 mutation 的微幅进度重锚定与外推钳位 */
    const val WAKEUP_MAX_COARSE_SLEEP_MS: Long = 500L

    /**
     * 💡 计算播放态歌词边界唤醒策略：
     * - `< 0L`  : 无后续边界（尾奏/空歌词），退出循环挂起等待 `mutationVersionFlow`；
     * - `== 0L` : 已进入 VSYNC 精控窗口（`<= 64ms`）或已越过边界，交由 `withFrameNanos` 精确踩点并杜绝忙等；
     * - `> 0L`  : 距离边界较远，执行粗睡眠 `delay(ms)`（单次上限 500ms）。
     */
    fun calculateNextWakeupDelayMs(nowMs: Long, nextBoundaryMs: Long): Long {
        if (nextBoundaryMs == Long.MAX_VALUE) return -1L
        val remainingMs = nextBoundaryMs - nowMs
        if (remainingMs <= WAKEUP_VSYNC_HANDOFF_MS) return 0L
        return (remainingMs - WAKEUP_VSYNC_LEAD_MARGIN_MS).coerceAtMost(WAKEUP_MAX_COARSE_SLEEP_MS)
    }

    /**
     * 边界安全字符区间钳位计算
     * 自动去除词尾空白字符，并对 startChar 与 endChar 进行 [0, fullText.length - 1] 边界安全钳位。
     * 当 fullText 为空时安全返回 (0, 0)。
     *
     * @return Pair(safeStart, endChar)
     */
    fun clampSafeCharBounds(fullText: String, startChar: Int, wordText: String): Pair<Int, Int> {
        val trimmedLen = wordText.trimEnd().length.coerceAtLeast(1)
        val maxChar = (fullText.length - 1).coerceAtLeast(0)
        val safeStart = startChar.coerceIn(0, maxChar)
        val endChar = (safeStart + trimmedLen - 1).coerceIn(safeStart, maxChar)
        return Pair(safeStart, endChar)
    }

    /**
     * 动态扫光羽化区间计算
     * 将羽化区间随 wordProgress 动态锚定：
     * 词首 progress == 0f 时完全收敛于 wordLeft，杜绝提前点亮未唱字符；
     * progress >= 0.2f 时展开至稳态标准羽化宽度。
     *
     * @return Pair(sweepStart, effectiveFeather)
     */
    fun calculateEffectiveFeather(
        sweep: Float,
        feather: Float,
        wordLeft: Float,
        wordProgress: Float
    ): Pair<Float, Float> {
        val halfFeather = feather * 0.5f
        val featherExpansion = (wordProgress / 0.2f).coerceIn(0f, 1f)
        val effectiveHalfFeather = halfFeather * featherExpansion
        val sweepStart = (sweep - effectiveHalfFeather).coerceAtLeast(wordLeft - halfFeather)
        val sweepEnd = sweep + effectiveHalfFeather
        val effectiveFeather = (sweepEnd - sweepStart).coerceAtLeast(1f)
        return Pair(sweepStart, effectiveFeather)
    }

    data class WrappedLineSegment(
        val visualLine: Int,
        val segStartChar: Int,
        val segEndChar: Int,
        val segProgress: Float
    )

    /**
     * 💡 Finding 6: 折行跨行单词视觉行片段划分与局部进度计算
     * 计算折行跨行单词在当前进度下落入的视觉行及该视觉行片段内的相对扫光进度 (0f..1f)。
     */
    fun calculateWrappedLineSegment(
        safeStart: Int,
        endChar: Int,
        progress: Float,
        getLineForOffset: (Int) -> Int
    ): WrappedLineSegment {
        val totalWordChars = (endChar - safeStart + 1).coerceAtLeast(1)
        val continuousChar = safeStart + (totalWordChars * progress).coerceIn(0f, totalWordChars.toFloat() - 0.0001f)
        val currentCharIndex = continuousChar.toInt().coerceIn(safeStart, endChar)
        val activeVisualLine = getLineForOffset(currentCharIndex)

        // 确定该词在当前视觉行 (activeVisualLine) 上的片段字符范围 [segStart, segEnd]
        var segStart = currentCharIndex
        while (segStart > safeStart && getLineForOffset(segStart - 1) == activeVisualLine) {
            segStart--
        }
        var segEnd = currentCharIndex
        while (segEnd < endChar && getLineForOffset(segEnd + 1) == activeVisualLine) {
            segEnd++
        }

        val segChars = (segEnd - segStart + 1).coerceAtLeast(1)
        val segProgress = ((continuousChar - segStart) / segChars).coerceIn(0f, 1f)
        return WrappedLineSegment(activeVisualLine, segStart, segEnd, segProgress)
    }

    /**
     * 💡 Finding 3: 38% 黄金视线垂直滚动偏移量计算
     * 计算视口中列表项物理中心相对目标视线位置 (默认 38%) 的滚动位移差。
     * 在 Compose LazyColumn 中，设置 contentPadding(top = topPaddingPx) 时：
     * - `visibleItem.offset` 相对于 contentPadding 之后的内容区起点；
     * - `layoutInfo.viewportStartOffset == -topPaddingPx`；
     * 因此元素相对视口顶部的真实物理 Y 坐标为 `itemOffsetInViewport - viewportStartOffset`。
     *
     * @return 需滚动的偏移量 delta (像素)，正数表示需向下滚动使元素上移，负数反之
     */
    fun calculateViewportScrollDelta(
        itemOffsetInViewport: Float,
        itemSize: Float,
        viewportHeightPx: Float,
        targetRatio: Float = 0.38f,
        viewportStartOffset: Float = 0f
    ): Float {
        val targetCenterY = viewportHeightPx * targetRatio
        val itemTopInViewport = itemOffsetInViewport - viewportStartOffset
        val currentScreenCenterY = itemTopInViewport + itemSize * 0.5f
        return currentScreenCenterY - targetCenterY
    }

    /**
     * 💡 Finding 10: 歌词行层级目标透明度计算
     * 激活行保持 1.0f；非激活行随与激活行的行距线性衰减 (0.36f -> 0.16f)，在 1:1 原生字号下呈现鲜明焦点层次。
     */
    fun calculateLineTargetAlpha(isActive: Boolean, distanceFromActive: Int): Float {
        return if (isActive) {
            1.0f
        } else {
            (0.36f - (distanceFromActive - 1) * 0.06f).coerceIn(0.16f, 0.36f)
        }
    }

    /**
     * 💡 Finding 10: 翻译行实际呈现透明度计算
     * 复合外层容器透明度计算最终呈现 alpha。
     */
    fun calculateEffectiveTranslationAlpha(outerAlpha: Float): Float {
        return TRANSLATION_ALPHA * outerAlpha
    }

    /**
     * 💡 景深虚化半径计算 (dp)：
     * 激活行保持 0dp 锐利对焦；非激活行随距高亮行行距递增轻微高斯虚化（1.2dp -> 2.0dp -> 2.8dp），
     * 营造 Apple Music TV 标志性的焦点景深层次。
     */
    fun calculateLineTargetBlurDp(isActive: Boolean, distanceFromActive: Int): Float {
        if (isActive) return 0f
        return when {
            distanceFromActive <= 1 -> 1.2f
            distanceFromActive == 2 -> 2.0f
            else -> 2.8f
        }
    }

    /**
     * 💡 零 FBO 视口边缘柔和渐隐系数（smoothstep 曲线）：
     * 替代全高 CompositingStrategy.Offscreen + BlendMode.DstIn，
     * 在顶部 [0, topFadePx] 与底部 [viewportHeightPx - bottomFadePx, viewportHeightPx] 区间内平滑衰减至 0，
     * 视口核心区域恒为 1.0f，0 离屏显存、0 FBO 切换。
     */
    fun calculateViewportEdgeFadeAlpha(
        lineCenterY: Float,
        viewportHeightPx: Float,
        topFadePx: Float,
        bottomFadePx: Float
    ): Float {
        if (viewportHeightPx <= 0f) return 1f
        val topT = if (topFadePx > 0f) (lineCenterY / topFadePx).coerceIn(0f, 1f) else 1f
        val bottomT = if (bottomFadePx > 0f) ((viewportHeightPx - lineCenterY) / bottomFadePx).coerceIn(0f, 1f) else 1f
        val t = minOf(topT, bottomT)
        return t * t * (3f - 2f * t)
    }

    /**
     * 💡 AMLL / Apple Music 同款逐行弹簧波浪参数（流水阻尼调校）：
     * 采用柔和刚度 + 近临界阻尼 + 舒展级联错峰，起步轻快、中后段拥有丝绸般长尾减速。
     */
    data class WaveSpringParams(
        val stiffness: Float,
        val dampingRatio: Float,
        val staggerStepMs: Long
    )

    fun calculateWaveSpringParams(prevLine: LyricLine?, currentLine: LyricLine?): WaveSpringParams {
        if (prevLine == null || currentLine == null) {
            return WaveSpringParams(
                stiffness = 300f,
                dampingRatio = 1.0f,
                staggerStepMs = 34L
            )
        }
        val startGapMs = (currentLine.startMs - prevLine.startMs).coerceAtLeast(0L)
        val prevVocalEndMs = prevLine.words?.lastOrNull()?.endMs ?: prevLine.endMs
        val vocalGapMs = (currentLine.startMs - prevVocalEndMs).coerceAtLeast(0L)
        return when {
            startGapMs < 1500L -> WaveSpringParams(
                stiffness = 380f,
                dampingRatio = 1.0f,
                staggerStepMs = 22L
            )
            startGapMs > 4500L || vocalGapMs >= 1500L -> WaveSpringParams(
                stiffness = 240f,
                dampingRatio = 1.0f,
                staggerStepMs = 42L
            )
            else -> WaveSpringParams(
                stiffness = 300f,
                dampingRatio = 1.0f,
                staggerStepMs = 34L
            )
        }
    }

    /**
     * 💡 长间奏/尾奏呼吸静息判定：
     * 仅当上一句人声唱完且距离下一句存在真正长间奏（总间隙 >= 5000ms，且已过唱完点 800ms），
     * 或歌曲尾奏（末句唱完 1500ms 后）时，让当前行优雅去高亮并显示呼吸三点，并持续保持静息直到切入下一句，
     * 既避免 2.5s~4.5s 常规换气停顿频繁弹出 `• • •` 与提前熄灭当前行，也杜绝回闪。
     */
    fun isInInterludeRest(timeline: LyricTimeline, activeLineIndex: Int, currentTimeMs: Long): Boolean {
        val lines = timeline.lines
        if (activeLineIndex !in lines.indices) return false
        val line = lines[activeLineIndex]
        val vocalEndMs = line.words?.lastOrNull()?.endMs ?: line.endMs
        if (vocalEndMs <= line.startMs) return false
        val nextStartMs = lines.getOrNull(activeLineIndex + 1)?.startMs
        return if (nextStartMs != null) {
            val gapMs = nextStartMs - vocalEndMs
            gapMs >= INTERLUDE_MIN_GAP_MS && currentTimeMs >= vocalEndMs + INTERLUDE_ENTER_DELAY_MS
        } else {
            currentTimeMs >= vocalEndMs + OUTRO_ENTER_DELAY_MS
        }
    }
}
