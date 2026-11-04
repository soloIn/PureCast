package com.solo.purecast.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.service.PlaybackService

/**
 * 💡 PureCast 大屏侧边栏设置与声卡自检面板 (Settings Drawer)
 * 允许用户通过遥控器查看底层 libusb 状态、调整 10 秒空闲让渡策略、手动执行声卡归还自检。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsDrawer(
    audioState: AudioPathState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(420.dp)
                .background(Color(0xFF181A22))
                .border(BorderStroke(1.dp, Color(0xFF2E3244)))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "系统与驱动设置",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )

            // 信息卡片: 硬件与内核驱动
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF222533))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "【底驱动与声卡链路】", fontSize = 13.sp, color = Color(0xFF8E8E93))
                Text(text = "设备名称: ${audioState.dacName}", fontSize = 14.sp, color = Color.White)
                Text(
                    text = "独占状态: " + if (audioState.isExclusive) "usbfs 独占 (绕过 SRC)" else "内核待机让渡中",
                    fontSize = 14.sp,
                    color = if (audioState.isExclusive) Color(0xFF30D158) else Color(0xFF0A84FF)
                )
                Text(
                    text = "libusb 核心: " + NativeAudioEngine.getLibusbVersion(),
                    fontSize = 13.sp,
                    color = Color(0xFFA1A1A6)
                )
            }

            // 让渡倒计时策略选择
            Text(text = "【空闲声卡让渡时间】", fontSize = 13.sp, color = Color(0xFF8E8E93))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(5, 10, 30).forEach { seconds ->
                    Button(
                        onClick = {
                            PlaybackService.instance?.setIdleTimeoutSeconds(seconds)
                        },
                        scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.1f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF2E3244),
                            focusedContainerColor = Color.White,
                            contentColor = Color.White,
                            focusedContentColor = Color.Black
                        )
                    ) {
                        Text(text = "${seconds}秒", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 手动触发让渡操作
            Button(
                onClick = {
                    PlaybackService.instance?.stopPlayback()
                },
                modifier = Modifier.fillMaxWidth(),
                scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.06f),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF3A3E52),
                    focusedContainerColor = Color(0xFFFF453A),
                    contentColor = Color.White,
                    focusedContentColor = Color.White
                )
            ) {
                Text(text = "🔄 手动归还声卡至系统内核", fontSize = 14.sp)
            }

            // 关闭抽屉
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.06f),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF0A84FF),
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) {
                Text(text = "返回大屏播放", fontSize = 14.sp)
            }
        }
    }
}
