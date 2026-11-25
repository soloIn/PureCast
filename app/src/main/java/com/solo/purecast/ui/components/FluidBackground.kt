package com.solo.purecast.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.solo.purecast.ui.background.FluidQuality
import com.solo.purecast.ui.background.MacBackground

/**
 * 💡 PureCast 动态流光背景外观封装 (FluidBackground)
 * 接入基于 GLES2 的 MacBackground 着色器引擎，为大屏提供极致自然的流体流动光影
 */
@Composable
fun FluidBackground(
    coverData: Any? = null,
    predecodedBitmap: Bitmap? = null,
    isPlaying: Boolean = true,
    isIdle: Boolean = false,
    quality: FluidQuality = FluidQuality.STANDARD,
    primaryColor: Color = Color(0xFF1E2A38),
    secondaryColor: Color = Color(0xFF141A29),
    coverHash: Int = 0,
    isForeground: Boolean = true,
    modifier: Modifier = Modifier
) {
    MacBackground(
        coverData = coverData,
        predecodedBitmap = predecodedBitmap,
        quality = quality,
        playing = isPlaying,
        isIdle = isIdle,
        coverHash = coverHash,
        isForeground = isForeground,
        modifier = modifier
    )
}
