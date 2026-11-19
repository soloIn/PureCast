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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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
 * 1. 主歌词文本保持为不可变 String，使 TextLayoutResult 在整句生命周期内 100% 缓存复用，排版 CPU 开销降为 0；
 * 2. 0 重组纯 Draw 阶段渲染：完全隔离在 drawWithContent 内部读取时间，重组次数彻底归 0；
 * 3. 视觉行分段裁切 (clipRect)：针对换行多行歌词，按视觉行分段绘制与渐变，根除跨行染色串色 Bug；
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
    isPlaying: Boolean = true
) {
    val words = line.words ?: return
    val fullText = line.mainText

    // 💡 Finding 3: 仅在播放态启动 60fps VSYNC 驱动循环；暂停时立即停止空转，CPU 负载降为 0
    var frameTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos { frameNanos ->
                frameTrigger = frameNanos
            }
        }
    }

    // 💡 Finding #1 & #4 & #5: 暂停态副作用精简 key 为 LaunchedEffect(isPlaying)，移除 mutationVersion，避免 key 重启与 collect 双重触发
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
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

    // 💡 Finding 3: 单一预分配 LinearGradient 与 Matrix 平移复用机制
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

    Text(
        text = fullText,
        fontSize = 31.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 40.sp,
        onTextLayout = { layoutResult = it },
        color = Color.White,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val lr = layoutResult ?: return@drawWithContent
                drawContent()

                // 💡 Finding #7: 头部快速返回保护，处理空文本或空词列表
                if (fullText.isEmpty() || words.isNullOrEmpty()) {
                    drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                // 💡 在 Draw 阶段读取 frameTrigger，注册每帧 60fps 重绘监听，彻底跳过 Composition 与 Layout 阶段
                frameTrigger.let { }

                val now = currentTimeProvider()
                val lineCount = lr.lineCount

                if (words.isEmpty() || now < words.first().startMs) {
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
                val range = wordRanges[activeWordIndex]
                // 💡 Finding 12 & Finding #9 & Finding #7: 通过 LyricsRenderingMath 统一进行边界钳制与字符区间计算
                val (safeStart, endChar) = LyricsRenderingMath.clampSafeCharBounds(
                    fullText = fullText,
                    startChar = range.first,
                    wordText = activeWord.text
                )

                val activeVisualLine: Int
                val currentSweepX: Float
                val currentWordLeft: Float
                val wordProgress: Float

                if (now in activeWord.startMs..activeWord.endMs) {
                    val progress = syncClock.calculateWordProgress(activeWord, now).coerceIn(0f, 1f)
                    wordProgress = progress
                    val startLine = lr.getLineForOffset(safeStart)
                    val endLine = lr.getLineForOffset(endChar)

                    if (startLine == endLine) {
                        // 单词处于同一视觉行：单行内匀速线性扫光
                        activeVisualLine = startLine
                        val wordLeft = lr.getBoundingBox(safeStart).left
                        val wordRight = lr.getBoundingBox(endChar).right
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
                    // 词间间隙：已完整唱完 activeWord，正在等待下一个词
                    activeVisualLine = lr.getLineForOffset(endChar)
                    val box = lr.getBoundingBox(endChar)
                    currentSweepX = box.right
                    currentWordLeft = box.left
                    // 💡 Finding 5: 词间停顿期将 wordProgress 置 0f（effectiveHalfFeather = 0f），刚好精确停在已唱完词的 box.right 边界，绝不向右越界染亮下一个未唱字
                    wordProgress = 0f
                }

                // 💡 Finding 12 & Finding #7: 将羽化区间随 progress 动态锚定，由 LyricsRenderingMath 计算有效羽化与平移
                val (sweepStart, effectiveFeather) = LyricsRenderingMath.calculateEffectiveFeather(
                    sweep = currentSweepX,
                    feather = feather,
                    wordLeft = currentWordLeft,
                    wordProgress = wordProgress
                )

                // 💡 Finding 3: 设置平移与缩放矩阵并更新 Shader，零对象分配
                sweepMatrix.reset()
                sweepMatrix.setScale(effectiveFeather / feather, 1f)
                sweepMatrix.postTranslate(sweepStart, 0f)
                sweepShader.setLocalMatrix(sweepMatrix)
                // 💡 Finding 5: 显式重绑 Paint.shader 确保底层 dirty 标记刷新，彻底消除部分设备本地矩阵缓存滞后
                sweepPaint.shader = sweepShader

                val nativeCanvas = drawContext.canvas.nativeCanvas

                // 💡 按视觉行分段精确裁切与绘制，彻底根除多行换行歌词跨行串色 Bug
                for (lineIdx in 0 until lineCount) {
                    val lineTop = lr.getLineTop(lineIdx)
                    val lineBottom = lr.getLineBottom(lineIdx)
                    val lineLeft = lr.getLineLeft(lineIdx)
                    val lineRight = lr.getLineRight(lineIdx)

                    clipRect(left = 0f, top = lineTop, right = size.width, bottom = lineBottom) {
                        if (lineIdx < activeVisualLine) {
                            // 该视觉行已全部唱完：整行主色高亮 (0 对象分配)
                            drawRect(
                                color = primaryColor,
                                blendMode = BlendMode.SrcIn
                            )
                        } else if (lineIdx > activeVisualLine) {
                            // 该视觉行尚未唱到：整行暗色未激活 (0 对象分配)
                            drawRect(
                                color = unlitColor,
                                blendMode = BlendMode.SrcIn
                            )
                        } else {
                            // 正在该视觉行演唱：仅在该行有效区域内应用扫光
                            val sweep = currentSweepX.coerceIn(lineLeft, lineRight)
                            if (sweep <= lineLeft) {
                                // 尚未扫到该行有效区域：直接单色绘制，彻底避免调用 Shader
                                drawRect(
                                    color = unlitColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else if (sweep >= lineRight) {
                                // 该视觉行已全部扫完：直接主色绘制，彻底避免调用 Shader
                                drawRect(
                                    color = primaryColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else {
                                // 处于该视觉行动态过渡扫光阶段：使用单一复用 Paint 绘制平移后的 Shader
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
