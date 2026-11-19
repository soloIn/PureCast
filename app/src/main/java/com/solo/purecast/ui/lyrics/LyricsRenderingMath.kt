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
     * 激活行保持 1.0f；非激活行随与激活行的行距线性衰减 (0.42f -> 0.18f)。
     */
    fun calculateLineTargetAlpha(isActive: Boolean, distanceFromActive: Int): Float {
        return if (isActive) {
            1.0f
        } else {
            (0.42f - (distanceFromActive - 1) * 0.08f).coerceIn(0.18f, 0.42f)
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
     * 💡 AMLL / Apple Music 同款逐行弹簧波浪参数：
     * 根据上下行歌词时间间隙动态调节每行独立弹簧刚度、阻尼与级联错峰延迟。
     */
    data class WaveSpringParams(
        val stiffness: Float,
        val dampingRatio: Float,
        val staggerStepMs: Long
    )

    fun calculateWaveSpringParams(prevLine: LyricLine?, currentLine: LyricLine?): WaveSpringParams {
        if (prevLine == null || currentLine == null) {
            return WaveSpringParams(
                stiffness = 380f,
                dampingRatio = 0.90f,
                staggerStepMs = 26L
            )
        }
        val startGapMs = (currentLine.startMs - prevLine.startMs).coerceAtLeast(0L)
        val prevVocalEndMs = prevLine.words?.lastOrNull()?.endMs ?: prevLine.endMs
        val vocalGapMs = (currentLine.startMs - prevVocalEndMs).coerceAtLeast(0L)
        return when {
            startGapMs < 1500L -> WaveSpringParams(
                stiffness = 520f,
                dampingRatio = 0.92f,
                staggerStepMs = 18L
            )
            startGapMs > 4500L || vocalGapMs >= 1500L -> WaveSpringParams(
                stiffness = 300f,
                dampingRatio = 0.90f,
                staggerStepMs = 32L
            )
            else -> WaveSpringParams(
                stiffness = 380f,
                dampingRatio = 0.90f,
                staggerStepMs = 26L
            )
        }
    }

    /**
     * 💡 长间奏/尾奏呼吸静息判定：
     * 当上一句人声唱完且距离下一句还有足够长的间奏（总间隙 >= 2500ms，且已过唱完点 500ms），
     * 或歌曲尾奏（末句唱完 1500ms 后）时，让当前行优雅去高亮并恢复至 0.95f，并持续保持静息直到 activeLineIndex 真正切入下一句，
     * 杜绝间奏 `• • •` 提前退出导致上一行歌词重新亮起的回闪 Bug。
     * 注：无逐字时间戳的标准 LRC 若解析器将 line.endMs 填为 nextLine.startMs，则 gapMs == 0 不会在曲中误熄灭长拖音，
     * 仅在 TTML/逐字歌词、含显式结束时间的 LRC 或歌曲尾奏触发。
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
            gapMs >= 2500L && currentTimeMs >= vocalEndMs + 500L
        } else {
            currentTimeMs >= vocalEndMs + 1500L
        }
    }
}
