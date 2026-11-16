package com.solo.purecast.core.lyrics

import com.solo.purecast.data.lyrics.LyricsCacheRepository
import com.solo.purecast.data.lyrics.LyricsWaterfallFetcher
import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.data.lyrics.remote.LyricsHttpClient
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
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
    fun `rapid track switching cancels earlier in-flight requests and executes only latest`() = testScope.runTest {
        val url2 = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/lyrics/artist2%20-%20song2.ttml"
        fakeClient.responses[url2] = LyricsHttpClient.HttpResponse(
            statusCode = 200,
            body = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:10.00" end="02:00.00"><span>Song 2</span></p></div></body></tt>"""
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
}
