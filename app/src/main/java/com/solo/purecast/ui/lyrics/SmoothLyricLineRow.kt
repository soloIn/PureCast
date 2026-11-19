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

    // 💡 1. 缩放动画 (Spring 曲线，优雅弹性，平滑缩放 1.0f -> 1.08f)
    val animatedScale by animateFloatAsState(
        targetValue = if (isActive) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 220f
        ),
        label = "lyricScale"
    )

    // 💡 2. 透明度动画 (针对激活行 1.0f 与非激活行平滑过渡，消除生硬突变)
    val targetAlpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive, distanceFromActive)
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "lyricAlpha"
    )

    // 💡 3. 微距位移动画 (第一句入场或行激活时平滑上浮 3dp，呈现沉浸层次感)
    val targetTranslationY = if (isActive) -3f else 0f
    val animatedTranslationY by animateFloatAsState(
        targetValue = targetTranslationY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 180f
        ),
        label = "lyricOffsetY"
    )

    Column(
        modifier = modifier
            .fillMaxWidth(0.96f) // 💡 预留 4% 物理余量 (0.96 * 1.08 ≈ 1.037，外层已有 padding)，确保 1.08x 放大时文字右侧不超出视口，且大幅提升单行容字量避免英文/长中文提前折行
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                // 💡 优化 2：直接在 graphicsLayer 内部转换 dp.toPx()，彻底跳过 Composition 与 Layout 阶段
                translationY = animatedTranslationY.dp.toPx()
                // 💡 锚定左侧居中，确保大屏文字放大时左边界平齐不抖动
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val translation = line.translationText

        // 💡 逐字歌词或普通整行歌词渲染
        if (isActive && hasWordSync) {
            // 💡 Finding 4: 逐字主歌词 WordSyncKaraokeText 保持纯净 1.0f（由其内部独立负责 0.45f -> 1.0f 纯净扫光），不受 animatedAlpha 乘算变暗
            WordSyncKaraokeText(
                line = line,
                syncClock = syncClock,
                currentTimeProvider = currentTimeProvider,
                primaryColor = primaryColor,
                isPlaying = isPlaying
            )
        } else {
            Text(
                text = line.mainText,
                fontSize = 31.sp,
                // 💡 Finding 10: 主歌词行统一固定为 FontWeight.Bold，完全由 scale (1.0f -> 1.08f) 和 alpha 区分激活层级，消除字重跳变引发的重新排版与重绘
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                lineHeight = 40.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = animatedAlpha }
            )
        }

        // 简体中文翻译行 (统一基础字号 20sp)
        if (!translation.isNullOrEmpty()) {
            Text(
                text = translation,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                // 💡 Finding 4: 中文翻译行 Text 独立享受 animatedAlpha (0.42f -> 1.0f) 的 350ms 丝滑淡入淡出动画，不再受外层逐字 1.0f 阻断
                color = primaryColor.copy(alpha = LyricsRenderingMath.TRANSLATION_ALPHA),
                lineHeight = 28.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = animatedAlpha }
            )
        }
    }
}
