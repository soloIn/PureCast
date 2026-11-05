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
 * 1. 采用官方 SF Symbols 矢量图标：上一曲 [ic_backward_end_fill]、播放/暂停 [ic_play_fill/ic_pause_fill]、下一曲 [ic_forward_end_fill] 与设置 [ic_gear]。
 * 2. 彻底解决色块脏暗与比例失调：
 *    - 未获焦状态：通透毛玻璃微白底 (Color(0x22FFFFFF))，纯白高亮图标，悬浮于背景之上；
 *    - 获得焦点时：1.12x 浮起放大并点亮纯白高光 (Color.White)，前景色翻转为高级深灰 (Color(0xFF1C1C1E))，带有柔和白色光边；
 * 3. 严格补偿 SF Symbols 的 30% 光学留白：主播放键外框 56dp 搭配 30dp 图标；辅键 46dp 搭配 24dp 图标，比例饱满协调。
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
        // 1. 上一曲按钮 (⏮ 标准 backward.end.fill)
        Button(
            onClick = onPreviousClick,
            modifier = Modifier.size(46.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x22FFFFFF),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color(0xFF1C1C1E)
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_backward_end_fill),
                contentDescription = "上一曲",
                tint = LocalContentColor.current,
                modifier = Modifier.size(24.dp)
            )
        }

        // 2. 播放 / 暂停主按钮 (▶ / ❚❚) - 绑定默认 FocusRequester
        Button(
            onClick = onPlayPauseClick,
            modifier = Modifier
                .size(56.dp)
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
                containerColor = if (isPlaying) Color(0x33FFFFFF) else Color(0x4DFFFFFF),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color(0xFF1C1C1E)
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
                modifier = Modifier.size(30.dp)
            )
        }

        // 3. 下一曲按钮 (⏭ 标准 forward.end.fill)
        Button(
            onClick = onNextClick,
            modifier = Modifier.size(46.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x22FFFFFF),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color(0xFF1C1C1E)
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_forward_end_fill),
                contentDescription = "下一曲",
                tint = LocalContentColor.current,
                modifier = Modifier.size(24.dp)
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
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x22FFFFFF),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color(0xFF1C1C1E)
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_gear),
                contentDescription = "设置",
                tint = LocalContentColor.current,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
