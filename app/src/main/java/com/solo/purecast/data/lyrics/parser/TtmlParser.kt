package com.solo.purecast.data.lyrics.parser

import com.solo.purecast.data.lyrics.ChineseConverter
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 💡 AMLL TTML 高精逐字歌词健壮解析器
 * 1. 彻底弃用易引发异常崩溃的 parser.nextText()，采用纯事件驱动的上下文栈遍历；
 * 2. 完美支持任意多层 <span> 嵌套与混合排版；
 * 3. 严格遵循用户决策：提取原词与翻译，自动过滤所有背景人声 (tt:role="bg") 与音译 (tt:role="roman")；
 * 4. 0 崩溃容错，任何畸形或非标 XML 均能安全降级。
 */
object TtmlParser {

    private const val TAG = "TtmlParser"

    private data class SpanContext(
        val role: String,
        val startMs: Long,
        val endMs: Long,
        val isIgnored: Boolean,
        val isTranslation: Boolean
    )

    fun parse(xmlContent: String, sourceName: String = "AMLL"): LyricTimeline? {
        if (xmlContent.isBlank()) return null

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            val lines = mutableListOf<LyricLine>()
            var eventType = parser.eventType

            var currentLineIndex = 0
            var inParagraph = false
            var pStartMs = 0L
            var pEndMs = 0L

            val currentWords = mutableListOf<LyricWord>()
            val currentMainTextBuilder = StringBuilder()
            val currentTranslationBuilder = StringBuilder()

            val spanStack = ArrayDeque<SpanContext>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val rawTagName = parser.name ?: ""
                        val localTagName = rawTagName.substringAfter(':').lowercase()

                        if (localTagName == "p") {
                            inParagraph = true
                            spanStack.clear()
                            val beginAttr = getAttr(parser, "begin")
                            val endAttr = getAttr(parser, "end")
                            pStartMs = if (beginAttr != null) parseTimestampMs(beginAttr) else 0L
                            pEndMs = if (endAttr != null) parseTimestampMs(endAttr) else 0L

                            currentWords.clear()
                            currentMainTextBuilder.setLength(0)
                            currentTranslationBuilder.setLength(0)
                        } else if (localTagName == "span" && inParagraph) {
                            val role = (getAttr(parser, "role") ?: "").lowercase()
                            val spanBegin = getAttr(parser, "begin")
                            val spanEnd = getAttr(parser, "end")

                            val parentCtx = spanStack.lastOrNull()
                            val spanStartMs = if (spanBegin != null) parseTimestampMs(spanBegin) else (parentCtx?.startMs ?: pStartMs)
                            val spanEndMs = if (spanEnd != null) parseTimestampMs(spanEnd) else (parentCtx?.endMs ?: pEndMs)

                            val isBgOrRoman = role.contains("bg") || role.contains("roman") || (parentCtx?.isIgnored == true)
                            val isTrans = role.contains("translation") || (parentCtx?.isTranslation == true)

                            spanStack.addLast(
                                SpanContext(
                                    role = role,
                                    startMs = spanStartMs,
                                    endMs = spanEndMs,
                                    isIgnored = isBgOrRoman,
                                    isTranslation = isTrans
                                )
                            )
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (inParagraph) {
                            val text = parser.text ?: ""
                            if (text.isNotEmpty()) {
                                val currentCtx = spanStack.lastOrNull()
                                if (currentCtx == null) {
                                    // p 标签直属文本：忽略标签间换行缩进空白
                                    if (text.isNotBlank()) {
                                        currentMainTextBuilder.append(text.trim())
                                    }
                                } else if (!currentCtx.isIgnored) {
                                    if (currentCtx.isTranslation) {
                                        currentTranslationBuilder.append(text)
                                    } else {
                                        currentWords.add(
                                            LyricWord(
                                                text = text,
                                                startMs = currentCtx.startMs,
                                                endMs = currentCtx.endMs
                                            )
                                        )
                                        currentMainTextBuilder.append(text)
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val rawTagName = parser.name ?: ""
                        val localTagName = rawTagName.substringAfter(':').lowercase()

                        if (localTagName == "span" && inParagraph) {
                            if (spanStack.isNotEmpty()) {
                                spanStack.removeLast()
                            }
                        } else if (localTagName == "p" && inParagraph) {
                            inParagraph = false
                            spanStack.clear()

                            val mainText = if (currentWords.isNotEmpty()) {
                                currentWords.joinToString("") { it.text }.trim()
                            } else {
                                currentMainTextBuilder.toString().trim()
                            }
                            val translationText = currentTranslationBuilder.toString().trim().takeIf { it.isNotEmpty() }

                            if (mainText.isNotEmpty() || translationText != null) {
                                val resolvedEndMs = if (pEndMs > pStartMs) {
                                    pEndMs
                                } else {
                                    currentWords.lastOrNull()?.endMs?.takeIf { it > pStartMs } ?: (pStartMs + 3000L)
                                }

                                val simplifiedMain = ChineseConverter.toSimplified(mainText)
                                val simplifiedTrans = translationText?.let { ChineseConverter.toSimplified(it) }
                                val wordsList = if (currentWords.isNotEmpty()) {
                                    currentWords.map { w -> w.copy(text = ChineseConverter.toSimplified(w.text)) }
                                } else null

                                lines.add(
                                    LyricLine(
                                        lineIndex = currentLineIndex++,
                                        startMs = pStartMs,
                                        endMs = resolvedEndMs,
                                        mainText = simplifiedMain,
                                        translationText = simplifiedTrans,
                                        words = wordsList
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (lines.isEmpty()) return null

            val totalDurationMs = lines.lastOrNull()?.endMs ?: 0L
            return LyricTimeline(
                source = sourceName,
                format = LyricFormat.TTML_SYLLABLE,
                isInstrumental = false,
                lines = lines,
                durationMs = totalDurationMs
            )
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 TTML 歌词异常", e)
            return null
        }
    }

    private fun getAttr(parser: XmlPullParser, attrName: String): String? {
        val count = parser.attributeCount
        for (i in 0 until count) {
            val name = parser.getAttributeName(i).substringAfter(':')
            if (name.equals(attrName, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    /**
     * 💡 解析 TTML 时间戳：支持 [mm:ss.xxx]、[hh:mm:ss.xxx] 或纯秒数 [123.456s]
     */
    fun parseTimestampMs(timeStr: String): Long {
        val trimmed = timeStr.trim().removeSuffix("s")
        if (trimmed.isEmpty()) return 0L

        return try {
            val parts = trimmed.split(':')
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLongOrNull() ?: 0L
                    val m = parts[1].toLongOrNull() ?: 0L
                    val sSec = parts[2].toDoubleOrNull() ?: 0.0
                    (h * 3600 * 1000L) + (m * 60 * 1000L) + (sSec * 1000L).toLong()
                }
                2 -> {
                    val m = parts[0].toLongOrNull() ?: 0L
                    val sSec = parts[1].toDoubleOrNull() ?: 0.0
                    (m * 60 * 1000L) + (sSec * 1000L).toLong()
                }
                1 -> {
                    val sSec = parts[0].toDoubleOrNull() ?: 0.0
                    (sSec * 1000L).toLong()
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
