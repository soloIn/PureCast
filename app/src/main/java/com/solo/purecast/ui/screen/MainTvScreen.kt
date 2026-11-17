package com.solo.purecast.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.ui.background.FluidQuality
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.components.FluidBackground
import com.solo.purecast.ui.components.PlaybackControls
import com.solo.purecast.ui.components.ProgressBarComponent
import com.solo.purecast.ui.components.SignalPathPill
import com.solo.purecast.ui.screen.LyricsScreen
import com.solo.purecast.ui.theme.AppPalette
import com.solo.purecast.util.Logger
import com.solo.purecast.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 💡 界面顶部告警严重度分级：硬故障醒目红色条 vs 软故障温和提示条
 */
internal enum class BannerSeverity {
    WARNING_BLOCKING,
    SOFT_HINT
}

internal data class UiErrorBanner(
    val code: ErrorCode,
    val message: String,
    val severity: BannerSeverity,
    val timestampMs: Long = 0L
)

/**
 * 💡 从全局会话快照提取最高优先级可见 UI 告警（不主动抢夺遥控器焦点）
 */
internal fun resolveUiErrorBanner(snapshot: PlayerSessionSnapshot): UiErrorBanner? {
    val err = snapshot.highestPriorityError ?: return null
    val severity = when (err.code) {
        ErrorCode.USB_DEVICE_DETACHED,
        ErrorCode.USB_PERMISSION_DENIED,
        ErrorCode.USB_RECONFIGURE_FAILED,
        ErrorCode.USB_OPEN_FAILED,
        ErrorCode.DECODE_ERROR -> BannerSeverity.WARNING_BLOCKING

        ErrorCode.NETWORK_STREAM_ERROR,
        ErrorCode.REMOTE_COMMAND_UNSUPPORTED,
        ErrorCode.REMOTE_COMMAND_TIMEOUT,
        ErrorCode.SERVER_ERROR -> BannerSeverity.SOFT_HINT
    }
    return UiErrorBanner(
        code = err.code,
        message = err.message,
        severity = severity,
        timestampMs = err.timestampMs
    )
}

/**
 * 💡 计算界面当前是否采用暗场背景渲染策略：
 * 1. 遵循系统全局深色模式；
 * 2. 或处于推流活跃状态且流光画质开启且持有有效封面时启用暗场；
 * 3. 待机、画质关闭或无封面时回退至系统主题底色，杜绝浅色模式文字对比度崩溃。
 */
internal fun resolveHasDarkBackground(
    isSystemDark: Boolean,
    isIdle: Boolean,
    fluidQuality: FluidQuality,
    hasActiveCover: Boolean
): Boolean = isSystemDark || (!isIdle && fluidQuality != FluidQuality.OFF && hasActiveCover)

/**
 * 💡 剥离高频 progressPositionMs 后的主屏 UI 投影态，避免每 500ms 进度推进触发封面与流光背景整屏重组
 */
internal data class MainTvUiState(
    val businessState: BusinessSessionState,
    val playbackStatus: PlaybackStatus,
    val hardwareState: HardwarePipelineState,
    val metadata: TrackMetadata,
    val audioPath: AudioPathState,
    val errorBanner: UiErrorBanner?,
    val uiProtectionActive: Boolean
) {
    val isIdle: Boolean
        get() = playbackStatus == PlaybackStatus.IDLE || playbackStatus == PlaybackStatus.STOPPED

    val isPlaying: Boolean
        get() = playbackStatus == PlaybackStatus.PLAYING

    val isTransitioning: Boolean
        get() = businessState == BusinessSessionState.TRANSITIONING

    /** 💡 TRANSITIONING 过渡期维持满尺寸封面放大与流光张力，杜绝切歌缩小闪屏 */
    val isCoverExpanded: Boolean
        get() = businessState == BusinessSessionState.PLAYING || businessState == BusinessSessionState.TRANSITIONING
}

internal fun PlayerSessionSnapshot.toMainTvUiState(): MainTvUiState = MainTvUiState(
    businessState = businessState,
    playbackStatus = playbackStatus,
    hardwareState = hardwareState,
    metadata = metadata,
    audioPath = audioPath,
    errorBanner = resolveUiErrorBanner(this),
    uiProtectionActive = uiProtectionActive
)

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
@OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun MainTvScreen(
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSeekTo: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val friendlyName by settingsRepo.friendlyNameFlow.collectAsState()
    val fluidQuality by settingsRepo.fluidQualityFlow.collectAsState()

    val uiState by remember {
        PlaybackService.sessionSnapshot
            .map { it.toMainTvUiState() }
            .distinctUntilChanged()
    }.collectAsState(initial = remember { PlaybackService.sessionSnapshot.value.toMainTvUiState() })

    val playbackStatus = uiState.playbackStatus
    val metadata = uiState.metadata
    val audioPathState = uiState.audioPath
    val errorBanner = uiState.errorBanner
    val visibleErrorBanner = errorBanner

    val playPauseFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val lyricsFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // 💡 歌词 UI 状态与 60fps/120fps 帧间平滑同步时钟 (U5, U6, U7)
    val lyricUiState by remember { PlaybackService.lyricsUiState }.collectAsState()
    var isLyricsExpanded by remember { mutableStateOf(false) }
    val lyricsSyncClock = remember { LyricsSyncClock() }

    val currentServiceSnapshot by remember { PlaybackService.sessionSnapshot }.collectAsState()
    var lastObservedTrackGen by remember { mutableLongStateOf(0L) }
    LaunchedEffect(
        currentServiceSnapshot.trackGeneration,
        currentServiceSnapshot.progressPositionMs,
        currentServiceSnapshot.playbackStatus,
        currentServiceSnapshot.businessState
    ) {
        // 💡 Finding 14 修复：TRANSITIONING 缓冲期间扬声器尚未真实出声，必须保持 playing = false 冻结时钟，防止歌词抢跑 1~2 秒
        val isTrulyPlaying = currentServiceSnapshot.businessState == BusinessSessionState.PLAYING &&
            currentServiceSnapshot.playbackStatus == PlaybackStatus.PLAYING

        if (currentServiceSnapshot.trackGeneration != lastObservedTrackGen) {
            lastObservedTrackGen = currentServiceSnapshot.trackGeneration
            // 💡 切歌换代：坚决瞬时排空旧代时钟并硬对齐目标进度，杜绝上一首歌残留时间对新歌产生干扰
            lyricsSyncClock.hardAlign(
                targetPositionMs = currentServiceSnapshot.progressPositionMs,
                playing = isTrulyPlaying
            )
        } else {
            lyricsSyncClock.updatePosition(
                newPositionMs = currentServiceSnapshot.progressPositionMs,
                playing = isTrulyPlaying
            )
        }
    }

    LaunchedEffect(uiState.isIdle) {
        if (uiState.isIdle) {
            isLyricsExpanded = false
            lyricsSyncClock.reset()
        }
    }

    // 💡 首次开机/权限自检向导显示状态
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val shouldShow = withContext(Dispatchers.IO) {
            !settingsRepo.isOnboardingCompleted() && PermissionHelper.hasMissingCriticalPermission(context)
        }
        if (shouldShow) {
            showOnboarding = true
        }
    }

    // 💡 焦点路由：从待机空闲 (IDLE/STOPPED) 首次进入活跃播放时，自动聚焦到“播放/暂停”；
    // 在切歌 (TRANSITIONING)、暂停、恢复与弹窗刷新期间，严格保留用户遥控器当前选中的按钮，绝不强行夺焦！
    var hasAutoFocusedOnPlay by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isIdle) {
        try {
            if (uiState.isIdle) {
                hasAutoFocusedOnPlay = false
                delay(200)
                settingsFocusRequester.requestFocus()
            } else if (!hasAutoFocusedOnPlay) {
                hasAutoFocusedOnPlay = true
                delay(200)
                playPauseFocusRequester.requestFocus()
            }
        } catch (e: Exception) {
            Logger.ui.w("MainTvScreen", "空闲与播放态自动焦点请求异常", e)
        }
    }

    // ==========================================
    // 2. 封面与着色器动态流光背景
    // ==========================================
    val coverData: Any? = metadata.coverBytes ?: metadata.coverUri
    val isPlaying = uiState.isPlaying
    val isIdle = uiState.isIdle
    val uri = metadata.coverUri
    val hasValidCover = metadata.coverBytes != null ||
            (!uri.isNullOrEmpty() &&
                    (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)))
    var persistentCover by remember { mutableStateOf<Any?>(null) }
    var persistentCoverHash by remember { mutableStateOf(0) }

    // 💡 封面占位保护与内容指纹防抖：
    // 1. 严格使用 contentHash / coverUri 内容指纹对比，彻底消除 ByteArray 对象引用变动引发的 Crossfade 整体闪烁；
    // 2. 只有在完全空闲 (IDLE/STOPPED) 且无封面时才清空；
    // 3. 切歌期间若新曲目暂无封面，保留上一曲封面 1.2s 防抖，若持续无封面则优雅过渡到默认占位。
    LaunchedEffect(
        metadata.title,
        metadata.artist,
        metadata.coverHash,
        metadata.coverUri,
        hasValidCover,
        isIdle
    ) {
        if (isIdle) {
            // 💡 完全空闲待机态：立即清空封面与指纹
            persistentCover = null
            persistentCoverHash = 0
        } else if (hasValidCover && coverData != null) {
            val incomingHash = if (metadata.coverHash != 0) {
                metadata.coverHash
            } else {
                metadata.coverUri.hashCode()
            }
            // 💡 修复 Finding 4: 指纹一致且已存在封面时严格跳过赋值，杜绝相同封面重复触发 Crossfade 动画
            if (incomingHash != persistentCoverHash || persistentCover == null) {
                persistentCover = coverData
                persistentCoverHash = incomingHash
            }
        } else {
            // 💡 修复 Finding 4: 切歌期间若新曲目暂无封面（如 DMAP 文本先于 image/jpeg 到达），
            // 给予 1200ms 宽限期防抖，杜绝旧封面瞬时闪黑与同一个专辑切歌误触发过渡；若 1200ms 超时仍无封面方优雅清除
            if (persistentCover != null) {
                delay(1200L)
            }
            persistentCover = null
            persistentCoverHash = 0
        }
    }

    val hasActiveCover = persistentCover != null
    val hasDarkBackground = resolveHasDarkBackground(AppPalette.isDark, isIdle, fluidQuality, hasActiveCover)

    // 封面放大弹簧动画：TRANSITIONING 切歌过渡期保持 1.0f 放大态，避免闪缩
    val coverScale by animateFloatAsState(
        targetValue = if (uiState.isCoverExpanded) 1.0f else 0.94f,
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
            coverData = persistentCover,
            isPlaying = isPlaying,
            isIdle = isIdle,
            quality = fluidQuality
        )

        // 💡 柔和暗化层：仅在推流播放动态流光背景时启用，防止高饱和流光吞没文字；待机时关闭以呈现原汁原味的深/浅主题
        if (!isIdle && fluidQuality != FluidQuality.OFF && hasActiveCover) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
            )
        }

        // 2. 主体展示层：支持标准 42%/58% 主屏与 Apple Music TV 歌词全屏展开空间层次过渡
        SharedTransitionLayout {
            AnimatedContent(
                targetState = isLyricsExpanded,
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it / 6 } +
                            fadeIn(animationSpec = tween(350))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { -it / 6 } +
                            fadeOut(animationSpec = tween(250)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { -it / 6 } +
                            fadeIn(animationSpec = tween(350))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it / 6 } +
                            fadeOut(animationSpec = tween(250)))
                    }
                },
                label = "lyricsScreenExpansionTransition"
            ) { expanded ->
                if (expanded) {
                    LyricsScreen(
                        lyricUiState = lyricUiState,
                        syncClock = lyricsSyncClock,
                        metadata = metadata,
                        playbackStatus = playbackStatus,
                        coverData = persistentCover,
                        hasDarkBackground = hasDarkBackground,
                        onPlayPauseClick = onTogglePlayPause,
                        onPreviousClick = onPreviousTrack,
                        onNextClick = onNextTrack,
                        onClose = {
                            isLyricsExpanded = false
                            coroutineScope.launch {
                                delay(150)
                                try {
                                    lyricsFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                            }
                        },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusProperties { canFocus = !showOnboarding }
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
                                    .sharedElement(
                                        state = rememberSharedContentState(key = "album_cover"),
                                        animatedVisibilityScope = this@AnimatedContent
                                    )
                                    .graphicsLayer {
                                        scaleX = coverScale
                                        scaleY = coverScale
                                    }
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
                            Crossfade(
                                targetState = persistentCoverHash to persistentCover,
                                animationSpec = tween(300),
                                label = "coverCrossfade"
                            ) { (_, resolvedCover) ->
                                if (resolvedCover != null) {
                                    val coverRequest = remember(context, resolvedCover) {
                                        ImageRequest.Builder(context)
                                            .data(resolvedCover)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = coverRequest,
                                        contentDescription = "Album Cover",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                        onError = {
                                            persistentCover = null
                                            persistentCoverHash = 0
                                        }
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
                    }

                    Spacer(modifier = Modifier.width(48.dp))

                    // 右侧 58% 控制与元数据区 (直接通透悬浮于背景之上，无任何死板 Dock)
                    Column(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        // 分级异常告警条（不可获焦，严禁抢夺 D-Pad 当前焦点；恢复后随快照自动消除）
                        if (visibleErrorBanner != null) {
                            val isBlocking = visibleErrorBanner.severity == BannerSeverity.WARNING_BLOCKING
                            val bannerBg = if (isBlocking) {
                                Color(0xFF8B0000).copy(alpha = 0.88f)
                            } else {
                                Color(0xFF3A2E00).copy(alpha = 0.85f)
                            }
                            val bannerBorder = if (isBlocking) {
                                Color(0xFFFF6961).copy(alpha = 0.45f)
                            } else {
                                Color(0xFFFFD60A).copy(alpha = 0.45f)
                            }
                            val prefix = if (isBlocking) "⚠️" else "ℹ️"
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bannerBg)
                                    .border(1.dp, bannerBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "$prefix ${visibleErrorBanner.message}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // 纯色彩语义的实时音频品质胶囊 (格式: 协议 · 音频格式 | 设备名 + 软过渡提示)
                        SignalPathPill(
                            audioState = audioPathState,
                            hardwareState = uiState.hardwareState,
                            isIdle = isIdle,
                            hasDarkBackground = hasDarkBackground
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // 歌曲标题与艺术家容器（共享元素绑定）
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sharedElement(
                                    state = rememberSharedContentState(key = "track_info"),
                                    animatedVisibilityScope = this@AnimatedContent
                                )
                        ) {
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
                                        repeatDelayMillis = 1500
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
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        IsolatedProgressBar(
                            syncClock = lyricsSyncClock,
                            totalDurationMs = metadata.durationMs,
                            durationState = metadata.durationState,
                            seekCapability = metadata.seekCapability,
                            playbackStatus = playbackStatus,
                            hasDarkBackground = hasDarkBackground,
                            onPlayPauseClick = onTogglePlayPause,
                            onSeekTo = onSeekTo
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 💡 纯几何图标交互控制按钮组 (上一曲 / 播放暂停 / 下一曲 / 歌词 / 设置，共享元素绑定)
                        PlaybackControls(
                            playbackStatus = playbackStatus,
                            hasDarkBackground = hasDarkBackground,
                            lyricState = lyricUiState,
                            isLyricsExpanded = isLyricsExpanded,
                            onLyricsClick = {
                                isLyricsExpanded = !isLyricsExpanded
                            },
                            onPlayPauseClick = onTogglePlayPause,
                            onPreviousClick = onPreviousTrack,
                            onNextClick = onNextTrack,
                            onSettingsClick = onOpenSettings,
                            playPauseFocusRequester = playPauseFocusRequester,
                            settingsFocusRequester = settingsFocusRequester,
                            lyricsFocusRequester = lyricsFocusRequester,
                            modifier = Modifier.sharedElement(
                                state = rememberSharedContentState(key = "playback_controls"),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                        )
                    }
                }
            }
        }
        }

        // 3. 首次开机/权限自检向导
        if (showOnboarding) {
            OnboardingWizard(
                onDismiss = {
                    showOnboarding = false
                    settingsRepo.setOnboardingCompleted(true)
                    coroutineScope.launch {
                        delay(100)
                        try {
                            if (uiState.isIdle) settingsFocusRequester.requestFocus() else playPauseFocusRequester.requestFocus()
                        } catch (e: Exception) {
                            Logger.ui.w("MainTvScreen", "向导关闭后请求焦点异常", e)
                        }
                    }
                }
            )
        }
    }
}

/**
 * 💡 辅助组件：主屏进度条组件，接入 LyricsSyncClock 单调高频插值 (60fps)，杜绝 500ms 离散跳动并与歌词大屏毫秒级完全对齐
 */
@Composable
private fun IsolatedProgressBar(
    syncClock: LyricsSyncClock,
    totalDurationMs: Long,
    durationState: DurationState,
    seekCapability: SeekCapability,
    playbackStatus: PlaybackStatus,
    hasDarkBackground: Boolean = true,
    onPlayPauseClick: () -> Unit,
    onSeekTo: (Long) -> Unit = {}
) {
    var interpolatedPositionMs by remember { mutableLongStateOf(0L) }
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                withFrameNanos {
                    interpolatedPositionMs = syncClock.getCurrentInterpolatedTimeMs()
                }
            }
        } else {
            interpolatedPositionMs = syncClock.getCurrentInterpolatedTimeMs()
        }
    }

    val currentSnapshot by remember { PlaybackService.sessionSnapshot }.collectAsState()
    val displayPosition = if (isPlaying) interpolatedPositionMs else currentSnapshot.progressPositionMs
    val clampedPosition = if (totalDurationMs > 0) displayPosition.coerceIn(0L, totalDurationMs) else displayPosition

    ProgressBarComponent(
        currentPositionMs = clampedPosition,
        totalDurationMs = totalDurationMs,
        durationState = durationState,
        playbackStatus = playbackStatus,
        hasDarkBackground = hasDarkBackground
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
