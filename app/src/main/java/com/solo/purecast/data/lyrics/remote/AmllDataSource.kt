package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.data.lyrics.MetadataNormalizer
import com.solo.purecast.data.lyrics.parser.TtmlParser
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 💡 AMLL (Apple Music-like Lyrics) 官方 RESTful API 远端数据源
 * 遵循官方 OpenAPI 规范 (https://api.amll.dev):
 * 1. Step 1: 调用 /v1/lyrics/search?musicName={title}&artistName={artist} 检索歌曲条目 ID;
 * 2. Step 2: 调用 /v1/lyrics/get?id={id} 获取高精音节逐字 TTML 歌词;
 * 3. 严格遵循 RFC 3986 百分号编码 (空格转为 %20，绝不转为 +)。
 */
class AmllDataSource(
    private val httpClient: LyricsHttpClient,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL
) {

    suspend fun fetch(artist: String, title: String): LyricTimeline? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return null

        val encArtist = rfc3986Encode(cleanArtist)
        val encTitle = rfc3986Encode(cleanTitle)

        // 1. 尝试以精准字段组合检索
        var searchUrl = "$apiBaseUrl/v1/lyrics/search?musicName=$encTitle&artistName=$encArtist"
        var searchResp = httpClient.get(searchUrl, timeoutMs = TIMEOUT_MS)

        // 若精准字段未匹配，降级使用全局模糊搜索 q
        if (!searchResp.isSuccessful || searchResp.body.isBlank() || !hasValidItem(searchResp.body)) {
            val encQ = rfc3986Encode("$cleanTitle $cleanArtist")
            searchUrl = "$apiBaseUrl/v1/lyrics/search?q=$encQ"
            searchResp = httpClient.get(searchUrl, timeoutMs = TIMEOUT_MS)
        }

        if (!searchResp.isSuccessful || searchResp.body.isBlank()) {
            Logger.lyrics.d(TAG, "AMLL 搜索未命中: $cleanArtist - $cleanTitle (HTTP ${searchResp.statusCode})")
            return null
        }

        val lyricId = extractFirstLyricId(searchResp.body)
        if (lyricId == null) {
            Logger.lyrics.d(TAG, "AMLL 搜索结果为空: $cleanArtist - $cleanTitle")
            return null
        }

        // 2. 根据获取到的歌词 ID 拉取完整 TTML 内容
        val getUrl = "$apiBaseUrl/v1/lyrics/get?id=$lyricId"
        val getResp = httpClient.get(getUrl, timeoutMs = TIMEOUT_MS)
        if (!getResp.isSuccessful || getResp.body.isBlank()) {
            Logger.lyrics.d(TAG, "AMLL /v1/lyrics/get 请求失败: id=$lyricId (HTTP ${getResp.statusCode})")
            return null
        }

        val ttmlContent = extractTtmlContent(getResp.body)
        if (ttmlContent.isNullOrBlank()) {
            Logger.lyrics.d(TAG, "AMLL 响应中缺少有效 TTML 歌词内容: id=$lyricId")
            return null
        }

        val timeline = TtmlParser.parse(ttmlContent, sourceName = "AMLL")
        if (timeline != null) {
            Logger.lyrics.i(TAG, "AMLL 官方 API 成功命中: $cleanArtist - $cleanTitle (id=$lyricId, lines=${timeline.lineCount})")
        }
        return timeline
    }

    private fun hasValidItem(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            val dataObj = root.optJSONObject("data")
            val items = dataObj?.optJSONArray("items") ?: root.optJSONArray("data")
            items != null && items.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun extractFirstLyricId(jsonStr: String): Long? {
        return try {
            val root = JSONObject(jsonStr)
            val dataObj = root.optJSONObject("data")
            val items = dataObj?.optJSONArray("items") ?: root.optJSONArray("data") ?: return null
            if (items.length() == 0) return null
            val first = items.getJSONObject(0)
            first.optLong("id").takeIf { it > 0 }
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "解析 AMLL 搜索结果异常", e)
            null
        }
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
    }
}
