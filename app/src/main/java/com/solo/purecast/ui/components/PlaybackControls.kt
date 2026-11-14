package com.solo.purecast.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import com.solo.purecast.R
import com.solo.purecast.model.PlaybackStatus

/**
 * 💡 PureCast 遥控器专属播放控制栏 (PlaybackControls)
 * 严格按照 lavande16/Apple-Music-For-Android-TV 与 Apple TV (tvOS) 原生质感重构：
 * 1. 彻底移除沉重的白色描边 (border = None) 与外层光晕 (glow = None)；
 * 2. 纯粹遵循 Apple 视觉反馈层级：
 *    - 非焦点按钮：稍低亮度 (White 55% alpha)，纯平半透悬浮，优雅不喧宾夺主；
 *    - 焦点按钮：点亮至 100% 纯白正常亮度 + 1.12x 浮起放大 + 极微弱的纯平微白磨砂底 (Color(0x22FFFFFF))；
 * 3. 几何对称居中：SF Symbols 矢量 100% 绝对置于几何中心，消除默认 Padding 偏心；
 * 4. 严谨标准图标：上一曲 [ic_backward_end_fill]、播放/暂停 [ic_play_fill/ic_pause_fill]、下一曲 [ic_forward_end_fill] 与设置 [ic_gear]。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaybackControls(
    playbackStatus: PlaybackStatus,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasDarkBackground: Boolean = true,
    playPauseFocusRequester: FocusRequester? = null,
    settingsFocusRequester: FocusRequester? = null
) {
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING
    val isIdle = playbackStatus == PlaybackStatus.IDLE || playbackStatus == PlaybackStatus.STOPPED
    val transportButtonsEnabled = !isIdle

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 上一曲按钮 (⏮) - 待机状态下半透明禁用
        TransportIconButton(
            iconResId = R.drawable.ic_backward_end_fill,
            contentDescription = "上一曲",
            buttonSize = 48.dp,
            iconSize = 24.dp,
            enabled = transportButtonsEnabled,
            hasDarkBackground = hasDarkBackground,
            onClick = onPreviousClick
        )

        // 2. 播放 / 暂停主按钮 (▶ / ❚❚) - 待机状态下半透明禁用，非待机绑定 playPauseFocusRequester
        // 💡 视觉规格统一：基础尺寸统一为 48.dp (icon 24.dp)，仅在获焦时通过 Surface 放大 1.12x，彻底根除“播放按钮一直处于放大状态”
        TransportIconButton(
            iconResId = if (isPlaying) R.drawable.ic_pause_fill else R.drawable.ic_play_fill,
            contentDescription = if (isPlaying) "暂停" else "播放",
            buttonSize = 48.dp,
            iconSize = 24.dp,
            isCircle = false,
            enabled = transportButtonsEnabled,
            hasDarkBackground = hasDarkBackground,
            focusRequester = if (transportButtonsEnabled) playPauseFocusRequester else null,
            onClick = onPlayPauseClick
        )

        // 3. 下一曲按钮 (⏭) - 待机状态下半透明禁用
        TransportIconButton(
            iconResId = R.drawable.ic_forward_end_fill,
            contentDescription = "下一曲",
            buttonSize = 48.dp,
            iconSize = 24.dp,
            enabled = transportButtonsEnabled,
            hasDarkBackground = hasDarkBackground,
            onClick = onNextClick
        )

        // 4. 系统与硬件设置按钮 (⚙) - 始终保持可用，待机时绑定 settingsFocusRequester
        TransportIconButton(
            iconResId = R.drawable.ic_gear,
            contentDescription = "设置",
            buttonSize = 48.dp,
            iconSize = 24.dp,
            enabled = true,
            hasDarkBackground = hasDarkBackground,
            focusRequester = settingsFocusRequester,
            onClick = onSettingsClick
        )
    }
}

/**
 * 💡 Apple TV 极简通透媒体按键 (TransportIconButton)
 * 采用 Surface + Box(fillMaxSize, Alignment.Center)：
 * - 去除任何厚重描边与发光轮廓；
 * - 焦点态：1.12x 放大 + 柔和通透底色；
 * - 自适应深/浅背景：有彩色流光或深色待机时为亮白，浅色待机时为纯净深黑；
 * - 禁用态：半透明低对比度，不可获焦。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportIconButton(
    iconResId: Int,
    contentDescription: String,
    buttonSize: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
    isCircle: Boolean = false,
    hasDarkBackground: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = if (isCircle) CircleShape else RoundedCornerShape(14.dp)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))

    val focusedContainerColor = if (hasDarkBackground) Color(0x22FFFFFF) else Color(0x18000000)
    val pressedContainerColor = if (hasDarkBackground) Color(0x3DFFFFFF) else Color(0x26000000)
    val iconColor = if (!enabled) {
        if (hasDarkBackground) Color.White.copy(alpha = 0.22f) else Color(0xFF1C1C1E).copy(alpha = 0.22f)
    } else if (hasDarkBackground) {
        if (isFocused) Color.White else Color.White.copy(alpha = 0.55f)
    } else {
        if (isFocused) Color(0xFF1C1C1E) else Color(0xFF1C1C1E).copy(alpha = 0.55f)
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(buttonSize)
            .then(if (enabled && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (enabled) isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(
            scale = 1.0f,
            focusedScale = if (enabled) 1.12f else 1.0f,
            pressedScale = if (enabled) 1.05f else 1.0f
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = focusedContainerColor,
            pressedContainerColor = pressedContainerColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = noBorder,
            focusedBorder = noBorder
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = Glow.None,
            pressedGlow = Glow.None
        )
    ) {
        // 💡 100% 绝对置于几何中心，杜绝任何 Padding 偏心
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
