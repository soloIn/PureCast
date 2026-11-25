package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.data.lyrics.LyricCandidateScorer
import com.solo.purecast.data.lyrics.LyricsQuerySpec
import com.solo.purecast.data.lyrics.MetadataNormalizer
import com.solo.purecast.data.lyrics.parser.LrcParser
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * 💡 LRCLIB 官方 RESTful 远端数据源
 * 遵循官方规范：支持 /api/get 两段精确匹配（先原文版本，未命中再降级清洗名）与 /api/search 候选打分决胜
 * 严格使用 RFC 3986 百分号编码 (空格转为 %20)。
 */
class LrclibDataSource(
    private val httpClient: LyricsHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private data class ExactAttemptResult(
        val timeline: LyricTimeline?,
        val shouldFallback: Boolean
    )

    /**
     * 💡 精确匹配检索 (/api/get)
     * 1. 优先使用保留版本标签与标点的原文（如 `Come Together (2019 Mix)` / `Abbey Road (2019 Mix)`）；
     * 2. 若未命中（且非网络超时/5xx 故障）且清洗后名称与原文存在差异，降级使用清洗后的四元组再精确查一次。
     */
    suspend fun getExact(spec: LyricsQuerySpec): LyricTimeline? {
        var allowCleanedFallback = true
        if (spec.exactArtist.isNotBlank() && spec.exactTitle.isNotBlank()) {
            val rawAttempt = fetchExactOnce(
                artist = spec.exactArtist,
                title = spec.exactTitle,
                album = spec.exactAlbum,
                durationMs = spec.durationMs,
                spec = spec,
                modeLabel = "exact"
            )
            if (rawAttempt.timeline != null) return rawAttempt.timeline
            allowCleanedFallback = rawAttempt.shouldFallback
        }

        if (allowCleanedFallback && spec.hasDistinctCleanedForm && spec.cleanArtist.isNotBlank() && spec.cleanTitle.isNotBlank()) {
            return fetchExactOnce(
                artist = spec.cleanArtist,
                title = spec.cleanTitle,
                album = spec.cleanAlbum,
                durationMs = spec.durationMs,
                spec = spec,
                modeLabel = "cleaned"
            ).timeline
        }
        return null
    }

    suspend fun getExact(
        artist: String,
        title: String,
        album: String? = null,
        durationMs: Long = 0L
    ): LyricTimeline? = getExact(
        MetadataNormalizer.buildQuerySpec(
            artist = artist,
            title = title,
            album = album.orEmpty(),
            durationMs = durationMs
        )
    )

    private suspend fun fetchExactOnce(
        artist: String,
        title: String,
        album: String,
        durationMs: Long,
        spec: LyricsQuerySpec,
        modeLabel: String
    ): ExactAttemptResult {
        val queryParams = StringBuilder("track_name=${rfc3986Encode(title)}&artist_name=${rfc3986Encode(artist)}")
        if (album.isNotBlank()) {
            queryParams.append("&album_name=").append(rfc3986Encode(album))
        }
        if (durationMs > 0L) {
            val durationSec = (durationMs / 1000L).toInt()
            queryParams.append("&duration=").append(durationSec)
        }

        val url = "$baseUrl/api/get?$queryParams"
        val resp = httpClient.get(url, timeoutMs = EXACT_TIMEOUT_MS)

        if (!resp.isSuccessful || resp.body.isBlank()) {
            val isTransientNetworkError = resp.statusCode <= 0 || resp.statusCode >= 500
            Logger.lyrics.d(TAG, "LRCLIB /api/get [$modeLabel] 未命中: $artist - $title (HTTP ${resp.statusCode})")
            return ExactAttemptResult(timeline = null, shouldFallback = !isTransientNetworkError)
        }

        return ExactAttemptResult(timeline = parseSingleEntry(resp.body, spec, modeLabel), shouldFallback = true)
    }

    /**
     * 💡 模糊搜索回退检索 (/api/search)
     * 支持精准参数 -> 复合全文 q -> 纯歌名 q 三级渐进决胜，评估前 10 条候选
     */
    suspend fun searchFuzzy(spec: LyricsQuerySpec): LyricTimeline? {
        val cleanArtist = spec.cleanArtist
        val cleanTitle = spec.cleanTitle
        val scoringArtist = spec.exactArtist.ifBlank { cleanArtist }
        val scoringTitle = spec.exactTitle.ifBlank { cleanTitle }

        if (cleanArtist.isBlank() && cleanTitle.isBlank()) return null

        // 1. 若歌手已知，优先使用精准参数组合搜索
        if (cleanArtist.isNotBlank()) {
            val url = "$baseUrl/api/search?track_name=${rfc3986Encode(cleanTitle)}&artist_name=${rfc3986Encode(cleanArtist)}"
            val hit = queryAndEvaluate(url, scoringArtist, scoringTitle, spec.durationMs)
            if (hit != null) return hit
        }

        // 2. 联合全文 q 检索 ("title artist")
        val combinedQuery = "$cleanTitle $cleanArtist".trim()
        if (combinedQuery.isNotBlank()) {
            val url = "$baseUrl/api/search?q=${rfc3986Encode(combinedQuery)}"
            val hit = queryAndEvaluate(url, scoringArtist, scoringTitle, spec.durationMs)
            if (hit != null) return hit
        }

        // 3. 纯歌名 q 兜底检索（依靠时长与标题相似度强约束过滤决胜）
        if (cleanTitle.isNotBlank()) {
            val url = "$baseUrl/api/search?q=${rfc3986Encode(cleanTitle)}"
            val hit = queryAndEvaluate(url, scoringArtist, scoringTitle, spec.durationMs)
            if (hit != null) return hit
        }

        Logger.lyrics.d(TAG, "LRCLIB /api/search 全链路检索未找到合格候选: $cleanArtist - $cleanTitle")
        return null
    }

    suspend fun searchFuzzy(
        artist: String,
        title: String,
        album: String? = null,
        durationMs: Long = 0L
    ): LyricTimeline? = searchFuzzy(
        MetadataNormalizer.buildQuerySpec(
            artist = artist,
            title = title,
            album = album.orEmpty(),
            durationMs = durationMs
        )
    )

    private suspend fun queryAndEvaluate(
        url: String,
        expectedArtist: String,
        expectedTitle: String,
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
                val syncedLrc = item.optCleanString("syncedLyrics")

                // 💡 瀑布流分级择优调度：searchFuzzy 仅采纳有效同步歌词，彻底排除纯文本与无有效 syncedLyrics 的纯音乐条目，
                // 防止精确时长纯音乐 (90分) 压过有轻微时长差/尾奏的真同步歌词并提前阻断后续回退查询。
                if (syncedLrc.isNullOrBlank()) {
                    continue
                }

                val candFormat = if (syncedLrc.contains("<")) LyricFormat.LRC_WORD_SYNC else LyricFormat.LRC_LINE_SYNC

                val scoreResult = LyricCandidateScorer.evaluate(
                    expectedArtist = expectedArtist,
                    expectedTitle = expectedTitle,
                    expectedDurationMs = durationMs,
                    candidateArtist = candArtist,
                    candidateTitle = candTitle,
                    candidateDurationMs = candDurationMs,
                    candidateFormat = candFormat,
                    isInstrumental = false
                )

                if (scoreResult.accepted && scoreResult.totalScore > bestScore) {
                    val timeline = LrcParser.parse(syncedLrc, sourceName = "LRCLIB", expectedDurationMs = candDurationMs)
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
        spec: LyricsQuerySpec,
        modeLabel: String
    ): LyricTimeline? {
        return try {
            val json = JSONObject(jsonStr)
            val id = json.optLong("id", -1L)
            val trackName = json.optCleanString("trackName") ?: ""
            val albumName = json.optCleanString("albumName") ?: ""
            val isInst = json.optBoolean("instrumental", false) || spec.isInstrumentalHint
            val candDurationSec = json.optDouble("duration", 0.0)
            val candDurationMs = (candDurationSec * 1000).toLong()
            val syncedLrc = json.optCleanString("syncedLyrics")

            // 💡 防止清洗降级后的 /api/get 误命中伴奏/纯音乐版本反推原唱为纯音乐
            if (!spec.isInstrumentalHint && LyricCandidateScorer.isInstrumentalTitle(trackName)) {
                Logger.lyrics.w(TAG, "LRCLIB /api/get [$modeLabel] 拒绝以伴奏/纯音乐条目匹配原唱: id=$id, track=$trackName")
                return null
            }

            if (spec.durationMs > 0L && candDurationMs > 0L && abs(spec.durationMs - candDurationMs) > MAX_EXACT_DURATION_DELTA_MS) {
                Logger.lyrics.w(
                    TAG,
                    "LRCLIB /api/get [$modeLabel] 返回条目时长偏差超标，拒绝采纳: id=$id, track=$trackName, album=$albumName, cand=${candDurationMs}ms, expected=${spec.durationMs}ms"
                )
                return null
            }

            if (isInst) {
                Logger.lyrics.i(
                    TAG,
                    "LRCLIB /api/get [$modeLabel] 命中纯音乐: id=$id, track=$trackName, album=$albumName, duration=${candDurationMs}ms"
                )
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

            Logger.lyrics.i(
                TAG,
                "LRCLIB /api/get [$modeLabel] 命中条目: id=$id, track=$trackName, album=$albumName, duration=${candDurationMs}ms (expected=${spec.durationMs}ms)"
            )
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
        const val MAX_EXACT_DURATION_DELTA_MS = 5000L
    }
}
