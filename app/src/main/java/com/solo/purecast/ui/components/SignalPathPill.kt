package com.solo.purecast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.ui.theme.PureCastSecondary

/**
 * 💡 PureCast 物理音频链路指示胶囊 (Signal Path Pill)
 * 清晰直观呈现：
 * 1. 硬件连接端点 (Sony ZX300A USB DAC)
 * 2. 真实时钟采样率与位深 (例如 44.1 kHz / 16-bit)
 * 3. 绕过 Android 48kHz SRC 的 Bit-Perfect 独占状态指示
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SignalPathPill(
    audioState: AudioPathState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1E2029).copy(alpha = 0.85f))
            .border(1.dp, Color(0xFF2C2F3E), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 状态指示呼吸灯
        val indicatorColor = if (audioState.isDacConnected && audioState.isExclusive) {
            PureCastSecondary // 亮绿色
        } else if (audioState.isDacConnected) {
            Color(0xFF0A84FF) // 待机蓝色
        } else {
            Color(0xFFFF453A) // 未连接红色
        }

        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(indicatorColor)
        )

        // 设备名称
        Text(
            text = audioState.dacName,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )

        // 分隔线
        Box(
            modifier = Modifier
                .size(width = 1.dp, height = 12.dp)
                .background(Color(0xFF3A3D4E))
        )

        // 采样率与规格
        val sampleRateKhz = String.format("%.1f", audioState.sampleRate / 1000.0)
        Text(
            text = "$sampleRateKhz kHz / ${audioState.bitDepth}-bit",
            fontSize = 12.sp,
            color = Color(0xFFD1D1D6)
        )

        // 协议标签
        val protocolLabel = when (audioState.protocol) {
            com.solo.purecast.model.StreamProtocol.AIRPLAY_1 -> "AirPlay 1 · ALAC"
            com.solo.purecast.model.StreamProtocol.UPNP_DLNA -> "UPnP / DLNA"
            com.solo.purecast.model.StreamProtocol.NONE -> null
        }
        if (protocolLabel != null) {
            Box(
                modifier = Modifier
                    .size(width = 1.dp, height = 12.dp)
                    .background(Color(0xFF3A3D4E))
            )
            Text(
                text = protocolLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFA1A1A6)
            )
        }

        // Bit-Perfect 独立高亮标签
        if (audioState.isExclusive) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(PureCastSecondary.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "BIT-PERFECT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureCastSecondary
                )
            }
        }
    }
}
