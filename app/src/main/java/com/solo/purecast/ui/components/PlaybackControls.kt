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
 * 严格按照 Apple TV (tvOS) 与 lavande16/Apple-Music-For-Android-TV 原生规范重构：
 * 1. 废弃带有默认不对称 ContentPadding 的 Button，全面采用 Surface + Box(fillMaxSize, Alignment.Center)；
 * 2. 彻底解决图标未居中问题：无论何种分辨率与缩放，SF Symbols 矢量均 100% 绝对置于几何中心；
 * 3. 悬浮呼吸质感：
 *    - 未获焦时：完全纯净透明 (Color.Transparent)，图标为纯白 (Color.White)；
 *    - 获焦时：1.12x 浮起放大并点亮 Apple 纯正微白毛玻璃高光 (Color(0x2EFFFFFF))，辅以柔和微发光边框；
 * 4. 严谨图标资源：上一曲 [ic_backward_end_fill]、播放/暂停 [ic_play_fill/ic_pause_fill]、下一曲 [ic_forward_end_fill] 与设置 [ic_gear]。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaybackControls(
    playbackStatus: PlaybackStatus,
    isLiveStream: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    playPauseFocusRequester: FocusRequester? = null
) {
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. 上一曲按钮 (⏮)
        TransportIconButton(
            iconResId = R.drawable.ic_backward_end_fill,
            contentDescription = "上一曲",
            buttonSize = 46.dp,
            iconSize = 22.dp,
            onClick = onPreviousClick
        )

        // 2. 播放 / 暂停主按钮 (▶ / ❚❚) - 绑定默认 FocusRequester
        TransportIconButton(
            iconResId = if (isPlaying) R.drawable.ic_pause_fill else R.drawable.ic_play_fill,
            contentDescription = if (isPlaying) "暂停" else "播放",
            buttonSize = 56.dp,
            iconSize = 28.dp,
            isCircle = true,
            focusRequester = playPauseFocusRequester,
            onClick = onPlayPauseClick
        )

        // 3. 下一曲按钮 (⏭)
        TransportIconButton(
            iconResId = R.drawable.ic_forward_end_fill,
            contentDescription = "下一曲",
            buttonSize = 46.dp,
            iconSize = 22.dp,
            onClick = onNextClick
        )

        // 4. 系统与硬件设置按钮 (⚙)
        TransportIconButton(
            iconResId = R.drawable.ic_gear,
            contentDescription = "设置",
            buttonSize = 46.dp,
            iconSize = 22.dp,
            onClick = onSettingsClick
        )
    }
}

/**
 * 💡 自适应完美居中的 Apple TV 风格媒体按键 (TransportIconButton)
 * 采用 Surface + Box(fillMaxSize, Alignment.Center)，彻底消除系统默认 Button 内边距导致的图标偏位
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportIconButton(
    iconResId: Int,
    contentDescription: String,
    buttonSize: Dp,
    iconSize: Dp,
    isCircle: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = if (isCircle) CircleShape else RoundedCornerShape(14.dp)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    val focusedBorder = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f)))

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(
            scale = 1.0f,
            focusedScale = 1.12f,
            pressedScale = 1.05f
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0x33FFFFFF),
            pressedContainerColor = Color(0x55FFFFFF)
        ),
        border = ClickableSurfaceDefaults.border(
            border = noBorder,
            focusedBorder = focusedBorder
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.3f), elevation = 8.dp)
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
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
