package com.solo.purecast.data.lyrics

import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.data.lyrics.remote.LyricsHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 💡 歌词双视角查询链条回归测试（精确版本优先、清洗降级与纯音乐保留）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsQueryPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheRepo: LyricsCacheRepository
    private lateinit var fakeClient: FakeClient
    private lateinit var fetcher: LyricsWaterfallFetcher

    private class FakeClient : LyricsHttpClient {
        val responses = mutableMapOf<String, LyricsHttpClient.HttpResponse>()
        val requestedUrls = mutableListOf<String>()

        override suspend fun get(
            url: String,
            timeoutMs: Int,
            headers: Map<String, String>
        ): LyricsHttpClient.HttpResponse {
            requestedUrls.add(url)
            return responses[url] ?: LyricsHttpClient.HttpResponse(statusCode = 404, body = "")
        }
    }

    @Before
    fun setUp() {
        cacheRepo = LyricsCacheRepository(tempFolder.newFolder("lyrics_cache"))
        fakeClient = FakeClient()
        fetcher = LyricsWaterfallFetcher(
            cacheRepository = cacheRepo,
            amllDataSource = AmllDataSource(fakeClient, apiBaseUrl = "http://mock.amll"),
            lrclibDataSource = LrclibDataSource(fakeClient, baseUrl = "http://mock.lrclib")
        )
    }

    @Test
    fun `lrclib exact prefers raw versioned title and album before cleaned fallback`() = runBlocking {
        val exactUrl =
            "http://mock.lrclib/api/get?track_name=Come%20Together%20%282019%20Mix%29&artist_name=The%20Beatles&album_name=Abbey%20Road%20%282019%20Mix%29&duration=260"
        val cleanedUrl =
            "http://mock.lrclib/api/get?track_name=come%20together&artist_name=the%20beatles&album_name=abbey%20road&duration=260"

        fakeClient.responses[exactUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "id": 9901,
                    "trackName": "Come Together (2019 Mix)",
                    "artistName": "The Beatles",
                    "albumName": "Abbey Road (2019 Mix)",
                    "duration": 260.0,
                    "instrumental": false,
                    "syncedLyrics": "[00:08.50]Here come old flat top"
                }
            """.trimIndent()
        )
        fakeClient.responses[cleanedUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "id": 1001,
                    "trackName": "Come Together",
                    "artistName": "The Beatles",
                    "albumName": "Abbey Road",
                    "duration": 259.0,
                    "instrumental": false,
                    "syncedLyrics": "[00:12.00]Wrong offset version"
                }
            """.trimIndent()
        )

        val timeline = fetcher.fetch(
            artist = "The Beatles",
            title = "Come Together (2019 Mix)",
            durationMs = 260000L,
            album = "Abbey Road (2019 Mix)"
        )

        assertNotNull(timeline)
        assertEquals(8500L, timeline?.lines?.first()?.startMs)
        assertEquals("Here come old flat top", timeline?.lines?.first()?.mainText)
        assertFalse(fakeClient.requestedUrls.contains(cleanedUrl))
    }

    @Test
    fun `lrclib exact falls back to cleaned query when raw versioned title misses`() = runBlocking {
        val cleanedUrl =
            "http://mock.lrclib/api/get?track_name=come%20together&artist_name=the%20beatles&album_name=abbey%20road&duration=260"
        fakeClient.responses[cleanedUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "id": 1001,
                    "trackName": "Come Together",
                    "artistName": "The Beatles",
                    "albumName": "Abbey Road",
                    "duration": 260.0,
                    "instrumental": false,
                    "syncedLyrics": "[00:09.00]Here come old flat top"
                }
            """.trimIndent()
        )

        val timeline = fetcher.fetch(
            artist = "The Beatles",
            title = "Come Together (2019 Mix)",
            durationMs = 260000L,
            album = "Abbey Road (2019 Mix)"
        )

        assertNotNull(timeline)
        assertEquals(9000L, timeline?.lines?.first()?.startMs)
        assertTrue(fakeClient.requestedUrls.contains(cleanedUrl))
    }

    @Test
    fun `instrumental parenthesis tag short-circuits without hitting remote sources`() = runBlocking {
        val timeline = fetcher.fetch("周杰伦", "安静 (伴奏版)", 240000L)

        assertNotNull(timeline)
        assertTrue(timeline!!.isInstrumental)
        assertTrue("纯音乐/伴奏曲目应直接短路，不发起网络检索", fakeClient.requestedUrls.isEmpty())
    }

    @Test
    fun `lrclib exact does not issue cleaned fallback on transient network timeout`() = runBlocking {
        val exactUrl =
            "http://mock.lrclib/api/get?track_name=Come%20Together%20%282019%20Mix%29&artist_name=The%20Beatles&album_name=Abbey%20Road%20%282019%20Mix%29&duration=260"
        val cleanedUrl =
            "http://mock.lrclib/api/get?track_name=come%20together&artist_name=the%20beatles&album_name=abbey%20road&duration=260"

        fakeClient.responses[exactUrl] = LyricsHttpClient.HttpResponse(statusCode = -1, body = "")
        fakeClient.responses[cleanedUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "id": 1001,
                    "trackName": "Come Together",
                    "artistName": "The Beatles",
                    "duration": 260.0,
                    "instrumental": false,
                    "syncedLyrics": "[00:09.00]Should not be called after timeout"
                }
            """.trimIndent()
        )

        val lrclib = LrclibDataSource(fakeClient, baseUrl = "http://mock.lrclib")
        val result = lrclib.getExact(
            artist = "The Beatles",
            title = "Come Together (2019 Mix)",
            album = "Abbey Road (2019 Mix)",
            durationMs = 260000L
        )

        assertEquals(null, result)
        assertTrue(fakeClient.requestedUrls.contains(exactUrl))
        assertFalse("exact 发生网络超时(-1)时不应再发起第二次 cleaned 请求", fakeClient.requestedUrls.contains(cleanedUrl))
    }
}
