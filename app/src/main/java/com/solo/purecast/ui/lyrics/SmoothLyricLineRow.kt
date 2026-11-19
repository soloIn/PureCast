package com.solo.purecast.ui.lyrics

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricLine

/**
 * 💡 Apple Music TV 级歌词行渲染器（全链路弹簧同频 + 景深虚化 + 原生 1:1 纹理缩放）：
 * 1. 缩放 (0.95f -> 1.0f)、透明度与景深虚化全部采用与滚动位移同频的 Spring 物理曲线，
 *    t = 0 同步爆发、同节奏落位，彻底消除 tween 缓起造成的脱节感；
 * 2. 启用 CompositingStrategy.Offscreen 将文字光栅化锁定为原生 1:1 GPU 纹理再平滑缩放，
 *    根除 Android HWUI/Skia 缩放期间逐帧重算字形 Hinting 导致的抖动与右侧裁切；
 * 3. Android 12+ (API 31+) 为非激活行叠加随行距递增的硬件高斯景深模糊 (1.2dp -> 2.8dp)，
 *    低版本自动无损降级为纯透明度层级。
 */
@Composable
fun SmoothLyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    distanceFromActive: Int,
    currentTimeProvider: () -> Long,
    syncClock: LyricsSyncClock,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    springStiffness: Float = 380f
) {
    val words = line.words
    val hasWordSync = !words.isNullOrEmpty()

    // 💡 1. 与滚动位移同频的弹簧缩放（TV 端克制 0.95f -> 1.0f，退场略缓无回弹）
    val scaleState = animateFloatAsState(
        targetValue = if (isActive) 1.0f else 0.95f,
        animationSpec = if (isActive) {
            spring(
                dampingRatio = 0.92f,
                stiffness = springStiffness,
                visibilityThreshold = 0.0005f
            )
        } else {
            spring(
                dampingRatio = 0.95f,
                stiffness = springStiffness * 0.78f,
                visibilityThreshold = 0.0005f
            )
        },
        label = "lyricScale"
    )

    // 💡 2. 与滚动位移同频的弹簧亮度编排
    val targetAlpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive, distanceFromActive)
    val alphaState = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (isActive) {
            spring(
                dampingRatio = 0.94f,
                stiffness = springStiffness * 1.1f,
                visibilityThreshold = 0.002f
            )
        } else {
            spring(
                dampingRatio = 0.96f,
                stiffness = springStiffness * 0.78f,
                visibilityThreshold = 0.002f
            )
        },
        label = "lyricAlpha"
    )

    // 💡 3. Android 12+ 景深虚化弹簧：滑入 38% 焦点时从虚焦平滑对焦至 0dp 锐利（API < 31 固定 0f 避免老盒子空跑动画）
    val targetBlurDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LyricsRenderingMath.calculateLineTargetBlurDp(isActive, distanceFromActive)
    } else {
        0f
    }
    val blurDpState = animateFloatAsState(
        targetValue = targetBlurDp,
        animationSpec = spring(
            dampingRatio = 0.94f,
            stiffness = springStiffness,
            visibilityThreshold = 0.05f
        ),
        label = "lyricBlur"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                // 💡 强制离线 GPU 纹理缓存（硬件图层），避免 Skia 在缩放过程中逐帧重算字形 Hinting 引起抖动
                compositingStrategy = CompositingStrategy.Offscreen
                val s = scaleState.value
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blurPx = blurDpState.value.dp.toPx()
                    renderEffect = if (blurPx > 0.25f) {
                        BlurEffect(blurPx, blurPx, TileMode.Decal)
                    } else {
                        null
                    }
                }
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
                fontSize = 33.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                lineHeight = 42.sp,
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
