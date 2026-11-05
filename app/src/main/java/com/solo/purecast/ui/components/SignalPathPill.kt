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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.StreamProtocol
import java.util.Locale

/**
 * 💡 PureCast 物理音频链路与品质指示胶囊 (SignalPathPill)
 * 严格遵循用户与规划约束：
 * 1. 纯静态展示，无 D-Pad 焦点交互，不干扰大屏遥控器焦点路径。
 * 2. 文本格式统一为：协议 · 音频格式 | 设备名称。
 * 3. 彻底去除 (独占) / (非独占) 等文字后缀，完全通过「设备名称」的颜色传达硬件状态：
 *    - 绿色 (#30D158)：USB DAC 硬件独占接管成功，处于 Bit-Perfect 源码直通状态；
 *    - 红色 (#FF453A)：未独占 / 声卡让渡 / 共享系统输出 / 设备异常；
 *    - 中性灰 (#8E8E93)：待机空闲状态。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SignalPathPill(
    audioState: AudioPathState,
    modifier: Modifier = Modifier
) {
    val isExclusive = audioState.isDacConnected && audioState.isExclusive
    val isPlayingStream = audioState.protocol != StreamProtocol.NONE && audioState.sampleRate > 0

    // 设备名着色：独占为亮绿，未独占为亮红，待机为浅灰
    val deviceNameColor = when {
        !isPlayingStream -> Color(0xFF8E8E93)
        isExclusive -> Color(0xFF30D158) // Apple Green
        else -> Color(0xFFFF453A) // Apple Red
    }

    // 状态呼吸小圆点颜色
    val dotColor = when {
        !isPlayingStream -> Color(0xFF636366)
        isExclusive -> Color(0xFF30D158)
        else -> Color(0xFFFF453A)
    }

    // 格式化文本：协议 · 音频格式 | 设备名称
    val pillAnnotatedText = remember(audioState) {
        buildAnnotatedString {
            if (!isPlayingStream) {
                withStyle(SpanStyle(color = Color(0xFFD1D1D6), fontWeight = FontWeight.Normal)) {
                    append("就绪 · 等待推流")
                }
                withStyle(SpanStyle(color = Color(0xFF636366))) {
                    append("  |  ")
                }
                withStyle(SpanStyle(color = deviceNameColor, fontWeight = FontWeight.SemiBold)) {
                    val displayName = if (audioState.isDacConnected) audioState.dacName else "未连接 USB DAC"
                    append(displayName)
                }
            } else {
                // 1. 协议名称
                val protocolName = when (audioState.protocol) {
                    StreamProtocol.AIRPLAY_1 -> "AirPlay"
                    StreamProtocol.UPNP_DLNA -> "UPnP"
                    StreamProtocol.NONE -> "Direct"
                }

                // 2. 音频编码格式与指标
                val codecName = when (audioState.protocol) {
                    StreamProtocol.AIRPLAY_1 -> "ALAC"
                    StreamProtocol.UPNP_DLNA -> if (audioState.bitDepth > 16 || audioState.sampleRate > 48000) "PCM Hi-Res" else "PCM"
                    StreamProtocol.NONE -> "PCM"
                }
                val sampleRateKhz = String.format(Locale.US, "%.1f", audioState.sampleRate / 1000.0)
                val formatSpec = "${sampleRateKhz}kHz/${audioState.bitDepth}bit"

                withStyle(SpanStyle(color = Color(0xFFE5E5EA), fontWeight = FontWeight.Medium)) {
                    append("$protocolName · $codecName · $formatSpec")
                }

                withStyle(SpanStyle(color = Color(0xFF636366))) {
                    append("  |  ")
                }

                // 3. 设备名称（纯色彩语义指示独占）
                withStyle(SpanStyle(color = deviceNameColor, fontWeight = FontWeight.SemiBold)) {
                    val displayName = if (audioState.isDacConnected) audioState.dacName else "系统默认输出"
                    append(displayName)
                }
            }
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF161823).copy(alpha = 0.85f))
            .border(1.dp, Color(0xFF2C2F3E), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 微光状态圆点
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )

        // 纯色彩语义文本
        Text(
            text = pillAnnotatedText,
            fontSize = 13.sp,
            letterSpacing = 0.3.sp
        )
    }
}
