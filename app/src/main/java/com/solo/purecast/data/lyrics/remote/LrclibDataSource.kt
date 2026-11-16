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
 * 💡 LRCLIB 远端数据源
 * 遵循官方 RESTful API 范式：支持 /api/get 精确四元组匹配与 /api/search 模糊降级搜索
 */
class LrclibDataSource(
    private val httpClient: LyricsHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    /**
     * 💡 精确匹配检索 (/api/get)
     * 超时限制: 1.5s
     */
    suspend fun getExact(artist: String, title: String, durationMs: Long): LyricTimeline? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return null

        val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")

        val url = if (durationMs > 0) {
            val durationSec = (durationMs / 1000L).toInt()
            "$baseUrl/api/get?artist_name=$encodedArtist&track_name=$encodedTitle&duration=$durationSec"
        } else {
            "$baseUrl/api/get?artist_name=$encodedArtist&track_name=$encodedTitle"
        }

        val resp = httpClient.get(url, timeoutMs = EXACT_TIMEOUT_MS)
        if (!resp.isSuccessful || resp.body.isBlank()) {
            Logger.lyrics.d(TAG, "LRCLIB /api/get 精确未命中: $cleanArtist - $cleanTitle (HTTP ${resp.statusCode})")
            return null
        }

        return parseSingleEntry(resp.body, cleanArtist, cleanTitle, durationMs)
    }

    /**
     * 💡 模糊搜索回退检索 (/api/search)
     * 超时限制: 2.0s，取前 3 条候选执行多维打分决胜
     */
    suspend fun searchFuzzy(artist: String, title: String, durationMs: Long): LyricTimeline? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() && cleanTitle.isBlank()) return null

        val query = "$cleanTitle $cleanArtist".trim()
        val encodedQ = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/api/search?q=$encodedQ"

        val resp = httpClient.get(url, timeoutMs = SEARCH_TIMEOUT_MS)
        if (!resp.isSuccessful || resp.body.isBlank()) {
            Logger.lyrics.d(TAG, "LRCLIB /api/search 搜索失败: query=$query (HTTP ${resp.statusCode})")
            return null
        }

        try {
            val array = JSONArray(resp.body)
            if (array.length() == 0) return null

            var bestTimeline: LyricTimeline? = null
            var bestScore = 0.0f

            // 限制最多评审前 3 条候选
            val evaluateCount = minOf(3, array.length())
            for (i in 0 until evaluateCount) {
                val item = array.getJSONObject(i)
                val candArtist = item.optString("artistName", "")
                val candTitle = item.optString("trackName", "")
                val candDurationSec = item.optDouble("duration", 0.0)
                val candDurationMs = (candDurationSec * 1000).toLong()
                val isInst = item.optBoolean("instrumental", false)
                val syncedLrc = item.optString("syncedLyrics", "")
                val plainLrc = item.optString("plainLyrics", "")

                val candFormat = when {
                    syncedLrc.contains("<") -> LyricFormat.LRC_WORD_SYNC
                    syncedLrc.isNotBlank() -> LyricFormat.LRC_LINE_SYNC
                    plainLrc.isNotBlank() -> LyricFormat.PLAIN_TEXT
                    else -> LyricFormat.PLAIN_TEXT
                }

                val scoreResult = LyricCandidateScorer.evaluate(
                    expectedArtist = cleanArtist,
                    expectedTitle = cleanTitle,
                    expectedDurationMs = durationMs,
                    candidateArtist = candArtist,
                    candidateTitle = candTitle,
                    candidateDurationMs = candDurationMs,
                    candidateFormat = candFormat,
                    isInstrumentalReported = isInst
                )

                if (scoreResult.isPassed && scoreResult.totalScore > bestScore) {
                    val timeline = when {
                        isInst || scoreResult.isInstrumental -> {
                            LyricTimeline(source = "LRCLIB-search", format = LyricFormat.PLAIN_TEXT, isInstrumental = true)
                        }
                        syncedLrc.isNotBlank() -> {
                            LrcParser.parse(syncedLrc, sourceName = "LRCLIB-search")
                        }
                        plainLrc.isNotBlank() -> {
                            LrcParser.parse(plainLrc, sourceName = "LRCLIB-search")
                        }
                        else -> null
                    }

                    if (timeline != null) {
                        bestScore = scoreResult.totalScore
                        bestTimeline = timeline
                    }
                }
            }

            if (bestTimeline != null) {
                Logger.lyrics.i(TAG, "LRCLIB /api/search 命中最佳候选 (评分=$bestScore): $cleanArtist - $cleanTitle")
            }
            return bestTimeline
        } catch (e: Exception) {
            Logger.lyrics.e(TAG, "解析 LRCLIB 搜索响应 JSON 失败", e)
            return null
        }
    }

    private fun parseSingleEntry(
        jsonStr: String,
        expectedArtist: String,
        expectedTitle: String,
        expectedDurationMs: Long
    ): LyricTimeline? {
        try {
            val root = JSONObject(jsonStr)
            val isInst = root.optBoolean("instrumental", false)
            val candArtist = root.optString("artistName", "")
            val candTitle = root.optString("trackName", "")
            val candDurationSec = root.optDouble("duration", 0.0)
            val candDurationMs = (candDurationSec * 1000).toLong()
            val syncedLrc = root.optString("syncedLyrics", "")
            val plainLrc = root.optString("plainLyrics", "")

            val candFormat = when {
                syncedLrc.contains("<") -> LyricFormat.LRC_WORD_SYNC
                syncedLrc.isNotBlank() -> LyricFormat.LRC_LINE_SYNC
                else -> LyricFormat.PLAIN_TEXT
            }

            val score = LyricCandidateScorer.evaluate(
                expectedArtist = expectedArtist,
                expectedTitle = expectedTitle,
                expectedDurationMs = expectedDurationMs,
                candidateArtist = candArtist,
                candidateTitle = candTitle,
                candidateDurationMs = candDurationMs,
                candidateFormat = candFormat,
                isInstrumentalReported = isInst
            )

            if (!score.isPassed) {
                Logger.lyrics.w(TAG, "LRCLIB 精确候选评分未达标 (score=${score.totalScore}): ${score.vetoReason}")
                return null
            }

            if (isInst || score.isInstrumental) {
                return LyricTimeline(
                    source = "LRCLIB",
                    format = LyricFormat.PLAIN_TEXT,
                    isInstrumental = true,
                    durationMs = candDurationMs
                )
            }

            if (syncedLrc.isNotBlank()) {
                val parsed = LrcParser.parse(syncedLrc, sourceName = "LRCLIB")
                if (parsed != null) return parsed
            }

            if (plainLrc.isNotBlank()) {
                return LrcParser.parse(plainLrc, sourceName = "LRCLIB")
            }

            return null
        } catch (e: Exception) {
            Logger.lyrics.e(TAG, "解析 LRCLIB 精确匹配 JSON 异常", e)
            return null
        }
    }

    companion object {
        private const val TAG = "LrclibDataSource"
        const val DEFAULT_BASE_URL = "https://lrclib.net"
        const val EXACT_TIMEOUT_MS = 1500
        const val SEARCH_TIMEOUT_MS = 2000
    }
}
