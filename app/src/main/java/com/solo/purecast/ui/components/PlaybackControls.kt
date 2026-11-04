package com.solo.purecast.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.ui.theme.PureCastPrimary

/**
 * 💡 PureCast 遥控器专属 10-Foot 交互控制条
 * 严格遵从 AGENTS.md 约束：
 * 1. 纯 androidx.tv.material3 组件体系，遥控器 D-Pad 获得焦点时触发 Scale 1.12x 浮起放大与外发光轮廓。
 * 2. 补齐「上一曲 (⏮)」、「播放/暂停 (▶/❚❚)」、「下一曲 (⏭)」、「设置 (⚙)」四组核心按键。
 * 3. 播放/暂停键绑定默认 FocusRequester，保障遥控器 OK 键直接响应与焦点自愈。
 * 4. 紧凑弹性布局，防止小尺寸或特殊分辨率下设置按钮被挤压折行。
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
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 上一曲按钮 (⏮)
        Button(
            onClick = onPreviousClick,
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.10f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF262833),
                focusedContainerColor = Color.White,
                contentColor = Color(0xFFE5E5EA),
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Text(
                text = "⏮ 上一曲",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // 2. 播放 / 暂停按钮 (▶ / ❚❚) - 绑定默认 FocusRequester
        Button(
            onClick = onPlayPauseClick,
            modifier = Modifier.then(
                if (playPauseFocusRequester != null) Modifier.focusRequester(playPauseFocusRequester) else Modifier
            ),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.10f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = PureCastPrimary,
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Text(
                text = if (isPlaying) "❚❚ 暂停" else "▶ 播放",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // 3. 下一曲按钮 (⏭)
        Button(
            onClick = onNextClick,
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.10f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF262833),
                focusedContainerColor = Color.White,
                contentColor = Color(0xFFE5E5EA),
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Text(
                text = "⏭ 下一曲",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // 4. 设置与硬件监控侧边栏按钮 (⚙)
        Button(
            onClick = onSettingsClick,
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.10f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF262833),
                focusedContainerColor = Color.White,
                contentColor = Color(0xFFE5E5EA),
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Text(
                text = "⚙ 设置",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
