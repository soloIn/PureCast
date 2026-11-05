package com.solo.purecast.ui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class ArtworkFrame(val source: Bitmap, val fallback: Bitmap)

/**
 * 💡 PureCast GLES2 动态流光交融着色器背景 (MacBackground)
 * 深度移植 Apple Music TV 工业级流光渲染核心：
 * 1. 异步从封面提取 256x256 ARGB_8888 软件位图；
 * 2. 由 GLES2 TextureView 驱动 Domain Warping 噪声扭曲与多层 Kawase 模糊，呈现自然流动光影；
 * 3. 前后封面切歌时执行 1.5 秒平滑交叉淡化；
 * 4. 出现异常或不支持 GPU 时毫秒级降级至 CPU Kawase 模糊静态图或暗夜灰底。
 */
@Composable
fun MacBackground(
    coverData: Any?,
    quality: FluidQuality = FluidQuality.STANDARD,
    playing: Boolean = true,
    reduceMotion: Boolean = false,
    lowPower: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (quality == FluidQuality.OFF || coverData == null) {
        Box(modifier.fillMaxSize().background(Color(0xFF0A0C13)))
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val artwork by produceState<ArtworkFrame?>(initialValue = null, coverData) {
        try {
            val result = withContext(Dispatchers.IO) {
                val req = ImageRequest.Builder(context)
                    .data(coverData)
                    .size(256, 256)
                    .allowHardware(false) // 💡 必须为软件位图以供 GLES2 上传纹理
                    .bitmapConfig(Bitmap.Config.ARGB_8888)
                    .build()
                context.imageLoader.execute(req)
            }

            currentCoroutineContext().ensureActive()
            val input = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            value = if (input == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    val copy = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    Canvas(copy).drawBitmap(input, null, Rect(0, 0, 256, 256), Paint(Paint.FILTER_BITMAP_FLAG))
                    val frame = ArtworkFrame(copy, staticColourMap(copy))
                    currentCoroutineContext().ensureActive()
                    frame
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.ui.w("MacBackground", "流光封面纹理生成失败，回退暗黑背景", e)
            value = null
        }
    }

    val effectiveQuality = when {
        reduceMotion -> FluidQuality.STATIC
        lowPower && quality.animated -> FluidQuality.POWER_SAVE
        else -> quality
    }

    var failed by remember(effectiveQuality) { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFF0A0C13))) {
        val frame = artwork
        if (frame != null) {
            // 底层 CPU 静态回退图（在 TextureView 准备好前提供即时背景）
            Image(
                bitmap = frame.fallback.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            // GLES2 动态流光渲染层
            if (effectiveQuality.animated && !failed) {
                AndroidView(
                    factory = { ctx ->
                        KawarpTextureView(ctx) { failed = true }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.close() },
                    update = { view ->
                        view.update(frame.source, FluidRenderState(effectiveQuality, playing), foreground)
                    }
                )
            }
        }
    }
}

/**
 * 💡 纯 CPU 算力生成的 Kawase 模糊色彩图（用作 GLES2 初始化前的首帧占位与降级回退）
 */
private fun staticColourMap(input: Bitmap): Bitmap {
    val size = 128
    val scaled = Bitmap.createScaledBitmap(input, size, size, true)
    var read = IntArray(size * size)
    var write = IntArray(read.size)
    scaled.getPixels(read, 0, size, 0, 0, size, size)
    scaled.recycle()

    for (i in read.indices) {
        val color = read[i]
        val r = (color ushr 16 and 255) / 255f
        val g = (color ushr 8 and 255) / 255f
        val b = (color and 255) / 255f
        val luma = r * 0.299f + g * 0.587f + b * 0.114f
        val t = (luma / 0.5f).coerceIn(0f, 1f)
        val mask = (1f - t * t * (3f - 2f * t)) * FluidTuning.TINT_INTENSITY
        read[i] = argb(r + (0.157f - r) * mask, g + (0.157f - g) * mask, b + (0.235f - b) * mask)
    }

    repeat(FluidTuning.BLUR_PASSES) { pass ->
        val offset = pass + 1
        for (y in 0 until size) {
            val top = (y - offset).coerceIn(0, size - 1) * size
            val bottom = (y + offset).coerceIn(0, size - 1) * size
            for (x in 0 until size) {
                val left = (x - offset).coerceIn(0, size - 1)
                val right = (x + offset).coerceIn(0, size - 1)
                val a = read[top + left]
                val b = read[top + right]
                val c = read[bottom + left]
                val d = read[bottom + right]
                val r = ((a ushr 16 and 255) + (b ushr 16 and 255) + (c ushr 16 and 255) + (d ushr 16 and 255)) / 4
                val g = ((a ushr 8 and 255) + (b ushr 8 and 255) + (c ushr 8 and 255) + (d ushr 8 and 255)) / 4
                val blue = ((a and 255) + (b and 255) + (c and 255) + (d and 255)) / 4
                write[y * size + x] = (255 shl 24) or (r shl 16) or (g shl 8) or blue
            }
        }
        val old = read
        read = write
        write = old
    }

    for (y in 0 until size) {
        for (x in 0 until size) {
            val sourceX = ((x + 0.5f - size / 2f) / FluidTuning.SCALE + size / 2f).toInt().coerceIn(0, size - 1)
            val sourceY = ((y + 0.5f - size / 2f) / FluidTuning.SCALE + size / 2f).toInt().coerceIn(0, size - 1)
            val color = read[(size - 1 - sourceY) * size + sourceX]
            val dx = x.toFloat() / size - 0.5f
            val dy = y.toFloat() / size - 0.5f
            val vignette = 1f - (dx * dx + dy * dy) * 0.3f
            val r = (color ushr 16 and 255) / 255f * vignette
            val g = (color ushr 8 and 255) / 255f * vignette
            val b = (color and 255) / 255f * vignette
            val gray = r * 0.299f + g * 0.587f + b * 0.114f
            write[y * size + x] = argb(
                gray + (r - gray) * FluidTuning.SATURATION,
                gray + (g - gray) * FluidTuning.SATURATION,
                gray + (b - gray) * FluidTuning.SATURATION
            )
        }
    }
    return Bitmap.createBitmap(write, size, size, Bitmap.Config.ARGB_8888)
}

private fun argb(r: Float, g: Float, b: Float): Int =
    (255 shl 24) or
    ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
    ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
    (b * 255f).roundToInt().coerceIn(0, 255)
