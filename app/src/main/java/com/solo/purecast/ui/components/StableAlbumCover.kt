package com.solo.purecast.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 💡 封面视觉指纹（64-bit 差异感知哈希 dHash + 平均 RGB 色彩均值）
 * 用于判定两张来源 URL 不同或经重编码压缩的封面在视觉上是否为同一张专辑封面，
 * 从而在同专辑切歌时彻底跳过无意义的封面重绘与淡入淡出闪烁。
 */
@Immutable
data class CoverVisualFingerprint(
    val dHash: Long,
    val avgR: Int,
    val avgG: Int,
    val avgB: Int
) {
    fun isVisuallyEquivalentTo(other: CoverVisualFingerprint): Boolean {
        val hammingDistance = java.lang.Long.bitCount(dHash xor other.dHash)
        val rgbDiff = maxOf(
            abs(avgR - other.avgR),
            abs(avgG - other.avgG),
            abs(avgB - other.avgB)
        )
        return hammingDistance <= MAX_EQUIVALENT_HAMMING && rgbDiff <= MAX_EQUIVALENT_RGB_DIFF
    }

    companion object {
        private const val MAX_EQUIVALENT_HAMMING = 4
        private const val MAX_EQUIVALENT_RGB_DIFF = 8

        /**
         * 从已解码软件位图计算 9x8 差异感知哈希与平均 RGB（单次耗时 < 0.2ms）
         */
        fun fromBitmap(bitmap: Bitmap): CoverVisualFingerprint {
            val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            val pixels = IntArray(9 * 8)
            scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
            if (scaled !== bitmap) {
                scaled.recycle()
            }

            var hash = 0L
            var bitIndex = 0
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L

            for (y in 0 until 8) {
                val rowOffset = y * 9
                for (x in 0 until 9) {
                    val c = pixels[rowOffset + x]
                    sumR += (c shr 16) and 0xFF
                    sumG += (c shr 8) and 0xFF
                    sumB += c and 0xFF
                }
                for (x in 0 until 8) {
                    val leftLum = luminance(pixels[rowOffset + x])
                    val rightLum = luminance(pixels[rowOffset + x + 1])
                    if (leftLum > rightLum) {
                        hash = hash or (1L shl bitIndex)
                    }
                    bitIndex++
                }
            }

            val count = pixels.size.toLong()
            return CoverVisualFingerprint(
                dHash = hash,
                avgR = (sumR / count).toInt(),
                avgG = (sumG / count).toInt(),
                avgB = (sumB / count).toInt()
            )
        }

        private fun luminance(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (r * 77 + g * 150 + b * 29) shr 8
        }
    }
}

/**
 * 💡 已解码且完成视觉指纹去重的稳定封面状态，由 MainTvScreen 顶层持有并同时透传给 LyricsScreen 与 FluidBackground，
 * 确保共享元素切换（展开/收起歌词）期间零重新加载、零闪烁，并复用于背景 256x256 采样避免二次 Coil 请求。
 */
@Immutable
data class ResolvedCoverArt(
    val bitmap: ImageBitmap,
    val fingerprint: CoverVisualFingerprint,
    val revision: Long,
    val backgroundBitmap: Bitmap? = null
)

private const val MAX_FOREGROUND_COVER_SIZE_PX = 1024
private const val BACKGROUND_COVER_SIZE_PX = 256

/**
 * 💡 顶层封面异步解码、1024x1024 尺寸限制、视觉指纹去重与 256x256 背景位图派生 Composable。
 * 切歌无封面宽限期统一由会话状态机 (TrackTransactionManager + PlaybackSessionCoordinator 1200ms) 管理，
 * UI 侧收到空封面后立即同步清空，保证大屏前景、流光背景与系统 MediaSession 严格一致。
 */
@Composable
fun rememberResolvedCoverArt(
    coverBytes: ByteArray?,
    coverUri: String?,
    coverHash: Int,
    isIdle: Boolean
): ResolvedCoverArt? {
    val context = LocalContext.current
    val validBytes = coverBytes?.takeIf { it.isNotEmpty() }
    val coverData: Any? = validBytes ?: coverUri
    val hasValidCover = validBytes != null ||
        (!coverUri.isNullOrEmpty() &&
            (coverUri.startsWith("http://", ignoreCase = true) || coverUri.startsWith("https://", ignoreCase = true)))

    var resolvedCoverArt by remember { mutableStateOf<ResolvedCoverArt?>(null) }
    var lastLoadedSourceHash by remember { mutableStateOf(0) }
    var coverRevisionCounter by remember { mutableLongStateOf(0L) }

    fun clearCover() {
        lastLoadedSourceHash = 0
        resolvedCoverArt = null
    }

    LaunchedEffect(coverHash, coverUri, hasValidCover, isIdle) {
        if (isIdle || !hasValidCover || coverData == null) {
            clearCover()
            return@LaunchedEffect
        }

        val incomingHash = if (coverHash != 0) coverHash else coverUri.hashCode()
        if (incomingHash == lastLoadedSourceHash && resolvedCoverArt != null) {
            return@LaunchedEffect
        }

        try {
            val result = withContext(Dispatchers.IO) {
                val cacheKey = if (incomingHash != 0) "cover_$incomingHash" else null
                val req = ImageRequest.Builder(context)
                    .data(coverData)
                    .apply {
                        if (cacheKey != null) {
                            memoryCacheKey(cacheKey)
                        }
                    }
                    .size(MAX_FOREGROUND_COVER_SIZE_PX, MAX_FOREGROUND_COVER_SIZE_PX)
                    .allowHardware(false)
                    .bitmapConfig(Bitmap.Config.ARGB_8888)
                    .build()
                context.imageLoader.execute(req)
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is SuccessResult -> {
                    val decodedBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (decodedBitmap != null) {
                        val incomingFp = withContext(Dispatchers.Default) {
                            CoverVisualFingerprint.fromBitmap(decodedBitmap)
                        }
                        currentCoroutineContext().ensureActive()
                        lastLoadedSourceHash = incomingHash
                        val existingArt = resolvedCoverArt
                        if (existingArt != null && existingArt.fingerprint.isVisuallyEquivalentTo(incomingFp)) {
                            // 💡 同专辑封面视觉一致（如 UPnP 换曲目 URL 或 AirPlay 重编码）：静默保持现有前景与背景，0 闪烁
                            return@LaunchedEffect
                        }
                        val bgBitmap = withContext(Dispatchers.Default) {
                            Bitmap.createBitmap(
                                BACKGROUND_COVER_SIZE_PX,
                                BACKGROUND_COVER_SIZE_PX,
                                Bitmap.Config.ARGB_8888
                            ).also { dst ->
                                Canvas(dst).drawBitmap(
                                    decodedBitmap,
                                    null,
                                    Rect(0, 0, BACKGROUND_COVER_SIZE_PX, BACKGROUND_COVER_SIZE_PX),
                                    Paint(Paint.FILTER_BITMAP_FLAG)
                                )
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        val nextRev = ++coverRevisionCounter
                        resolvedCoverArt = ResolvedCoverArt(
                            bitmap = decodedBitmap.asImageBitmap(),
                            fingerprint = incomingFp,
                            revision = nextRev,
                            backgroundBitmap = bgBitmap
                        )
                    } else {
                        clearCover()
                    }
                }
                is ErrorResult -> {
                    clearCover()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.ui.w("StableAlbumCover", "封面解码异常", e)
            clearCover()
        }
    }

    return resolvedCoverArt
}

/**
 * 💡 零闪烁双层封面渲染组件：
 * 1. 底层始终以 alpha = 1f 保持当前已显示的封面，绝不像 Crossfade 那样将旧图淡出露出暗色底框；
 * 2. 仅当新专辑封面解码完成且视觉指纹不同时，在上层以 0f -> 1f 淡入叠加，全程总不透明度恒为 100%；
 * 3. 淡入完成后将新图晋升为底层并清空上层，稳态零过绘。
 */
@Composable
fun StableAlbumCover(
    coverArt: ResolvedCoverArt?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fadeDurationMs: Int = 260,
    placeholder: @Composable () -> Unit
) {
    var baseCover by remember { mutableStateOf(coverArt) }
    var incomingCover by remember { mutableStateOf<ResolvedCoverArt?>(null) }
    val incomingAlpha = remember { Animatable(0f) }

    LaunchedEffect(coverArt?.revision) {
        if (coverArt == null) {
            baseCover = null
            incomingCover = null
            incomingAlpha.snapTo(0f)
        } else if (baseCover == null) {
            baseCover = coverArt
            incomingCover = null
            incomingAlpha.snapTo(0f)
        } else if (coverArt.revision != baseCover?.revision) {
            // 💡 快速连续切歌防回跳：若上一张新图已淡入过半，先将其晋升为底层，避免画面瞬间倒退回更早的旧封面
            val inProgress = incomingCover
            if (inProgress != null && incomingAlpha.value >= 0.5f) {
                baseCover = inProgress
            }
            incomingCover = coverArt
            incomingAlpha.snapTo(0f)
            incomingAlpha.animateTo(1f, animationSpec = tween(durationMillis = fadeDurationMs))
            baseCover = coverArt
            incomingCover = null
            incomingAlpha.snapTo(0f)
        }
    }

    Box(modifier = modifier) {
        val currentBase = baseCover
        if (currentBase != null) {
            Image(
                bitmap = currentBase.bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("stable_cover_base_${currentBase.revision}")
            )
            val currentIncoming = incomingCover
            if (currentIncoming != null) {
                Image(
                    bitmap = currentIncoming.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("stable_cover_incoming_${currentIncoming.revision}")
                        .graphicsLayer { alpha = incomingAlpha.value }
                )
            }
        } else {
            placeholder()
        }
    }
}
