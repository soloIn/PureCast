package com.solo.purecast.data.lyrics.parser

import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 💡 AMLL TTML 高精逐字歌词解析器
 * 基于 XmlPullParser 流式解析音节级（逐字）时间戳与翻译：
 * 严格按照用户决策：仅保留原词与翻译，过滤背景音 (tt:role="bg") 与罗马音 (tt:role="roman")。
 */
object TtmlParser {

    private const val TAG = "TtmlParser"

    fun parse(xmlContent: String, sourceName: String = "AMLL"): LyricTimeline? {
        if (xmlContent.isBlank()) return null

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false // 💡 关闭命名空间敏感，直接按 tag 和属性字面量匹配
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
            var currentTranslationText: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val rawTagName = parser.name ?: ""
                        val localTagName = rawTagName.substringAfter(':').lowercase()

                        if (localTagName == "p") {
                            inParagraph = true
                            val beginAttr = getAttr(parser, "begin")
                            val endAttr = getAttr(parser, "end")
                            pStartMs = if (beginAttr != null) parseTimestampMs(beginAttr) else 0L
                            pEndMs = if (endAttr != null) parseTimestampMs(endAttr) else 0L

                            currentWords.clear()
                            currentMainTextBuilder.setLength(0)
                            currentTranslationText = null
                        } else if (localTagName == "span" && inParagraph) {
                            val role = getAttr(parser, "role") ?: ""
                            val spanBegin = getAttr(parser, "begin")
                            val spanEnd = getAttr(parser, "end")

                            val spanStartMs = if (spanBegin != null) parseTimestampMs(spanBegin) else pStartMs
                            val spanEndMs = if (spanEnd != null) parseTimestampMs(spanEnd) else pEndMs

                            val text = parser.nextText() ?: ""

                            // 💡 决策过滤：剔除 bg 和 roman 节点
                            when {
                                role.contains("translation", ignoreCase = true) -> {
                                    currentTranslationText = text.trim()
                                }
                                role.contains("roman", ignoreCase = true) || role.contains("bg", ignoreCase = true) -> {
                                    // 过滤不采纳
                                }
                                else -> {
                                    if (text.isNotEmpty()) {
                                        currentWords.add(LyricWord(text = text, startMs = spanStartMs, endMs = spanEndMs))
                                        currentMainTextBuilder.append(text)
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val rawTagName = parser.name ?: ""
                        val localTagName = rawTagName.substringAfter(':').lowercase()

                        if (localTagName == "p" && inParagraph) {
                            inParagraph = false
                            val mainText = currentMainTextBuilder.toString().trim()
                            if (mainText.isNotEmpty() || !currentTranslationText.isNullOrEmpty()) {
                                // 如果没有逐字音节，以整行作为单字或空 words
                                val wordsList = if (currentWords.isNotEmpty()) currentWords.toList() else null

                                // 边界自愈：若 pEndMs 为 0，取最后一个音节的 endMs 或默认 +3000ms
                                val resolvedEndMs = if (pEndMs > pStartMs) {
                                    pEndMs
                                } else {
                                    currentWords.lastOrNull()?.endMs?.takeIf { it > pStartMs } ?: (pStartMs + 3000L)
                                }

                                lines.add(
                                    LyricLine(
                                        lineIndex = currentLineIndex++,
                                        startMs = pStartMs,
                                        endMs = resolvedEndMs,
                                        mainText = mainText,
                                        translationText = currentTranslationText,
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

            // 按时间排序
            lines.sortBy { it.startMs }

            val maxDuration = lines.maxOfOrNull { it.endMs } ?: 0L

            return LyricTimeline(
                source = sourceName,
                format = LyricFormat.TTML_SYLLABLE,
                isInstrumental = false,
                lines = lines,
                durationMs = maxDuration
            )
        } catch (e: Exception) {
            Logger.lyrics.e(TAG, "解析 TTML 失败", e)
            return null
        }
    }

    private fun getAttr(parser: XmlPullParser, attrName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i).substringAfter(':')
            if (name.equals(attrName, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    /**
     * 💡 解析 TTML 时间戳字符串为毫秒数值
     * 支持: "01:23.456", "00:01:23.456", "12.345s", "12.345"
     */
    fun parseTimestampMs(raw: String): Long {
        val s = raw.trim().removeSuffix("s")
        val parts = s.split(":")
        return try {
            when (parts.size) {
                1 -> {
                    val sec = parts[0].toDoubleOrNull() ?: 0.0
                    (sec * 1000).toLong()
                }
                2 -> {
                    val min = parts[0].toLongOrNull() ?: 0L
                    val sec = parts[1].toDoubleOrNull() ?: 0.0
                    (min * 60 * 1000L) + (sec * 1000).toLong()
                }
                3 -> {
                    val hour = parts[0].toLongOrNull() ?: 0L
                    val min = parts[1].toLongOrNull() ?: 0L
                    val sec = parts[2].toDoubleOrNull() ?: 0.0
                    (hour * 3600 * 1000L) + (min * 60 * 1000L) + (sec * 1000).toLong()
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
