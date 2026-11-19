package com.solo.purecast.ui.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricLine
import kotlin.math.abs

/**
 * 💡 丝滑 Apple Music TV 级歌词行渲染器：
 * 1. 消除原 Active/Inactive 树节点瞬间重建，统一为单一稳定组件；
 * 2. 通过 Spring 弹性缩放 (1.0f -> 1.08f) 与平滑透明度淡入淡出，消除生硬跳变；
 * 3. 针对前奏结束第一句入场实施优雅微距上浮 (translationY)，杜绝大字突现闪烁；
 * 4. 纯 GPU RenderNode (graphicsLayer) 硬件加速合成，避免 CPU 重新 measure/layout，低端盒子 60fps 不掉帧。
 */
@Composable
fun SmoothLyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isFirstLine: Boolean,
    distanceFromActive: Int,
    currentTimeProvider: () -> Long,
    syncClock: LyricsSyncClock,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true
) {
    val words = line.words
    val hasWordSync = !words.isNullOrEmpty()

    // 💡 1. 缩放动画 (使用 State 延迟在 graphicsLayer 读取，滚动期间 Composition 重组次数降为 0)
    val scaleState = animateFloatAsState(
        targetValue = if (isActive) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = 200f
        ),
        label = "lyricScale"
    )

    // 💡 2. 透明度动画 (使用 State 延迟在 graphicsLayer 读取，消除全列表 600次/秒重组卡顿)
    val targetAlpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive, distanceFromActive)
    val alphaState = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "lyricAlpha"
    )

    // 💡 3. 微距位移动画 (使用 State 延迟在 graphicsLayer 读取，零重组零重新布局)
    val targetTranslationY = if (isActive) -3f else 0f
    val translationYState = animateFloatAsState(
        targetValue = targetTranslationY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 180f
        ),
        label = "lyricOffsetY"
    )

    Column(
        modifier = modifier
            .fillMaxWidth(0.96f) // 💡 预留 4% 物理余量，确保 1.08x 放大时文字右侧不超出视口
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
                translationY = translationYState.value.dp.toPx()
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val translation = line.translationText

        // 💡 消除切句首帧 Text ⇄ WordSyncKaraokeText 销毁重建：
        // 只要该行具备逐字时间戳 (hasWordSync)，始终保持单一稳定 WordSyncKaraokeText 节点，仅原位切换 isActive！
        if (hasWordSync) {
            WordSyncKaraokeText(
                line = line,
                syncClock = syncClock,
                currentTimeProvider = currentTimeProvider,
                primaryColor = primaryColor,
                isPlaying = isPlaying,
                isActive = isActive,
                inactiveAlphaState = alphaState
            )
        } else {
            Text(
                text = line.mainText,
                fontSize = 31.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                lineHeight = 40.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = alphaState.value }
            )
        }

        // 简体中文翻译行 (统一基础字号 20sp)
        if (!translation.isNullOrEmpty()) {
            Text(
                text = translation,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = primaryColor.copy(alpha = LyricsRenderingMath.TRANSLATION_ALPHA),
                lineHeight = 28.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = alphaState.value }
            )
        }
    }
}
