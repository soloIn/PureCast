package com.solo.purecast.data.lyrics

import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.data.lyrics.remote.LyricsHttpClient
import com.solo.purecast.model.LyricFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 💡 U4 严格阶梯瀑布流检索测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsWaterfallFetcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheRepo: LyricsCacheRepository
    private lateinit var fakeClient: FakeLyricsHttpClient
    private lateinit var fetcher: LyricsWaterfallFetcher

    class FakeLyricsHttpClient : LyricsHttpClient {
        val responses = mutableMapOf<String, LyricsHttpClient.HttpResponse>()
        val requestedUrls = mutableListOf<String>()

        override suspend fun get(url: String, timeoutMs: Int, headers: Map<String, String>): LyricsHttpClient.HttpResponse {
            requestedUrls.add(url)
            return responses[url] ?: LyricsHttpClient.HttpResponse(statusCode = 404, body = "")
        }
    }

    @Before
    fun setUp() {
        val cacheDir = tempFolder.newFolder("lyrics_cache")
        cacheRepo = LyricsCacheRepository(cacheDir)
        fakeClient = FakeLyricsHttpClient()
        val amllSource = AmllDataSource(fakeClient)
        val lrclibSource = LrclibDataSource(fakeClient)
        fetcher = LyricsWaterfallFetcher(cacheRepo, amllSource, lrclibSource)
    }

    @Test
    fun `step 1 AMLL hit returns TTML without calling LRCLIB`() = runBlocking {
        val amllSearchUrl = "https://api.amll.dev/v1/lyrics/search?musicName=%E6%99%B4%E5%A4%A9&artistName=%E5%91%A8%E6%9D%B0%E4%BC%A6"
        fakeClient.responses[amllSearchUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "code": 200,
                    "data": [
                        { "id": "7192686136682066", "musicName": "晴天", "artists": ["周杰伦"] }
                    ]
                }
            """.trimIndent()
        )

        val amllGetUrl = "https://api.amll.dev/v1/lyrics/get?id=7192686136682066"
        fakeClient.responses[amllGetUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "code": 200,
                    "data": {
                        "id": "7192686136682066",
                        "lyrics": "<?xml version=\"1.0\" encoding=\"utf-8\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:10.00\" end=\"04:29.00\"><span>晴天</span></p></div></body></tt>"
                    }
                }
            """.trimIndent()
        )

        val timeline = fetcher.fetch("周杰伦", "晴天", 269000L)

        assertNotNull(timeline)
        assertEquals("AMLL", timeline?.source)
        assertEquals(LyricFormat.TTML_SYLLABLE, timeline?.format)
        // 验证只请求了 AMLL (search + get)，未发起 LRCLIB
        assertEquals(2, fakeClient.requestedUrls.size)
        // 验证写入了缓存
        val cacheKey = cacheRepo.computeCacheKey("周杰伦", "晴天", 269000L)
        assertNotNull(cacheRepo.get(cacheKey))
    }

    @Test
    fun `step 1 AMLL 404 falls back to step 2 LRCLIB exact match`() = runBlocking {
        val lrclibGetUrl = "https://lrclib.net/api/get?track_name=%E6%99%B4%E5%A4%A9&artist_name=%E5%91%A8%E6%9D%B0%E4%BC%A6&duration=269"
        fakeClient.responses[lrclibGetUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "trackName": "晴天",
                    "artistName": "周杰伦",
                    "duration": 269.0,
                    "instrumental": false,
                    "syncedLyrics": "[00:10.00]故事的小黄花\n[00:15.00]从出生那年就飘着"
                }
            """.trimIndent()
        )

        val timeline = fetcher.fetch("周杰伦", "晴天", 269000L)

        assertNotNull(timeline)
        assertEquals("LRCLIB", timeline?.source)
        assertEquals(LyricFormat.LRC_LINE_SYNC, timeline?.format)
        assertEquals(2, timeline?.lines?.size)
    }

    @Test
    fun `step 2 LRCLIB exact 404 falls back to step 3 LRCLIB fuzzy search`() = runBlocking {
        val lrclibSearchUrl = "https://lrclib.net/api/search?track_name=%E6%99%B4%E5%A4%A9&artist_name=%E5%91%A8%E6%9D%B0%E4%BC%A6"
        fakeClient.responses[lrclibSearchUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                [
                    {
                        "trackName": "晴天",
                        "artistName": "周杰伦",
                        "duration": 269.0,
                        "instrumental": false,
                        "syncedLyrics": "[00:10.00]故事的小黄花"
                    }
                ]
            """.trimIndent()
        )

        val timeline = fetcher.fetch("周杰伦", "晴天", 269000L)

        assertNotNull(timeline)
        assertEquals("LRCLIB", timeline?.source)
        assertEquals(LyricFormat.LRC_LINE_SYNC, timeline?.format)
    }

    @Test
    fun `step 2 LRCLIB plain text with null syncedLyrics continues to step 4 Netease for synced lyrics`() = runBlocking {
        val amllSource = AmllDataSource(fakeClient)
        val lrclibSource = LrclibDataSource(fakeClient)
        val neteaseSource = com.solo.purecast.data.lyrics.remote.NeteaseDataSource(fakeClient)
        val fetcherWithNetease = LyricsWaterfallFetcher(cacheRepo, amllSource, lrclibSource, neteaseSource)

        val lrclibGetUrl = "https://lrclib.net/api/get?track_name=%E6%B3%AA%E6%B5%B7&artist_name=%E8%AE%B8%E8%8C%B9%E8%8A%B8&duration=300"
        fakeClient.responses[lrclibGetUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "trackName": "泪海",
                    "artistName": "许茹芸",
                    "duration": 300.0,
                    "instrumental": false,
                    "syncedLyrics": null,
                    "plainLyrics": "爱已不能动\n还有什么 值得我心痛"
                }
            """.trimIndent()
        )

        val lrclibSearchUrl = "https://lrclib.net/api/search?track_name=%E6%B3%AA%E6%B5%B7&artist_name=%E8%AE%B8%E8%8C%B9%E8%8A%B8"
        fakeClient.responses[lrclibSearchUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = "[]"
        )

        val neteaseQuery = java.net.URLEncoder.encode("泪海 许茹芸", "UTF-8")
        val neteaseSearchUrl = "https://music.163.com/api/search/get?s=$neteaseQuery&type=1&limit=8"
        fakeClient.responses[neteaseSearchUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "result": {
                        "songs": [
                            { "id": 123456, "name": "泪海", "artists": [{ "name": "许茹芸" }], "duration": 300000 }
                        ]
                    }
                }
            """.trimIndent()
        )

        val neteaseLyricUrl = "https://music.163.com/api/song/lyric?id=123456&lv=1&kv=1&tv=1"
        fakeClient.responses[neteaseLyricUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "lrc": {
                        "lyric": "[00:10.00]爱已不能动\n[00:15.00]还有什么 值得我心痛"
                    }
                }
            """.trimIndent()
        )

        val timeline = fetcherWithNetease.fetch("许茹芸", "泪海", 300000L)

        assertNotNull(timeline)
        assertEquals("Netease", timeline?.source)
        assertEquals(LyricFormat.LRC_LINE_SYNC, timeline?.format)
        assertEquals(2, timeline?.lines?.size)
        assertEquals("爱已不能动", timeline?.lines?.get(0)?.mainText)
        assertEquals(10000L, timeline?.lines?.get(0)?.startMs)
    }

    @Test
    fun `all waterfall steps failing returns null`() = runBlocking {
        val timeline = fetcher.fetch("Unknown", "NonExistentSong", 120000L)
        assertNull(timeline)
    }
}
