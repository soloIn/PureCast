package com.solo.purecast.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.solo.purecast.R
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.components.SignalPathPill
import kotlin.math.abs

/**
 * 💡 PureCast Apple Music TV 风格歌词全屏展开屏 (LyricsScreen)
 * 严格遵照 10-Foot UI 与 Android TV 交互规范：
 * 1. 左侧 35%：紧凑封面 + 音频品质胶囊 + 歌曲标题与艺术家；
 * 2. 右侧 65%：巨幅沉浸歌词流 (TvLazyColumn + 80dp 上下羽化渐变遮罩)；
 * 3. 聚焦行：36sp 粗体纯白高亮，音节级逐字扫光；下方一行 22sp 60% 白色翻译；
 * 4. 非聚焦行：28sp 35%~45% 透明度，随距离行数景深平滑衰减；
 * 5. 遥控器 Back 键通过 BackHandler 拦截优雅缩回主屏。
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LyricsScreen(
    lyricUiState: LyricUiState,
    syncClock: LyricsSyncClock,
    metadata: TrackMetadata,
    audioPathState: AudioPathState,
    hardwareState: HardwarePipelineState,
    coverData: Any?,
    hasDarkBackground: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 💡 优先拦截遥控器返回键，优雅缩回主屏而非退出 App
    BackHandler(enabled = true) {
        onClose()
    }

    // 💡 VSYNC 60fps/120fps 单调平滑时钟推进
    var currentRenderTimeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lyricUiState) {
        val timeline = (lyricUiState as? LyricUiState.Success)?.timeline
        if (timeline != null) {
            while (true) {
                withFrameNanos {
                    currentRenderTimeMs = syncClock.getCurrentInterpolatedTimeMs()
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ==========================================
        // 左侧 35%: 紧凑封面与歌曲元数据信息
        // ==========================================
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            val coverShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
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
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 音频信号胶囊
            SignalPathPill(
                audioState = audioPathState,
                hardwareState = hardwareState,
                isIdle = false,
                hasDarkBackground = hasDarkBackground
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 歌曲标题
            Text(
                text = metadata.title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E),
                maxLines = 1,
                lineHeight = 34.sp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .basicMarquee(initialDelayMillis = 2000, delayMillis = 1500)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 艺术家
            Text(
                text = metadata.artist,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (hasDarkBackground) Color.White.copy(alpha = 0.70f) else Color(0xFF1C1C1E).copy(alpha = 0.70f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }
        }

        Spacer(modifier = Modifier.width(32.dp))

        // ==========================================
        // 右侧 65%: 沉浸巨幅歌词流与异常分支
        // ==========================================
        Box(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            when (lyricUiState) {
                is LyricUiState.Success -> {
                    LyricsStreamView(
                        timeline = lyricUiState.timeline,
                        currentTimeMs = currentRenderTimeMs,
                        syncClock = syncClock,
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
 */
@Composable
private fun LyricsStreamView(
    timeline: LyricTimeline,
    currentTimeMs: Long,
    syncClock: LyricsSyncClock,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val activeState = remember(currentTimeMs, timeline) {
        syncClock.calculateActiveLineState(timeline, currentTimeMs)
    }
    val activeLineIndex = activeState.lineIndex

    // 💡 智能居中位移：当高亮行推进时，平滑带动视口将当前行保持在偏上方阅读黄金区域
    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex in timeline.lines.indices) {
            listState.animateScrollToItem(
                index = activeLineIndex,
                scrollOffset = -120
            )
        }
    }

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
                val distance = abs(index - activeLineIndex)

                LyricLineRow(
                    line = line,
                    isActive = isActive,
                    distanceFromActive = distance,
                    currentTimeMs = currentTimeMs,
                    syncClock = syncClock
                )
            }
        }
    }
}

/**
 * 💡 歌词单行渲染器：支持音节级逐字扫光与双语翻译并排
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    distanceFromActive: Int,
    currentTimeMs: Long,
    syncClock: LyricsSyncClock,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val words = line.words
        val translation = line.translationText

        if (isActive) {
            // ==========================================
            // 激活高亮行 (36sp 纯白粗体，音节级扫光)
            // ==========================================
            if (!words.isNullOrEmpty()) {
                val annotatedText = buildAnnotatedString {
                    words.forEach { word ->
                        val progress = syncClock.calculateWordProgress(word, currentTimeMs)
                        val alpha = 0.35f + 0.65f * progress
                        withStyle(SpanStyle(color = Color.White.copy(alpha = alpha))) {
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
                    color = Color.White,
                    lineHeight = 46.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 翻译行 (22sp, 60% 白色)
            if (!translation.isNullOrEmpty()) {
                Text(
                    text = translation,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.60f),
                    lineHeight = 30.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            // ==========================================
            // 非激活行 (28sp，随行间距平滑景深衰减)
            // ==========================================
            val lineAlpha = (0.45f - (distanceFromActive - 1) * 0.08f).coerceIn(0.18f, 0.45f)

            Text(
                text = line.mainText,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = lineAlpha),
                lineHeight = 38.sp,
                modifier = Modifier.fillMaxWidth()
            )

            if (!translation.isNullOrEmpty()) {
                Text(
                    text = translation,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = lineAlpha * 0.70f),
                    lineHeight = 24.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
    val barColor = if (hasDarkBackground) Color.White.copy(alpha = 0.15f) else Color(0xFF1C1C1E).copy(alpha = 0.10f)
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
