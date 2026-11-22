package com.solo.purecast.core.lyrics

import com.solo.purecast.data.lyrics.LyricsCacheRepository
import com.solo.purecast.data.lyrics.LyricsWaterfallFetcher
import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.data.lyrics.remote.LyricsHttpClient
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.TrackMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 💡 U4 歌词触发调度器、切歌防抖与世代隔离单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsTriggerManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var cacheRepo: LyricsCacheRepository
    private lateinit var fakeClient: FakeLyricsHttpClient
    private lateinit var triggerManager: LyricsTriggerManager

    class FakeLyricsHttpClient : LyricsHttpClient {
        var fetchCount = 0
        val responses = mutableMapOf<String, LyricsHttpClient.HttpResponse>()

        override suspend fun get(url: String, timeoutMs: Int, headers: Map<String, String>): LyricsHttpClient.HttpResponse {
            fetchCount++
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
        val fetcher = LyricsWaterfallFetcher(cacheRepo, amllSource, lrclibSource)

        triggerManager = LyricsTriggerManager(
            scope = testScope,
            cacheRepository = cacheRepo,
            waterfallFetcher = fetcher,
            debounceDelayMs = 350L
        )
    }

    @Test
    fun `ignores default placeholders and stays Idle`() = testScope.runTest {
        val snapshot = PlayerSessionSnapshot(
            businessState = BusinessSessionState.PLAYING,
            trackGeneration = 1L,
            metadata = TrackMetadata(
                title = TrackMetadata.DEFAULT_TITLE,
                artist = TrackMetadata.DEFAULT_ARTIST
            )
        )

        triggerManager.onSessionSnapshotChanged(snapshot)
        advanceUntilIdle()

        assertEquals(LyricUiState.Idle, triggerManager.lyricUiState.value)
        assertEquals(0, fakeClient.fetchCount)
    }

    @Test
    fun `ignores airplay passthrough placeholder title and skips network query`() = testScope.runTest {
        val snapshot = PlayerSessionSnapshot(
            businessState = BusinessSessionState.PLAYING,
            trackGeneration = 1L,
            metadata = TrackMetadata(
                title = "AirPlay 音频直通",
                artist = "AirPlay 设备"
            )
        )

        triggerManager.onSessionSnapshotChanged(snapshot)
        advanceUntilIdle()

        assertEquals(LyricUiState.Idle, triggerManager.lyricUiState.value)
        assertEquals(0, fakeClient.fetchCount)
    }

    @Test
    fun `rapid track switching cancels earlier in-flight requests and executes only latest`() = testScope.runTest {
        val amllSearchUrl = "https://api.amll.dev/v1/lyrics/search?musicName=song2&artistName=artist2"
        fakeClient.responses[amllSearchUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """{"code":200,"data":[{"id":2002,"musicName":"Song 2","artists":["Artist 2"]}]}"""
        )
        val amllGetUrl = "https://api.amll.dev/v1/lyrics/get?id=2002"
        fakeClient.responses[amllGetUrl] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """{"code":200,"data":{"id":2002,"lyrics":"<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:10.00\" end=\"02:00.00\"><span>Song 2</span></p></div></body></tt>"}}"""
        )

        // 连续快速触发代数 1 与 代数 2 (间隔 100ms < 350ms)
        triggerManager.onSessionSnapshotChanged(
            PlayerSessionSnapshot(
                businessState = BusinessSessionState.PLAYING,
                trackGeneration = 1L,
                metadata = TrackMetadata(title = "Song1", artist = "Artist1", durationMs = 120000L)
            )
        )
        advanceTimeBy(100L)

        triggerManager.onSessionSnapshotChanged(
            PlayerSessionSnapshot(
                businessState = BusinessSessionState.PLAYING,
                trackGeneration = 2L,
                metadata = TrackMetadata(title = "Song2", artist = "Artist2", durationMs = 120000L)
            )
        )

        // 推进时间越过 350ms 防抖
        advanceTimeBy(400L)
        advanceUntilIdle()

        val state = triggerManager.lyricUiState.value
        assertTrue(state is LyricUiState.Success)
        val success = state as LyricUiState.Success
        assertEquals(2L, success.trackGeneration)
        assertEquals("Song 2", success.timeline.lines[0].mainText)
    }

    @Test
    fun `live stream immediately resets to NotFound without network query`() = testScope.runTest {
        val snapshot = PlayerSessionSnapshot(
            businessState = BusinessSessionState.PLAYING,
            trackGeneration = 10L,
            metadata = TrackMetadata(
                title = "BBC World Service",
                artist = "BBC",
                durationState = DurationState.LIVE
            )
        )

        triggerManager.onSessionSnapshotChanged(snapshot)
        advanceUntilIdle()

        val state = triggerManager.lyricUiState.value
        assertTrue(state is LyricUiState.NotFound)
        assertEquals(10L, (state as LyricUiState.NotFound).trackGeneration)
        assertEquals(0, fakeClient.fetchCount)
    }

    @Test
    fun `same-track resume with incremented trackGeneration preserves Success timeline without reloading`() = testScope.runTest {
        val cachedTimeline = LyricTimeline(
            source = "cache",
            format = LyricFormat.LRC_LINE_SYNC,
            durationMs = 242_000L,
            lines = listOf(LyricLine(0, 10_000L, 20_000L, "情意结"))
        )
        val cacheKey = cacheRepo.computeCacheKey("陈慧娴", "情意结", 242_000L)
        cacheRepo.put(cacheKey, cachedTimeline)

        triggerManager.onSessionSnapshotChanged(
            PlayerSessionSnapshot(
                businessState = BusinessSessionState.PLAYING,
                trackGeneration = 6L,
                metadata = TrackMetadata(title = "情意结", artist = "陈慧娴", durationMs = 242_000L)
            )
        )
        advanceTimeBy(400L)
        advanceUntilIdle()

        val initialSuccess = triggerManager.lyricUiState.value as LyricUiState.Success
        val fetchCountAfterLoad = fakeClient.fetchCount

        // 模拟 TEARDOWN 暂停后重连 SETUP 导致 trackGeneration 递增为 7，但歌名与歌手未变
        triggerManager.onSessionSnapshotChanged(
            PlayerSessionSnapshot(
                businessState = BusinessSessionState.TRANSITIONING,
                trackGeneration = 7L,
                metadata = TrackMetadata(title = "情意结", artist = "陈慧娴", durationMs = 242_000L)
            )
        )
        advanceUntilIdle()

        val resumedState = triggerManager.lyricUiState.value
        assertTrue("同曲目续播绝不能退回 Loading 重载大屏", resumedState is LyricUiState.Success)
        val resumedSuccess = resumedState as LyricUiState.Success
        assertEquals(7L, resumedSuccess.trackGeneration)
        assertTrue("timeline 引用必须保持一致以防 Compose 重建视图", resumedSuccess.timeline === initialSuccess.timeline)
        assertEquals("不应产生任何重复网络检索", fetchCountAfterLoad, fakeClient.fetchCount)
    }

    @Test
    fun `track boundary updating duration before new title does not trigger wasted old-song recalibration`() = testScope.runTest {
        val oldTimeline = LyricTimeline(
            source = "cache",
            format = LyricFormat.LRC_LINE_SYNC,
            durationMs = 225_000L,
            lines = listOf(LyricLine(0, 10_000L, 20_000L, "Old Song Line"))
        )
        val newTimeline = LyricTimeline(
            source = "cache",
            format = LyricFormat.LRC_LINE_SYNC,
            durationMs = 233_500L,
            lines = listOf(LyricLine(0, 10_000L, 20_000L, "New Song Line"))
        )
        cacheRepo.put(cacheRepo.computeCacheKey("Artist A", "Song A", 225_000L), oldTimeline)
        cacheRepo.put(cacheRepo.computeCacheKey("Artist B", "Song B", 233_500L), newTimeline)

        // 1. 先加载旧歌 A
        triggerManager.onSessionSnapshotChanged(
            PlayerSessionSnapshot(
                businessState = BusinessSessionState.PLAYING,
                trackGeneration = 5L,
                metadata = TrackMetadata(title = "Song A", artist = "Artist A", durationMs = 225_000L)
            )
        )
        advanceTimeBy(400L)
        advanceUntilIdle()
        assertEquals(0, fakeClient.fetchCount)

        // 2. 模拟 TrackBoundaryEvent 先行到达：trackGeneration=6 且 durationMs 变为新歌时长，但歌名仍为旧歌
        triggerManager.onSessionSnapshotChanged(
            PlayerSessionSnapshot(
                businessState = BusinessSessionState.PLAYING,
                trackGeneration = 6L,
                metadata = TrackMetadata(title = "Song A", artist = "Artist A", durationMs = 233_500L)
            )
        )
        advanceTimeBy(10L)

        // 3. 10ms 后 MetadataPatchEvent 到达，更新为新歌 B
        triggerManager.onSessionSnapshotChanged(
            PlayerSessionSnapshot(
                businessState = BusinessSessionState.PLAYING,
                trackGeneration = 6L,
                metadata = TrackMetadata(title = "Song B", artist = "Artist B", durationMs = 233_500L)
            )
        )
        advanceTimeBy(400L)
        advanceUntilIdle()

        // 全程命中缓存，绝不以旧歌名 + 新时长发起网络检索
        assertEquals("切歌边界绝不应以旧歌名+新时长触发网络重校准", 0, fakeClient.fetchCount)
        val finalState = triggerManager.lyricUiState.value as LyricUiState.Success
        assertEquals(6L, finalState.trackGeneration)
        assertEquals("New Song Line", finalState.timeline.lines[0].mainText)
    }
}
