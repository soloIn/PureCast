package com.solo.purecast.data.lyrics.parser

import com.solo.purecast.data.lyrics.ChineseConverter
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import java.util.regex.Pattern

/**
 * 💡 标准与增强逐字 (Enhanced LRC) 歌词高容错解析器
 * 1. 修复双行同时间戳对照翻译：自动将第 2 行译文合并至第 1 行 translationText，杜绝原词被压扁成 0.5 秒；
 * 2. 修复空时间标签 [01:30.00]（间奏结束符）：作为前一行的 endMs，绝不生成空白歌词行；
 * 3. 修复多时间戳压缩行：自动对逐字时间戳按每行基准时间差精准平移，杜绝时戳错位；
 * 4. 提取 [length: mm:ss] 音轨时长标签，杜绝尾奏 >5s 被误杀淘汰；
 * 5. 全面支持多格式时间戳与 offset 偏移。
 */
object LrcParser {

    private const val TAG = "LrcParser"

    private val TIME_TAG_REGEX = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]")
    private val WORD_TAG_REGEX = Pattern.compile("<(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?>([^<]*)")
    private val META_TAG_REGEX = Pattern.compile("\\[([a-zA-Z]+):([^\\]]*)\\]")

    private data class RawLrcLine(
        val startMs: Long,
        val content: String,
        val originalTimestampMs: Long
    )

    fun parse(
        lrcContent: String,
        sourceName: String = "LRC",
        expectedDurationMs: Long = 0L
    ): LyricTimeline? {
        val trimmedContent = lrcContent.trim()
        if (trimmedContent.isEmpty() || trimmedContent.equals("null", ignoreCase = true)) return null

        try {
            var offsetMs = 0L
            var metaLengthMs = 0L
            val rawEntries = mutableListOf<RawLrcLine>()
            var hasWordLevelSync = false

            val lines = trimmedContent.lines()
            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) continue

                // 1. 解析元数据标签
                val metaMatcher = META_TAG_REGEX.matcher(trimmed)
                if (metaMatcher.matches()) {
                    val key = metaMatcher.group(1)?.lowercase() ?: ""
                    val value = metaMatcher.group(2)?.trim() ?: ""
                    when (key) {
                        "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                        "length" -> metaLengthMs = parseDurationString(value)
                    }
                    continue
                }

                // 2. 匹配行首的一个或多个时间戳
                val timeMatcher = TIME_TAG_REGEX.matcher(trimmed)
                val timestamps = mutableListOf<Long>()
                var lastEnd = 0

                while (timeMatcher.find()) {
                    val min = timeMatcher.group(1)?.toLongOrNull() ?: 0L
                    val sec = timeMatcher.group(2)?.toLongOrNull() ?: 0L
                    val frac = parseFractionMs(timeMatcher.group(3))
                    val timeMs = (min * 60 * 1000L) + (sec * 1000L) + frac
                    timestamps.add(timeMs)
                    lastEnd = timeMatcher.end()
                }

                if (timestamps.isEmpty()) continue

                val content = trimmed.substring(lastEnd).trim()
                if (content.equals("null", ignoreCase = true)) continue

                if (content.contains("<") && content.contains(">")) {
                    hasWordLevelSync = true
                }

                val baseTimestamp = timestamps.first()
                for (ts in timestamps) {
                    rawEntries.add(
                        RawLrcLine(
                            startMs = ts + offsetMs,
                            content = content,
                            originalTimestampMs = baseTimestamp
                        )
                    )
                }
            }

            if (rawEntries.isEmpty()) {
                // 💡 瀑布流分级调度优化：彻底屏蔽无时间戳纯文本歌词。大屏滚动与逐字高亮必须依赖时间戳，无时间戳歌词无渲染价值，直接返回 null
                return null
            }

            // 3. 按时间戳升序排序
            rawEntries.sortBy { it.startMs }

            // 4. 处理空时间标签（间奏结束符）与连续同时间戳对照翻译合并
            val consolidatedLines = mutableListOf<LyricLine>()
            var currentLineIndex = 0

            var i = 0
            while (i < rawEntries.size) {
                val curr = rawEntries[i]

                // 若该行内容为空且紧随上一行有效歌词，则其为间奏结束符/上一行真实结束点
                if (curr.content.isEmpty()) {
                    if (consolidatedLines.isNotEmpty()) {
                        val last = consolidatedLines.last()
                        if (curr.startMs > last.startMs) {
                            consolidatedLines[consolidatedLines.size - 1] = last.copy(endMs = curr.startMs)
                        }
                    }
                    i++
                    continue
                }

                var mainText = curr.content
                var translationText: String? = null
                var words: List<LyricWord>? = null

                // 检查下一行是否为完全相同时间戳（或时差 <= 50ms）的双语翻译行
                if (i + 1 < rawEntries.size) {
                    val next = rawEntries[i + 1]
                    if (Math.abs(next.startMs - curr.startMs) <= 50L && next.content.isNotEmpty()) {
                        translationText = next.content
                        i++ // 跳过已被合并的翻译行
                    }
                }

                // 确定该行的合理结束时间
                var nextValidStart: Long? = null
                for (k in (i + 1) until rawEntries.size) {
                    val future = rawEntries[k]
                    if (future.startMs > curr.startMs) {
                        nextValidStart = future.startMs
                        break
                    }
                }
                val endMs = nextValidStart ?: (curr.startMs + 5000L)

                // 5. 若包含逐字内联打点，进行音节时间戳解析并应用展开行的位移补偿 (Finding 16)
                val wordMatcher = WORD_TAG_REGEX.matcher(mainText)
                val wordMatches = mutableListOf<java.util.regex.MatchResult>()
                while (wordMatcher.find()) {
                    wordMatches.add(wordMatcher.toMatchResult())
                }

                if (wordMatches.isNotEmpty()) {
                    val wordList = mutableListOf<LyricWord>()
                    val textBuilder = StringBuilder()
                    val lineTimeDelta = curr.startMs - (curr.originalTimestampMs + offsetMs)

                    // 💡 修复 Finding 2: 捕获首个逐字时间标签前的引导原词 (如 [01:23.45]Hello <01:24.00>world)
                    val firstMatchStart = wordMatches.first().start()
                    if (firstMatchStart > 0) {
                        val leadingText = mainText.substring(0, firstMatchStart)
                        val firstWm = wordMatches.first()
                        val fwMin = firstWm.group(1)?.toLongOrNull() ?: 0L
                        val fwSec = firstWm.group(2)?.toLongOrNull() ?: 0L
                        val fwFrac = parseFractionMs(firstWm.group(3))
                        val firstWordStartMs = (fwMin * 60 * 1000L) + (fwSec * 1000L) + fwFrac + offsetMs + lineTimeDelta

                        if (leadingText.isNotBlank()) {
                            wordList.add(
                                LyricWord(
                                    text = leadingText,
                                    startMs = curr.startMs,
                                    endMs = firstWordStartMs.coerceAtLeast(curr.startMs)
                                )
                            )
                        }
                        textBuilder.append(leadingText)
                    }

                    for (j in wordMatches.indices) {
                        val wm = wordMatches[j]
                        val wMin = wm.group(1)?.toLongOrNull() ?: 0L
                        val wSec = wm.group(2)?.toLongOrNull() ?: 0L
                        val wFrac = parseFractionMs(wm.group(3))
                        val baseWordStartMs = (wMin * 60 * 1000L) + (wSec * 1000L) + wFrac + offsetMs
                        val wStartMs = baseWordStartMs + lineTimeDelta
                        val wText = wm.group(4) ?: ""

                        val wNextStart = if (j + 1 < wordMatches.size) {
                            val nextWm = wordMatches[j + 1]
                            val nwMin = nextWm.group(1)?.toLongOrNull() ?: 0L
                            val nwSec = nextWm.group(2)?.toLongOrNull() ?: 0L
                            val nwFrac = parseFractionMs(nextWm.group(3))
                            val baseNext = (nwMin * 60 * 1000L) + (nwSec * 1000L) + nwFrac + offsetMs
                            baseNext + lineTimeDelta
                        } else {
                            endMs
                        }

                        if (wText.isNotEmpty()) {
                            wordList.add(
                                LyricWord(
                                    text = wText,
                                    startMs = wStartMs.coerceAtLeast(0L),
                                    endMs = wNextStart.coerceAtLeast(wStartMs)
                                )
                            )
                            textBuilder.append(wText)
                        }
                    }

                    if (wordList.isNotEmpty()) {
                        words = wordList
                        mainText = textBuilder.toString()
                    }
                }

                val simplifiedMain = ChineseConverter.toSimplified(mainText)
                val simplifiedTrans = translationText?.let { ChineseConverter.toSimplified(it) }
                val simplifiedWords = words?.map { w ->
                    w.copy(text = ChineseConverter.toSimplified(w.text))
                }

                consolidatedLines.add(
                    LyricLine(
                        lineIndex = currentLineIndex++,
                        startMs = curr.startMs,
                        endMs = endMs,
                        mainText = simplifiedMain,
                        translationText = simplifiedTrans,
                        words = simplifiedWords
                    )
                )

                i++
            }

            if (consolidatedLines.isEmpty()) return null

            val format = if (hasWordLevelSync) LyricFormat.LRC_WORD_SYNC else LyricFormat.LRC_LINE_SYNC
            val resolvedDuration = when {
                metaLengthMs > 0L -> metaLengthMs
                expectedDurationMs > 0L -> expectedDurationMs
                else -> consolidatedLines.lastOrNull()?.endMs ?: 0L
            }

            return LyricTimeline(
                source = sourceName,
                format = format,
                isInstrumental = false,
                lines = consolidatedLines,
                durationMs = resolvedDuration
            )
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 LRC 歌词异常", e)
            return null
        }
    }

    private fun parseFractionMs(fracStr: String?): Long {
        if (fracStr.isNullOrEmpty()) return 0L
        return when (fracStr.length) {
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            3 -> fracStr.toLongOrNull() ?: 0L
            else -> fracStr.substring(0, 3).toLongOrNull() ?: 0L
        }
    }

    private fun parseDurationString(str: String): Long {
        val parts = str.trim().split(':')
        return try {
            when (parts.size) {
                2 -> {
                    val m = parts[0].toLongOrNull() ?: 0L
                    val s = parts[1].toLongOrNull() ?: 0L
                    (m * 60 + s) * 1000L
                }
                1 -> (parts[0].toLongOrNull() ?: 0L) * 1000L
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
