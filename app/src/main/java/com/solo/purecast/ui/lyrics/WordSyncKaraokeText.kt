package com.solo.purecast.ui.lyrics

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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
 * 4. 零堆内存分配优化：非扫光视觉行直接调用 drawRect(color)，消除每帧临时 SolidColor/Brush 分配；
 * 5. 实现真正的 Apple Music 卡拉 OK 逐像素平滑扫光与抗锯齿羽化边缘。
 */
@Composable
fun WordSyncKaraokeText(
    line: LyricLine,
    syncClock: LyricsSyncClock,
    currentTimeProvider: () -> Long,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    val words = line.words ?: return
    val fullText = line.mainText

    // 💡 仅用于触发 Draw 阶段重绘的 VSYNC 信号，绝不在此读取时间引发 Composable 重组
    var frameTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos ->
                frameTrigger = frameNanos
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

                // 💡 在 Draw 阶段读取 frameTrigger，注册每帧 60fps 重绘监听，彻底跳过 Composition 与 Layout 阶段
                frameTrigger.let { }

                val now = currentTimeProvider()
                val unlitColor = primaryColor.copy(alpha = 0.45f)
                val feather = 10.dp.toPx()
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
                val startChar = range.first
                val endChar = (range.second - 1).coerceIn(startChar, (fullText.length - 1).coerceAtLeast(0))

                val activeVisualLine: Int
                val currentSweepX: Float

                if (now in activeWord.startMs..activeWord.endMs) {
                    val progress = syncClock.calculateWordProgress(activeWord, now)
                    val charOffset = (startChar + ((endChar - startChar) * progress).toInt()).coerceIn(startChar, endChar)
                    activeVisualLine = lr.getLineForOffset(charOffset)
                    val startLine = lr.getLineForOffset(startChar)
                    val endLine = lr.getLineForOffset(endChar)

                    currentSweepX = if (startLine == endLine && startLine == activeVisualLine) {
                        val wordLeft = lr.getBoundingBox(startChar).left
                        val wordRight = lr.getBoundingBox(endChar).right
                        wordLeft + (wordRight - wordLeft) * progress
                    } else {
                        val charBox = lr.getBoundingBox(charOffset)
                        charBox.left + charBox.width * progress
                    }
                } else {
                    // 词间间隙：已完整唱完 activeWord，正在等待下一个词
                    activeVisualLine = lr.getLineForOffset(endChar)
                    currentSweepX = lr.getBoundingBox(endChar).right
                }

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
                            // 正在该视觉行演唱：仅在该行宽度内应用局部水平渐变扫光
                            val sweep = currentSweepX.coerceIn(lineLeft, lineRight)
                            if (sweep <= lineLeft) {
                                // 尚未扫到该行有效区域：直接单色绘制，彻底避免创建 Brush
                                drawRect(
                                    color = unlitColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else if (sweep >= lineRight) {
                                // 该视觉行已全部扫完：直接主色绘制，彻底避免创建 Brush
                                drawRect(
                                    color = primaryColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else {
                                // 仅处于该视觉行动态过渡扫光阶段时创建水平渐变画刷
                                val lineWidth = (lineRight - lineLeft).coerceAtLeast(1f)
                                val stopLit = ((sweep - lineLeft) / lineWidth).coerceIn(0f, 1f)
                                val stopUnlit = (((sweep + feather) - lineLeft) / lineWidth).coerceIn(0f, 1f)
                                val brush = Brush.horizontalGradient(
                                    0f to primaryColor,
                                    stopLit to primaryColor,
                                    stopUnlit to unlitColor,
                                    1f to unlitColor,
                                    startX = lineLeft,
                                    endX = lineRight
                                )
                                drawRect(
                                    brush = brush,
                                    blendMode = BlendMode.SrcIn
                                )
                            }
                        }
                    }
                }
            }
    )
}
