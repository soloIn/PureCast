package com.solo.purecast.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.SeekCapability

/**
 * 💡 进度条右侧状态标签语义模式（严格由 DurationState + PlaybackStatus 派生）
 */
internal sealed interface ProgressRightLabelMode {
    data object Standby : ProgressRightLabelMode
    data object LiveBadge : ProgressRightLabelMode
    data class Placeholder(val text: String) : ProgressRightLabelMode
    data class FormattedTime(val text: String) : ProgressRightLabelMode
}

/**
 * 💡 PureCast 纤细圆角进度条与时间轴组件 (ProgressBarComponent)
 * 1. 轨道高度默认为 4dp，纯展示状态指示，完全禁用焦点捕获与点击/Seek 交互。
 * 2. 悬浮对比度保护：轨道底槽采用高透半白 [Color(0x40FFFFFF)]，填充条使用高纯亮白 [Color.White]，在任意流光背景下清晰可见。
 * 3. 左侧展示当前播放时间 (mm:ss)，右侧依据 [DurationState] 展示总时长、"等待投屏接入"、"--:--" 或实时流 "LIVE / 实时直通" 状态。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProgressBarComponent(
    currentPositionMs: Long,
    totalDurationMs: Long,
    durationState: DurationState,
    seekCapability: SeekCapability = SeekCapability.UNSEEKABLE,
    onSeek: ((newPositionMs: Long) -> Unit)? = null,
    onSeekImmediate: ((newPositionMs: Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hasDarkBackground: Boolean = true,
    focusRequester: FocusRequester? = null,
    onPlayPauseClick: (() -> Unit)? = null,
    playbackStatus: PlaybackStatus = PlaybackStatus.IDLE
) {
    val isIdle = playbackStatus == PlaybackStatus.IDLE || playbackStatus == PlaybackStatus.STOPPED
    val rightLabelMode = resolveProgressRightLabel(
        totalDurationMs = totalDurationMs,
        durationState = durationState,
        playbackStatus = playbackStatus
    )

    // 💡 视觉对比度自适应色彩
    val trackBgColor = if (hasDarkBackground) Color(0x40FFFFFF) else Color(0x18000000)
    val progressFillColor = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)
    val currentTimeColor = if (hasDarkBackground) Color(0xEEFFFFFF) else Color(0xFF3C3C43)
    val totalTimeColor = if (hasDarkBackground) Color(0xB3FFFFFF) else Color(0xFF6E6E73)

    val trackHeight = 4.dp

    val progressFraction = calculateProgressFraction(
        currentPositionMs = currentPositionMs,
        totalDurationMs = totalDurationMs,
        durationState = durationState,
        isIdle = isIdle
    )

    // 💡 500ms 线性平滑插值：消除 500ms 进度推送的阶跃顿挫感，实现 60fps 平滑推进
    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "progressBarFractionAnim"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        // 进度条轨道容器 (纯展示，无交互；0 Layout 重新测量，100% 运行于 GPU Draw 阶段)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // 背景槽与高亮进度：完全在 Draw 阶段合成圆角填充，跳过 Compose Layout 重新测量
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(trackHeight / 2))
                    .background(trackBgColor)
                    .drawWithContent {
                        drawContent()
                        val fillWidth = size.width * animatedFraction.coerceIn(0f, 1f)
                        if (fillWidth > 0f) {
                            val radius = size.height / 2f
                            drawRoundRect(
                                color = progressFillColor,
                                size = Size(fillWidth, size.height),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 底部时间指示栏 (左侧已播放，右侧总时长 / "--:--" / LIVE 徽章)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧已播放时间
            Text(
                text = formatTimeMs(currentPositionMs),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = currentTimeColor
            )

            when (rightLabelMode) {
                ProgressRightLabelMode.Standby -> {
                    Text(
                        text = "等待投屏接入",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = totalTimeColor
                    )
                }

                ProgressRightLabelMode.LiveBadge -> {
                    // 💡 仅在直播播放分支启动呼吸动画并延后至 graphicsLayer 读取，避免非直播态空转帧渲染
                    val infiniteTransition = rememberInfiniteTransition(label = "liveDotTransition")
                    val liveDotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 800),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "liveDotAlpha"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .graphicsLayer { alpha = liveDotAlpha }
                                .background(Color(0xFFFF453A))
                        )
                        Text(
                            text = "LIVE / 实时直通",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF453A)
                        )
                    }
                }

                is ProgressRightLabelMode.Placeholder -> {
                    Text(
                        text = rightLabelMode.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = totalTimeColor
                    )
                }

                is ProgressRightLabelMode.FormattedTime -> {
                    Text(
                        text = rightLabelMode.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = totalTimeColor
                    )
                }
            }
        }
    }
}

/**
 * 💡 辅助工具：将毫秒时间平滑转换为 mm:ss 格式
 */
internal fun formatTimeMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 💡 纯函数：解析进度条右侧展示文本模式
 * - IDLE / STOPPED：显示待机等待接入；
 * - UNKNOWN：绝不误标红点 LIVE，显示中性 "--:--" 占位；
 * - LIVE 且 PLAYING：显示呼吸红点 LIVE；
 * - FIXED（或带有效总时长）：格式化为 mm:ss。
 */
internal fun resolveProgressRightLabel(
    totalDurationMs: Long,
    durationState: DurationState,
    playbackStatus: PlaybackStatus
): ProgressRightLabelMode {
    if (playbackStatus == PlaybackStatus.IDLE || playbackStatus == PlaybackStatus.STOPPED) {
        return ProgressRightLabelMode.Standby
    }
    if (durationState == DurationState.LIVE && playbackStatus == PlaybackStatus.PLAYING) {
        return ProgressRightLabelMode.LiveBadge
    }
    if (totalDurationMs > 0L && durationState != DurationState.UNKNOWN) {
        return ProgressRightLabelMode.FormattedTime(formatTimeMs(totalDurationMs))
    }
    return ProgressRightLabelMode.Placeholder("--:--")
}

/**
 * 💡 纯函数：计算进度百分比 (0.0f ~ 1.0f)
 * - 待机 / 空闲 / 时长未知 ([DurationState.UNKNOWN])：保持 0.0f（杜绝未获取时长前进度条误满）；
 * - 显式实时流 ([DurationState.LIVE])：当前有推流位置推进则展示满条 1.0f；
 * - 固定时长点播 ([DurationState.FIXED])：按当前播放位置与总时长精确比例推进。
 */
internal fun calculateProgressFraction(
    currentPositionMs: Long,
    totalDurationMs: Long,
    durationState: DurationState,
    isIdle: Boolean = false
): Float {
    if (isIdle || durationState == DurationState.UNKNOWN) {
        return 0.0f
    }
    if (durationState == DurationState.LIVE) {
        return if (currentPositionMs > 0L || totalDurationMs > 0L) 1.0f else 0.0f
    }
    if (totalDurationMs <= 0L) {
        return 0.0f
    }
    return (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
}
