package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.data.lyrics.LyricCandidateScorer
import com.solo.purecast.data.lyrics.MetadataNormalizer
import com.solo.purecast.data.lyrics.parser.TtmlParser
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 💡 AMLL (Apple Music-like Lyrics) 官方 RESTful API 远端数据源
 * 遵循官方 OpenAPI 规范 (https://api.amll.dev):
 * 1. Step 1: 调用 /v1/lyrics/search?musicName={title}&artistName={artist} 检索歌曲条目列表并按真实元数据打分择优;
 * 2. Step 2: 调用 /v1/lyrics/get?id={id} 获取高精音节逐字 TTML 歌词;
 * 3. 严格遵循 RFC 3986 百分号编码 (空格转为 %20，绝不转为 +)。
 */
class AmllDataSource(
    private val httpClient: LyricsHttpClient,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL
) {

    data class AmllFetchResult(
        val timeline: LyricTimeline,
        val candidateArtist: String,
        val candidateTitle: String
    )

    private data class AmllSearchCandidate(
        val id: Long,
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    suspend fun fetch(artist: String, title: String): LyricTimeline? {
        return fetchWithMetadata(artist, title, expectedDurationMs = 0L)?.timeline
    }

    suspend fun fetchWithMetadata(
        artist: String,
        title: String,
        expectedDurationMs: Long = 0L
    ): AmllFetchResult? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return null

        val encArtist = rfc3986Encode(cleanArtist)
        val encTitle = rfc3986Encode(cleanTitle)

        // 1. 尝试以精准字段组合检索
        var searchUrl = "$apiBaseUrl/v1/lyrics/search?musicName=$encTitle&artistName=$encArtist"
        var searchResp = httpClient.get(searchUrl, timeoutMs = TIMEOUT_MS)
        var candidates = if (searchResp.isSuccessful && searchResp.body.isNotBlank()) {
            extractCandidates(searchResp.body)
        } else {
            emptyList()
        }

        // 若精准字段未匹配，降级使用全局模糊搜索 q
        if (candidates.isEmpty()) {
            val encQ = rfc3986Encode("$cleanTitle $cleanArtist")
            searchUrl = "$apiBaseUrl/v1/lyrics/search?q=$encQ"
            searchResp = httpClient.get(searchUrl, timeoutMs = TIMEOUT_MS)
            if (searchResp.isSuccessful && searchResp.body.isNotBlank()) {
                candidates = extractCandidates(searchResp.body)
            }
        }

        if (candidates.isEmpty()) {
            Logger.lyrics.d(TAG, "AMLL 搜索结果为空: $cleanArtist - $cleanTitle (HTTP ${searchResp.statusCode})")
            return null
        }

        val bestCandidate = selectBestCandidate(
            candidates = candidates,
            expectedArtist = cleanArtist,
            expectedTitle = cleanTitle,
            expectedDurationMs = expectedDurationMs
        )
        if (bestCandidate == null) {
            Logger.lyrics.w(TAG, "AMLL 候选列表 (${candidates.size} 条) 均未通过元数据打分校验: $cleanArtist - $cleanTitle")
            return null
        }

        // 2. 根据择优后的歌词 ID 拉取完整 TTML 内容
        val getUrl = "$apiBaseUrl/v1/lyrics/get?id=${bestCandidate.id}"
        val getResp = httpClient.get(getUrl, timeoutMs = TIMEOUT_MS)
        if (!getResp.isSuccessful || getResp.body.isBlank()) {
            Logger.lyrics.d(TAG, "AMLL /v1/lyrics/get 请求失败: id=${bestCandidate.id} (HTTP ${getResp.statusCode})")
            return null
        }

        val ttmlContent = extractTtmlContent(getResp.body)
        if (ttmlContent.isNullOrBlank()) {
            Logger.lyrics.d(TAG, "AMLL 响应中缺少有效 TTML 歌词内容: id=${bestCandidate.id}")
            return null
        }

        val timeline = TtmlParser.parse(ttmlContent, sourceName = "AMLL")
        if (timeline != null) {
            val (ttmlArtist, ttmlTitle) = extractTtmlMetadata(ttmlContent)
            val resolvedArtist = bestCandidate.artist.ifBlank { ttmlArtist }
            val resolvedTitle = bestCandidate.title.ifBlank { ttmlTitle }
            Logger.lyrics.i(
                TAG,
                "AMLL 官方 API 成功命中: $cleanArtist - $cleanTitle -> cand=$resolvedArtist - $resolvedTitle (id=${bestCandidate.id}, lines=${timeline.lineCount})"
            )
            return AmllFetchResult(
                timeline = timeline,
                candidateArtist = resolvedArtist.ifBlank { cleanArtist },
                candidateTitle = resolvedTitle.ifBlank { cleanTitle }
            )
        }
        return null
    }

    private fun extractCandidates(jsonStr: String): List<AmllSearchCandidate> {
        return try {
            val root = JSONObject(jsonStr)
            val dataObj = root.optJSONObject("data")
            val items = dataObj?.optJSONArray("items") ?: root.optJSONArray("data") ?: return emptyList()
            val result = ArrayList<AmllSearchCandidate>(items.length())
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L).takeIf { it > 0L }
                    ?: obj.optString("id", "").toLongOrNull()?.takeIf { it > 0L }
                    ?: continue
                val title = obj.optString("musicName", "")
                    .ifBlank { obj.optString("music_name", "") }
                    .ifBlank { obj.optString("name", "") }
                    .ifBlank { obj.optString("title", "") }
                    .ifBlank { obj.optString("songName", "") }
                    .ifBlank { obj.optString("song_name", "") }
                    .ifBlank { obj.optString("trackName", "") }
                    .ifBlank { obj.optString("track_name", "") }
                    .trim()
                val artist = extractArtistString(obj)
                val rawDur = obj.optLong("duration", 0L)
                    .takeIf { it > 0L }
                    ?: obj.optLong("durationMs", 0L).takeIf { it > 0L }
                    ?: obj.optLong("duration_ms", 0L)
                val durationMs = if (rawDur in 1L..3600L) rawDur * 1000L else rawDur.coerceAtLeast(0L)
                if (i == 0 && title.isBlank() && artist.isBlank()) {
                    val keys = buildList {
                        val iter = obj.keys()
                        while (iter.hasNext()) add(iter.next())
                    }
                    Logger.lyrics.d(TAG, "AMLL 搜索条目缺少歌名/歌手字段, keys=$keys")
                }
                result.add(AmllSearchCandidate(id = id, title = title, artist = artist, durationMs = durationMs))
            }
            result
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 AMLL 搜索结果异常", e)
            emptyList()
        }
    }

    private fun extractArtistString(obj: JSONObject): String {
        val artistsArr: JSONArray? = obj.optJSONArray("artists") ?: obj.optJSONArray("singers")
        if (artistsArr != null && artistsArr.length() > 0) {
            val parts = ArrayList<String>(artistsArr.length())
            for (j in 0 until artistsArr.length()) {
                val item = artistsArr.opt(j)
                val name = when (item) {
                    is String -> item.trim()
                    is JSONObject -> item.optString("name", "").trim()
                    else -> ""
                }
                if (name.isNotEmpty()) parts.add(name)
            }
            if (parts.isNotEmpty()) return parts.joinToString(" ")
        }
        return obj.optString("artistName", "")
            .ifBlank { obj.optString("artist_name", "") }
            .ifBlank { obj.optString("artist", "") }
            .ifBlank { obj.optString("singer", "") }
            .trim()
    }

    /**
     * 💡 从 AMLL TTML 头部 `<amll:meta key="musicName"/"artists" value="..."/>` 提取真实元数据，
     * 当搜索接口响应未回传歌名/歌手字段时作为权威回退，供上层打分器与续集/Part 门禁校验。
     */
    private fun extractTtmlMetadata(ttmlContent: String): Pair<String, String> {
        val headerSlice = if (ttmlContent.length > 8192) ttmlContent.substring(0, 8192) else ttmlContent
        var title = ""
        val artists = ArrayList<String>(2)
        for (tagMatch in AMLL_META_TAG_REGEX.findAll(headerSlice)) {
            val attrs = tagMatch.groupValues[1]
            val key = AMLL_META_KEY_REGEX.find(attrs)?.groupValues?.get(1) ?: continue
            val rawAttrVal = AMLL_META_VALUE_REGEX.find(attrs)?.groupValues?.get(1) ?: continue
            val rawVal = unescapeXmlAttr(rawAttrVal).trim()
            if (rawVal.isEmpty()) continue
            when (key) {
                "musicName" -> if (title.isEmpty()) title = rawVal
                "artists" -> artists.add(rawVal)
            }
        }
        return artists.joinToString(" ") to title
    }

    private fun unescapeXmlAttr(value: String): String {
        val withNumericDecoded = XML_NUMERIC_ENTITY_REGEX.replace(value) { match ->
            val isHex = match.groupValues[1].isNotEmpty()
            val digits = match.groupValues[2]
            val codePoint = digits.toIntOrNull(if (isHex) 16 else 10)
            if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                String(Character.toChars(codePoint))
            } else {
                match.value
            }
        }
        return withNumericDecoded
            .replace("&amp;", "&")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun selectBestCandidate(
        candidates: List<AmllSearchCandidate>,
        expectedArtist: String,
        expectedTitle: String,
        expectedDurationMs: Long
    ): AmllSearchCandidate? {
        var best: AmllSearchCandidate? = null
        var bestScore = -1f
        for (cand in candidates) {
            // 若接口条目完全缺失歌名与歌手字段，回退保留首条交由后续 TTML 时长校验把关
            if (cand.title.isBlank() && cand.artist.isBlank()) {
                if (best == null) {
                    best = cand
                    bestScore = LyricCandidateScorer.PASSING_THRESHOLD
                }
                continue
            }
            val score = LyricCandidateScorer.evaluate(
                expectedArtist = expectedArtist,
                expectedTitle = expectedTitle,
                expectedDurationMs = expectedDurationMs,
                candidateArtist = cand.artist.ifBlank { expectedArtist },
                candidateTitle = cand.title.ifBlank { expectedTitle },
                candidateDurationMs = cand.durationMs,
                candidateFormat = LyricFormat.TTML_SYLLABLE
            )
            if (score.isPassed && score.totalScore > bestScore) {
                bestScore = score.totalScore
                best = cand
            }
        }
        return best
    }

    private fun extractTtmlContent(jsonStr: String): String? {
        return try {
            val root = JSONObject(jsonStr)
            root.optJSONObject("data")?.optString("lyrics")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 AMLL 歌词内容异常", e)
            null
        }
    }

    private fun rfc3986Encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    companion object {
        private const val TAG = "AmllDataSource"
        const val DEFAULT_API_BASE_URL = "https://api.amll.dev"
        const val TIMEOUT_MS = 1500
        private val AMLL_META_TAG_REGEX = Regex("""<amll:meta\b([^>]*)>""")
        private val AMLL_META_KEY_REGEX = Regex("""\bkey=["'](musicName|artists)["']""")
        private val AMLL_META_VALUE_REGEX = Regex("""\bvalue=["']([^"']*)["']""")
        private val XML_NUMERIC_ENTITY_REGEX = Regex("""&#(x?)([0-9a-fA-F]+);""")
    }
}
