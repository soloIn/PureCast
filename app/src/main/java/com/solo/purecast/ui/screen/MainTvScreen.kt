package com.solo.purecast.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.components.FluidBackground
import com.solo.purecast.ui.components.PlaybackControls
import com.solo.purecast.ui.components.ProgressBarComponent
import com.solo.purecast.ui.components.SignalPathPill
import com.solo.purecast.util.Logger
import com.solo.purecast.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * 💡 PureCast 大屏主界面 (MainTvScreen)
 * 严格按照 Apple TV (tvOS) 原生设计与 lavande16/Apple-Music-For-Android-TV 实现：
 * 1. 彻底去除人造 Dock 外框，恢复通透、纯净、无界面的自然呼吸感；
 * 2. 文字与控件“悬浮”于背景之上：
 *    - 纯白标题 (Color.White) 与柔和半透白副标题 (Color.White.copy(alpha = 0.70f))；
 *    - 进度条与播放按钮直接通透漂浮，没有任何生硬的底色补丁；
 *    - 背景层覆盖柔和的高透保护遮罩，杜绝任何背景高亮对文字的对比度污染；
 * 3. 控制栏与进度条常驻显示，永不自动淡出；
 * 4. 42% / 58% Apple TV 黄金比例布局 + OLED 微像素平移保护。
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainTvScreen(
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onOpenSettings: () -> Unit,
    userInteractionTrigger: Long = 0L,
    modifier: Modifier = Modifier
) {
    val playbackStatus by PlaybackService.playbackStatus.collectAsState()
    val metadata by PlaybackService.currentMetadata.collectAsState()
    val audioPathState by PlaybackService.audioPathState.collectAsState()
    val serverError by PlaybackService.serverError.collectAsState()

    val playPauseFocusRequester = remember { FocusRequester() }

    // ==========================================
    // 1. OLED 防烧屏引擎: 微像素正弦平移 (每 10 秒平滑微移 2~3 像素)
    // ==========================================
    var pixelShiftX by remember { mutableFloatStateOf(0f) }
    var pixelShiftY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var tick = 0.0
        while (true) {
            delay(10_000)
            tick += 0.2
            pixelShiftX = (sin(tick) * 3).toFloat()
            pixelShiftY = (sin(tick * 0.7) * 2).toFloat()
        }
    }

    // 💡 页面初次进入时自动请求焦点至播放/暂停按钮
    LaunchedEffect(Unit) {
        delay(200)
        try {
            playPauseFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // ==========================================
    // 2. 封面与着色器动态流光背景
    // ==========================================
    val coverData: Any? = metadata.coverBytes ?: metadata.coverUri
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING

    // 封面放大弹簧动画
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.94f,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        ),
        label = "coverScaleAnim"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E15))
    ) {
        // 1. GLES2 材质网格动态流光背景
        FluidBackground(
            coverData = coverData,
            isPlaying = isPlaying
        )

        // 💡 柔和暗化层：保护前景文字与图标不被高饱和流光背景吞没，使所有前景自然“悬浮”
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.22f))
        )

        // 2. 主体 42% / 58% Apple TV 黄金比例布局
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(pixelShiftX.toInt(), pixelShiftY.toInt()) }
                .padding(horizontal = 72.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 42% 封面区
            Box(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val coverShape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f)
                        .scale(coverScale)
                        .shadow(28.dp, coverShape, spotColor = Color(0x80000000))
                        .clip(coverShape)
                        .background(Color(0xFF161822)),
                    contentAlignment = Alignment.Center
                ) {
                    val uri = metadata.coverUri
                    val hasValidCover = metadata.coverBytes != null ||
                            (!uri.isNullOrEmpty() &&
                                    (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)))

                    if (hasValidCover && coverData != null) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(coverData)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Album Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = { DefaultCoverCard(isPlaying = isPlaying, album = metadata.album) },
                            error = { DefaultCoverCard(isPlaying = isPlaying, album = metadata.album) }
                        )
                    } else {
                        DefaultCoverCard(isPlaying = isPlaying, album = metadata.album)
                    }
                }
            }

            Spacer(modifier = Modifier.width(48.dp))

            // 右侧 58% 控制与元数据区 (直接通透悬浮于背景之上，无任何死板 Dock)
            Column(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                // 异常警告指示 (如 UPnP 端口绑定异常或推流错误)
                if (serverError != null) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF8B0000).copy(alpha = 0.85f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "⚠️ $serverError",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 纯色彩语义的实时音频品质胶囊 (格式: 协议 · 音频格式 | 设备名)
                SignalPathPill(audioState = audioPathState)

                Spacer(modifier = Modifier.height(20.dp))

                // 歌曲标题：纯白高亮，平滑跑马灯永不截断
                Text(
                    text = metadata.title,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    lineHeight = 48.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 2000,
                            delayMillis = 1500
                        )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 艺术家：纯净次级白 (White 70% alpha)
                Text(
                    text = metadata.artist,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 专辑名称 (若存在)
                if (metadata.album.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = metadata.album,
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.50f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // 💡 纤细圆角进度条 (直接透明悬浮于背景上)
                IsolatedProgressBar(
                    totalDurationMs = metadata.durationMs,
                    isLiveStream = metadata.isLiveStream,
                    onPlayPauseClick = onTogglePlayPause
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 💡 纯几何图标交互控制按钮组 (上一曲 / 播放暂停 / 下一曲 / 设置，绝对居中自适应)
                PlaybackControls(
                    playbackStatus = playbackStatus,
                    isLiveStream = metadata.isLiveStream,
                    onPlayPauseClick = onTogglePlayPause,
                    onPreviousClick = onPreviousTrack,
                    onNextClick = onNextTrack,
                    onSettingsClick = onOpenSettings,
                    playPauseFocusRequester = playPauseFocusRequester
                )
            }
        }

        // 3. 首次开机/权限自检向导
        var showOnboarding by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val settingsRepo = remember { SettingsRepository(context) }
        LaunchedEffect(Unit) {
            if (!settingsRepo.isOnboardingCompleted() && PermissionHelper.hasMissingCriticalPermission(context)) {
                showOnboarding = true
            }
        }
        if (showOnboarding) {
            OnboardingWizard(
                onDismiss = {
                    showOnboarding = false
                    settingsRepo.setOnboardingCompleted(true)
                }
            )
        }
    }
}

/**
 * 💡 辅助组件：隔离 500ms 高频播放进度重组范围至叶子组件，避免触发主屏幕全量重绘
 */
@Composable
private fun IsolatedProgressBar(
    totalDurationMs: Long,
    isLiveStream: Boolean,
    onPlayPauseClick: () -> Unit
) {
    val servicePositionMs by PlaybackService.playbackPositionMs.collectAsState()
    var seekPositionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(servicePositionMs) {
        seekPositionMs = servicePositionMs
    }

    ProgressBarComponent(
        currentPositionMs = seekPositionMs,
        totalDurationMs = totalDurationMs,
        isLiveStream = isLiveStream,
        onSeek = { newPositionMs ->
            seekPositionMs = newPositionMs
            Logger.ui.i("MainTvScreen", "D-Pad 寻道至: ${newPositionMs / 1000} 秒")
        },
        onPlayPauseClick = onPlayPauseClick
    )
}

/**
 * 💡 辅助组件：默认唱片封面占位卡片
 */
@Composable
private fun DefaultCoverCard(
    isPlaying: Boolean,
    album: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E202B)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.tv.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(id = com.solo.purecast.R.drawable.ic_waveform),
                contentDescription = "Default Music Icon",
                tint = if (isPlaying) Color.White else Color(0xFF6E6E73),
                modifier = Modifier.width(64.dp).height(48.dp)
            )
            Text(
                text = album.ifEmpty { "PureCast Hi-Fi" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.60f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
