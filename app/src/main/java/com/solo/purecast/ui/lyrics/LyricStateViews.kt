package com.solo.purecast.ui.lyrics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.solo.purecast.R

/**
 * 💡 纯音乐艺术呈现卡片 (Instrumental)
 */
@Composable
fun InstrumentalStateView(
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_waveform),
                contentDescription = "Instrumental",
                tint = if (hasDarkBackground) Color.White.copy(alpha = 0.7f) else Color(0xFF1C1C1E).copy(alpha = 0.7f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "纯音乐，请欣赏",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)
            )
            Text(
                text = "沉浸聆听纯净旋律",
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = if (hasDarkBackground) Color.White.copy(alpha = 0.55f) else Color(0xFF1C1C1E).copy(alpha = 0.55f)
            )
        }
    }
}

/**
 * 💡 歌词加载中骨架呼吸占位 (Loading)
 */
@Composable
fun LoadingShimmerView(
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val alphaAnim by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    val baseColor = if (hasDarkBackground) Color.White else Color(0xFF1C1C1E)
    val barColor = baseColor.copy(alpha = alphaAnim)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.8f),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth(0.7f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
            Box(modifier = Modifier.fillMaxWidth(0.9f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
            Box(modifier = Modifier.fillMaxWidth(0.5f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
            Box(modifier = Modifier.fillMaxWidth(0.75f).height(32.dp).clip(RoundedCornerShape(8.dp)).background(barColor))
        }
    }
}

/**
 * 💡 未找到歌词状态 (NotFound)
 */
@Composable
fun NotFoundStateView(
    hasDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_quote_bubble),
                contentDescription = "No Lyrics",
                tint = if (hasDarkBackground) Color.White.copy(alpha = 0.35f) else Color(0xFF1C1C1E).copy(alpha = 0.35f),
                modifier = Modifier.size(52.dp)
            )
            Text(
                text = "暂无歌词",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasDarkBackground) Color.White.copy(alpha = 0.7f) else Color(0xFF1C1C1E).copy(alpha = 0.7f)
            )
            Text(
                text = "未匹配到同步歌词数据",
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                color = if (hasDarkBackground) Color.White.copy(alpha = 0.45f) else Color(0xFF1C1C1E).copy(alpha = 0.45f)
            )
        }
    }
}
