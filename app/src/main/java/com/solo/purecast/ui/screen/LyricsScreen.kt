package com.solo.purecast.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.components.TransportIconButton
import com.solo.purecast.ui.lyrics.InstrumentalStateView
import com.solo.purecast.ui.lyrics.LoadingShimmerView
import com.solo.purecast.ui.lyrics.LyricsStreamView
import com.solo.purecast.ui.lyrics.NotFoundStateView
import kotlinx.coroutines.delay

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
