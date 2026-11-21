package com.solo.purecast.ui.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 💡 沉浸式 Apple Music TV 风格逐行弹簧波浪歌词视口（参考 AMLL / AMLV 架构）：
 * 1. 由父容器统一持有每行独立 Y 轴物理弹簧 (posYAnims)，彻底杜绝虚拟窗口滑动时 key 重建导致的滚动动画丢失；
 * 2. 切句瞬间已唱行与当前行立即起动，下方队列行按行距错峰延迟形成波浪上涌，同时与高亮放大同频衔接、零延迟踩点；
 * 3. 每行弹簧在 1:1 刚性 Offscreen graphicsLayer 中以连续浮点 translationY 纯 GPU 合成（0 重组、0 重新布局），visibilityThreshold = 0.5px 根除低速长尾拖影，
 *    且快节奏连续切句自动继承当前物理速度，零顿挫；
 * 4. 长前奏与长间奏优雅呈现 Apple Music 三点呼吸脉动指示器 (• • •)。
 */
@Composable
fun LyricsStreamView(
    timeline: LyricTimeline,
    syncClock: LyricsSyncClock,
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true
) {
    val density = LocalDensity.current
    val currentTimeProvider = remember(syncClock) { { syncClock.getCurrentInterpolatedTimeMs() } }

    val isWordSync = timeline.format == LyricFormat.TTML_SYLLABLE || timeline.format == LyricFormat.LRC_WORD_SYNC
    var activeLineIndex by remember(timeline) { mutableIntStateOf(-1) }
    var scrollLineIndex by remember(timeline) { mutableIntStateOf(-1) }
    var isInterludeRest by remember(timeline) { mutableStateOf(false) }
    // 💡 O(1) 切句边界缓存：[0] = validFromMs, [1] = validUntilMs；未越过下一关键时间边界时直接跳过每帧两次二分查找
    val boundaryWindowMs = remember(timeline) { longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE) }

    fun computeNextBoundaryMs(nowTimeMs: Long, curActive: Int, curScroll: Int, curRest: Boolean): Long {
        val lines = timeline.lines
        if (lines.isEmpty()) return Long.MAX_VALUE
        var nextBoundary = Long.MAX_VALUE
        fun consider(t: Long) {
            if (t > nowTimeMs && t < nextBoundary) nextBoundary = t
        }
        // 1. 下一激活行起始边界
        val nextActiveIdx = if (curActive < 0) 0 else curActive + 1
        if (nextActiveIdx in lines.indices) {
            consider(syncClock.getEffectiveStartMs(nextActiveIdx, lines))
        }
        // 2. 下一滚动锚点行起始边界
        val nextScrollIdx = if (curScroll < 0) 0 else curScroll + 1
        if (nextScrollIdx in lines.indices) {
            consider(syncClock.getScrollEffectiveStartMs(nextScrollIdx, lines))
        }
        // 3. 当前行结束时间与间奏静息进入边界
        if (curActive in lines.indices) {
            val curLine = lines[curActive]
            consider(curLine.endMs + 1L)
            if (!curRest) {
                val vocalEndMs = curLine.words?.lastOrNull()?.endMs ?: curLine.endMs
                if (vocalEndMs > curLine.startMs) {
                    val nextStartMs = lines.getOrNull(curActive + 1)?.startMs
                    if (nextStartMs != null) {
                        if (nextStartMs - vocalEndMs >= 2500L) {
                            consider(vocalEndMs + 500L)
                        }
                    } else {
                        consider(vocalEndMs + 1500L)
                    }
                }
            }
        }
        return nextBoundary
    }

    fun syncPlaybackIndices(nowTimeMs: Long, force: Boolean = false) {
        if (!force && nowTimeMs >= boundaryWindowMs[0] && nowTimeMs < boundaryWindowMs[1]) {
            return
        }
        // 💡 60fps 零分配热路径：仅在越过切句/滚动/间奏边界或 Seek 强制刷新时执行二分查找
        val nextActive = syncClock.calculateActiveLineIndex(timeline, nowTimeMs)
        val rawScroll = syncClock.calculateScrollLineIndex(timeline, nowTimeMs)
        val nextScroll = if (nextActive >= 0) maxOf(nextActive, rawScroll) else rawScroll
        if (nextActive != activeLineIndex) {
            activeLineIndex = nextActive
        }
        if (nextScroll != scrollLineIndex) {
            scrollLineIndex = nextScroll
        }
        val rest = LyricsRenderingMath.isInInterludeRest(timeline, nextActive, nowTimeMs)
        if (rest != isInterludeRest) {
            isInterludeRest = rest
        }
        boundaryWindowMs[0] = nowTimeMs
        boundaryWindowMs[1] = computeNextBoundaryMs(nowTimeMs, nextActive, nextScroll, rest)
    }

    // 💡 播放态主循环：逐字与逐行歌词统一由 VSYNC 驱动，零重组且切句精准对齐 60Hz 帧边界
    LaunchedEffect(timeline, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        // 切歌或恢复播放首帧强制对齐一次边界缓存
        syncPlaybackIndices(syncClock.getCurrentInterpolatedTimeMs(), force = true)
        while (true) {
            withFrameNanos {
                syncPlaybackIndices(syncClock.getCurrentInterpolatedTimeMs())
            }
        }
    }

    // 💡 暂停态副作用：监听 mutationVersionFlow，确保暂停下 Seek 或切歌立即更新高亮行与间奏静息态
    LaunchedEffect(isPlaying, timeline) {
        if (!isPlaying) {
            syncPlaybackIndices(syncClock.getCurrentInterpolatedTimeMs(), force = true)
            syncClock.mutationVersionFlow.collect {
                syncPlaybackIndices(syncClock.getCurrentInterpolatedTimeMs(), force = true)
            }
        }
    }

    val primaryTextColor = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)
    val lineSpacingPx = with(density) { 32.dp.toPx() }.roundToInt().toFloat()
    val fallbackSingleHeightPx = with(density) { 46.dp.toPx() }.roundToInt().toFloat()
    val fallbackBilingualHeightPx = with(density) { 78.dp.toPx() }.roundToInt().toFloat()

    // 💡 统一封装每行测量高度、Y 轴弹簧、目标坐标与波浪代数至稳定状态 Holder（按 timeline 隔离，切歌自动重建）
    val layoutState = remember(timeline) { LyricsStreamLayoutState() }
    val measuredHeights = layoutState.measuredHeights
    val posYAnims = layoutState.posYAnims
    val latestTargetYs = layoutState.latestTargetYs
    val lastAnchorRef = layoutState.lastAnchorRef
    val waveGenCounter = layoutState.waveGenCounter
    val activeWaveLineGen = layoutState.activeWaveLineGen
    var layoutMeasureVersion by remember(timeline) { mutableIntStateOf(0) }
    // 💡 批处理首帧/滑窗期间的多次 onSizeChanged：同一布局帧内最多写入一次 Snapshot State，避免 13 行连续递增触发重复失效
    val measureFlushPending = layoutState.measureFlushPending

    val anchorIndex = scrollLineIndex.coerceAtLeast(0).coerceAtMost((timeline.lines.size - 1).coerceAtLeast(0))

    val waveParams = remember(timeline, anchorIndex) {
        LyricsRenderingMath.calculateWaveSpringParams(
            prevLine = timeline.lines.getOrNull(anchorIndex - 1),
            currentLine = timeline.lines.getOrNull(anchorIndex)
        )
    }

    val topEdgeFadePx = with(density) { 110.dp.toPx() }
    val bottomEdgeFadePx = with(density) { 130.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat()
        if (viewportHeightPx <= 0f) return@BoxWithConstraints
        val targetCenterY = viewportHeightPx * 0.38f

        if (timeline.lines.isNotEmpty()) {
            // 💡 订阅 layoutMeasureVersion，确保普通 HashMap 写入新测量行高后触发重算 latestTargetYs，仅在组合提交后通过 SideEffect 复位脏标记
            @Suppress("UNUSED_VARIABLE")
            val measureVersion = layoutMeasureVersion
            SideEffect {
                measureFlushPending[0] = false
            }
            // 💡 虚拟化窗口：仅挂载可见视口附近的最多 13 行，保证低端电视盒子满帧 60fps
            val windowStart = (anchorIndex - 4).coerceAtLeast(0)
            val windowEnd = (anchorIndex + 8).coerceAtMost(timeline.lines.lastIndex)

            fun heightOf(idx: Int): Float {
                val measured = measuredHeights[idx]
                if (measured != null && measured > 0f) return measured
                val line = timeline.lines.getOrNull(idx)
                return if (line?.hasTranslation == true) fallbackBilingualHeightPx else fallbackSingleHeightPx
            }

            // 💡 预计算当前窗口内每一行精确的整像素目标 Y 坐标（保证 anchorIndex 落位在整像素上，杜绝亚像素抖动）
            val anchorHeight = heightOf(anchorIndex)
            val anchorTopY = (targetCenterY - anchorHeight * 0.5f).roundToInt().toFloat()
            latestTargetYs[anchorIndex] = anchorTopY

            var cursorY = anchorTopY + anchorHeight + lineSpacingPx
            for (i in (anchorIndex + 1)..windowEnd) {
                latestTargetYs[i] = cursorY
                cursorY += heightOf(i) + lineSpacingPx
            }

            cursorY = anchorTopY
            for (i in (anchorIndex - 1) downTo windowStart) {
                val h = heightOf(i)
                cursorY -= (lineSpacingPx + h)
                latestTargetYs[i] = cursorY
            }

            // 💡 确保当前窗口内每行都有稳定的父级 Animatable，不会随 windowStart 滑动被销毁重建
            val prevAnchorForInit = lastAnchorRef[0]
            val isJumpForInit = prevAnchorForInit < 0 || abs(anchorIndex - prevAnchorForInit) > 2
            for (index in windowStart..windowEnd) {
                val targetY = latestTargetYs[index] ?: continue
                if (!posYAnims.containsKey(index)) {
                    val initialOffset = when {
                        isJumpForInit -> 0f
                        index > anchorIndex && anchorIndex >= prevAnchorForInit -> anchorHeight + lineSpacingPx
                        index < anchorIndex && anchorIndex < prevAnchorForInit -> -(anchorHeight + lineSpacingPx)
                        else -> 0f
                    }
                    layoutState.ensurePosYAnim(index, targetY + initialOffset)
                }
            }

            // 💡 统一编排每行波浪弹簧（切句瞬间与高亮放大同步起动，零延迟踩点）
            LaunchedEffect(timeline, anchorIndex, viewportHeightPx) {
                val prevAnchor = lastAnchorRef[0]
                val isBigJump = prevAnchor < 0 || abs(anchorIndex - prevAnchor) > 2
                lastAnchorRef[0] = anchorIndex

                // 清理远离当前视口的旧行弹簧，防止长歌曲内存增长
                posYAnims.keys.removeAll { it < windowStart - 2 || it > windowEnd + 2 }

                if (isBigJump) {
                    activeWaveLineGen.clear()
                    for (idx in windowStart..windowEnd) {
                        val ty = latestTargetYs[idx] ?: continue
                        posYAnims[idx]?.snapTo(ty)
                    }
                } else {
                    val currentWaveGen = ++waveGenCounter[0]

                    // 1. 先同步登记本轮波浪涵盖的所有行号（包含处于 stagger delay 等待期的下方行），
                    //    避免新挂载底部行触发 onSizeChanged -> layoutMeasureVersion 时误把等待中的下方行 snapTo！
                    for (idx in windowStart..windowEnd) {
                        activeWaveLineGen[idx] = currentWaveGen
                    }

                    // 2. 并发驱动视口内所有行（旧行与即将开口的新行同步 0ms 起动加速就位，下方队列行按 42ms 错峰形成流水波浪）
                    val isScrollingForward = anchorIndex >= prevAnchor
                    for (idx in windowStart..windowEnd) {
                        launch {
                            try {
                                val anim = posYAnims[idx] ?: return@launch
                                val staggerDelayMs = if (isScrollingForward) {
                                    when {
                                        idx <= anchorIndex -> 0L
                                        else -> ((idx - anchorIndex) * waveParams.staggerStepMs).coerceAtMost(240L)
                                    }
                                } else {
                                    when {
                                        idx >= anchorIndex -> 0L
                                        else -> ((anchorIndex - idx) * waveParams.staggerStepMs).coerceAtMost(240L)
                                    }
                                }
                                if (staggerDelayMs > 0L) {
                                    delay(staggerDelayMs)
                                }
                                val ty = latestTargetYs[idx] ?: return@launch
                                if (abs(anim.value - ty) > 0.5f) {
                                    anim.animateTo(
                                        targetValue = ty,
                                        animationSpec = spring(
                                            dampingRatio = waveParams.dampingRatio,
                                            stiffness = waveParams.stiffness,
                                            visibilityThreshold = 0.5f
                                        )
                                    )
                                }
                                // 若波浪进行期间上方新行完成了多行换行测量导致 latestTargetYs 更新，补做一次平滑收尾
                                val settledTy = latestTargetYs[idx]
                                if (settledTy != null && abs(anim.value - settledTy) > 0.5f) {
                                    anim.animateTo(
                                        targetValue = settledTy,
                                        animationSpec = spring(
                                            dampingRatio = waveParams.dampingRatio,
                                            stiffness = waveParams.stiffness,
                                            visibilityThreshold = 0.5f
                                        )
                                    )
                                }
                            } finally {
                                if (activeWaveLineGen[idx] == currentWaveGen) {
                                    activeWaveLineGen.remove(idx)
                                }
                            }
                        }
                    }
                }
            }

            // 💡 首帧或大跳变后完成真实高度测量时，静默修正未处于波浪动画/等待期中的行位置
            LaunchedEffect(timeline, layoutMeasureVersion) {
                if (layoutMeasureVersion == 0) return@LaunchedEffect
                for (idx in windowStart..windowEnd) {
                    if (activeWaveLineGen.containsKey(idx)) continue
                    val anim = posYAnims[idx] ?: continue
                    val ty = latestTargetYs[idx] ?: continue
                    if (!anim.isRunning && abs(anim.value - ty) > 1f) {
                        anim.snapTo(ty)
                    }
                }
            }

            // 💡 前奏期 (activeLineIndex == -1 且首句 >= 3s) 或长间奏静息期显示 Apple Music 经典三点呼吸指示器
            // 当间奏最后 450ms scrollLineIndex 已提前切入下一句上滑时，三点指示器在原位平滑淡出（锁定 lastValidDotsYRef 绝不瞬移）
            val showIntroDots = activeLineIndex == -1 && scrollLineIndex == -1 && (timeline.lines.firstOrNull()?.startMs ?: 0L) >= 3000L
            val showInterludeDots = isInterludeRest &&
                scrollLineIndex == activeLineIndex &&
                activeLineIndex in timeline.lines.indices &&
                activeLineIndex < timeline.lines.lastIndex
            val dotsHeightPx = with(density) { 20.dp.toPx() }
            val lastValidDotsYRef = remember(timeline) { floatArrayOf(targetCenterY) }
            val resolvedDotsY = when {
                showIntroDots -> {
                    val y = (anchorTopY - with(density) { 42.dp.toPx() }).coerceAtLeast(with(density) { 48.dp.toPx() })
                    lastValidDotsYRef[0] = y
                    y
                }
                showInterludeDots -> {
                    // 在 32dp 行间距中精确垂直居中，避免底部紧贴下一行歌词
                    val y = anchorTopY + anchorHeight + (lineSpacingPx - dotsHeightPx) * 0.5f
                    lastValidDotsYRef[0] = y
                    y
                }
                else -> lastValidDotsYRef[0]
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 8.dp)
            ) {
                // 经典三点呼吸指示器 (• • •)：退出淡出期间保持 lastValidDotsYRef，杜绝瞬移叠加到高亮行上
                Box(
                    modifier = Modifier.graphicsLayer {
                        translationY = resolvedDotsY
                    }
                ) {
                    AnimatedVisibility(
                        visible = showIntroDots || showInterludeDots,
                        enter = fadeIn(tween(380, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(260, easing = FastOutSlowInEasing))
                    ) {
                        InterludeBreathingDots(
                            color = primaryTextColor,
                            isPlaying = isPlaying
                        )
                    }
                }

                key(timeline) {
                    val isInterludeHolding = isInterludeRest && (scrollLineIndex == activeLineIndex)
                    for (index in windowStart..windowEnd) {
                        val line = timeline.lines[index]
                        val posYAnim = posYAnims[index] ?: continue
                        // 💡 逐字歌词用 activeLineIndex 保护上一句尾字扫光不被截断；
                        // 普通逐行歌词直接与 scrollLineIndex 同频点亮，消除“滑到位了字还没亮”的割裂感。
                        val visualFocusIndex = if (isWordSync) activeLineIndex else scrollLineIndex
                        val isFocused = if (isWordSync) {
                            (index == activeLineIndex) && !isInterludeRest
                        } else {
                            (index == scrollLineIndex) && !isInterludeHolding
                        }
                        // 💡 远距行透明度在 distance >= 5 时已钳位至下限，钳位入参避免切句时远距行无谓重组
                        val distance = (if (visualFocusIndex >= 0) abs(index - visualFocusIndex) else (index + 1)).coerceAtMost(5)
                        val estimatedLineHeight = heightOf(index)

                        key(index) {
                            val translationYProvider = remember(posYAnim) { { posYAnim.value } }
                            val edgeAlphaProvider = remember(
                                estimatedLineHeight,
                                viewportHeightPx,
                                topEdgeFadePx,
                                bottomEdgeFadePx
                            ) {
                                { y: Float ->
                                    LyricsRenderingMath.calculateViewportEdgeFadeAlpha(
                                        lineCenterY = y + estimatedLineHeight * 0.5f,
                                        viewportHeightPx = viewportHeightPx,
                                        topFadePx = topEdgeFadePx,
                                        bottomFadePx = bottomEdgeFadePx
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { size ->
                                        if (size.height > 0) {
                                            val h = size.height.toFloat()
                                            if (measuredHeights[index] != h) {
                                                measuredHeights[index] = h
                                                // 💡 底部新挂载行 (index == windowEnd && index != anchorIndex) 不影响当前窗口任何可见行坐标，
                                                // 静默写入缓存供下一句使用，彻底消除每次切句第 1 帧的无效二次重组；
                                                // 同一布局帧内其余多行首次测量仅合并触发一次 layoutMeasureVersion++。
                                                if ((index < windowEnd || index == anchorIndex) && !measureFlushPending[0]) {
                                                    measureFlushPending[0] = true
                                                    layoutMeasureVersion++
                                                }
                                            }
                                        }
                                    }
                            ) {
                                SmoothLyricLineRow(
                                    line = line,
                                    isActive = isFocused,
                                    distanceFromActive = distance,
                                    currentTimeProvider = currentTimeProvider,
                                    syncClock = syncClock,
                                    primaryColor = primaryTextColor,
                                    isPlaying = isPlaying,
                                    springStiffness = waveParams.stiffness,
                                    translationYProvider = translationYProvider,
                                    edgeAlphaProvider = edgeAlphaProvider
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Stable
private class LyricsStreamLayoutState {
    val measuredHeights = HashMap<Int, Float>()
    val posYAnims = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()
    val latestTargetYs = mutableMapOf<Int, Float>()
    val lastAnchorRef = intArrayOf(-1)
    val waveGenCounter = intArrayOf(0)
    val activeWaveLineGen = mutableMapOf<Int, Int>()
    val measureFlushPending = booleanArrayOf(false)

    fun ensurePosYAnim(index: Int, initialValue: Float) {
        posYAnims.getOrPut(index) { Animatable(initialValue) }
    }
}

/**
 * 💡 Apple Music 经典长前奏 / 间奏三点呼吸脉动指示器 (• • •)
 * 仅在播放态由 VSYNC 驱动相位，并在 Canvas Draw 阶段延迟读取（0 重组，暂停态 0 功耗）。
 */
@Composable
private fun InterludeBreathingDots(
    color: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val phaseState = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastNanos = 0L
        val radiansPerNano = (2.0 * PI) / 1_800_000_000.0
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val delta = (frameNanos - lastNanos).coerceAtLeast(0L)
                    phaseState.floatValue = ((phaseState.floatValue + delta * radiansPerNano) % (2.0 * PI)).toFloat()
                }
                lastNanos = frameNanos
            }
        }
    }

    Canvas(
        modifier = modifier
            .width(56.dp)
            .height(20.dp)
    ) {
        val phase = phaseState.floatValue
        val baseRadius = 4.2.dp.toPx()
        val spacing = 16.dp.toPx()
        val cy = size.height * 0.5f
        for (i in 0 until 3) {
            val dotPhase = if (isPlaying) phase - i * 0.85f else 0f
            val wave = ((sin(dotPhase.toDouble()) + 1.0) * 0.5).toFloat()
            val alpha = 0.28f + 0.62f * wave
            val radius = baseRadius * (0.86f + 0.24f * wave)
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(x = baseRadius + 2.dp.toPx() + i * spacing, y = cy)
            )
        }
    }
}
