package com.solo.purecast.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.solo.purecast.R
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.components.TransportIconButton
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/**
 * 💡 PureCast Apple Music TV 风格歌词全屏展开屏 (LyricsScreen)
 * 严格遵照 10-Foot UI 与 Android TV 交互规范：
 * 1. 左侧 45%：封面 + 歌曲信息水平居中 + 上一曲/暂停/下一曲播放控制条；
 * 2. 右侧 55%：巨幅沉浸歌词流 (动态隔离 60fps 逐字扫光)；
 * 3. 严格遵循“PureCast 播放进度是唯一真实来源”的设计契约，与 iPhone 进度毫秒级同步；
 * 4. 遥控器 Back 键通过 BackHandler 拦截优雅缩回主屏。
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun LyricsScreen(
    lyricUiState: LyricUiState,
    syncClock: LyricsSyncClock,
    metadata: TrackMetadata,
    playbackStatus: PlaybackStatus,
    coverData: Any?,
    coverHash: Int = 0,
    hasDarkBackground: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current

    // 💡 优先拦截遥控器返回键，优雅缩回主屏而非退出 App
    BackHandler(enabled = true) {
        onClose()
    }

    val isPlaying = playbackStatus == PlaybackStatus.PLAYING
    val playPauseFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 💡 歌词大屏入场时，主动将焦点置于播放/暂停按钮，保证 D-Pad 遥控器即刻可用
        delay(120L)
        try {
            playPauseFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // 💡 优化 4：左栏封面动效统一，与 MainTvScreen 共享 1.0f <-> 0.94f 播放态缩放弹簧动画
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lyricsCoverScaleAnim"
    )

    val coverSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                state = rememberSharedContentState(key = "album_cover"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 64.dp, end = 36.dp, top = 36.dp, bottom = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ==========================================
        // 左侧 40%: 封面、歌曲元数据与播放控制栏 (水平居中)
        // ==========================================
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val coverShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .aspectRatio(1f)
                    .then(coverSharedModifier)
                    .graphicsLayer {
                        scaleX = coverScale
                        scaleY = coverScale
                    }
                    .shadow(
                        elevation = 24.dp,
                        shape = coverShape,
                        spotColor = if (hasDarkBackground) Color(0x80000000) else Color(0x20000000)
                    )
                    .clip(coverShape)
                    .background(if (hasDarkBackground) Color(0xFF161822) else Color.White)
                    .then(
                        if (!hasDarkBackground) {
                            Modifier.border(1.dp, Color(0xFFE5E5EA), coverShape)
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverData != null) {
                    val coverRequest = remember(context, coverData, coverHash) {
                        val cacheKey = if (coverHash != 0) "cover_$coverHash" else null
                        ImageRequest.Builder(context)
                            .data(coverData)
                            .apply {
                                if (cacheKey != null) {
                                    memoryCacheKey(cacheKey)
                                    placeholderMemoryCacheKey(cacheKey)
                                }
                            }
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = coverRequest,
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_waveform),
                        contentDescription = "Music Placeholder",
                        tint = if (hasDarkBackground) Color.White.copy(alpha = 0.6f) else Color(0xFF1C1C1E).copy(alpha = 0.6f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 歌曲信息（普通布局，消除异构空间几何形变）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = metadata.title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E),
                    maxLines = 1,
                    lineHeight = 34.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .basicMarquee(initialDelayMillis = 2000, repeatDelayMillis = 1500)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = metadata.artist,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (hasDarkBackground) Color.White.copy(alpha = 0.70f) else Color(0xFF1C1C1E).copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.92f)
                )

                if (metadata.album.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = metadata.album,
                        fontSize = 14.sp,
                        color = if (hasDarkBackground) Color.White.copy(alpha = 0.50f) else Color(0xFF1C1C1E).copy(alpha = 0.50f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.92f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 💡 播放控制栏：上一曲、播放/暂停、下一曲（普通布局，消除异构按钮数与尺寸拉伸形变）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 上一曲按钮 (⏮)
                TransportIconButton(
                    iconResId = R.drawable.ic_backward_end_fill,
                    contentDescription = "上一曲",
                    buttonSize = 48.dp,
                    iconSize = 24.dp,
                    hasDarkBackground = hasDarkBackground,
                    onClick = onPreviousClick
                )

                // 播放/暂停按钮 (▶ / ❚❚) - 绑定 focusRequester 确保大屏遥控器入场时焦点立即可用
                TransportIconButton(
                    iconResId = if (isPlaying) R.drawable.ic_pause_fill else R.drawable.ic_play_fill,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    buttonSize = 52.dp,
                    iconSize = 26.dp,
                    hasDarkBackground = hasDarkBackground,
                    focusRequester = playPauseFocusRequester,
                    onClick = onPlayPauseClick
                )

                // 下一曲按钮 (⏭)
                TransportIconButton(
                    iconResId = R.drawable.ic_forward_end_fill,
                    contentDescription = "下一曲",
                    buttonSize = 48.dp,
                    iconSize = 24.dp,
                    hasDarkBackground = hasDarkBackground,
                    onClick = onNextClick
                )
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        // ==========================================
        // 右侧 60%: 沉浸巨幅歌词流与异常分支 (空间充裕防右侧文字裁切)
        // ==========================================
        Box(
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            when (lyricUiState) {
                is LyricUiState.Success -> {
                    LyricsStreamView(
                        timeline = lyricUiState.timeline,
                        syncClock = syncClock,
                        hasDarkBackground = hasDarkBackground,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is LyricUiState.Instrumental -> {
                    InstrumentalStateView(
                        hasDarkBackground = hasDarkBackground,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is LyricUiState.Loading -> {
                    LoadingShimmerView(
                        hasDarkBackground = hasDarkBackground,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                is LyricUiState.NotFound, LyricUiState.Idle -> {
                    NotFoundStateView(
                        hasDarkBackground = hasDarkBackground,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * 💡 沉浸式歌词流滚动视口：上下 80dp 渐变羽化遮罩 + 黄金居中自动滚动
 * 针对低端电视盒子实施高频隔离渲染：仅高亮激活行参与 60fps VSYNC 重组
 */
@Composable
private fun LyricsStreamView(
    timeline: LyricTimeline,
    syncClock: LyricsSyncClock,
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    val isWordSync = timeline.format == LyricFormat.TTML_SYLLABLE || timeline.format == LyricFormat.LRC_WORD_SYNC
    var activeLineIndex by remember { mutableIntStateOf(-1) }

    // 💡 优化 1：按歌词格式按需降频。
    // 当非逐字格式时，无需在每帧 VSYNC 更新时间戳，仅需每 33ms (~30Hz) 监测 activeLineIndex 变化；
    // 兼顾极低 CPU 负载与毫秒级高亮提前量 (280ms Lead-in) 的精准响应，稳态下全列表重组开销降至 0 次/秒！
    LaunchedEffect(timeline, isWordSync) {
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

    // 💡 滚动曲线定制与垂直中间偏上对齐 (38% 黄金视线分割点)
    // 动态计算目标行物理中心与视口 38% 高度差值，通过 animateScrollBy 配合 FastOutSlowInEasing，获得更具阻尼感的 Apple TV 级平滑滚动体验
    var lastScrolledLineIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex in timeline.lines.indices) {
            val delta = abs(activeLineIndex - lastScrolledLineIndex)
            lastScrolledLineIndex = activeLineIndex
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val targetCenterY = if (viewportHeight > 0) (viewportHeight * 0.38f) else with(density) { 240.dp.toPx() }

            if (delta > 2) {
                // 💡 跨行寻道 (Seek) 或切歌大跨度跳变：立即瞬时定位对齐，杜绝缓慢滑行造成的滞后感
                val topPaddingPx = with(density) { 180.dp.roundToPx() }
                val scrollOffsetPx = (topPaddingPx - targetCenterY.toInt()).coerceAtLeast(0)
                listState.scrollToItem(
                    index = activeLineIndex,
                    scrollOffset = scrollOffsetPx
                )
            } else {
                // 💡 正常逐句推进：高阻尼平滑动画过渡，动态将高亮行物理中心平移对齐至垂直中间偏上 (38%)
                val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                if (visibleItem != null) {
                    val currentCenter = visibleItem.offset + visibleItem.size / 2
                    val scrollDelta = currentCenter - targetCenterY
                    listState.animateScrollBy(
                        value = scrollDelta,
                        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
                    )
                } else {
                    val topPaddingPx = with(density) { 180.dp.roundToPx() }
                    val scrollOffsetPx = (topPaddingPx - targetCenterY.toInt()).coerceAtLeast(0)
                    listState.animateScrollToItem(
                        index = activeLineIndex,
                        scrollOffset = scrollOffsetPx
                    )
                }
            }
        }
    }

    val primaryTextColor = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)

    Box(
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
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 0.dp, top = 180.dp, end = 0.dp, bottom = 220.dp),
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
                    primaryColor = primaryTextColor
                )
            }
        }
    }
}

/**
 * 💡 丝滑 Apple Music TV 级歌词行渲染器：
 * 1. 消除原 Active/Inactive 树节点瞬间重建，统一为单一稳定组件；
 * 2. 通过 Spring 弹性缩放 (1.0f -> 1.08f) 与平滑透明度淡入淡出，消除生硬跳变；
 * 3. 针对前奏结束第一句入场实施优雅微距上浮 (translationY)，杜绝大字突现闪烁；
 * 4. 纯 GPU RenderNode (graphicsLayer) 硬件加速合成，避免 CPU 重新 measure/layout，低端盒子 60fps 不掉帧。
 */
@Composable
private fun SmoothLyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isFirstLine: Boolean,
    distanceFromActive: Int,
    currentTimeProvider: () -> Long,
    syncClock: LyricsSyncClock,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    // 💡 1. 缩放动画 (Spring 曲线，优雅弹性，平滑缩放 1.0f -> 1.08f)
    val animatedScale by animateFloatAsState(
        targetValue = if (isActive) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 220f
        ),
        label = "lyricScale"
    )

    // 💡 2. 透明度动画 (针对激活行 1.0f 与非激活行平滑过渡，消除生硬突变)
    val targetAlpha = if (isActive) {
        1.0f
    } else {
        (0.42f - (distanceFromActive - 1) * 0.08f).coerceIn(0.18f, 0.42f)
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "lyricAlpha"
    )

    // 💡 3. 微距位移动画 (第一句入场或行激活时平滑上浮 3dp，呈现沉浸层次感)
    val targetTranslationY = if (isActive) -3f else 0f
    val animatedTranslationY by animateFloatAsState(
        targetValue = targetTranslationY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 180f
        ),
        label = "lyricOffsetY"
    )

    Column(
        modifier = modifier
            .fillMaxWidth(0.925f) // 💡 预留 7.5% 物理余量 (0.925 * 1.08 = 0.999)，确保 1.08x 放大时文字右侧绝不超出视口被裁剪，且多容纳 2~3 个大号汉字
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                alpha = animatedAlpha
                // 💡 优化 2：直接在 graphicsLayer 内部转换 dp.toPx()，彻底跳过 Composition 与 Layout 阶段
                translationY = animatedTranslationY.dp.toPx()
                // 💡 锚定左侧居中，确保大屏文字放大时左边界平齐不抖动
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val words = line.words
        val translation = line.translationText

        // 💡 优化 3：逐字歌词视觉与排版开销优化
        if (isActive && !words.isNullOrEmpty()) {
            WordSyncKaraokeText(
                line = line,
                syncClock = syncClock,
                currentTimeProvider = currentTimeProvider,
                primaryColor = primaryColor
            )
        } else {
            Text(
                text = line.mainText,
                fontSize = 34.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                color = primaryColor,
                lineHeight = 44.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 简体中文翻译行 (统一基础字号 20sp)
        if (!translation.isNullOrEmpty()) {
            Text(
                text = translation,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = primaryColor.copy(alpha = if (isActive) 0.75f else 0.55f),
                lineHeight = 28.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 💡 优化 3：逐字歌词（Karaoke Word-Sync）视觉与排版开销优化组件：
 * 1. 主歌词文本保持为不可变 String，使 TextLayoutResult 在整句生命周期内 100% 缓存复用，排版 CPU 开销降为 0；
 * 2. 0 重组纯 Draw 阶段渲染：完全隔离在 drawWithContent 内部读取时间，重组次数彻底归 0；
 * 3. 视觉行分段裁切 (clipRect)：针对换行多行歌词，按视觉行分段绘制与渐变，根除跨行染色串色 Bug；
 * 4. 实现真正的 Apple Music 卡拉 OK 逐像素平滑扫光与抗锯齿羽化边缘。
 */
@Composable
private fun WordSyncKaraokeText(
    line: LyricLine,
    syncClock: LyricsSyncClock,
    currentTimeProvider: () -> Long,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    val words = line.words ?: return
    val fullText = line.mainText

    // 💡 仅用于触发 Draw 阶段重绘的 VSYNC 信号，绝不在此读取时间引发 Composable 重组
    var frameTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos ->
                frameTrigger = frameNanos
            }
        }
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 预先计算各词在 fullText 中的字符索引范围
    val wordRanges = remember(words, fullText) {
        var searchIndex = 0
        words.map { word ->
            val startIndex = fullText.indexOf(word.text, searchIndex).takeIf { it >= 0 } ?: searchIndex
            val endIndex = (startIndex + word.text.length).coerceAtMost(fullText.length)
            searchIndex = endIndex
            startIndex to endIndex
        }
    }

    Text(
        text = fullText,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 44.sp,
        onTextLayout = { layoutResult = it },
        color = Color.White,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val lr = layoutResult ?: return@drawWithContent
                drawContent()

                // 💡 在 Draw 阶段读取 frameTrigger，注册每帧 60fps 重绘监听，彻底跳过 Composition 与 Layout 阶段
                frameTrigger.let { }

                val now = currentTimeProvider()
                val unlitColor = primaryColor.copy(alpha = 0.45f)
                val feather = 10.dp.toPx()
                val lineCount = lr.lineCount

                if (words.isEmpty() || now < words.first().startMs) {
                    drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }
                if (now >= words.last().endMs) {
                    drawRect(color = primaryColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                // 寻找当前活跃的词索引 activeWordIndex
                var activeWordIndex = -1
                for (i in words.indices) {
                    val w = words[i]
                    if (now in w.startMs..w.endMs) {
                        activeWordIndex = i
                        break
                    } else if (now < w.startMs) {
                        activeWordIndex = (i - 1).coerceAtLeast(0)
                        break
                    } else if (i == words.lastIndex) {
                        activeWordIndex = i
                    }
                }

                if (activeWordIndex !in words.indices) {
                    drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                val activeWord = words[activeWordIndex]
                val range = wordRanges[activeWordIndex]
                val startChar = range.first
                val endChar = (range.second - 1).coerceIn(startChar, (fullText.length - 1).coerceAtLeast(0))

                val activeVisualLine: Int
                val currentSweepX: Float

                if (now in activeWord.startMs..activeWord.endMs) {
                    val progress = syncClock.calculateWordProgress(activeWord, now)
                    val charOffset = (startChar + ((endChar - startChar) * progress).toInt()).coerceIn(startChar, endChar)
                    activeVisualLine = lr.getLineForOffset(charOffset)
                    val startLine = lr.getLineForOffset(startChar)
                    val endLine = lr.getLineForOffset(endChar)

                    currentSweepX = if (startLine == endLine && startLine == activeVisualLine) {
                        val wordLeft = lr.getBoundingBox(startChar).left
                        val wordRight = lr.getBoundingBox(endChar).right
                        wordLeft + (wordRight - wordLeft) * progress
                    } else {
                        val charBox = lr.getBoundingBox(charOffset)
                        charBox.left + charBox.width * progress
                    }
                } else {
                    // 词间间隙：已完整唱完 activeWord，正在等待下一个词
                    activeVisualLine = lr.getLineForOffset(endChar)
                    currentSweepX = lr.getBoundingBox(endChar).right
                }

                // 💡 优化 2：按视觉行分段精确裁切与绘制，彻底根除多行换行歌词跨行串色 Bug
                for (lineIdx in 0 until lineCount) {
                    val lineTop = lr.getLineTop(lineIdx)
                    val lineBottom = lr.getLineBottom(lineIdx)
                    val lineLeft = lr.getLineLeft(lineIdx)
                    val lineRight = lr.getLineRight(lineIdx)

                    clipRect(left = 0f, top = lineTop, right = size.width, bottom = lineBottom) {
                        if (lineIdx < activeVisualLine) {
                            // 该视觉行已全部唱完：整行主色高亮
                            drawRect(
                                color = primaryColor,
                                blendMode = BlendMode.SrcIn
                            )
                        } else if (lineIdx > activeVisualLine) {
                            // 该视觉行尚未唱到：整行暗色未激活
                            drawRect(
                                color = unlitColor,
                                blendMode = BlendMode.SrcIn
                            )
                        } else {
                            // 正在该视觉行演唱：仅在该行宽度内应用局部水平渐变扫光
                            val sweep = currentSweepX.coerceIn(lineLeft, lineRight)
                            val brush = if (sweep <= lineLeft) {
                                SolidColor(unlitColor)
                            } else if (sweep >= lineRight) {
                                SolidColor(primaryColor)
                            } else {
                                val stopLit = ((sweep - lineLeft) / (lineRight - lineLeft).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                val stopUnlit = (((sweep + feather) - lineLeft) / (lineRight - lineLeft).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                Brush.horizontalGradient(
                                    0f to primaryColor,
                                    stopLit to primaryColor,
                                    stopUnlit to unlitColor,
                                    1f to unlitColor,
                                    startX = lineLeft,
                                    endX = lineRight
                                )
                            }
                            drawRect(
                                brush = brush,
                                blendMode = BlendMode.SrcIn
                            )
                        }
                    }
                }
            }
    )
}

/**
 * 💡 纯音乐艺术呈现卡片 (Instrumental)
 */
@Composable
private fun InstrumentalStateView(
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_waveform),
                contentDescription = "Instrumental",
                tint = if (hasDarkBackground) Color.White.copy(alpha = 0.7f) else Color(0xFF1C1C1E).copy(alpha = 0.7f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "纯音乐，请欣赏",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)
            )
            Text(
                text = "沉浸聆听纯净旋律",
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = if (hasDarkBackground) Color.White.copy(alpha = 0.55f) else Color(0xFF1C1C1E).copy(alpha = 0.55f)
            )
        }
    }
}

/**
 * 💡 歌词加载中骨架呼吸占位 (Loading)
 */
@Composable
private fun LoadingShimmerView(
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val alphaAnim by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    val baseColor = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)
    val barColor = baseColor.copy(alpha = alphaAnim)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.8f),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth(0.7f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
            Box(modifier = Modifier.fillMaxWidth(0.9f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
            Box(modifier = Modifier.fillMaxWidth(0.5f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
            Box(modifier = Modifier.fillMaxWidth(0.75f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
        }
    }
}

/**
 * 💡 未找到歌词状态 (NotFound)
 */
@Composable
private fun NotFoundStateView(
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_quote_bubble),
                contentDescription = "No Lyrics",
                tint = if (hasDarkBackground) Color.White.copy(alpha = 0.35f) else Color(0xFF1C1C1E).copy(alpha = 0.35f),
                modifier = Modifier.size(52.dp)
            )
            Text(
                text = "暂无歌词",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasDarkBackground) Color.White.copy(alpha = 0.7f) else Color(0xFF1C1C1E).copy(alpha = 0.7f)
            )
            Text(
                text = "未匹配到同步歌词数据",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                color = if (hasDarkBackground) Color.White.copy(alpha = 0.45f) else Color(0xFF1C1C1E).copy(alpha = 0.45f)
            )
        }
    }
}
