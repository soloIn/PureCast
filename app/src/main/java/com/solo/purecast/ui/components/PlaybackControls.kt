package com.solo.purecast.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import com.solo.purecast.R
import com.solo.purecast.model.PlaybackStatus

/**
 * 💡 PureCast 遥控器专属 10-Foot 纯矢量图标控制条 (PlaybackControls)
 * 严格遵从 Apple TV HIG 工业设计美学与 AGENTS.md 规范：
 * 1. 采用官方 SF Symbols 矢量图标：上一曲 [ic_backward_fill]、播放/暂停 [ic_play_fill/ic_pause_fill]、下一曲 [ic_forward_fill] 与设置 [ic_gear]。
 * 2. 纯 androidx.tv.material3.Button 与 Icon 组合，彻底解决自绘 Canvas 缺失 contentColor 导致的隐形透明 bug。
 * 3. 获得焦点时触发 1.12x 浮起放大与白发光，前景色自动翻转为纯黑，未聚焦时为半透白底 + 高清纯白图标。
 * 4. 播放/暂停键为主键（54dp 圆形），辅键与设置键规格一致（46dp，圆角 12dp）。
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
        modifier = modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 上一曲按钮 (⏮)
        Button(
            onClick = onPreviousClick,
            modifier = Modifier.size(46.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(12.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x33282B38),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_backward_fill),
                contentDescription = "上一曲",
                tint = LocalContentColor.current,
                modifier = Modifier.size(20.dp)
            )
        }

        // 2. 播放 / 暂停主按钮 (▶ / ❚❚) - 绑定默认 FocusRequester
        Button(
            onClick = onPlayPauseClick,
            modifier = Modifier
                .size(54.dp)
                .then(
                    if (playPauseFocusRequester != null) Modifier.focusRequester(playPauseFocusRequester) else Modifier
                ),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.14f
            ),
            shape = ButtonDefaults.shape(
                shape = CircleShape
            ),
            colors = ButtonDefaults.colors(
                containerColor = if (isPlaying) Color(0x4D282B38) else Color(0x80282B38),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.5.dp, Color.White))
            )
        ) {
            Icon(
                painter = painterResource(
                    id = if (isPlaying) R.drawable.ic_pause_fill else R.drawable.ic_play_fill
                ),
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = LocalContentColor.current,
                modifier = Modifier.size(24.dp)
            )
        }

        // 3. 下一曲按钮 (⏭)
        Button(
            onClick = onNextClick,
            modifier = Modifier.size(46.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(12.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x33282B38),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_forward_fill),
                contentDescription = "下一曲",
                tint = LocalContentColor.current,
                modifier = Modifier.size(20.dp)
            )
        }

        // 4. 风格统一的系统与硬件设置按钮 (⚙)
        Button(
            onClick = onSettingsClick,
            modifier = Modifier.size(46.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(12.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x33282B38),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_gear),
                contentDescription = "设置与硬件",
                tint = LocalContentColor.current,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
