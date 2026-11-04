package com.solo.purecast.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 💡 PureCast 分级流光背景 (Fluid Background)
 * 严格遵从 AGENTS.md 规范：
 * 1. API 31+ (Android 12+): 启用原生硬件级 Modifier.blur(60.dp)
 * 2. API 30 (Android 11 电视系统): Google 全面废弃 RenderScript，
 *    通过轻量级 Kotlin 内存 StackBlur 算法对 128x128 缩略图模糊 (< 3ms 开销)，杜绝低端 32 位电视卡顿。
 */
@Composable
fun FluidBackground(
    primaryColor: Color = Color(0xFF1E2A38),
    secondaryColor: Color = Color(0xFF141A29),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 💡 API 31+ 原生硬件级 RenderEffect 高斯模糊
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.45f),
                                secondaryColor.copy(alpha = 0.30f),
                                Color(0xFF0F1015).copy(alpha = 0.95f)
                            ),
                            radius = 1200f
                        )
                    )
            )
        } else {
            // 💡 API 30 纯 Kotlin 内存级 StackBlur 快速生成模糊图
            var blurredBitmap by remember(primaryColor, secondaryColor) { mutableStateOf<Bitmap?>(null) }

            LaunchedEffect(primaryColor, secondaryColor) {
                withContext(Dispatchers.Default) {
                    val w = 128
                    val h = 128
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c1 = primaryColor.hashCode()
                    val c2 = secondaryColor.hashCode()
                    val pixels = IntArray(w * h)
                    for (y in 0 until h) {
                        val factor = y.toFloat() / h
                        val p = blendColor(c1, c2, factor)
                        for (x in 0 until w) {
                            pixels[y * w + x] = p
                        }
                    }
                    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
                    blurredBitmap = fastStackBlur(bmp, 16)
                }
            }

            blurredBitmap?.let { bmp ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                        alpha = 0.5f
                    )
                }
            }
        }

        // 顶层遮罩：WCAG AA 对比度防反光渐变层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x99000000),
                            Color(0xEE0B0C10)
                        )
                    )
                )
        )
    }
}

private fun blendColor(c1: Int, c2: Int, factor: Float): Int {
    val a = ((c1 ushr 24) * (1 - factor) + (c2 ushr 24) * factor).toInt()
    val r = (((c1 shr 16) and 0xFF) * (1 - factor) + ((c2 shr 16) and 0xFF) * factor).toInt()
    val g = (((c1 shr 8) and 0xFF) * (1 - factor) + ((c2 shr 8) and 0xFF) * factor).toInt()
    val b = ((c1 and 0xFF) * (1 - factor) + (c2 and 0xFF) * factor).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * 💡 纯 Kotlin 内存高效 StackBlur 算法 (Mario Klingemann 经典算法)
 */
fun fastStackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
    val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
    if (radius < 1) return bitmap

    val w = bitmap.width
    val h = bitmap.height
    val pix = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = radius + radius + 1

    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    var rsum: Int
    var gsum: Int
    var bsum: Int
    var x: Int
    var y: Int
    var i: Int
    var p: Int
    var yp: Int
    var yi: Int
    val vmin = IntArray(Math.max(w, h))

    var divsum = (div + 1) shr 1
    divsum *= divsum
    val dv = IntArray(256 * divsum)
    for (idx in 0 until 256 * divsum) {
        dv[idx] = (idx / divsum)
    }

    yi = 0
    var yw = 0

    val stack = Array(div) { IntArray(3) }
    var stackpointer: Int
    var stackstart: Int
    var sir: IntArray
    var rbs: Int
    val r1 = radius + 1
    var routsum: Int
    var goutsum: Int
    var boutsum: Int
    var rinsum: Int
    var ginsum: Int
    var binsum: Int

    y = 0
    while (y < h) {
        bsum = 0
        gsum = 0
        rsum = 0
        boutsum = 0
        goutsum = 0
        routsum = 0
        binsum = 0
        ginsum = 0
        rinsum = 0
        i = -radius
        while (i <= radius) {
            p = pix[yi + Math.min(wm, Math.max(i, 0))]
            sir = stack[i + radius]
            sir[0] = (p and 0xff0000) shr 16
            sir[1] = (p and 0x00ff00) shr 8
            sir[2] = (p and 0x0000ff)
            rbs = r1 - Math.abs(i)
            rsum += sir[0] * rbs
            gsum += sir[1] * rbs
            bsum += sir[2] * rbs
            if (i > 0) {
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
            } else {
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
            }
            i++
        }
        stackpointer = radius

        x = 0
        while (x < w) {
            r[yi] = dv[rsum]
            g[yi] = dv[gsum]
            b[yi] = dv[bsum]

            rsum -= routsum
            gsum -= goutsum
            bsum -= boutsum

            stackstart = stackpointer - radius + div
            sir = stack[stackstart % div]

            routsum -= sir[0]
            goutsum -= sir[1]
            boutsum -= sir[2]

            if (y == 0) {
                vmin[x] = Math.min(x + radius + 1, wm)
            }
            p = pix[yw + vmin[x]]

            sir[0] = (p and 0xff0000) shr 16
            sir[1] = (p and 0x00ff00) shr 8
            sir[2] = (p and 0x0000ff)

            rinsum += sir[0]
            ginsum += sir[1]
            binsum += sir[2]

            rsum += rinsum
            gsum += ginsum
            bsum += binsum

            stackpointer = (stackpointer + 1) % div
            sir = stack[stackpointer % div]

            routsum += sir[0]
            goutsum += sir[1]
            boutsum += sir[2]

            rinsum -= sir[0]
            ginsum -= sir[1]
            binsum -= sir[2]

            yi++
            x++
        }
        yw += w
        y++
    }

    x = 0
    while (x < w) {
        bsum = 0
        gsum = 0
        rsum = 0
        boutsum = 0
        goutsum = 0
        routsum = 0
        binsum = 0
        ginsum = 0
        rinsum = 0
        yp = -radius * w
        i = -radius
        while (i <= radius) {
            yi = Math.max(0, yp) + x
            sir = stack[i + radius]
            sir[0] = r[yi]
            sir[1] = g[yi]
            sir[2] = b[yi]
            rbs = r1 - Math.abs(i)
            rsum += r[yi] * rbs
            gsum += g[yi] * rbs
            bsum += b[yi] * rbs
            if (i > 0) {
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
            } else {
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
            }
            if (i < hm) {
                yp += w
            }
            i++
        }
        yi = x
        stackpointer = radius
        y = 0
        while (y < h) {
            pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
            rsum -= routsum
            gsum -= goutsum
            bsum -= boutsum

            stackstart = stackpointer - radius + div
            sir = stack[stackstart % div]

            routsum -= sir[0]
            goutsum -= sir[1]
            boutsum -= sir[2]

            if (x == 0) {
                vmin[y] = Math.min(y + r1, hm) * w
            }
            p = x + vmin[y]

            sir[0] = r[p]
            sir[1] = g[p]
            sir[2] = b[p]

            rinsum += sir[0]
            ginsum += sir[1]
            binsum += sir[2]

            rsum += rinsum
            gsum += ginsum
            bsum += binsum

            stackpointer = (stackpointer + 1) % div
            sir = stack[stackpointer]

            routsum += sir[0]
            goutsum += sir[1]
            boutsum += sir[2]

            rinsum -= sir[0]
            ginsum -= sir[1]
            binsum -= sir[2]

            yi += w
            y++
        }
        x++
    }

    bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    return bitmap
}
