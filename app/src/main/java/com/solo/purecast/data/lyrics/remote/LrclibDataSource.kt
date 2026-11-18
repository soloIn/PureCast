package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.data.lyrics.LyricCandidateScorer
import com.solo.purecast.data.lyrics.MetadataNormalizer
import com.solo.purecast.data.lyrics.parser.LrcParser
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 💡 LRCLIB 官方 RESTful 远端数据源
 * 遵循官方规范：支持 /api/get 精确多元匹配与 /api/search 候选打分决胜
 * 严格使用 RFC 3986 百分号编码 (空格转为 %20)。
 */
class LrclibDataSource(
    private val httpClient: LyricsHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    /**
     * 💡 精确匹配检索 (/api/get)
     * 支持 track_name, artist_name, album_name 及 duration 四元组精确匹配
     */
    suspend fun getExact(
        artist: String,
        title: String,
        album: String? = null,
        durationMs: Long = 0L
    ): LyricTimeline? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return null

        val encArtist = rfc3986Encode(cleanArtist)
        val encTitle = rfc3986Encode(cleanTitle)

        val queryParams = StringBuilder("track_name=$encTitle&artist_name=$encArtist")

        if (!album.isNullOrBlank()) {
            val cleanAlbum = MetadataNormalizer.normalizeTitle(album)
            if (cleanAlbum.isNotBlank()) {
                queryParams.append("&album_name=").append(rfc3986Encode(cleanAlbum))
            }
        }

        if (durationMs > 0L) {
            val durationSec = (durationMs / 1000L).toInt()
            queryParams.append("&duration=").append(durationSec)
        }

        val url = "$baseUrl/api/get?$queryParams"
        val resp = httpClient.get(url, timeoutMs = EXACT_TIMEOUT_MS)

        if (!resp.isSuccessful || resp.body.isBlank()) {
            Logger.lyrics.d(TAG, "LRCLIB /api/get 精确未命中: $cleanArtist - $cleanTitle (HTTP ${resp.statusCode})")
            return null
        }

        return parseSingleEntry(resp.body, cleanArtist, cleanTitle, durationMs)
    }

    /**
     * 💡 模糊搜索回退检索 (/api/search)
     * 支持精准参数 -> 复合全文 q -> 纯歌名 q 三级渐进决胜，评估前 10 条候选
     */
    suspend fun searchFuzzy(
        artist: String,
        title: String,
        album: String? = null,
        durationMs: Long = 0L
    ): LyricTimeline? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() && cleanTitle.isBlank()) return null

        // 1. 若歌手已知，优先使用精准参数组合搜索
        if (cleanArtist.isNotBlank()) {
            val url = "$baseUrl/api/search?track_name=${rfc3986Encode(cleanTitle)}&artist_name=${rfc3986Encode(cleanArtist)}"
            val hit = queryAndEvaluate(url, cleanArtist, cleanTitle, durationMs)
            if (hit != null) return hit
        }

        // 2. 联合全文 q 检索 ("title artist")
        val combinedQuery = "$cleanTitle $cleanArtist".trim()
        if (combinedQuery.isNotBlank()) {
            val url = "$baseUrl/api/search?q=${rfc3986Encode(combinedQuery)}"
            val hit = queryAndEvaluate(url, cleanArtist, cleanTitle, durationMs)
            if (hit != null) return hit
        }

        // 3. 纯歌名 q 兜底检索（依靠时长与标题相似度强约束过滤决胜）
        if (cleanTitle.isNotBlank()) {
            val url = "$baseUrl/api/search?q=${rfc3986Encode(cleanTitle)}"
            val hit = queryAndEvaluate(url, cleanArtist, cleanTitle, durationMs)
            if (hit != null) return hit
        }

        Logger.lyrics.d(TAG, "LRCLIB /api/search 全链路检索未找到合格候选: $cleanArtist - $cleanTitle")
        return null
    }

    private suspend fun queryAndEvaluate(
        url: String,
        cleanArtist: String,
        cleanTitle: String,
        durationMs: Long
    ): LyricTimeline? {
        val resp = httpClient.get(url, timeoutMs = SEARCH_TIMEOUT_MS)
        if (!resp.isSuccessful || resp.body.isBlank() || isEmptyArray(resp.body)) return null

        return try {
            val array = JSONArray(resp.body)
            if (array.length() == 0) return null

            var bestTimeline: LyricTimeline? = null
            var bestScore = 0.0f

            val evaluateCount = minOf(10, array.length())
            for (i in 0 until evaluateCount) {
                val item = array.getJSONObject(i)
                val candArtist = item.optCleanString("artistName") ?: ""
                val candTitle = item.optCleanString("trackName") ?: ""
                val candDurationSec = item.optDouble("duration", 0.0)
                val candDurationMs = (candDurationSec * 1000).toLong()
                val isInst = item.optBoolean("instrumental", false)
                val syncedLrc = item.optCleanString("syncedLyrics")
                val plainLrc = item.optCleanString("plainLyrics")

                // 💡 瀑布流分级择优调度：彻底屏蔽无时间戳纯文本歌词候选，不予打分采纳
                if (!isInst && syncedLrc.isNullOrBlank()) {
                    continue
                }

                val candFormat = when {
                    syncedLrc != null && syncedLrc.contains("<") -> LyricFormat.LRC_WORD_SYNC
                    syncedLrc != null && syncedLrc.isNotBlank() -> LyricFormat.LRC_LINE_SYNC
                    else -> LyricFormat.LRC_LINE_SYNC
                }

                val scoreResult = LyricCandidateScorer.evaluate(
                    expectedArtist = cleanArtist,
                    expectedTitle = cleanTitle,
                    expectedDurationMs = durationMs,
                    candidateArtist = candArtist,
                    candidateTitle = candTitle,
                    candidateDurationMs = candDurationMs,
                    candidateFormat = candFormat,
                    isInstrumental = isInst
                )

                if (scoreResult.accepted && scoreResult.totalScore > bestScore) {
                    val timeline = if (isInst) {
                        LyricTimeline(
                            source = "LRCLIB",
                            format = candFormat,
                            isInstrumental = true,
                            lines = emptyList(),
                            durationMs = candDurationMs
                        )
                    } else if (!syncedLrc.isNullOrBlank()) {
                        LrcParser.parse(syncedLrc, sourceName = "LRCLIB", expectedDurationMs = candDurationMs)
                    } else null

                    if (timeline != null) {
                        bestTimeline = timeline
                        bestScore = scoreResult.totalScore
                    }
                }
            }

            bestTimeline
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 LRCLIB 响应异常: url=$url", e)
            null
        }
    }

    private fun parseSingleEntry(
        jsonStr: String,
        cleanArtist: String,
        cleanTitle: String,
        expectedDurationMs: Long
    ): LyricTimeline? {
        return try {
            val json = JSONObject(jsonStr)
            val isInst = json.optBoolean("instrumental", false)
            val candDurationSec = json.optDouble("duration", 0.0)
            val candDurationMs = (candDurationSec * 1000).toLong()
            val syncedLrc = json.optCleanString("syncedLyrics")

            if (isInst) {
                return LyricTimeline(
                    source = "LRCLIB",
                    format = LyricFormat.LRC_LINE_SYNC,
                    isInstrumental = true,
                    lines = emptyList(),
                    durationMs = candDurationMs
                )
            }

            // 💡 严格要求有效时间戳歌词，纯文本直接返回 null 以便瀑布流继续降级至网易云或下一级
            if (syncedLrc.isNullOrBlank()) return null

            LrcParser.parse(syncedLrc, sourceName = "LRCLIB", expectedDurationMs = candDurationMs)
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 LRCLIB 条目异常", e)
            null
        }
    }

    private fun JSONObject.optCleanString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val v = optString(name, "")
        return if (v.equals("null", ignoreCase = true) || v.isBlank()) null else v
    }

    private fun isEmptyArray(jsonStr: String): Boolean {
        return try {
            val arr = JSONArray(jsonStr)
            arr.length() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun rfc3986Encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    companion object {
        private const val TAG = "LrclibDataSource"
        const val DEFAULT_BASE_URL = "https://lrclib.net"
        const val EXACT_TIMEOUT_MS = 1500
        const val SEARCH_TIMEOUT_MS = 2000
    }
}
