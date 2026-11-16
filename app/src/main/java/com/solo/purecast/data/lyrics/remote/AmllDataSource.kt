package com.solo.purecast.data.lyrics.remote

import com.solo.purecast.data.lyrics.MetadataNormalizer
import com.solo.purecast.data.lyrics.parser.TtmlParser
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import java.net.URLEncoder

/**
 * 💡 AMLL TTML DB 远端数据源
 * 依据规范化元数据请求 GitHub 上的高精音节逐字歌词库，硬超时 1.5s
 */
class AmllDataSource(
    private val httpClient: LyricsHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    suspend fun fetch(artist: String, title: String): LyricTimeline? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return null

        // 尝试构建常见的 AMLL 路径
        val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")

        // 路径样式 1: lyrics/artist - title.ttml
        val path1 = "$baseUrl/lyrics/$encodedArtist%20-%20$encodedTitle.ttml"
        val resp1 = httpClient.get(path1, timeoutMs = TIMEOUT_MS)
        if (resp1.isSuccessful && resp1.body.isNotBlank()) {
            val timeline = TtmlParser.parse(resp1.body, sourceName = "AMLL")
            if (timeline != null) {
                Logger.lyrics.i(TAG, "AMLL 路径 1 命中: $cleanArtist - $cleanTitle")
                return timeline
            }
        }

        // 路径样式 2: lyrics/artist/title.ttml
        val path2 = "$baseUrl/lyrics/$encodedArtist/$encodedTitle.ttml"
        val resp2 = httpClient.get(path2, timeoutMs = TIMEOUT_MS)
        if (resp2.isSuccessful && resp2.body.isNotBlank()) {
            val timeline = TtmlParser.parse(resp2.body, sourceName = "AMLL")
            if (timeline != null) {
                Logger.lyrics.i(TAG, "AMLL 路径 2 命中: $cleanArtist - $cleanTitle")
                return timeline
            }
        }

        Logger.lyrics.d(TAG, "AMLL 未命中: $cleanArtist - $cleanTitle")
        return null
    }

    companion object {
        private const val TAG = "AmllDataSource"
        const val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main"
        const val TIMEOUT_MS = 1500
    }
}
