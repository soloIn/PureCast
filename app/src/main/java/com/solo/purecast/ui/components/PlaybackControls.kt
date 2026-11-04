package com.solo.purecast.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.ui.theme.PureCastPrimary

/**
 * 💡 PureCast 遥控器专属 10-Foot 交互控制条
 * 严格使用 androidx.tv.material3 组件，具备 D-Pad 遥控器 1.12x 浮起放大与外发光轮廓。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaybackControls(
    playbackStatus: PlaybackStatus,
    isLiveStream: Boolean,
    onPlayPauseClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val isPlaying = playbackStatus == PlaybackStatus.PLAYING

        // 播放 / 暂停按钮
        Button(
            onClick = onPlayPauseClick,
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(16.dp)
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
                text = if (isPlaying) "❚❚  暂停" else "▶  播放",
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // 设置与自检侧边栏按钮
        Button(
            onClick = onSettingsClick,
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(16.dp)
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
                text = "⚙  设备与让渡设置",
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        if (isLiveStream) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "● 实时音频流 (Live Stream)",
                fontSize = 13.sp,
                color = Color(0xFF8E8E93)
            )
        }
    }
}
