package com.solo.purecast.ui.lyrics

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricLine

/**
 * 💡 Apple Music TV 级歌词行渲染器（1:1 刚性 Offscreen 纹理 + 原生 Hinting 锐度 + 顺滑连续滚动）：
 * 1. 全程保持 1:1 原生字号与原生 Hinting（不做非整数 scale 缩放、不关 Hinting），一次性光栅化为刚性 GPU 纹理缓存；
 * 2. 滚动过程以连续浮点 translationY 平移刚性纹理，彻底根除 Skia 垂直亚像素重算导致的字形起伏变形与阶梯顿挫；
 * 3. 焦点层级由鲜明亮度对比 (1.0f vs 0.36f -> 0.16f) 与逐字扫光共同呈现。
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
    springStiffness: Float = 300f,
    enableDepthBlur: Boolean = false,
    translationYProvider: () -> Float = { 0f },
    edgeAlphaProvider: (Float) -> Float = { 1f }
) {
    val words = line.words
    val hasWordSync = !words.isNullOrEmpty()

    // 💡 与滚动位移同频的弹簧亮度编排（临界阻尼干脆收敛）
    val targetAlpha = LyricsRenderingMath.calculateLineTargetAlpha(isActive, distanceFromActive)
    val alphaState = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (isActive) {
            spring(
                dampingRatio = 1.0f,
                stiffness = springStiffness * 1.15f,
                visibilityThreshold = 0.002f
            )
        } else {
            spring(
                dampingRatio = 1.0f,
                stiffness = springStiffness * 0.85f,
                visibilityThreshold = 0.002f
            )
        },
        label = "lyricAlpha"
    )

    // 💡 景深虚化：单核 Mali-G31 等 TV GPU 默认关闭（彻底消除 12 行 BlurEffect 造成的 38MB Scratch FBO 挤占与 100% GPU 满载）
    val useBlur = enableDepthBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurDpState = if (useBlur) {
        animateFloatAsState(
            targetValue = LyricsRenderingMath.calculateLineTargetBlurDp(isActive, distanceFromActive),
            animationSpec = spring(
                dampingRatio = 1.0f,
                stiffness = springStiffness,
                visibilityThreshold = 0.05f
            ),
            label = "lyricBlur"
        )
    } else {
        null
    }

    val blurEffectCache = if (useBlur) {
        remember {
            object {
                var lastQuantizedPx: Int = 0
                var effect: BlurEffect? = null
            }
        }
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val y = translationYProvider()
                val edgeAlpha = edgeAlphaProvider(y)
                val currentAlpha = alphaState.value
                // 💡 仅在逐字行刚从激活态退场且透明度仍在 1.0f -> 0.45f 区间时短时保持 Offscreen，
                // 既防止行尾未唱字符在失活首帧闪白，又避免非激活行在 0.36f -> 0.16f 距离衰减时误开 Scratch FBO！
                val isExitingWordSync = hasWordSync && !isActive && currentAlpha > 0.45f
                val useOffscreen = (hasWordSync && (isActive || isExitingWordSync)) || useBlur
                val rowAlpha = if (hasWordSync && isActive) 1.0f else currentAlpha
                compositingStrategy = if (useOffscreen) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.ModulateAlpha
                }
                translationY = y
                alpha = edgeAlpha * rowAlpha
                if (useBlur && blurDpState != null && blurEffectCache != null) {
                    val blurPx = blurDpState.value.dp.toPx()
                    val quantizedPx = if (blurPx > 0.5f) kotlin.math.round(blurPx).toInt() else 0
                    if (quantizedPx != blurEffectCache.lastQuantizedPx) {
                        blurEffectCache.lastQuantizedPx = quantizedPx
                        blurEffectCache.effect = if (quantizedPx > 0) {
                            val r = quantizedPx.toFloat()
                            BlurEffect(r, r, TileMode.Decal)
                        } else {
                            null
                        }
                    }
                    renderEffect = blurEffectCache.effect
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val translation = line.translationText

        // 💡 消除切句首帧 Text ⇄ WordSyncKaraokeText 销毁重建：
        // 只要该行具备逐字时间戳 (hasWordSync)，始终保持单一稳定 WordSyncKaraokeText 节点，仅原位切换 isActive！
        if (hasWordSync) {
            val rowAlphaProvider = remember(alphaState) { { alphaState.value } }
            WordSyncKaraokeText(
                line = line,
                syncClock = syncClock,
                currentTimeProvider = currentTimeProvider,
                primaryColor = primaryColor,
                isPlaying = isPlaying,
                isActive = isActive,
                exitingRowAlphaProvider = rowAlphaProvider
            )
        } else {
            Text(
                text = line.mainText,
                fontSize = 33.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                lineHeight = 42.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 简体中文翻译行 (统一基础字号 20sp)
        if (!translation.isNullOrEmpty()) {
            val translationModifier = if (hasWordSync) {
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = if (isActive) alphaState.value else 1.0f
                    }
            } else {
                Modifier.fillMaxWidth()
            }
            Text(
                text = translation,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = primaryColor.copy(alpha = LyricsRenderingMath.TRANSLATION_ALPHA),
                lineHeight = 28.sp,
                modifier = translationModifier
            )
        }
    }
}
