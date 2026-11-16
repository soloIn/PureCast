package com.solo.purecast.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.solo.purecast.R
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.components.TransportIconButton
import kotlin.math.abs

/**
 * 💡 PureCast Apple Music TV 风格歌词全屏展开屏 (LyricsScreen)
 * 严格遵照 10-Foot UI 与 Android TV 交互规范：
 * 1. 左侧 45%：封面 + 歌曲信息水平居中 + 上一曲/暂停/下一曲播放控制条；
 * 2. 右侧 55%：巨幅沉浸歌词流 (动态隔离 60fps 逐字扫光)；
 * 3. 严格遵循“PureCast 播放进度是唯一真实来源”的设计契约，与 iPhone 进度毫秒级同步；
 * 4. 遥控器 Back 键通过 BackHandler 拦截优雅缩回主屏。
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LyricsScreen(
    lyricUiState: LyricUiState,
    syncClock: LyricsSyncClock,
    metadata: TrackMetadata,
    playbackStatus: PlaybackStatus,
    coverData: Any?,
    hasDarkBackground: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 💡 优先拦截遥控器返回键，优雅缩回主屏而非退出 App
    BackHandler(enabled = true) {
        onClose()
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ==========================================
        // 左侧 45%: 封面、歌曲元数据与播放控制栏 (水平居中)
        // ==========================================
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val coverShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(1f)
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
                    val coverRequest = remember(context, coverData) {
                        ImageRequest.Builder(context)
                            .data(coverData)
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

            // 歌曲标题 (水平居中)
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
                    .basicMarquee(initialDelayMillis = 2000, delayMillis = 1500)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 艺术家 (水平居中)
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

            Spacer(modifier = Modifier.height(20.dp))

            // 💡 播放控制栏：上一曲、播放/暂停、下一曲
            val isPlaying = playbackStatus == PlaybackStatus.PLAYING
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

                // 播放/暂停按钮 (▶ / ❚❚)
                TransportIconButton(
                    iconResId = if (isPlaying) R.drawable.ic_pause_fill else R.drawable.ic_play_fill,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    buttonSize = 52.dp,
                    iconSize = 26.dp,
                    hasDarkBackground = hasDarkBackground,
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

        Spacer(modifier = Modifier.width(36.dp))

        // ==========================================
        // 右侧 55%: 沉浸巨幅歌词流与异常分支
        // ==========================================
        Box(
            modifier = Modifier
                .weight(0.55f)
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

    // 💡 只有当前激活行索引改变时，才驱动列表重组与平滑滚动
    var activeLineIndex by remember { mutableIntStateOf(-1) }
    var currentRenderTimeMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(timeline) {
        while (true) {
            withFrameNanos {
                val nowTimeMs = syncClock.getCurrentInterpolatedTimeMs()
                currentRenderTimeMs = nowTimeMs
                val state = syncClock.calculateActiveLineState(timeline, nowTimeMs)
                if (state.lineIndex != activeLineIndex) {
                    activeLineIndex = state.lineIndex
                }
            }
        }
    }

    val targetOffsetPx = remember(density) {
        with(density) { (-120).dp.roundToPx() }
    }

    // 前奏期 (activeLineIndex == -1) 时抑制滚动与高亮
    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex in timeline.lines.indices) {
            listState.animateScrollToItem(
                index = activeLineIndex,
                scrollOffset = targetOffsetPx
            )
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
            contentPadding = PaddingValues(top = 180.dp, bottom = 220.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = timeline.lines,
                key = { index, line -> "${line.startMs}_$index" }
            ) { index, line ->
                val isActive = index == activeLineIndex
                val distance = if (activeLineIndex >= 0) abs(index - activeLineIndex) else 10

                if (isActive) {
                    ActiveLyricLineRow(
                        line = line,
                        currentTimeMs = currentRenderTimeMs,
                        syncClock = syncClock,
                        primaryColor = primaryTextColor
                    )
                } else {
                    InactiveLyricLineRow(
                        line = line,
                        distanceFromActive = distance,
                        primaryColor = primaryTextColor
                    )
                }
            }
        }
    }
}

/**
 * 💡 激活行专属渲染器：仅在此组件内进行音节级 60fps 扫光，不影响列表其他所有行
 */
@Composable
private fun ActiveLyricLineRow(
    line: LyricLine,
    currentTimeMs: Long,
    syncClock: LyricsSyncClock,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val words = line.words
        val translation = line.translationText

        if (!words.isNullOrEmpty()) {
            val annotatedText = buildAnnotatedString {
                words.forEach { word ->
                    val progress = syncClock.calculateWordProgress(word, currentTimeMs)
                    val alpha = 0.35f + 0.65f * progress
                    withStyle(SpanStyle(color = primaryColor.copy(alpha = alpha))) {
                        append(word.text)
                    }
                }
            }
            Text(
                text = annotatedText,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 46.sp,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = line.mainText,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                lineHeight = 46.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 翻译行 (22sp, 60% 基础色)
        if (!translation.isNullOrEmpty()) {
            Text(
                text = translation,
                fontSize = 22.sp,
                fontWeight = FontWeight.Normal,
                color = primaryColor.copy(alpha = 0.60f),
                lineHeight = 30.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 💡 非激活行静态渲染器：完全解耦高频时间戳，零无效重组
 */
@Composable
private fun InactiveLyricLineRow(
    line: LyricLine,
    distanceFromActive: Int,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val lineAlpha = (0.45f - (distanceFromActive - 1) * 0.08f).coerceIn(0.18f, 0.45f)
        val translation = line.translationText

        Text(
            text = line.mainText,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = primaryColor.copy(alpha = lineAlpha),
            lineHeight = 38.sp,
            modifier = Modifier.fillMaxWidth()
        )

        if (!translation.isNullOrEmpty()) {
            Text(
                text = translation,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                color = primaryColor.copy(alpha = lineAlpha * 0.70f),
                lineHeight = 24.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
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
