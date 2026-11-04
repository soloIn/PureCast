package com.solo.purecast.ui.screen

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.components.FluidBackground
import com.solo.purecast.ui.components.PlaybackControls
import com.solo.purecast.ui.components.ProgressBarComponent
import com.solo.purecast.ui.components.SignalPathPill
import com.solo.purecast.ui.theme.PureCastPrimary
import com.solo.purecast.util.Logger
import com.solo.purecast.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * 💡 PureCast 大屏主界面 (MainTvScreen)
 * 严格遵循 Apple TV (tvOS) 极简工业美学与 10-Foot 遥控器大屏交互规范：
 * 1. 42% / 58% 黄金比例布局：左侧呈现 24dp 浮动圆角高清网络封面与弹簧缩放；右侧居中展示排版信息与控制项。
 * 2. Coil + Palette 动态流光背景：异步加载网络封面，并在后台提取主次色板无缝驱动流体背景，无封面优雅降级深邃夜空灰。
 * 3. 歌曲标题平滑跑马灯：通过 Modifier.basicMarquee() 保证超长标题永不截断。
 * 4. 纤细圆角进度条：支持 D-Pad 上下键聚焦、左右键步进快进/快退 10 秒；实时流显示 "LIVE / 实时直通" 并禁用寻道。
 * 5. 防烧屏双重防护引擎：
 *    - 微像素正弦平移 (Pixel Shift): 每 10 秒平滑微移 2~3 像素。
 *    - 3 分钟无操作控制栏自动淡出，按任意方向键即刻唤醒。
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainTvScreen(
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    menuKeyTrigger: Long = 0L,
    userInteractionTrigger: Long = 0L,
    modifier: Modifier = Modifier
) {
    val playbackStatus by PlaybackService.playbackStatus.collectAsState()
    val metadata by PlaybackService.currentMetadata.collectAsState()
    val audioPathState by PlaybackService.audioPathState.collectAsState()
    val serverError by PlaybackService.serverError.collectAsState()

    var showDrawer by remember { mutableStateOf(false) }
    val playPauseFocusRequester = remember { FocusRequester() }

    // 💡 遥控器 MENU 键响应：呼出/收起侧边栏
    LaunchedEffect(menuKeyTrigger) {
        if (menuKeyTrigger > 0L) {
            showDrawer = !showDrawer
        }
    }

    // ==========================================
    // 1. 防烧屏引擎: 微像素平移 + 3分钟自动淡出控制栏
    // ==========================================
    var pixelShiftX by remember { mutableFloatStateOf(0f) }
    var pixelShiftY by remember { mutableFloatStateOf(0f) }
    var lastInteractionTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isControlsVisible by remember { mutableStateOf(true) }

    // 10 秒周期性正弦微移，有效保护 OLED / LCD 电视屏幕
    LaunchedEffect(Unit) {
        var tick = 0.0
        while (true) {
            delay(10_000)
            tick += 0.2
            pixelShiftX = (sin(tick) * 3).toFloat()
            pixelShiftY = (sin(tick * 0.7) * 2).toFloat()
        }
    }

    // 响应 Activity 全局按键分发：重置倒计时并即时唤醒控制栏
    LaunchedEffect(userInteractionTrigger) {
        if (userInteractionTrigger > 0L) {
            lastInteractionTimestamp = System.currentTimeMillis()
            if (!isControlsVisible) {
                isControlsVisible = true
            }
        }
    }

    // 3 分钟无操作自动淡出控制栏检查
    LaunchedEffect(playbackStatus) {
        while (true) {
            delay(2000)
            if (playbackStatus == PlaybackStatus.PLAYING) {
                val elapsed = System.currentTimeMillis() - lastInteractionTimestamp
                if (elapsed >= 180_000L && isControlsVisible) {
                    isControlsVisible = false
                }
            } else {
                if (!isControlsVisible) {
                    isControlsVisible = true
                }
            }
        }
    }

    // 💡 控制栏显现或抽屉关闭时自动将遥控器焦点恢复至播放/暂停按钮
    LaunchedEffect(isControlsVisible, showDrawer) {
        if (isControlsVisible && !showDrawer) {
            delay(150)
            try {
                playPauseFocusRequester.requestFocus()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // ==========================================
    // 2. Coil + Palette 动态流光背景提取
    // ==========================================
    val (palettePrimary, paletteSecondary) = rememberPaletteFromUri(metadata.coverUri)

    // 封面放大弹簧动画
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.94f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "coverScale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                // 💡 界面内任意遥控器按键事件触发时即刻唤醒控制栏并重置防烧屏倒计时
                lastInteractionTimestamp = System.currentTimeMillis()
                if (!isControlsVisible) {
                    isControlsVisible = true
                }
                false
            }
    ) {
        // 1. 流光动态背景 (结合 Palette 色调与 API 31+ 硬件模糊 / API 30 StackBlur)
        FluidBackground(
            primaryColor = palettePrimary,
            secondaryColor = paletteSecondary
        )

        // 2. 主体 42% / 58% Apple TV 黄金比例布局
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(pixelShiftX.toInt(), pixelShiftY.toInt()) }
                .padding(horizontal = 72.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 38% 封面区
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .scale(coverScale)
                        .shadow(32.dp, RoundedCornerShape(24.dp), spotColor = Color(0x66000000))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1E202B))
                        .border(1.dp, Color(0xFF32364A), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // 💡 Coil 异步加载高清网络封面，仅允许合法 http/https 协议以防异常 scheme 导致崩溃
                    val cover = metadata.coverUri
                    val isSafeCoverUri = !cover.isNullOrEmpty() &&
                            (cover.startsWith("http://", ignoreCase = true) || cover.startsWith("https://", ignoreCase = true))

                    if (isSafeCoverUri) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(cover)
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

            Spacer(modifier = Modifier.width(36.dp))

            // 右侧 62% 控制与元数据区
            Column(
                modifier = Modifier
                    .weight(0.62f)
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

                // 链路状态指示徽章
                SignalPathPill(audioState = audioPathState)

                Spacer(modifier = Modifier.height(24.dp))

                // 💡 歌曲标题：启用 basicMarquee 实现超长平滑横向滚动
                Text(
                    text = metadata.title,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    lineHeight = 46.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 2000,
                            delayMillis = 1500
                        )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 艺术家
                Text(
                    text = metadata.artist,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFB0B0B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 专辑名称 (若存在)
                if (metadata.album.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = metadata.album,
                        fontSize = 16.sp,
                        color = Color(0xFFA1A1A6),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 💡 底部控制区域：支持 3 分钟防烧屏平滑淡出与按键即刻唤醒
                AnimatedVisibility(
                    visible = isControlsVisible,
                    enter = fadeIn(animationSpec = tween(400)),
                    exit = fadeOut(animationSpec = tween(800))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 纤细圆角进度条 (隔离 500ms 重组作用域至叶子节点)
                        IsolatedProgressBar(
                            totalDurationMs = metadata.durationMs,
                            isLiveStream = metadata.isLiveStream,
                            onPlayPauseClick = onTogglePlayPause
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 交互控制按钮组 (上一曲 / 播放暂停 / 下一曲 / 设置)
                        PlaybackControls(
                            playbackStatus = playbackStatus,
                            isLiveStream = metadata.isLiveStream,
                            onPlayPauseClick = onTogglePlayPause,
                            onPreviousClick = onPreviousTrack,
                            onNextClick = onNextTrack,
                            onSettingsClick = { showDrawer = true },
                            playPauseFocusRequester = playPauseFocusRequester
                        )
                    }
                }
            }
        }

        // 3. 全功能设置侧边栏抽屉
        if (showDrawer) {
            SettingsDrawer(
                audioState = audioPathState,
                onClose = { showDrawer = false }
            )
        }

        // 4. 首次开机/权限自检向导 (当存在未就绪的关键权限且未标记已完成时自动弹出)
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
 * 💡 辅助组件：默认唱片占位卡片 (无封面或加载中时使用)
 */
@Composable
private fun DefaultCoverCard(
    isPlaying: Boolean,
    album: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (isPlaying) "♪" else "♫",
            fontSize = 64.sp,
            color = PureCastPrimary
        )
        Text(
            text = if (album.isNotEmpty()) album else "PureCast Lossless",
            fontSize = 14.sp,
            color = Color(0xFFA1A1A6),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 💡 异步从封面 Uri 提取 Palette 调色板主色调与次色调
 * 注意：必须通过 allowHardware(false) 获取软件位图，否则 API 28+ 访问硬件位图会抛出异常
 */
@Composable
private fun rememberPaletteFromUri(
    coverUri: String?,
    defaultPrimary: Color = Color(0xFF1E2838),
    defaultSecondary: Color = Color(0xFF0F121C)
): Pair<Color, Color> {
    val context = LocalContext.current
    var colors by remember(coverUri) {
        mutableStateOf(Pair(defaultPrimary, defaultSecondary))
    }

    LaunchedEffect(coverUri) {
        val isValidScheme = coverUri?.startsWith("http://", ignoreCase = true) == true ||
                coverUri?.startsWith("https://", ignoreCase = true) == true
        if (coverUri.isNullOrEmpty() || !isValidScheme) {
            colors = Pair(defaultPrimary, defaultSecondary)
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                val imageLoader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(coverUri)
                    .allowHardware(false) // 💡 必须禁用硬件位图，Palette 提取像素需要软件 Bitmap
                    .size(256, 256) // 缩放解析以加速提取并降低电视内存占用
                    .build()

                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    val dominantColor = palette.getDominantColor(defaultPrimary.toArgb())
                    val secondaryColor = palette.getVibrantColor(
                        palette.getDarkVibrantColor(
                            palette.getMutedColor(defaultSecondary.toArgb())
                        )
                    )
                    colors = Pair(Color(dominantColor), Color(secondaryColor))
                    Logger.ui.i("MainTvScreen", "成功从封面提取 Palette 调色板: primary=$dominantColor")
                }
            } catch (e: Exception) {
                Logger.ui.w("MainTvScreen", "Palette 提取封面色彩失败，回退暗夜灰", e)
                colors = Pair(defaultPrimary, defaultSecondary)
            }
        }
    }

    return colors
}
