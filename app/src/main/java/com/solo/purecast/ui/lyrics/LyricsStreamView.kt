package com.solo.purecast.ui.lyrics

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricTimeline
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 💡 沉浸式歌词流滚动视口：上下 80dp 渐变羽化遮罩 + 38% 黄金居中自动平滑滚动
 * 针对低端电视盒子实施高频隔离渲染：仅高亮激活行参与 60fps VSYNC 绘制
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
    val listState = rememberLazyListState()
    val currentTimeProvider = remember(syncClock) { { syncClock.getCurrentInterpolatedTimeMs() } }

    val isWordSync = timeline.format == LyricFormat.TTML_SYLLABLE || timeline.format == LyricFormat.LRC_WORD_SYNC
    var activeLineIndex by remember { mutableIntStateOf(-1) }

    // 💡 播放态主循环由 (timeline, isWordSync, isPlaying) 驱动，杜绝稳态播放下动画重启
    LaunchedEffect(timeline, isWordSync, isPlaying) {
        if (!isPlaying) return@LaunchedEffect

        if (isWordSync) {
            while (true) {
                withFrameNanos {
                    val nowTimeMs = syncClock.getCurrentInterpolatedTimeMs()
                    val state = syncClock.calculateActiveLineState(timeline, nowTimeMs)
                    if (state.lineIndex != activeLineIndex) {
                        activeLineIndex = state.lineIndex
                    }
                }
            }
        } else {
            while (true) {
                val nowTimeMs = syncClock.getCurrentInterpolatedTimeMs()
                val state = syncClock.calculateActiveLineState(timeline, nowTimeMs)
                if (state.lineIndex != activeLineIndex) {
                    activeLineIndex = state.lineIndex
                }
                delay(33L)
            }
        }
    }

    // 💡 暂停态副作用：监听 mutationVersionFlow，确保暂停下 Seek 或切歌立即更新高亮行
    LaunchedEffect(isPlaying, timeline) {
        if (!isPlaying) {
            val nowTimeMs = syncClock.getCurrentInterpolatedTimeMs()
            val state = syncClock.calculateActiveLineState(timeline, nowTimeMs)
            if (state.lineIndex != activeLineIndex) {
                activeLineIndex = state.lineIndex
            }
            syncClock.mutationVersionFlow.collect {
                val seekTimeMs = syncClock.getCurrentInterpolatedTimeMs()
                val seekState = syncClock.calculateActiveLineState(timeline, seekTimeMs)
                if (seekState.lineIndex != activeLineIndex) {
                    activeLineIndex = seekState.lineIndex
                }
            }
        }
    }

    val primaryTextColor = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadeHeight = 80.dp.toPx()
                // 顶部 80dp 羽化渐隐
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = 0f,
                        endY = fadeHeight
                    ),
                    blendMode = BlendMode.DstIn
                )
                // 底部 80dp 羽化渐隐
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = size.height - fadeHeight,
                        endY = size.height
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val targetRatio = 0.38f // 💡 38% 黄金视线分割点（垂直中间偏上）
        val targetCenterY = viewportHeightPx * targetRatio

        // 💡 动态计算首尾内容填充（按单语/双语估算首行半高），确保首行与末行均能精准对齐至 38% 黄金视线分割点
        val hasTranslation = remember(timeline) { timeline.lines.firstOrNull()?.hasTranslation == true }
        val halfItemHeightPx = with(density) { (if (hasTranslation) 42.dp else 24.dp).toPx() }
        val topPaddingPx = (targetCenterY - halfItemHeightPx).coerceAtLeast(0f)
        val bottomPaddingPx = (viewportHeightPx - targetCenterY - halfItemHeightPx).coerceAtLeast(0f)
        val topPaddingDp = with(density) { topPaddingPx.toDp() }
        val bottomPaddingDp = with(density) { bottomPaddingPx.toDp() }

        var lastScrolledTimeline by remember { mutableStateOf<LyricTimeline?>(null) }
        var lastScrolledLineIndex by remember { mutableIntStateOf(-1) }

        LaunchedEffect(activeLineIndex, timeline) {
            val isNewTimeline = (timeline != lastScrolledTimeline)
            if (isNewTimeline) {
                lastScrolledTimeline = timeline
                lastScrolledLineIndex = -1
                listState.scrollToItem(0, 0)
            }

            if (activeLineIndex in timeline.lines.indices) {
                val delta = if (isNewTimeline) Int.MAX_VALUE else abs(activeLineIndex - lastScrolledLineIndex)
                lastScrolledLineIndex = activeLineIndex

                val viewportStartOffset = listState.layoutInfo.viewportStartOffset.toFloat()

                if (delta > 2 || isNewTimeline) {
                    // 💡 跨行寻道 (Seek) 或切歌大跨度跳变：立即瞬时定位对齐
                    listState.scrollToItem(index = activeLineIndex, scrollOffset = 0)

                    val postInfo = listState.layoutInfo
                    val postItem = postInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                    if (postItem != null) {
                        val fineDelta = LyricsRenderingMath.calculateViewportScrollDelta(
                            itemOffsetInViewport = postItem.offset.toFloat(),
                            itemSize = postItem.size.toFloat(),
                            viewportHeightPx = viewportHeightPx,
                            targetRatio = targetRatio,
                            viewportStartOffset = postInfo.viewportStartOffset.toFloat()
                        )
                        if (abs(fineDelta) > 1f) {
                            listState.scrollBy(fineDelta)
                        }
                    }
                } else {
                    // 💡 正常逐句推进：临界阻尼物理弹簧平滑过渡，精准对齐视口 38% 黄金视线分割点
                    val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                    if (visibleItem != null) {
                        val scrollDelta = LyricsRenderingMath.calculateViewportScrollDelta(
                            itemOffsetInViewport = visibleItem.offset.toFloat(),
                            itemSize = visibleItem.size.toFloat(),
                            viewportHeightPx = viewportHeightPx,
                            targetRatio = targetRatio,
                            viewportStartOffset = viewportStartOffset
                        )
                        if (abs(scrollDelta) > 1f) {
                            listState.animateScrollBy(
                                value = scrollDelta,
                                animationSpec = spring(
                                    dampingRatio = 0.92f,
                                    stiffness = 135f
                                )
                            )
                        }
                    } else {
                        listState.scrollToItem(index = activeLineIndex, scrollOffset = 0)
                        val postInfo = listState.layoutInfo
                        val postItem = postInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                        if (postItem != null) {
                            val fineDelta = LyricsRenderingMath.calculateViewportScrollDelta(
                                itemOffsetInViewport = postItem.offset.toFloat(),
                                itemSize = postItem.size.toFloat(),
                                viewportHeightPx = viewportHeightPx,
                                targetRatio = targetRatio,
                                viewportStartOffset = postInfo.viewportStartOffset.toFloat()
                            )
                            if (abs(fineDelta) > 1f) {
                                listState.scrollBy(fineDelta)
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 0.dp, top = topPaddingDp, end = 0.dp, bottom = bottomPaddingDp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = timeline.lines,
                key = { index, line -> "${line.startMs}_$index" }
            ) { index, line ->
                val isActive = index == activeLineIndex
                val distance = if (activeLineIndex >= 0) abs(index - activeLineIndex) else (index + 1)

                SmoothLyricLineRow(
                    line = line,
                    isActive = isActive,
                    isFirstLine = (index == 0),
                    distanceFromActive = distance,
                    currentTimeProvider = currentTimeProvider,
                    syncClock = syncClock,
                    primaryColor = primaryTextColor,
                    isPlaying = isPlaying
                )
            }
        }
    }
}
