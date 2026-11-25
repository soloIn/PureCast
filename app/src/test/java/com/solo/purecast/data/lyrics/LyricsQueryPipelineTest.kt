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

    @Test
    fun `fuzzy search unrelated instrumental cover returns null instead of instrumental`() = runBlocking {
        val searchUrl = "http://mock.lrclib/api/search?q=%E7%A7%8B%E5%A4%A9"
        fakeClient.responses[searchUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                [
                    {
                        "trackName": "秋天 (Instrumental)",
                        "artistName": "Other Artist",
                        "duration": 180.0,
                        "instrumental": true,
                        "syncedLyrics": null
                    }
                ]
            """.trimIndent()
        )

        val timeline = fetcher.fetch("张三", "秋天", 240000L)
        assertEquals("找不到歌词且仅有无关纯音乐条目时必须返回 null（暂无歌词）", null, timeline)
    }

    @Test
    fun `remote instrumental fallback is cached in L1 memory only without persisting to L2 disk`() = runBlocking {
        val exactUrl =
            "http://mock.lrclib/api/get?track_name=River%20Flows%20In%20You&artist_name=Yiruma&duration=188"
        fakeClient.responses[exactUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "id": 8801,
                    "trackName": "River Flows In You",
                    "artistName": "Yiruma",
                    "duration": 188.0,
                    "instrumental": true,
                    "syncedLyrics": null
                }
            """.trimIndent()
        )

        val timeline = fetcher.fetch("Yiruma", "River Flows In You", 188000L)
        assertNotNull(timeline)
        assertTrue(timeline!!.isInstrumental)
        assertEquals("远端推断纯音乐不应落盘 L2 磁盘缓存", 0, cacheRepo.getCacheStats().fileCount)
        assertNotNull("L1 内存缓存应命中", cacheRepo.get(cacheRepo.computeCacheKey("Yiruma", "River Flows In You", 188000L)))
    }

    @Test
    fun `lrclib instrumental flag defers to Step 4 Netease when Netease has synced lyrics`() = runBlocking {
        val neteaseSource = com.solo.purecast.data.lyrics.remote.NeteaseDataSource(
            httpClient = fakeClient,
            searchApiUrl = "http://mock.netease/search",
            lyricApiUrl = "http://mock.netease/lyric"
        )
        val fetcherWithNetease = LyricsWaterfallFetcher(
            cacheRepository = cacheRepo,
            amllDataSource = AmllDataSource(fakeClient, apiBaseUrl = "http://mock.amll"),
            lrclibDataSource = LrclibDataSource(fakeClient, baseUrl = "http://mock.lrclib"),
            neteaseDataSource = neteaseSource
        )
        val exactUrl =
            "http://mock.lrclib/api/get?track_name=%E5%A4%9C%E6%9B%B2&artist_name=%E5%91%A8%E6%9D%B0%E4%BC%A6&duration=226"
        fakeClient.responses[exactUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "id": 7701,
                    "trackName": "夜曲",
                    "artistName": "周杰伦",
                    "duration": 226.0,
                    "instrumental": true,
                    "syncedLyrics": null
                }
            """.trimIndent()
        )
        val neteaseQuery = java.net.URLEncoder.encode("夜曲 周杰伦", "UTF-8")
        fakeClient.responses["http://mock.netease/search?s=$neteaseQuery&type=1&limit=8"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """
                    {
                        "result": {
                            "songs": [
                                { "id": 654321, "name": "夜曲", "artists": [{"name": "周杰伦"}], "duration": 226000 }
                            ]
                        }
                    }
                """.trimIndent()
            )
        fakeClient.responses["http://mock.netease/lyric?id=654321&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """
                    {
                        "lrc": {
                            "lyric": "[00:20.00]夜色降临微风轻吹"
                        }
                    }
                """.trimIndent()
            )

        val timeline = fetcherWithNetease.fetch("周杰伦", "夜曲", 226000L)
        assertNotNull(timeline)
        assertEquals("Netease", timeline?.source)
        assertFalse(timeline!!.isInstrumental)
        assertEquals("夜色降临微风轻吹", timeline.lines.first().mainText)
    }

    @Test
    fun `netease explicit nolyric and pure music banner are recognized as instrumental`() = runBlocking {
        val neteaseSource = com.solo.purecast.data.lyrics.remote.NeteaseDataSource(
            httpClient = fakeClient,
            searchApiUrl = "http://mock.netease/search",
            lyricApiUrl = "http://mock.netease/lyric"
        )
        val q1 = java.net.URLEncoder.encode("summer 久石让", "UTF-8")
        fakeClient.responses["http://mock.netease/search?s=$q1&type=1&limit=8"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """{"result":{"songs":[{"id":101,"name":"Summer","artists":[{"name":"久石让"}],"duration":141000}]}}"""
            )
        fakeClient.responses["http://mock.netease/lyric?id=101&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """{"nolyric":true}"""
            )

        val inst1 = neteaseSource.fetch("久石让", "Summer", 141000L)
        assertNotNull(inst1)
        assertTrue(inst1!!.isInstrumental)

        val q2 = java.net.URLEncoder.encode("天空之城 久石让", "UTF-8")
        fakeClient.responses["http://mock.netease/search?s=$q2&type=1&limit=8"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """{"result":{"songs":[{"id":102,"name":"天空之城","artists":[{"name":"久石让"}],"duration":200000}]}}"""
            )
        fakeClient.responses["http://mock.netease/lyric?id=102&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """{"lrc":{"lyric":"[00:00.00]纯音乐，请欣赏"}}"""
            )

        val inst2 = neteaseSource.fetch("久石让", "天空之城", 200000L)
        assertNotNull(inst2)
        assertTrue(inst2!!.isInstrumental)
    }

    @Test
    fun `lrclib fuzzy search excludes instrumental without syncedLyrics so real synced lyrics win and fallback continues`() = runBlocking {
        // 1. 精确时长纯音乐 (90分) 与有 8s 尾奏的真同步歌词 (88分) 共存：纯音乐必须被排除，真歌词胜出
        val searchUrl1 = "http://mock.lrclib/api/search?track_name=hello&artist_name=adele"
        fakeClient.responses[searchUrl1] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                [
                    {
                        "trackName": "Hello",
                        "artistName": "Adele",
                        "duration": 295.0,
                        "instrumental": true,
                        "syncedLyrics": null
                    },
                    {
                        "trackName": "Hello",
                        "artistName": "Adele",
                        "duration": 287.0,
                        "instrumental": false,
                        "syncedLyrics": "[00:05.00]Hello, it's me"
                    }
                ]
            """.trimIndent()
        )

        val hit1 = fetcher.fetch("Adele", "Hello", 295_000L)
        assertNotNull(hit1)
        assertFalse(hit1!!.isInstrumental)
        assertEquals("Hello, it's me", hit1.lines.first().mainText)

        // 2. 首轮子查询仅有纯音乐条目时不得提前返回，应继续回退至第 2 级联合全文 q 检索并采纳有效同步歌词
        val subQuery1 = "http://mock.lrclib/api/search?track_name=skyfall&artist_name=adele"
        val subQuery2 = "http://mock.lrclib/api/search?q=skyfall%20adele"
        fakeClient.responses[subQuery1] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                [
                    {
                        "trackName": "Skyfall",
                        "artistName": "Adele",
                        "duration": 286.0,
                        "instrumental": true,
                        "syncedLyrics": null
                    }
                ]
            """.trimIndent()
        )
        fakeClient.responses[subQuery2] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                [
                    {
                        "trackName": "Skyfall",
                        "artistName": "Adele",
                        "duration": 286.0,
                        "instrumental": true,
                        "syncedLyrics": "[00:35.00]This is the end"
                    }
                ]
            """.trimIndent()
        )

        val hit2 = fetcher.fetch("Adele", "Skyfall", 286_000L)
        assertNotNull(hit2)
        assertFalse("同时存在 instrumental=true 与有效 syncedLyrics 时应采纳同步歌词", hit2!!.isInstrumental)
        assertEquals("This is the end", hit2.lines.first().mainText)
        assertTrue(fakeClient.requestedUrls.contains(subQuery2))
    }

    @Test
    fun `netease skips malformed blank or literal null metadata so wrong translation never poisons lyrics`() = runBlocking {
        val neteaseSource = com.solo.purecast.data.lyrics.remote.NeteaseDataSource(
            httpClient = fakeClient,
            searchApiUrl = "http://mock.netease/search",
            lyricApiUrl = "http://mock.netease/lyric"
        )
        val fetcherWithNetease = LyricsWaterfallFetcher(
            cacheRepository = cacheRepo,
            amllDataSource = AmllDataSource(fakeClient, apiBaseUrl = "http://mock.amll"),
            lrclibDataSource = LrclibDataSource(fakeClient, baseUrl = "http://mock.lrclib"),
            neteaseDataSource = neteaseSource
        )
        val exactUrl =
            "http://mock.lrclib/api/get?track_name=Yellow&artist_name=Coldplay&duration=269"
        fakeClient.responses[exactUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """
                {
                    "id": 5001,
                    "trackName": "Yellow",
                    "artistName": "Coldplay",
                    "duration": 269.0,
                    "instrumental": false,
                    "syncedLyrics": "[00:10.00]Look at the stars"
                }
            """.trimIndent()
        )
        val q1 = java.net.URLEncoder.encode("yellow coldplay", "UTF-8")
        fakeClient.responses["http://mock.netease/search?s=$q1&type=1&limit=8"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """
                    {
                        "result": {
                            "songs": [
                                { "id": 9001, "name": null, "duration": 0 },
                                { "id": 9002, "name": "   ", "duration": 0 },
                                { "id": 9003, "name": "Yellow", "artists": [{ "name": null }], "duration": 0 }
                            ]
                        }
                    }
                """.trimIndent()
            )
        fakeClient.responses["http://mock.netease/lyric?id=9001&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(statusCode = 200, body = """{"tlyric":{"lyric":"[00:10.00]错误翻译1"}}""")
        fakeClient.responses["http://mock.netease/lyric?id=9002&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(statusCode = 200, body = """{"tlyric":{"lyric":"[00:10.00]错误翻译2"}}""")
        fakeClient.responses["http://mock.netease/lyric?id=9003&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(statusCode = 200, body = """{"tlyric":{"lyric":"[00:10.00]错误翻译3"}}""")

        val timeline = fetcherWithNetease.fetch("Coldplay", "Yellow", 269_000L)
        assertNotNull(timeline)
        assertEquals("Look at the stars", timeline!!.lines.first().mainText)
        assertEquals("残缺或 null 条目不得自评通过并注入错译", null, timeline.lines.first().translationText)
    }

    @Test
    fun `netease filters wrong artist and karaoke via scorer with ar field and defers nolyric across songIds`() = runBlocking {
        val neteaseSource = com.solo.purecast.data.lyrics.remote.NeteaseDataSource(
            httpClient = fakeClient,
            searchApiUrl = "http://mock.netease/search",
            lyricApiUrl = "http://mock.netease/lyric"
        )
        val q = java.net.URLEncoder.encode("稻香 周杰伦", "UTF-8")
        fakeClient.responses["http://mock.netease/search?s=$q&type=1&limit=8"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """
                    {
                        "result": {
                            "songs": [
                                { "id": 201, "name": "稻香 (伴奏)", "ar": [{ "name": "周杰伦" }], "duration": 223000 },
                                { "id": 202, "name": "稻香", "ar": [{ "name": "翻唱路人甲" }], "duration": 223000 },
                                { "id": 203, "name": "稻香", "ar": [{ "name": "周杰伦" }], "duration": 223000 },
                                { "id": 204, "name": "稻香", "artists": [{ "name": "周杰伦" }], "duration": 224500 }
                            ]
                        }
                    }
                """.trimIndent()
            )
        // id=203（最高分）显式声明 nolyric=true，应暂存并继续检查次高分 id=204，最终采纳 id=204 的同步歌词
        fakeClient.responses["http://mock.netease/lyric?id=203&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(statusCode = 200, body = """{"nolyric":true}""")
        fakeClient.responses["http://mock.netease/lyric?id=204&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """{"lrc":{"lyric":"[00:12.00]对这个世界如果你有太多的抱怨"}}"""
            )

        val timeline = neteaseSource.fetch("周杰伦", "稻香", 223_000L)
        assertNotNull(timeline)
        assertFalse(timeline!!.isInstrumental)
        assertEquals("对这个世界如果你有太多的抱怨", timeline.lines.first().mainText)
        assertFalse("卡拉OK/伴奏条目应被一票否决不请求歌词", fakeClient.requestedUrls.any { it.contains("id=201") })
        assertFalse("错误歌手条目应被评分淘汰不请求歌词", fakeClient.requestedUrls.any { it.contains("id=202") })
    }

    @Test
    fun `step 4 netease instrumental fallback caches in L1 memory only without persisting to L2 disk`() = runBlocking {
        val neteaseSource = com.solo.purecast.data.lyrics.remote.NeteaseDataSource(
            httpClient = fakeClient,
            searchApiUrl = "http://mock.netease/search",
            lyricApiUrl = "http://mock.netease/lyric"
        )
        val fetcherWithNetease = LyricsWaterfallFetcher(
            cacheRepository = cacheRepo,
            amllDataSource = AmllDataSource(fakeClient, apiBaseUrl = "http://mock.amll"),
            lrclibDataSource = LrclibDataSource(fakeClient, baseUrl = "http://mock.lrclib"),
            neteaseDataSource = neteaseSource
        )
        val q = java.net.URLEncoder.encode("kiss the rain yiruma", "UTF-8")
        fakeClient.responses["http://mock.netease/search?s=$q&type=1&limit=8"] =
            LyricsHttpClient.HttpResponse(
                statusCode = 200,
                body = """{"result":{"songs":[{"id":301,"name":"Kiss the Rain","ar":[{"name":"Yiruma"}],"duration":256000}]}}"""
            )
        fakeClient.responses["http://mock.netease/lyric?id=301&lv=1&kv=1&tv=1"] =
            LyricsHttpClient.HttpResponse(statusCode = 200, body = """{"nolyric":true}""")

        val timeline = fetcherWithNetease.fetch("Yiruma", "Kiss the Rain", 256_000L)
        assertNotNull(timeline)
        assertEquals("Netease", timeline!!.source)
        assertTrue(timeline.isInstrumental)
        assertEquals("Step 4 网易云纯音乐仅写入 L1，不得落盘 L2", 0, cacheRepo.getCacheStats().fileCount)
        assertNotNull("L1 内存缓存应命中", cacheRepo.get(cacheRepo.computeCacheKey("Yiruma", "Kiss the Rain", 256_000L)))
    }
}
