package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.data.lyrics.ChineseConverter
import com.solo.purecast.data.lyrics.MetadataNormalizer
import com.solo.purecast.data.lyrics.parser.LrcParser
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * 💡 网易云公开音乐歌词与简体中文逐句翻译数据源 (NeteaseDataSource)
 * 专为满足“以简体中文为主的歌词及双语翻译”设计：
 * 1. 免鉴权直接访问网易云海量高精翻译库 (tlyric)；
 * 2. 具备逐句时间戳对齐能力，可为 AMLL TTML 及 LRCLIB 缺失翻译的外文歌精准补齐简体中文译文；
 * 3. 自动将繁体中文歌词与翻译归一化为规范简体中文。
 */
class NeteaseDataSource(
    private val httpClient: LyricsHttpClient
) {

    /**
     * 💡 检索网易云完整歌词（包含原文与简体中文逐句翻译）
     */
    suspend fun fetch(artist: String, title: String, durationMs: Long): LyricTimeline? {
        val songIds = searchCandidateSongIds(artist, title, durationMs)
        if (songIds.isEmpty()) return null

        for (songId in songIds) {
            val timeline = fetchLyricsBySongId(songId, durationMs)
            if (timeline != null && timeline.lines.isNotEmpty()) {
                return timeline
            }
        }
        return null
    }

    /**
     * 💡 检索纯简体中文逐句翻译时间轴 (时间戳 -> 译文)，用于为已有歌词补齐翻译
     */
    suspend fun fetchTranslationTimeline(artist: String, title: String, durationMs: Long): List<Pair<Long, String>>? {
        val songIds = searchCandidateSongIds(artist, title, durationMs)
        if (songIds.isEmpty()) return null

        for (songId in songIds) {
            val url = "$LYRIC_API_URL?id=$songId&lv=1&kv=1&tv=1"
            try {
                val resp = httpClient.get(url, timeoutMs = TIMEOUT_MS)
                if (!resp.isSuccessful || resp.body.isBlank()) continue

                val root = JSONObject(resp.body)
                val tlyricStr = root.optJSONObject("tlyric")?.optString("lyric") ?: continue
                if (tlyricStr.isBlank()) continue

                val lines = parseTranslationLines(tlyricStr)
                if (lines.isNotEmpty()) {
                    Logger.lyrics.d(TAG, "从网易云歌曲 id=$songId 成功解析到 ${lines.size} 行简体中文翻译")
                    return lines
                }
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "拉取网易云翻译歌词异常: id=$songId", e)
            }
        }
        return null
    }

    private suspend fun searchCandidateSongIds(artist: String, title: String, durationMs: Long): List<Long> {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)
        if (cleanTitle.isBlank() && cleanArtist.isBlank()) return emptyList()

        val queries = mutableListOf<String>()
        if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) {
            queries.add("$cleanTitle $cleanArtist")
        }
        if (cleanTitle.isNotBlank()) {
            queries.add(cleanTitle)
        }

        for (query in queries) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$SEARCH_API_URL?s=$encodedQuery&type=1&limit=8"

            try {
                val resp = httpClient.get(searchUrl, timeoutMs = TIMEOUT_MS)
                if (!resp.isSuccessful || resp.body.isBlank()) continue

                val root = JSONObject(resp.body)
                val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: continue
                if (songs.length() == 0) continue

                val candidateList = mutableListOf<Pair<Long, Long>>() // id to durationDiff

                for (i in 0 until songs.length()) {
                    val song = songs.getJSONObject(i)
                    val id = song.optLong("id")
                    val songDuration = song.optLong("duration")

                    val diff = if (durationMs > 0L && songDuration > 0L) {
                        abs(songDuration - durationMs)
                    } else {
                        1000L * i // 保持原本顺序
                    }
                    candidateList.add(id to diff)
                }

                // 优先选择时长最接近的（容差 8s 内），其次按原本排名
                val sortedIds = candidateList.sortedWith(compareBy<Pair<Long, Long>> { (id, diff) ->
                    if (durationMs > 0L && diff <= 8000L) 0 else 1
                }.thenBy { it.second }).map { it.first }

                if (sortedIds.isNotEmpty()) return sortedIds
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "检索网易云歌曲异常: $query", e)
            }
        }
        return emptyList()
    }

    private suspend fun fetchLyricsBySongId(songId: Long, durationMs: Long): LyricTimeline? {
        val url = "$LYRIC_API_URL?id=$songId&lv=1&kv=1&tv=1"

        return try {
            val resp = httpClient.get(url, timeoutMs = TIMEOUT_MS)
            if (!resp.isSuccessful || resp.body.isBlank()) return null

            val root = JSONObject(resp.body)
            val lrcStr = root.optJSONObject("lrc")?.optString("lyric")?.trim() ?: ""
            val tlyricStr = root.optJSONObject("tlyric")?.optString("lyric")?.trim() ?: ""

            if (lrcStr.isBlank() || lrcStr.equals("null", ignoreCase = true)) return null

            val baseTimeline = LrcParser.parse(lrcStr, sourceName = "Netease", expectedDurationMs = durationMs) ?: return null

            // 若存在网易云逐句中文翻译，对齐注入到每行歌词中
            if (tlyricStr.isNotBlank()) {
                val translationLines = parseTranslationLines(tlyricStr)
                if (translationLines.isNotEmpty()) {
                    val enrichedLines = baseTimeline.lines.map { line ->
                        val matchedTrans = findBestMatchingTranslation(line.startMs, translationLines)
                        if (matchedTrans != null) {
                            line.copy(translationText = ChineseConverter.toSimplified(matchedTrans))
                        } else {
                            line
                        }
                    }
                    return baseTimeline.copy(lines = enrichedLines)
                }
            }

            baseTimeline
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "拉取网易云歌词内容异常: id=$songId", e)
            null
        }
    }

    /**
     * 💡 解析翻译歌词文本为时间戳与译文对列表
     */
    private fun parseTranslationLines(tlyricStr: String): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        val lines = tlyricStr.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val match = TIME_REGEX.find(trimmed) ?: continue
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val fracStr = match.groupValues[3]
            val frac = when (fracStr.length) {
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                3 -> fracStr.toLongOrNull() ?: 0L
                else -> fracStr.take(3).toLongOrNull() ?: 0L
            }
            val timeMs = min * 60_000L + sec * 1_000L + frac
            val text = trimmed.substring(match.range.last + 1).trim()
            val isMeta = text.startsWith("by:") || text.startsWith("作词") || text.startsWith("作曲") ||
                    text.startsWith("编曲") || text.startsWith("制作") || text.startsWith("混音") ||
                    text.startsWith("吉他") || text.startsWith("贝斯") || text.startsWith("鼓") ||
                    text.startsWith("键盘") || text.startsWith("录音") || text.startsWith("母带")
            if (text.isNotEmpty() && !isMeta && !text.equals("null", ignoreCase = true)) {
                result.add(timeMs to ChineseConverter.toSimplified(text))
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * 💡 根据主歌词行的起始时间在翻译列表中模糊匹配最佳对齐行 (容差 2.0s)
     */
    fun findBestMatchingTranslation(lineStartMs: Long, translationLines: List<Pair<Long, String>>): String? {
        if (translationLines.isEmpty()) return null
        var bestText: String? = null
        var minDelta = Long.MAX_VALUE

        for ((transTime, transText) in translationLines) {
            val delta = abs(transTime - lineStartMs)
            if (delta <= 2000L && delta < minDelta) {
                minDelta = delta
                bestText = transText
            }
        }
        return bestText
    }

    companion object {
        private const val TAG = "NeteaseDataSource"
        private const val SEARCH_API_URL = "https://music.163.com/api/search/get"
        private const val LYRIC_API_URL = "https://music.163.com/api/song/lyric"
        private const val TIMEOUT_MS = 2500
        private val TIME_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
    }
}
