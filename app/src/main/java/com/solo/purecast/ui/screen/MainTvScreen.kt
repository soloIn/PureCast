package com.solo.purecast.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.components.FluidBackground
import com.solo.purecast.ui.components.PlaybackControls
import com.solo.purecast.ui.components.SignalPathPill
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * 💡 PureCast 大屏主界面 (MainTvScreen)
 * 遵循 Apple TV 极简工业美学设计规范：
 * 1. 黄金分割视效：左侧 42% 居中展示浮动圆角封面 (带弹簧动画与柔光投影)；右侧 58% 垂直居中展示信息与控制项。
 * 2. 遥控器防烧屏像素微移 (Pixel Shift): 长时间无操作时在 2~4 像素内进行正弦微动。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainTvScreen(
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playbackStatus by PlaybackService.playbackStatus.collectAsState()
    val metadata by PlaybackService.currentMetadata.collectAsState()
    val audioPathState by PlaybackService.audioPathState.collectAsState()

    var showDrawer by remember { mutableStateOf(false) }

    // 💡 3 分钟防烧屏微像素位移引擎
    var pixelShiftX by remember { mutableFloatStateOf(0f) }
    var pixelShiftY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var tick = 0.0
        while (true) {
            delay(10_000) // 每 10 秒平滑更新微位移
            tick += 0.2
            pixelShiftX = (sin(tick) * 3).toFloat()
            pixelShiftY = (sin(tick * 0.7) * 2).toFloat()
        }
    }

    // 封面放大弹簧动画
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING
    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.94f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "coverScale"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 流光动态背景
        FluidBackground(
            primaryColor = if (audioPathState.isExclusive) Color(0xFF163224) else Color(0xFF1E2838),
            secondaryColor = Color(0xFF0F121C)
        )

        // 2. 主体 42% / 58% 黄金比例布局
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(pixelShiftX.toInt(), pixelShiftY.toInt()) }
                .padding(horizontal = 72.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 42% 封面区
            Box(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .scale(coverScale)
                        .shadow(32.dp, RoundedCornerShape(24.dp), spotColor = Color(0x66000000))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1E202B))
                        .border(1.dp, Color(0xFF32364A), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "♪" else "♫",
                            fontSize = 64.sp,
                            color = Color(0xFF0A84FF)
                        )
                        Text(
                            text = if (metadata.album.isNotEmpty()) metadata.album else "PureCast Lossless",
                            fontSize = 14.sp,
                            color = Color(0xFFA1A1A6),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(48.dp))

            // 右侧 58% 控制与元数据区
            Column(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                // 链路状态指示徽章
                SignalPathPill(audioState = audioPathState)

                Spacer(modifier = Modifier.height(24.dp))

                // 歌曲标题
                Text(
                    text = metadata.title,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 46.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 艺术家
                Text(
                    text = metadata.artist,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFB0B0B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 交互控制按钮
                PlaybackControls(
                    playbackStatus = playbackStatus,
                    isLiveStream = metadata.isLiveStream,
                    onPlayPauseClick = onTogglePlayPause,
                    onSettingsClick = { showDrawer = true }
                )
            }
        }

        // 3. 侧边栏设置抽屉
        if (showDrawer) {
            SettingsDrawer(
                audioState = audioPathState,
                onClose = { showDrawer = false }
            )
        }
    }
}
