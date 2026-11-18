package com.solo.purecast.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.solo.purecast.model.LyricUiState

/**
 * 💡 PureCast 遥控器歌词专属控制按钮 (LyricsControlButton)
 * 严格按照 lavande16/Apple-Music-For-Android-TV 与 Apple TV (tvOS) 原生质感重构：
 * 1. 纯 TV Compose 规范，无移动端混用；
 * 2. 状态映射：
 *    - isExpanded (大屏已展开): 激活常驻高亮 (纯白底或强透明度底，指示当前正处于歌词模式)；
 *    - Ready (歌词已就绪): 正常半透白色 (55%)，获焦 100% 纯白 + 1.12x 放大；
 *    - Instrumental (纯音乐): 图标正常可用，提示纯音乐；
 *    - Loading (检索中): 正常呈现；
 *    - NotFound / Idle: 禁用低透明度 (22%)；
 * 3. D-Pad 平滑横向获焦。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsControlButton(
    lyricState: LyricUiState,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hasDarkBackground: Boolean = true,
    focusRequester: FocusRequester? = null,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp
) {
    var isFocused by remember { mutableStateOf(false) }

    // 💡 当控件被禁用时立即清除获焦状态，防止失焦事件残留
    LaunchedEffect(enabled) {
        if (!enabled) {
            isFocused = false
        }
    }

    val shape = RoundedCornerShape(14.dp)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))

    val normalContainerColor = when {
        isExpanded -> if (hasDarkBackground) Color(0x33FFFFFF) else Color(0x22000000)
        else -> Color.Transparent
    }
    val focusedContainerColor = if (hasDarkBackground) Color(0x22FFFFFF) else Color(0x18000000)
    val pressedContainerColor = if (hasDarkBackground) Color(0x3DFFFFFF) else Color(0x26000000)

    val contentDesc = when {
        isExpanded -> "收起歌词"
        lyricState is LyricUiState.Instrumental -> "纯音乐，点击查看"
        lyricState is LyricUiState.Success -> "展开歌词"
        lyricState is LyricUiState.Loading -> "正在检索歌词"
        else -> "暂无歌词"
    }

    val iconColor = if (!enabled) {
        if (hasDarkBackground) Color.White.copy(alpha = 0.22f) else Color(0xFF1C1C1E).copy(alpha = 0.22f)
    } else if (hasDarkBackground) {
        when {
            isFocused -> Color.White
            isExpanded -> Color.White
            lyricState is LyricUiState.Success -> Color.White.copy(alpha = 0.70f)
            else -> Color.White.copy(alpha = 0.55f)
        }
    } else {
        when {
            isFocused -> Color(0xFF1C1C1E)
            isExpanded -> Color(0xFF1C1C1E)
            lyricState is LyricUiState.Success -> Color(0xFF1C1C1E).copy(alpha = 0.70f)
            else -> Color(0xFF1C1C1E).copy(alpha = 0.55f)
        }
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(buttonSize)
            .then(if (enabled && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = enabled && it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(
            scale = 1.0f,
            focusedScale = if (enabled) 1.12f else 1.0f,
            pressedScale = if (enabled) 1.05f else 1.0f
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = normalContainerColor,
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
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_quote_bubble),
                contentDescription = contentDesc,
                tint = iconColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
