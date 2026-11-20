package com.solo.purecast.ui.lyrics

import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.LyricLine

/**
 * 💡 逐字歌词（Karaoke Word-Sync）视觉与排版开销优化组件：
 * 1. 常驻单一稳定节点（传入 isActive），彻底消除切句首帧 Text ⇄ WordSyncKaraokeText 销毁重建与 TextLayout 重排卡顿；
 * 2. 0 重组纯 Draw 阶段渲染：完全隔离在 drawWithContent 内部读取时间，重组次数彻底归 0；
 * 3. 直接复用父级 SmoothLyricLineRow 的 1:1 刚性常驻 Offscreen 层执行 SrcIn 扫光，非激活态自动关闭 VSYNC 循环（CPU 归 0）；
 * 4. 零堆内存分配优化：复用预分配的 LinearGradient、Matrix 与 Native Paint，彻底消除 60fps 下每帧临时 Brush 堆内存分配；
 * 5. 实现真正的 Apple Music 卡拉 OK 逐像素平滑扫光与抗锯齿羽化边缘。
 */
@Composable
fun WordSyncKaraokeText(
    line: LyricLine,
    syncClock: LyricsSyncClock,
    currentTimeProvider: () -> Long,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    isActive: Boolean = true
) {
    val words = line.words ?: return
    val fullText = line.mainText

    // 💡 仅在激活且播放态启动 60fps VSYNC 驱动循环；非激活或暂停时立即停止空转，CPU 负载降为 0
    var frameTrigger by remember { mutableLongStateOf(0L) }
    val shouldAnimate = isActive && isPlaying
    LaunchedEffect(shouldAnimate) {
        if (!shouldAnimate) return@LaunchedEffect
        while (true) {
            withFrameNanos { frameNanos ->
                frameTrigger = frameNanos
            }
        }
    }

    // 💡 激活但暂停态监听时钟离散变更（如 Seek / 硬对齐），立即重绘扫光位置
    LaunchedEffect(isActive, isPlaying) {
        if (isActive && !isPlaying) {
            frameTrigger = System.nanoTime()
            syncClock.mutationVersionFlow.collect {
                frameTrigger = System.nanoTime()
            }
        }
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 预先计算各词在 fullText 中的字符索引范围
    val wordRanges = remember(words, fullText) {
        var searchIndex = 0
        words.map { word ->
            val startIndex = fullText.indexOf(word.text, searchIndex).takeIf { it >= 0 } ?: searchIndex
            val endIndex = (startIndex + word.text.length).coerceAtMost(fullText.length)
            searchIndex = endIndex
            startIndex to endIndex
        }
    }

    val density = LocalDensity.current
    val feather = with(density) { 10.dp.toPx() }
    val unlitColor = remember(primaryColor) { primaryColor.copy(alpha = 0.45f) }

    // 💡 单一预分配 LinearGradient 与 Matrix 平移复用机制
    // 杜绝在 Draw 阶段每帧分配 Brush.horizontalGradient、ColorStops、List 等高频临时堆对象
    val sweepMatrix = remember { Matrix() }
    val sweepShader = remember(primaryColor, unlitColor, feather) {
        android.graphics.LinearGradient(
            0f, 0f, feather, 0f,
            primaryColor.toArgb(),
            unlitColor.toArgb(),
            Shader.TileMode.CLAMP
        )
    }
    val sweepPaint = remember(sweepShader) {
        android.graphics.Paint().apply {
            shader = sweepShader
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
    }

    // 💡 预计算并扁平缓存每个视觉行与每个词的几何边界，彻底消除 60fps 绘制热路径中的 Rect / Pair 堆分配
    val cachedGeometry = remember(layoutResult, wordRanges, fullText) {
        val lr = layoutResult ?: return@remember null
        if (fullText.isEmpty() || words.isEmpty() || lr.layoutInput.text.text != fullText) return@remember null
        val lc = lr.lineCount
        val lineTops = FloatArray(lc) { lr.getLineTop(it) }
        val lineBottoms = FloatArray(lc) { lr.getLineBottom(it) }
        val lineLefts = FloatArray(lc) { lr.getLineLeft(it) }
        val lineRights = FloatArray(lc) { lr.getLineRight(it) }

        val wc = words.size
        val safeStarts = IntArray(wc)
        val endChars = IntArray(wc)
        val startLines = IntArray(wc)
        val endLines = IntArray(wc)
        val wordLefts = FloatArray(wc)
        val wordRights = FloatArray(wc)
        val endCharLefts = FloatArray(wc)
        val endCharRights = FloatArray(wc)

        for (i in 0 until wc) {
            val (s, e) = LyricsRenderingMath.clampSafeCharBounds(
                fullText = fullText,
                startChar = wordRanges[i].first,
                wordText = words[i].text
            )
            safeStarts[i] = s
            endChars[i] = e
            startLines[i] = lr.getLineForOffset(s)
            endLines[i] = lr.getLineForOffset(e)
            val startBox = lr.getBoundingBox(s)
            val endBox = if (s == e) startBox else lr.getBoundingBox(e)
            wordLefts[i] = startBox.left
            wordRights[i] = endBox.right
            endCharLefts[i] = endBox.left
            endCharRights[i] = endBox.right
        }
        CachedKaraokeGeometry(
            lineCount = lc,
            lineTops = lineTops,
            lineBottoms = lineBottoms,
            lineLefts = lineLefts,
            lineRights = lineRights,
            safeStarts = safeStarts,
            endChars = endChars,
            startLines = startLines,
            endLines = endLines,
            wordLefts = wordLefts,
            wordRights = wordRights,
            endCharLefts = endCharLefts,
            endCharRights = endCharRights
        )
    }

    Text(
        text = fullText,
        fontSize = 33.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 42.sp,
        onTextLayout = { layoutResult = it },
        color = if (isActive) Color.White else primaryColor,
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                if (!isActive) return@drawWithContent

                val lr = layoutResult ?: return@drawWithContent
                val geom = cachedGeometry ?: run {
                    drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                // 💡 在 Draw 阶段读取 frameTrigger，注册每帧 60fps 重绘监听，彻底跳过 Composition 与 Layout 阶段
                frameTrigger.let { }

                val now = currentTimeProvider()
                val lineCount = geom.lineCount

                if (now < words.first().startMs) {
                    drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }
                if (now >= words.last().endMs) {
                    drawRect(color = primaryColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                // 寻找当前活跃的词索引 activeWordIndex
                var activeWordIndex = -1
                for (i in words.indices) {
                    val w = words[i]
                    if (now in w.startMs..w.endMs) {
                        activeWordIndex = i
                        break
                    } else if (now < w.startMs) {
                        activeWordIndex = (i - 1).coerceAtLeast(0)
                        break
                    } else if (i == words.lastIndex) {
                        activeWordIndex = i
                    }
                }

                if (activeWordIndex !in words.indices) {
                    drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                val activeWord = words[activeWordIndex]
                val safeStart = geom.safeStarts[activeWordIndex]
                val endChar = geom.endChars[activeWordIndex]

                val activeVisualLine: Int
                val currentSweepX: Float
                val currentWordLeft: Float
                val wordProgress: Float

                if (now in activeWord.startMs..activeWord.endMs) {
                    val progress = syncClock.calculateWordProgress(activeWord, now).coerceIn(0f, 1f)
                    wordProgress = progress
                    val startLine = geom.startLines[activeWordIndex]
                    val endLine = geom.endLines[activeWordIndex]

                    if (startLine == endLine) {
                        // 单词处于同一视觉行：直接读取扁平预缓存边界（0 堆分配）
                        activeVisualLine = startLine
                        val wordLeft = geom.wordLefts[activeWordIndex]
                        val wordRight = geom.wordRights[activeWordIndex]
                        currentWordLeft = wordLeft
                        currentSweepX = wordLeft + (wordRight - wordLeft) * progress
                    } else {
                        // 💡 Finding 6: 折行跨行单词匀速线性扫光
                        val seg = LyricsRenderingMath.calculateWrappedLineSegment(
                            safeStart = safeStart,
                            endChar = endChar,
                            progress = progress,
                            getLineForOffset = lr::getLineForOffset
                        )
                        activeVisualLine = seg.visualLine
                        val segLeft = lr.getBoundingBox(seg.segStartChar).left
                        val segRight = lr.getBoundingBox(seg.segEndChar).right
                        currentWordLeft = segLeft
                        currentSweepX = segLeft + (segRight - segLeft) * seg.segProgress
                    }
                } else {
                    // 词间间隙：已完整唱完 activeWord，直接读取预缓存末端边界（0 堆分配）
                    activeVisualLine = geom.endLines[activeWordIndex]
                    currentSweepX = geom.endCharRights[activeWordIndex]
                    currentWordLeft = geom.endCharLefts[activeWordIndex]
                    wordProgress = 0f
                }

                // 💡 内联零分配羽化计算（与 LyricsRenderingMath.calculateEffectiveFeather 数学严格等价，避免每帧分配 Pair）
                val halfFeather = (feather * 0.5f).coerceAtLeast(1f)
                val maxSweepFromWordLeft = (currentSweepX - currentWordLeft).coerceAtLeast(0f)
                val effectiveHalfFeather = if (wordProgress <= 0f) {
                    0f
                } else {
                    minOf(halfFeather, maxSweepFromWordLeft)
                }
                val sweepStart = currentSweepX - effectiveHalfFeather
                val effectiveFeather = (effectiveHalfFeather * 2f).coerceAtLeast(0.001f)

                // 💡 Finding 3: 设置平移与缩放矩阵并更新 Shader，零对象分配
                sweepMatrix.reset()
                sweepMatrix.setScale(effectiveFeather / feather, 1f)
                sweepMatrix.postTranslate(sweepStart, 0f)
                sweepShader.setLocalMatrix(sweepMatrix)
                // 💡 Finding 5: 显式重绑 Paint.shader 确保底层 dirty 标记刷新，彻底消除部分设备本地矩阵缓存滞后
                sweepPaint.shader = sweepShader

                val nativeCanvas = drawContext.canvas.nativeCanvas

                // 💡 按视觉行分段精确裁切与绘制（读取预缓存行坐标，0 堆分配）
                for (lineIdx in 0 until lineCount) {
                    val lineTop = geom.lineTops[lineIdx]
                    val lineBottom = geom.lineBottoms[lineIdx]
                    val lineLeft = geom.lineLefts[lineIdx]
                    val lineRight = geom.lineRights[lineIdx]

                    clipRect(left = 0f, top = lineTop, right = size.width, bottom = lineBottom) {
                        if (lineIdx < activeVisualLine) {
                            drawRect(
                                color = primaryColor,
                                blendMode = BlendMode.SrcIn
                            )
                        } else if (lineIdx > activeVisualLine) {
                            drawRect(
                                color = unlitColor,
                                blendMode = BlendMode.SrcIn
                            )
                        } else {
                            val sweep = currentSweepX.coerceIn(lineLeft, lineRight)
                            if (sweep <= lineLeft) {
                                drawRect(
                                    color = unlitColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else if (sweep >= lineRight) {
                                drawRect(
                                    color = primaryColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else {
                                nativeCanvas.drawRect(
                                    lineLeft,
                                    lineTop,
                                    lineRight,
                                    lineBottom,
                                    sweepPaint
                                )
                            }
                        }
                    }
                }
            }
    )
}

private class CachedKaraokeGeometry(
    val lineCount: Int,
    val lineTops: FloatArray,
    val lineBottoms: FloatArray,
    val lineLefts: FloatArray,
    val lineRights: FloatArray,
    val safeStarts: IntArray,
    val endChars: IntArray,
    val startLines: IntArray,
    val endLines: IntArray,
    val wordLefts: FloatArray,
    val wordRights: FloatArray,
    val endCharLefts: FloatArray,
    val endCharRights: FloatArray
)
