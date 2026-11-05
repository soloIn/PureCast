package com.solo.purecast.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.ui.theme.PureCastPrimary
import kotlin.math.cos
import kotlin.math.sin

/**
 * 💡 PureCast 遥控器专属 10-Foot 纯几何图标控制条 (PlaybackControls)
 * 严格遵从 Apple TV 工业设计美学与 AGENTS.md 规范：
 * 1. 彻底淘汰中文字符按钮，改用纯几何图标：上一曲 [⏮]、播放/暂停 [▶/❚❚]、下一曲 [⏭] 与设置按钮 [⚙]。
 * 2. 纯 androidx.tv.material3.Button 架构，D-Pad 聚焦时触发 1.12x 浮起放大与白发光。
 * 3. 播放/暂停键作为核心主键（56dp 圆形），辅键与设置键规格一致（48dp 圆角 14dp）。
 * 4. 播放/暂停键绑定默认 FocusRequester，保障按键分发焦点自愈。
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
            modifier = Modifier.size(48.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x33262838),
                focusedContainerColor = Color.White,
                contentColor = Color(0xFFE5E5EA),
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                drawPreviousIcon()
            }
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
                containerColor = PureCastPrimary,
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.5.dp, Color.White))
            )
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                if (isPlaying) {
                    drawPauseIcon()
                } else {
                    drawPlayIcon()
                }
            }
        }

        // 3. 下一曲按钮 (⏭)
        Button(
            onClick = onNextClick,
            modifier = Modifier.size(48.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x33262838),
                focusedContainerColor = Color.White,
                contentColor = Color(0xFFE5E5EA),
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                drawNextIcon()
            }
        }

        // 4. 风格统一的系统与硬件设置按钮 (⚙)
        Button(
            onClick = onSettingsClick,
            modifier = Modifier.size(48.dp),
            scale = ButtonDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.12f
            ),
            shape = ButtonDefaults.shape(
                shape = RoundedCornerShape(14.dp)
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x33262838),
                focusedContainerColor = Color.White,
                contentColor = Color(0xFFE5E5EA),
                focusedContentColor = Color.Black
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
            )
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                drawGearIcon()
            }
        }
    }
}

/** 绘制上一曲矢量图标 */
private fun DrawScope.drawPreviousIcon() {
    val barWidth = size.width * 0.16f
    // 左侧竖杠
    drawRect(
        color = Color.Unspecified,
        topLeft = Offset(0f, 0f),
        size = Size(barWidth, size.height)
    )
    // 指向左侧的三角
    val path = Path().apply {
        moveTo(size.width, 0f)
        lineTo(barWidth + 2f, size.height / 2f)
        lineTo(size.width, size.height)
        close()
    }
    drawPath(path, color = Color.Unspecified)
}

/** 绘制下一曲矢量图标 */
private fun DrawScope.drawNextIcon() {
    val barWidth = size.width * 0.16f
    // 指向右侧的三角
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width - barWidth - 2f, size.height / 2f)
        lineTo(0f, size.height)
        close()
    }
    drawPath(path, color = Color.Unspecified)
    // 右侧竖杠
    drawRect(
        color = Color.Unspecified,
        topLeft = Offset(size.width - barWidth, 0f),
        size = Size(barWidth, size.height)
    )
}

/** 绘制播放实心三角 */
private fun DrawScope.drawPlayIcon() {
    val path = Path().apply {
        moveTo(size.width * 0.12f, 0f)
        lineTo(size.width * 0.95f, size.height / 2f)
        lineTo(size.width * 0.12f, size.height)
        close()
    }
    drawPath(path, color = Color.Unspecified)
}

/** 绘制暂停双竖线 */
private fun DrawScope.drawPauseIcon() {
    val barWidth = size.width * 0.30f
    val gap = size.width * 0.20f
    drawRect(
        color = Color.Unspecified,
        topLeft = Offset(size.width * 0.1f, 0f),
        size = Size(barWidth, size.height)
    )
    drawRect(
        color = Color.Unspecified,
        topLeft = Offset(size.width * 0.1f + barWidth + gap, 0f),
        size = Size(barWidth, size.height)
    )
}

/** 绘制标准齿轮设置图标 */
private fun DrawScope.drawGearIcon() {
    val center = Offset(size.width / 2f, size.height / 2f)
    val outerRadius = size.width * 0.44f
    val innerRadius = size.width * 0.32f
    val holeRadius = size.width * 0.16f
    val teeth = 8

    val path = Path()
    for (i in 0 until teeth * 2) {
        val angle = i * Math.PI / teeth
        val r = if (i % 2 == 0) outerRadius else innerRadius
        val x = (center.x + r * cos(angle)).toFloat()
        val y = (center.y + r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = Color.Unspecified, style = Fill)
    // 齿轮中心圆孔
    drawCircle(
        color = Color.Transparent,
        radius = holeRadius,
        center = center
    )
    // 外圈描边确保清晰度
    drawCircle(
        color = Color.Unspecified,
        radius = holeRadius,
        center = center,
        style = Stroke(width = 2.5f)
    )
}
