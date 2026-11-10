package com.solo.purecast.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberCoroutineScope
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.components.FluidBackground
import com.solo.purecast.ui.components.PlaybackControls
import com.solo.purecast.ui.components.ProgressBarComponent
import com.solo.purecast.ui.components.SignalPathPill
import com.solo.purecast.ui.theme.AppPalette
import com.solo.purecast.util.Logger
import com.solo.purecast.util.PermissionHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val friendlyName by settingsRepo.friendlyNameFlow.collectAsState()
    val fluidQuality by settingsRepo.fluidQualityFlow.collectAsState()

    val playbackStatus by PlaybackService.playbackStatus.collectAsState()
    val metadata by PlaybackService.currentMetadata.collectAsState()
    val audioPathState by PlaybackService.audioPathState.collectAsState()
    val serverError by PlaybackService.serverError.collectAsState()

    val playPauseFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }

    // ==========================================
    // 1. OLED 防烧屏引擎: 微像素平滑微移 (每 10 秒平滑移动 2~3 像素，3秒线性插值杜绝生硬抽动)
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

    val animatedShiftX by animateFloatAsState(
        targetValue = pixelShiftX,
        animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
        label = "pixelShiftXAnim"
    )
    val animatedShiftY by animateFloatAsState(
        targetValue = pixelShiftY,
        animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
        label = "pixelShiftYAnim"
    )

    // 💡 焦点路由：从待机空闲 (IDLE) 首次进入活跃播放时，自动聚焦到“播放/暂停”；
    // 在切歌、暂停、恢复期间，严格保留用户遥控器当前选中的按钮（如“下一曲”），绝不强行夺焦！
    var hasAutoFocusedOnPlay by remember { mutableStateOf(false) }

    LaunchedEffect(playbackStatus) {
        try {
            if (playbackStatus == PlaybackStatus.IDLE || playbackStatus == PlaybackStatus.STOPPED) {
                hasAutoFocusedOnPlay = false
                delay(200)
                settingsFocusRequester.requestFocus()
            } else if (!hasAutoFocusedOnPlay) {
                hasAutoFocusedOnPlay = true
                delay(200)
                playPauseFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    // ==========================================
    // 2. 封面与着色器动态流光背景
    // ==========================================
    val coverData: Any? = metadata.coverBytes ?: metadata.coverUri
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING
    val isIdle = playbackStatus == PlaybackStatus.IDLE || playbackStatus == PlaybackStatus.STOPPED
    val hasActiveCover = coverData != null

    // 💡 视觉色彩稳定性：推流活跃期间始终呈现全屏暗场流光，控件与进度条恒定保持纯净高亮白；
    // 仅在完全待机 (IDLE) 时才跟随系统深浅色切换，彻底杜绝切歌瞬间黑白颜色闪烁颠簸
    val hasDarkBackground = !isIdle || hasActiveCover || AppPalette.isDark

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
            .background(AppPalette.background)
    ) {
        // 1. GLES2 材质网格动态流光背景 (无推流时 MacBackground 优雅呈现自适应纯净背景)
        FluidBackground(
            coverData = coverData,
            isPlaying = isPlaying,
            isIdle = isIdle,
            quality = fluidQuality
        )

        // 💡 柔和暗化层：仅在推流播放动态流光背景时启用，防止高饱和流光吞没文字；待机时关闭以呈现原汁原味的深/浅主题
        if (hasActiveCover) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
            )
        }

        // 2. 主体 42% / 58% Apple TV 黄金比例布局
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(animatedShiftX.roundToInt(), animatedShiftY.roundToInt()) }
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
                        .shadow(
                            elevation = 28.dp,
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
                    val uri = metadata.coverUri
                    val hasValidCover = metadata.coverBytes != null ||
                            (!uri.isNullOrEmpty() &&
                                    (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)))
                    var persistentCover by remember { mutableStateOf<Any?>(null) }

                    // 💡 封面占位保护：只有在完全空闲 (IDLE/STOPPED) 且无封面时才清空；
                    // 切歌期间若新曲目暂无封面（如无封面电台/播客），保留上一曲封面 1.2s 防抖，若持续无封面则优雅清除过渡到默认占位
                    LaunchedEffect(metadata.title, metadata.artist, hasValidCover, coverData, isIdle) {
                        if (isIdle) {
                            persistentCover = null
                        } else if (hasValidCover && coverData != null) {
                            persistentCover = coverData
                        } else {
                            delay(1200)
                            if (!hasValidCover && coverData == null) {
                                persistentCover = null
                            }
                        }
                    }

                    if (persistentCover != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(persistentCover)
                                .crossfade(300)
                                .build(),
                            contentDescription = "Album Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        DefaultCoverCard(
                            isPlaying = isPlaying,
                            album = metadata.album,
                            hasDarkBackground = hasDarkBackground
                        )
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

                val isIdle = playbackStatus == PlaybackStatus.IDLE || playbackStatus == PlaybackStatus.STOPPED
                val displayTitle = if (isIdle) "等待音频投屏接入" else metadata.title

                // 歌曲标题：推流时纯白高亮，待机浅色时纯黑；平滑跑马灯永不截断
                Text(
                    text = displayTitle,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasDarkBackground) Color.White else AppPalette.primaryText,
                    maxLines = 1,
                    lineHeight = 48.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            initialDelayMillis = 2000,
                            delayMillis = 1500
                        )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 艺术家 / 待机投屏引导：次级色彩自适应
                val airPlayGuide = PlaybackService.formatAirPlayName(friendlyName)
                val upnpGuide = PlaybackService.formatUpnpName(friendlyName)
                val displayArtist = if (isIdle) {
                    "AirPlay: $airPlayGuide  ·  UPnP: $upnpGuide"
                } else {
                    metadata.artist
                }

                Text(
                    text = displayArtist,
                    fontSize = if (isIdle) 18.sp else 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (hasDarkBackground) Color.White.copy(alpha = 0.70f) else AppPalette.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 专辑名称 (若存在且非待机)
                if (!isIdle && metadata.album.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = metadata.album,
                        fontSize = 16.sp,
                        color = if (hasDarkBackground) Color.White.copy(alpha = 0.50f) else AppPalette.secondaryText.copy(alpha = 0.70f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // 💡 纤细圆角进度条 (自适应深浅，直接透明悬浮于背景上)
                IsolatedProgressBar(
                    totalDurationMs = metadata.durationMs,
                    isLiveStream = metadata.isLiveStream,
                    playbackStatus = playbackStatus,
                    canSeek = false,
                    hasDarkBackground = hasDarkBackground,
                    onPlayPauseClick = onTogglePlayPause
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 💡 纯几何图标交互控制按钮组 (上一曲 / 播放暂停 / 下一曲 / 设置，绝对居中自适应深浅)
                PlaybackControls(
                    playbackStatus = playbackStatus,
                    isLiveStream = metadata.isLiveStream,
                    hasDarkBackground = hasDarkBackground,
                    onPlayPauseClick = onTogglePlayPause,
                    onPreviousClick = onPreviousTrack,
                    onNextClick = onNextTrack,
                    onSettingsClick = onOpenSettings,
                    playPauseFocusRequester = playPauseFocusRequester,
                    settingsFocusRequester = settingsFocusRequester
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
    playbackStatus: PlaybackStatus,
    canSeek: Boolean = false,
    hasDarkBackground: Boolean = true,
    onPlayPauseClick: () -> Unit
) {
    val servicePositionMs by PlaybackService.playbackPositionMs.collectAsState()
    var seekPositionMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    // 💡 只有在非用户拖拽寻道防抖期间，才接受 servicePositionMs 的覆盖，并结合总时长安全钳位
    LaunchedEffect(servicePositionMs, totalDurationMs) {
        if (!isScrubbing) {
            val clamped = if (totalDurationMs > 0) servicePositionMs.coerceIn(0L, totalDurationMs) else servicePositionMs
            seekPositionMs = clamped
        }
    }

    ProgressBarComponent(
        currentPositionMs = seekPositionMs,
        totalDurationMs = totalDurationMs,
        isLiveStream = isLiveStream,
        playbackStatus = playbackStatus,
        canSeek = canSeek,
        hasDarkBackground = hasDarkBackground,
        onSeek = { newPositionMs ->
            seekPositionMs = newPositionMs
            isScrubbing = true
            debounceJob?.cancel()
            debounceJob = coroutineScope.launch {
                delay(300)
                isScrubbing = false
                Logger.ui.i("MainTvScreen", "D-Pad 寻道防抖完成，提交 Seek: ${newPositionMs / 1000} 秒")
            }
        },
        onPlayPauseClick = onPlayPauseClick
    )
}

/**
 * 💡 辅助组件：默认唱片封面占位卡片 (自适应深浅)
 */
@Composable
private fun DefaultCoverCard(
    isPlaying: Boolean,
    album: String,
    hasDarkBackground: Boolean = true,
    modifier: Modifier = Modifier
) {
    val cardBg = if (hasDarkBackground) Color(0xFF1E202B) else Color.White
    val iconColor = if (hasDarkBackground) {
        if (isPlaying) Color.White else Color(0xFF6E6E73)
    } else {
        if (isPlaying) Color(0xFF1C1C1E) else Color(0xFF8E8E93)
    }
    val textColor = if (hasDarkBackground) Color.White.copy(alpha = 0.60f) else Color(0xFF6E6E73)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(cardBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.tv.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(id = com.solo.purecast.R.drawable.ic_waveform),
                contentDescription = "Default Music Icon",
                tint = iconColor,
                modifier = Modifier.width(64.dp).height(48.dp)
            )
            Text(
                text = album.ifEmpty { "PureCast Hi-Fi" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
