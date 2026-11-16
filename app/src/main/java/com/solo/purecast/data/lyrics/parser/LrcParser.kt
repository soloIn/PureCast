package com.solo.purecast.data.lyrics.parser

import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger

/**
 * 💡 标准与逐字 LRC 歌词解析器
 * 支持多重时戳标签 [mm:ss.xx]、逐字内联打点 <mm:ss.xx> 及 [offset:xxx] 偏移修正。
 */
object LrcParser {

    private const val TAG = "LrcParser"

    private val TIME_TAG_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val WORD_TAG_REGEX = Regex("""<(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?>([^<]*)""")
    private val OFFSET_TAG_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    fun parse(lrcContent: String, sourceName: String = "LRCLIB"): LyricTimeline? {
        if (lrcContent.isBlank()) return null

        try {
            var offsetMs = 0L
            val rawLines = lrcContent.lines()

            // 1. 扫描 offset 标签
            for (line in rawLines) {
                val match = OFFSET_TAG_REGEX.find(line)
                if (match != null) {
                    offsetMs = match.groupValues[1].toLongOrNull() ?: 0L
                    break
                }
            }

            data class TempParsedLine(
                val startMs: Long,
                val rawText: String
            )

            val tempLines = mutableListOf<TempParsedLine>()
            var hasWordLevelSync = false

            // 2. 解析每行的时间标签
            for (line in rawLines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val timeMatches = TIME_TAG_REGEX.findAll(trimmed).toList()
                if (timeMatches.isEmpty()) continue

                // 移除全部时间标签后剩下的文本内容
                val textContent = trimmed.replace(TIME_TAG_REGEX, "").trim()

                for (match in timeMatches) {
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val sec = match.groupValues[2].toLongOrNull() ?: 0L
                    val fractionStr = match.groupValues[3]
                    val fractionMs = parseFractionMs(fractionStr)

                    val startMs = (min * 60 * 1000L) + (sec * 1000L) + fractionMs + offsetMs
                    tempLines.add(TempParsedLine(startMs.coerceAtLeast(0L), textContent))
                }
            }

            if (tempLines.isEmpty()) {
                // 💡 若无时间标签，降级为纯文本模式
                val plainLines = rawLines.filter { it.isNotBlank() && !it.startsWith("[") }
                if (plainLines.isNotEmpty()) {
                    val resultLines = plainLines.mapIndexed { index, text ->
                        LyricLine(
                            lineIndex = index,
                            startMs = index * 4000L,
                            endMs = (index + 1) * 4000L,
                            mainText = text
                        )
                    }
                    return LyricTimeline(
                        source = sourceName,
                        format = LyricFormat.PLAIN_TEXT,
                        isInstrumental = false,
                        lines = resultLines,
                        durationMs = resultLines.last().endMs
                    )
                }
                return null
            }

            // 按时间排序
            tempLines.sortBy { it.startMs }

            val parsedLines = mutableListOf<LyricLine>()

            for (i in tempLines.indices) {
                val curr = tempLines[i]
                val nextStartMs = if (i + 1 < tempLines.size) tempLines[i + 1].startMs else curr.startMs + 4000L
                val endMs = nextStartMs.coerceAtLeast(curr.startMs + 500L)

                // 检查是否包含逐字打点 <mm:ss.xx>word
                val wordMatches = WORD_TAG_REGEX.findAll(curr.rawText).toList()
                var words: List<LyricWord>? = null
                var mainText = curr.rawText

                if (wordMatches.isNotEmpty()) {
                    hasWordLevelSync = true
                    val wordList = mutableListOf<LyricWord>()
                    val textBuilder = StringBuilder()

                    for (j in wordMatches.indices) {
                        val wm = wordMatches[j]
                        val wMin = wm.groupValues[1].toLongOrNull() ?: 0L
                        val wSec = wm.groupValues[2].toLongOrNull() ?: 0L
                        val wFrac = parseFractionMs(wm.groupValues[3])
                        val wStartMs = (wMin * 60 * 1000L) + (wSec * 1000L) + wFrac + offsetMs
                        val wText = wm.groupValues[4]

                        val wNextStart = if (j + 1 < wordMatches.size) {
                            val nextWm = wordMatches[j + 1]
                            val nwMin = nextWm.groupValues[1].toLongOrNull() ?: 0L
                            val nwSec = nextWm.groupValues[2].toLongOrNull() ?: 0L
                            val nwFrac = parseFractionMs(nextWm.groupValues[3])
                            (nwMin * 60 * 1000L) + (nwSec * 1000L) + nwFrac + offsetMs
                        } else {
                            endMs
                        }

                        if (wText.isNotEmpty()) {
                            wordList.add(LyricWord(wText, wStartMs.coerceAtLeast(0L), wNextStart.coerceAtLeast(wStartMs)))
                            textBuilder.append(wText)
                        }
                    }

                    if (wordList.isNotEmpty()) {
                        words = wordList
                        mainText = textBuilder.toString()
                    }
                }

                parsedLines.add(
                    LyricLine(
                        lineIndex = i,
                        startMs = curr.startMs,
                        endMs = endMs,
                        mainText = mainText,
                        translationText = null,
                        words = words
                    )
                )
            }

            val format = if (hasWordLevelSync) LyricFormat.LRC_WORD_SYNC else LyricFormat.LRC_LINE_SYNC
            val duration = parsedLines.lastOrNull()?.endMs ?: 0L

            return LyricTimeline(
                source = sourceName,
                format = format,
                isInstrumental = false,
                lines = parsedLines,
                durationMs = duration
            )
        } catch (e: Exception) {
            Logger.lyrics.e(TAG, "解析 LRC 失败", e)
            return null
        }
    }

    private fun parseFractionMs(fractionStr: String?): Long {
        if (fractionStr.isNullOrEmpty()) return 0L
        return when (fractionStr.length) {
            1 -> fractionStr.toLong() * 100L
            2 -> fractionStr.toLong() * 10L
            3 -> fractionStr.toLong()
            else -> fractionStr.substring(0, 3).toLong()
        }
    }
}
