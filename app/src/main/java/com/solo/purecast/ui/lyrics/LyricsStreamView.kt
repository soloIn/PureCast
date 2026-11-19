package com.solo.purecast.ui.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
 * 针对低端电视盒子实施高频隔离渲染：仅高亮激活行参与 60fps VSYNC 重组
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

    val isWordSync = timeline.format == LyricFormat.TTML_SYLLABLE || timeline.format == LyricFormat.LRC_WORD_SYNC
    var activeLineIndex by remember { mutableIntStateOf(-1) }

    // 💡 Finding #4: 播放态主循环由 (timeline, isWordSync, isPlaying) 驱动，移除 syncClock.mutationVersion！
    // 播放期间由 isPlaying 与 withFrameNanos / 33ms 驱动，彻底杜绝稳态播放下动画重启与 2Hz 重组
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

    // 💡 Finding #1 & #4: 暂停态副作用绑定 (isPlaying, timeline)，彻底从 key 列表中移除 syncClock.mutationVersion（消除 key 重启与 Flow collect 双重触发），同时确保异步歌词到达或切歌时捕获最新 timeline
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

        // 💡 动态计算首尾内容填充，确保首行与末行均能精准对齐至 38% 黄金视线分割点
        val halfItemHeightPx = with(density) { 36.dp.toPx() }
        val topPaddingPx = (targetCenterY - halfItemHeightPx).coerceAtLeast(0f)
        val bottomPaddingPx = (viewportHeightPx - targetCenterY - halfItemHeightPx).coerceAtLeast(0f)
        val topPaddingDp = with(density) { topPaddingPx.toDp() }
        val bottomPaddingDp = with(density) { bottomPaddingPx.toDp() }

        // 💡 滚动曲线定制与垂直中间偏上对齐 (38% 黄金视线分割点)
        // 修复 Finding 1: 绑定 (activeLineIndex, timeline) 双 Key 并记录 lastScrolledTimeline，
        // 彻底根除切歌换曲时因首行索引同值 (如均为 0) 导致新曲目列表停留于旧曲目滚动偏移的缺陷！
        var lastScrolledTimeline by remember { mutableStateOf<LyricTimeline?>(null) }
        var lastScrolledLineIndex by remember { mutableIntStateOf(-1) }

        LaunchedEffect(activeLineIndex, timeline) {
            // 💡 修复 Finding 3: 优先处理新歌时间轴切换归零。
            // 无论当前处于前奏期 (activeLineIndex == -1) 还是首行开唱，切歌换曲时立即将滚动列表归零对齐顶部，彻底杜绝残留旧歌偏移与开唱瞬间跳动
            val isNewTimeline = (timeline != lastScrolledTimeline)
            if (isNewTimeline) {
                lastScrolledTimeline = timeline
                lastScrolledLineIndex = -1
                listState.scrollToItem(0, 0)
            }

            if (activeLineIndex in timeline.lines.indices) {
                val delta = if (isNewTimeline) Int.MAX_VALUE else abs(activeLineIndex - lastScrolledLineIndex)
                lastScrolledLineIndex = activeLineIndex

                if (delta > 2 || isNewTimeline) {
                    // 💡 跨行寻道 (Seek) 或切歌大跨度跳变：立即瞬时定位对齐，杜绝缓慢滑行造成的滞后感
                    listState.scrollToItem(index = activeLineIndex, scrollOffset = 0)

                    val postItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                    if (postItem != null) {
                        // 💡 Finding #3: visibleItem.offset 已经是相对于视口容器的坐标，不加 topPaddingPx 杜绝双倍偏移
                        val fineDelta = LyricsRenderingMath.calculateViewportScrollDelta(
                            itemOffsetInViewport = postItem.offset.toFloat(),
                            itemSize = postItem.size.toFloat(),
                            viewportHeightPx = viewportHeightPx,
                            targetRatio = targetRatio
                        )
                        if (abs(fineDelta) > 1f) {
                            listState.scrollBy(fineDelta)
                        }
                    }
                } else {
                    // 💡 正常逐句推进：高阻尼平滑动画过渡，动态将高亮行物理中心平移对齐至垂直中间偏上 (38%)
                    val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                    if (visibleItem != null) {
                        // 💡 Finding #3: visibleItem.offset 已经是相对于视口物理坐标，直接与 targetCenterY 比较
                        val scrollDelta = LyricsRenderingMath.calculateViewportScrollDelta(
                            itemOffsetInViewport = visibleItem.offset.toFloat(),
                            itemSize = visibleItem.size.toFloat(),
                            viewportHeightPx = viewportHeightPx,
                            targetRatio = targetRatio
                        )
                        listState.animateScrollBy(
                            value = scrollDelta,
                            animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
                        )
                    } else {
                        listState.scrollToItem(index = activeLineIndex, scrollOffset = 0)
                        val postItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                        if (postItem != null) {
                            val fineDelta = LyricsRenderingMath.calculateViewportScrollDelta(
                                itemOffsetInViewport = postItem.offset.toFloat(),
                                itemSize = postItem.size.toFloat(),
                                viewportHeightPx = viewportHeightPx,
                                targetRatio = targetRatio
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
                    currentTimeProvider = { syncClock.getCurrentInterpolatedTimeMs() },
                    syncClock = syncClock,
                    primaryColor = primaryTextColor,
                    isPlaying = isPlaying
                )
            }
        }
    }
}
