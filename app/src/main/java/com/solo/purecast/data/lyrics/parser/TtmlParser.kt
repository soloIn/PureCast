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
 * 3. 保留英文/西文 <span> 之间的真实空格（杜绝单词粘连），同时过滤纯空白 <span> 防止行尾钳位回闪；
 * 4. 提取原词与翻译，自动过滤所有背景人声 (tt:role="bg") 与音译 (tt:role="roman")；
 * 5. 0 崩溃容错，任何畸形或非标 XML 均能安全降级。
 */
object TtmlParser {

    private const val TAG = "TtmlParser"

    data class TtmlParseResult(
        val timeline: LyricTimeline,
        val metadataTitle: String = "",
        val metadataArtists: List<String> = emptyList()
    ) {
        val metadataArtist: String
            get() = metadataArtists.joinToString(" ")
    }

    private data class SpanContext(
        val role: String,
        val startMs: Long,
        val endMs: Long,
        val isIgnored: Boolean,
        val isTranslation: Boolean
    )

    fun parse(xmlContent: String, sourceName: String = "AMLL"): LyricTimeline? =
        parseWithMetadata(xmlContent, sourceName)?.timeline

    fun parseWithMetadata(xmlContent: String, sourceName: String = "AMLL"): TtmlParseResult? {
        if (xmlContent.isBlank()) return null

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            val lines = mutableListOf<LyricLine>()
            var metadataTitle = ""
            val metadataArtists = mutableListOf<String>()
            var eventType = parser.eventType

            var currentLineIndex = 0
            var inParagraph = false
            var pStartMs = 0L
            var pEndMs = 0L

            val currentWords = mutableListOf<LyricWord>()
            val currentMainTextBuilder = StringBuilder()
            val currentTranslationBuilder = StringBuilder()
            // 💡 记录两个 <span> 之间是否出现过纯水平空格或换行，确保英文单词保留空格且不给中文误插空格
            var pendingSpaceAfterPrevWord = false
            var pendingNewlineAfterPrevWord = false

            val spanStack = ArrayDeque<SpanContext>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val rawTagName = parser.name ?: ""
                        val localTagName = rawTagName.substringAfter(':').lowercase()

                        if (localTagName == "meta") {
                            val metaKey = getAttr(parser, "key")?.trim().orEmpty()
                            val metaValue = getAttr(parser, "value")?.trim().orEmpty()
                            if (metaValue.isNotEmpty()) {
                                when (metaKey) {
                                    "musicName" -> if (metadataTitle.isEmpty()) metadataTitle = metaValue
                                    "artists" -> metadataArtists.add(metaValue)
                                }
                            }
                        } else if (localTagName == "p") {
                            inParagraph = true
                            spanStack.clear()
                            val beginAttr = getAttr(parser, "begin")
                            val endAttr = getAttr(parser, "end")
                            pStartMs = if (beginAttr != null) parseTimestampMs(beginAttr) else 0L
                            pEndMs = if (endAttr != null) parseTimestampMs(endAttr) else 0L

                            currentWords.clear()
                            currentMainTextBuilder.setLength(0)
                            currentTranslationBuilder.setLength(0)
                            pendingSpaceAfterPrevWord = false
                            pendingNewlineAfterPrevWord = false
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
                                    // p 标签直属文本：
                                    // 1) 若是 <span> 之间的水平空格（如 " "），标记为待补空格，附着到前一个词尾；
                                    // 2) 若包含换行符（XML 缩进），仅在相邻两词为拉丁/数字词时补空格；
                                    // 3) 若含非空白字符，直接拼接。
                                    if (text.isNotBlank()) {
                                        currentMainTextBuilder.append(text.trim())
                                    } else if (currentWords.isNotEmpty()) {
                                        if (text.contains('\n') || text.contains('\r')) {
                                            pendingNewlineAfterPrevWord = true
                                        } else {
                                            pendingSpaceAfterPrevWord = true
                                        }
                                    }
                                } else if (!currentCtx.isIgnored) {
                                    if (currentCtx.isTranslation) {
                                        currentTranslationBuilder.append(text)
                                    } else if (text.isBlank()) {
                                        // 纯空白 <span> 不作为独立 LyricWord 入队（防止尾随空白词触发行尾钳位回闪），
                                        // 若不含换行则作为词间空格标记保留
                                        if (currentWords.isNotEmpty() && !text.contains('\n') && !text.contains('\r')) {
                                            pendingSpaceAfterPrevWord = true
                                        }
                                    } else {
                                        if (currentWords.isNotEmpty()) {
                                            val prev = currentWords.last()
                                            val needSpace = (pendingSpaceAfterPrevWord ||
                                                (pendingNewlineAfterPrevWord && needsLatinWordSpace(prev.text, text))) &&
                                                !prev.text.endsWith(" ") &&
                                                !text.startsWith(" ")
                                            if (needSpace) {
                                                currentWords[currentWords.lastIndex] = prev.copy(text = prev.text + " ")
                                                currentMainTextBuilder.append(' ')
                                            }
                                        }
                                        pendingSpaceAfterPrevWord = false
                                        pendingNewlineAfterPrevWord = false

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
                            pendingSpaceAfterPrevWord = false
                            pendingNewlineAfterPrevWord = false

                            // 去除首词前导空格与末词尾随空格，保证 mainText 与 words 字符边界完全一致
                            if (currentWords.isNotEmpty()) {
                                val firstWord = currentWords.first()
                                val trimmedFirst = firstWord.text.trimStart()
                                if (trimmedFirst.isEmpty()) {
                                    currentWords.removeAt(0)
                                } else if (trimmedFirst != firstWord.text) {
                                    currentWords[0] = firstWord.copy(text = trimmedFirst)
                                }
                            }
                            if (currentWords.isNotEmpty()) {
                                val lastWord = currentWords.last()
                                val trimmedLast = lastWord.text.trimEnd()
                                if (trimmedLast.isEmpty()) {
                                    currentWords.removeAt(currentWords.lastIndex)
                                } else if (trimmedLast != lastWord.text) {
                                    currentWords[currentWords.lastIndex] = lastWord.copy(text = trimmedLast)
                                }
                            }

                            val mainText = if (currentWords.isNotEmpty()) {
                                currentWords.joinToString("") { it.text }.trim()
                            } else {
                                currentMainTextBuilder.toString().trim()
                            }
                            val translationText = currentTranslationBuilder.toString().trim().takeIf { it.isNotEmpty() }

                            // 💡 修复 Finding 6: 若原词为空但含翻译，以翻译作为主原词；若二者均为空则丢弃，杜绝生成空行
                            val resolvedMain = when {
                                mainText.isNotEmpty() -> mainText
                                translationText != null -> translationText
                                else -> ""
                            }
                            val resolvedTrans = if (mainText.isNotEmpty()) translationText else null

                            if (resolvedMain.isNotEmpty()) {
                                // 💡 逐字行优先以主唱末字 endMs 作为行结束时间，避免被已过滤的背景和声 (tt:role="bg") 拖尾拉长导致与后续行交叠回闪
                                val lastWordEndMs = currentWords.lastOrNull()?.endMs?.takeIf { it > pStartMs }
                                val resolvedEndMs = lastWordEndMs
                                    ?: pEndMs.takeIf { it > pStartMs }
                                    ?: (pStartMs + 3000L)

                                val simplifiedMain = ChineseConverter.toSimplified(resolvedMain)
                                val simplifiedTrans = resolvedTrans?.let { ChineseConverter.toSimplified(it) }
                                val wordsList = if (currentWords.isNotEmpty() && mainText.isNotEmpty()) {
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
            val timeline = LyricTimeline(
                source = sourceName,
                format = LyricFormat.TTML_SYLLABLE,
                isInstrumental = false,
                lines = lines,
                durationMs = totalDurationMs
            )
            return TtmlParseResult(
                timeline = timeline,
                metadataTitle = metadataTitle,
                metadataArtists = metadataArtists
            )
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 TTML 歌词异常", e)
            return null
        }
    }

    private fun needsLatinWordSpace(prevText: String, nextText: String): Boolean {
        val lastChar = prevText.trimEnd().lastOrNull() ?: return false
        val firstChar = nextText.trimStart().firstOrNull() ?: return false
        val prevIsLatinOrPunct = lastChar.code in 0x21..0x7E && lastChar != '\'' && lastChar != '-' && lastChar != '('
        val nextIsLatinWord = firstChar.code in 0x30..0x39 ||
            firstChar.code in 0x41..0x5A ||
            firstChar.code in 0x61..0x7A
        return prevIsLatinOrPunct && nextIsLatinWord
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
