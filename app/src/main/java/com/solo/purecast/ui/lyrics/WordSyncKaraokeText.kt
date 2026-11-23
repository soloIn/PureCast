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
 * 4. 退场无缝衔接：失活淡出期冻结最后一次扫光位置并保持未唱尾部单调衰减，彻底杜绝进度不同步时行尾闪白；
 * 5. 行尾羽化平滑收口：扫光区间覆盖至词尾 + halfFeather，彻底消除行尾最后一帧硬切全亮的轻微跳闪。
 */
@Composable
fun WordSyncKaraokeText(
    line: LyricLine,
    syncClock: LyricsSyncClock,
    currentTimeProvider: () -> Long,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    isActive: Boolean = true,
    exitingRowAlphaProvider: () -> Float = { 0f }
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
    // 💡 记录最近一次激活帧的时间戳，供失活退场淡出期冻结扫光进度使用（防止行尾未唱字符闪白）
    val lastActiveTimeMsRef = remember(line) { longArrayOf(Long.MIN_VALUE) }

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
    val baseUnlitAlpha = 0.45f
    val unlitColor = remember(primaryColor) { primaryColor.copy(alpha = baseUnlitAlpha) }

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
    // 💡 退场淡出期专用复用 Paint 与量化 Shader 缓存，彻底消除退场 60fps 帧循环中每帧 new LinearGradient 分配
    val exitingSweepPaint = remember {
        android.graphics.Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
    }
    val exitShaderCache = remember(primaryColor, feather) {
        object {
            var lastQuantizedAlphaStep: Int = -1
            var shader: android.graphics.LinearGradient? = null
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
        color = primaryColor,
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()

                val exitingAlpha = if (!isActive) exitingRowAlphaProvider() else 1.0f
                val isExitingActive = !isActive &&
                    exitingAlpha > baseUnlitAlpha &&
                    lastActiveTimeMsRef[0] != Long.MIN_VALUE

                if (!isActive && !isExitingActive) return@drawWithContent

                val lr = layoutResult ?: return@drawWithContent
                val effectiveUnlitAlpha = if (isExitingActive) {
                    // 💡 退场时保持 unlit 尾部实测亮度 = effectiveUnlitAlpha * exitingAlpha <= baseUnlitAlpha，
                    // 等 exitingAlpha 跌破 0.45f 时自然汇合到 1.0f，做到 0 闪白、0 跳变！
                    (baseUnlitAlpha / exitingAlpha.coerceAtLeast(0.01f)).coerceAtMost(1.0f)
                } else {
                    baseUnlitAlpha
                }
                val activeUnlitColor = if (effectiveUnlitAlpha == baseUnlitAlpha) {
                    unlitColor
                } else {
                    primaryColor.copy(alpha = effectiveUnlitAlpha)
                }

                // 若退场已汇合至统一亮度，直接复用普通文字绘制即可，无需再叠加 SrcIn 渐变
                if (isExitingActive && effectiveUnlitAlpha >= 0.995f) {
                    return@drawWithContent
                }

                val geom = cachedGeometry ?: run {
                    drawRect(color = activeUnlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                // 💡 在 Draw 阶段读取 frameTrigger，注册每帧 60fps 重绘监听，彻底跳过 Composition 与 Layout 阶段
                if (isActive) {
                    frameTrigger.let { }
                }

                val now = if (isActive) {
                    val t = currentTimeProvider()
                    lastActiveTimeMsRef[0] = t
                    t
                } else {
                    lastActiveTimeMsRef[0]
                }
                val lineCount = geom.lineCount

                if (now < words.first().startMs) {
                    drawRect(color = activeUnlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }
                val isVocalFinished = now >= words.last().endMs ||
                    (isExitingActive && now >= words.last().endMs - 50L)
                if (isVocalFinished) {
                    // 💡 维持与扫光阶段一致的 SRC_IN 合成路径，彻底消除唱完瞬间切回裸 Text 引起的字形亮度/抗锯齿微闪
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
                    drawRect(color = activeUnlitColor, blendMode = BlendMode.SrcIn)
                    return@drawWithContent
                }

                val activeWord = words[activeWordIndex]
                val safeStart = geom.safeStarts[activeWordIndex]
                val endChar = geom.endChars[activeWordIndex]
                val halfFeather = (feather * 0.5f).coerceAtLeast(1f)

                val activeVisualLine: Int
                val currentSweepX: Float
                val currentWordLeft: Float
                val wordProgress: Float

                if (now in activeWord.startMs..activeWord.endMs) {
                    val progress = syncClock.calculateWordProgress(activeWord, now).coerceIn(0f, 1f)
                    wordProgress = progress
                    val startLine = geom.startLines[activeWordIndex]
                    val endLine = geom.endLines[activeWordIndex]

                    val isLastWordOfLine = activeWordIndex == words.lastIndex
                    if (startLine == endLine) {
                        // 单词处于同一视觉行：整句末词延伸至行尾右边界（覆盖行尾标点）+ halfFeather，消除唱完瞬间尾标点突亮
                        activeVisualLine = startLine
                        val wordLeft = geom.wordLefts[activeWordIndex]
                        val wordRight = geom.wordRights[activeWordIndex]
                        val isLastWordOnVisualLine = isLastWordOfLine ||
                            geom.startLines[activeWordIndex + 1] > activeVisualLine
                        val effectiveRight = if (isLastWordOfLine) {
                            maxOf(wordRight, geom.lineRights[activeVisualLine])
                        } else {
                            wordRight
                        }
                        val endExtra = if (isLastWordOnVisualLine) halfFeather else 0f
                        currentWordLeft = wordLeft
                        currentSweepX = wordLeft + ((effectiveRight - wordLeft) + endExtra) * progress
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
                        val isLastWordOnVisualLine = isLastWordOfLine ||
                            geom.startLines[activeWordIndex + 1] > activeVisualLine
                        val effectiveSegRight = if (isLastWordOfLine && seg.visualLine == endLine) {
                            maxOf(segRight, geom.lineRights[activeVisualLine])
                        } else {
                            segRight
                        }
                        val endExtra = if (isLastWordOnVisualLine && seg.visualLine == endLine) halfFeather else 0f
                        currentWordLeft = segLeft
                        currentSweepX = segLeft + ((effectiveSegRight - segLeft) + endExtra) * seg.segProgress
                    }
                } else {
                    // 词间间隙：已完整唱完 activeWord，羽化起点锚定在词尾右侧，保持 100% 纯亮无暗尾
                    activeVisualLine = geom.endLines[activeWordIndex]
                    currentSweepX = geom.endCharRights[activeWordIndex]
                    currentWordLeft = geom.endCharLefts[activeWordIndex]
                    wordProgress = 0f
                }

                val maxSweepFromWordLeft = (currentSweepX - currentWordLeft).coerceAtLeast(0f)
                val effectiveHalfFeather = if (wordProgress <= 0f) {
                    0f
                } else {
                    minOf(halfFeather, maxSweepFromWordLeft)
                }
                val useHardEdgeSplit = effectiveHalfFeather < 1f
                val sweepStart = currentSweepX - effectiveHalfFeather
                val effectiveFeather = (effectiveHalfFeather * 2f).coerceAtLeast(2f)

                val activePaint = if (useHardEdgeSplit) {
                    null
                } else {
                    // 💡 仅在 effectiveFeather >= 2px 时更新局部矩阵（scaleX >= 0.1f，远高于 Skia SK_ScalarNearlyZero=2.44e-4），
                    // 彻底根除词间间隙 / 词首帧 5e-5 奇异矩阵导致 Skia invert() 失败引发的整行闪烁！
                    sweepMatrix.reset()
                    sweepMatrix.setScale(effectiveFeather / feather, 1f)
                    sweepMatrix.postTranslate(sweepStart, 0f)
                    if (!isExitingActive) {
                        sweepShader.setLocalMatrix(sweepMatrix)
                        sweepPaint.shader = sweepShader
                        sweepPaint
                    } else {
                        val quantizedStep = (effectiveUnlitAlpha * 25f).toInt().coerceIn(0, 25)
                        val exitShader = if (exitShaderCache.shader != null && exitShaderCache.lastQuantizedAlphaStep == quantizedStep) {
                            exitShaderCache.shader!!
                        } else {
                            val quantizedColor = primaryColor.copy(alpha = quantizedStep / 25f).toArgb()
                            android.graphics.LinearGradient(
                                0f, 0f, feather, 0f,
                                primaryColor.toArgb(),
                                quantizedColor,
                                Shader.TileMode.CLAMP
                            ).also { created ->
                                exitShaderCache.lastQuantizedAlphaStep = quantizedStep
                                exitShaderCache.shader = created
                            }
                        }
                        exitShader.setLocalMatrix(sweepMatrix)
                        exitingSweepPaint.shader = exitShader
                        exitingSweepPaint
                    }
                }

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
                                color = activeUnlitColor,
                                blendMode = BlendMode.SrcIn
                            )
                        } else {
                            if (currentSweepX <= lineLeft) {
                                drawRect(
                                    color = activeUnlitColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else if (sweepStart >= lineRight) {
                                drawRect(
                                    color = primaryColor,
                                    blendMode = BlendMode.SrcIn
                                )
                            } else if (activePaint == null) {
                                // 💡 无羽化硬切分界（词间间隙 / 词首 0 进度帧）：直接画左右两个纯色 SrcIn 矩形，零奇异矩阵风险
                                val splitX = currentSweepX.coerceIn(0f, size.width)
                                if (splitX > 0f) {
                                    drawRect(
                                        color = primaryColor,
                                        topLeft = androidx.compose.ui.geometry.Offset(0f, lineTop),
                                        size = androidx.compose.ui.geometry.Size(splitX, lineBottom - lineTop),
                                        blendMode = BlendMode.SrcIn
                                    )
                                }
                                if (splitX < size.width) {
                                    drawRect(
                                        color = activeUnlitColor,
                                        topLeft = androidx.compose.ui.geometry.Offset(splitX, lineTop),
                                        size = androidx.compose.ui.geometry.Size(size.width - splitX, lineBottom - lineTop),
                                        blendMode = BlendMode.SrcIn
                                    )
                                }
                            } else {
                                nativeCanvas.drawRect(
                                    0f,
                                    lineTop,
                                    size.width,
                                    lineBottom,
                                    activePaint
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
