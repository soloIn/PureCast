package com.solo.purecast.ui

import android.graphics.Bitmap
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.LyricWord
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.ui.components.CoverVisualFingerprint
import com.solo.purecast.ui.components.ResolvedCoverArt
import com.solo.purecast.ui.components.StableAlbumCover
import com.solo.purecast.ui.lyrics.LyricsStreamView
import com.solo.purecast.ui.lyrics.SmoothLyricLineRow
import com.solo.purecast.ui.lyrics.WordSyncKaraokeText
import com.solo.purecast.ui.screen.LyricsScreen
import com.solo.purecast.ui.screen.SettingsScreen
import com.solo.purecast.ui.theme.PureCastTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 💡 SettingsScreen 存储与缓存清理、协议开关、StableAlbumCover 状态机及歌词核心组件 Compose UI 回归测试
 */
@OptIn(ExperimentalTestApi::class, ExperimentalTvMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsAndLyricsInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun testCoverArt(revision: Long, rgb: Int): ResolvedCoverArt {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(rgb) }
        return ResolvedCoverArt(
            bitmap = bmp.asImageBitmap(),
            fingerprint = CoverVisualFingerprint(dHash = revision, avgR = (rgb shr 16) and 0xFF, avgG = (rgb shr 8) and 0xFF, avgB = rgb and 0xFF),
            revision = revision
        )
    }

    @Test
    fun settingsScreen_displaysStorageCacheSectionAndAllowsClearCacheAction() {
        composeTestRule.setContent {
            PureCastTheme {
                SettingsScreen(
                    audioState = AudioPathState(),
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("存储与缓存").assertExists()
        composeTestRule.onNodeWithText("清空歌词缓存").assertExists()
        composeTestRule.onNodeWithText("清空缓存", substring = true)
            .assertIsEnabled()
            .assertHasClickAction()
            .requestFocus()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("当前无本地歌词缓存 (0 B)").assertExists()

        // 切换至“接收协议引擎”分类验证 AirPlay 与 UPnP 开关存在且可交互
        composeTestRule.onNodeWithText("接收协议引擎").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("AirPlay 1 (AirTunes / RAOP)").assertExists()
        composeTestRule.onNodeWithText("UPnP / DLNA (MediaRenderer 2.0)").assertExists()
    }

    @Test
    fun lyricsStreamView_rendersLineSyncAndWordSyncWithExitFadeWithoutCrash() {
        val syncClock = LyricsSyncClock()
        syncClock.hardAlign(targetPositionMs = 1_000L, playing = false)

        val lrcTimeline = LyricTimeline(
            source = "LRCLIB",
            format = LyricFormat.LRC_LINE_SYNC,
            durationMs = 30_000L,
            lines = listOf(
                LyricLine(0, 6_000L, 10_000L, "First LRC Line"),
                LyricLine(1, 16_000L, 20_000L, "Second LRC Line After Interlude")
            )
        )

        var timeline by mutableStateOf(lrcTimeline)
        composeTestRule.setContent {
            PureCastTheme {
                LyricsStreamView(
                    timeline = timeline,
                    syncClock = syncClock,
                    hasDarkBackground = true,
                    isPlaying = false
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("First LRC Line").assertExists()

        // 切换为逐字 TTML 歌词并推进到词中位置，再失活验证退场防闪白路径不崩溃
        val wordLine = LyricLine(
            lineIndex = 0,
            startMs = 1_000L,
            endMs = 4_000L,
            mainText = "Hello world",
            translationText = "你好世界",
            words = listOf(
                LyricWord("Hello ", 1_000L, 2_500L),
                LyricWord("world", 2_500L, 4_000L)
            )
        )
        timeline = LyricTimeline(
            source = "AMLL",
            format = LyricFormat.TTML_SYLLABLE,
            durationMs = 10_000L,
            lines = listOf(wordLine)
        )
        syncClock.hardAlign(targetPositionMs = 2_000L, playing = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hello world").assertExists()
        composeTestRule.onNodeWithText("你好世界").assertExists()
    }

    @Test
    fun wordSyncKaraokeText_transitionsFromActiveToExitingStateCleanly() {
        val syncClock = LyricsSyncClock()
        val line = LyricLine(
            lineIndex = 0,
            startMs = 1_000L,
            endMs = 4_000L,
            mainText = "Shine bright",
            words = listOf(
                LyricWord("Shine ", 1_000L, 2_500L),
                LyricWord("bright", 2_500L, 4_000L)
            )
        )
        var isActive by mutableStateOf(true)
        var exitingAlpha by mutableStateOf(0.8f)

        composeTestRule.setContent {
            PureCastTheme {
                SmoothLyricLineRow(
                    line = line,
                    isActive = isActive,
                    distanceFromActive = if (isActive) 0 else 1,
                    currentTimeProvider = { 1_800L },
                    syncClock = syncClock,
                    primaryColor = Color.White,
                    isPlaying = false
                )
                WordSyncKaraokeText(
                    line = line,
                    syncClock = syncClock,
                    currentTimeProvider = { 1_800L },
                    primaryColor = Color.White,
                    isPlaying = false,
                    isActive = isActive,
                    exitingRowAlphaProvider = { exitingAlpha }
                )
            }
        }
        composeTestRule.waitForIdle()

        // 模拟词中退场淡出与汇合阶段
        isActive = false
        exitingAlpha = 0.72f
        composeTestRule.waitForIdle()

        exitingAlpha = 0.40f
        composeTestRule.waitForIdle()
    }

    /**
     * 💡 Finding #8: StableAlbumCover 核心防闪状态机 5 项转换断言与 LyricsScreen resolvedCoverArt 分支覆盖
     */
    @Test
    fun stableAlbumCover_stateMachineTransitionsAndLyricsScreenResolvedCoverBranch() {
        composeTestRule.mainClock.autoAdvance = false

        var coverState by mutableStateOf<ResolvedCoverArt?>(null)
        val rev1 = testCoverArt(revision = 1L, rgb = 0xFFCC2222.toInt())
        val rev2 = testCoverArt(revision = 2L, rgb = 0xFF22CC22.toInt())
        val rev3 = testCoverArt(revision = 3L, rgb = 0xFF2222CC.toInt())

        composeTestRule.setContent {
            PureCastTheme {
                StableAlbumCover(
                    coverArt = coverState,
                    contentDescription = "TestCover",
                    fadeDurationMs = 200
                ) {
                    Text("Placeholder")
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        // 1. 初始为空 -> 展示占位符
        composeTestRule.onNodeWithText("Placeholder").assertExists()

        // 2. 首图直出 -> 直接进入底层 base(rev=1)，无上层 incoming 淡入
        coverState = rev1
        composeTestRule.mainClock.advanceTimeBy(32L)
        composeTestRule.onNodeWithTag("stable_cover_base_1").assertExists()
        composeTestRule.onNodeWithTag("stable_cover_incoming_1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Placeholder").assertDoesNotExist()

        // 3. 切新图 rev2 -> 底层保持 base(rev=1) alpha=1f 保底，上层挂载 incoming(rev=2) 淡入
        coverState = rev2
        composeTestRule.mainClock.advanceTimeBy(120L) // > 50% (FastOutSlowInEasing 下 alpha > 0.5f)
        composeTestRule.onNodeWithTag("stable_cover_base_1").assertExists()
        composeTestRule.onNodeWithTag("stable_cover_incoming_2").assertExists()

        // 4. 快切 rev3 且 incomingAlpha >= 0.5f -> rev2 立即晋升为底层，rev3 挂载于上层淡入；完成后 rev3 晋升且清空上层
        coverState = rev3
        composeTestRule.mainClock.advanceTimeBy(32L)
        composeTestRule.onNodeWithTag("stable_cover_base_2").assertExists()
        composeTestRule.onNodeWithTag("stable_cover_incoming_3").assertExists()

        composeTestRule.mainClock.advanceTimeBy(260L)
        composeTestRule.onNodeWithTag("stable_cover_base_3").assertExists()
        composeTestRule.onNodeWithTag("stable_cover_incoming_3").assertDoesNotExist()

        // 5. 置空复位 -> 清除双层并回退占位符
        coverState = null
        composeTestRule.mainClock.advanceTimeBy(32L)
        composeTestRule.onNodeWithTag("stable_cover_base_3").assertDoesNotExist()
        composeTestRule.onNodeWithText("Placeholder").assertExists()
    }

    @Test
    fun lyricsScreen_rendersResolvedCoverArtAndPlaceholderCleanly() {
        val syncClock = LyricsSyncClock()
        var resolvedCover by mutableStateOf<ResolvedCoverArt?>(null)
        val art = testCoverArt(revision = 10L, rgb = 0xFF336699.toInt())

        composeTestRule.setContent {
            PureCastTheme {
                LyricsScreen(
                    lyricUiState = LyricUiState.NotFound(trackGeneration = 1L),
                    syncClock = syncClock,
                    metadata = TrackMetadata(title = "Test Song", artist = "Test Artist", album = "Test Album"),
                    playbackStatus = PlaybackStatus.PLAYING,
                    resolvedCoverArt = resolvedCover,
                    hasDarkBackground = true,
                    onPlayPauseClick = {},
                    onPreviousClick = {},
                    onNextClick = {},
                    onClose = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Music Placeholder").assertExists()

        resolvedCover = art
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("stable_cover_base_10").assertExists()
        composeTestRule.onNodeWithContentDescription("Cover").assertExists()
    }

    /**
     * 💡 Finding #9: LyricsStreamView 播放态 (isPlaying = true) 两段式边界调度器可控时钟测试
     * 覆盖：粗睡眠 delay -> 临近边界 withFrameNanos -> 尾奏后 Long.MAX_VALUE 挂起 -> hardAlign 抢占恢复
     */
    @Test
    fun lyricsStreamView_playingTwoStageSchedulerHandlesCoarseSleepVsyncOutroSuspendAndHardAlignPreemption() {
        composeTestRule.mainClock.autoAdvance = false

        var fakeNowNanos = 1_000_000_000L
        val syncClock = LyricsSyncClock(
            maxExtrapolationMs = 30_000L,
            nanoTimeProvider = { fakeNowNanos }
        )
        syncClock.hardAlign(targetPositionMs = 500L, playing = true, nowNanoTime = fakeNowNanos)

        val timeline = LyricTimeline(
            source = "LRCLIB",
            format = LyricFormat.LRC_LINE_SYNC,
            durationMs = 20_000L,
            lines = listOf(
                LyricLine(0, 2_000L, 4_000L, "Line One"),
                LyricLine(1, 5_000L, 7_000L, "Line Two"),
                LyricLine(2, 8_000L, 10_000L, "Line Three")
            )
        )

        composeTestRule.setContent {
            PureCastTheme {
                LyricsStreamView(
                    timeline = timeline,
                    syncClock = syncClock,
                    hasDarkBackground = true,
                    isPlaying = true
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(32L)
        composeTestRule.onNodeWithTag("lyric_line_0").assertIsNotSelected()

        // 1. 粗睡眠推进至 1000ms（距第 1 行 1900ms 点亮边界仍有 900ms，走 delay 粗睡眠分支且尚未点亮）
        fakeNowNanos += 500_000_000L
        composeTestRule.mainClock.advanceTimeBy(500L)
        composeTestRule.onNodeWithTag("lyric_line_0").assertIsNotSelected()

        // 2. 分步推进至 1860ms（距 1900ms 边界仅 40ms <= 64ms，切入 withFrameNanos 逐帧锁定分支）
        fakeNowNanos += 860_000_000L
        composeTestRule.mainClock.advanceTimeBy(900L)
        composeTestRule.onNodeWithTag("lyric_line_0").assertIsNotSelected()

        // 3. 再推进 80ms 至 1940ms，经 withFrameNanos 精准跨过 1900ms 边界点亮第 1 行
        fakeNowNanos += 80_000_000L
        composeTestRule.mainClock.advanceTimeBy(80L)
        composeTestRule.onNodeWithTag("lyric_line_0").assertIsSelected()

        // 4. 推进跨过 Line Two 与 Line Three 尾奏边界（> 10000ms + 尾奏静息延迟），触发 Long.MAX_VALUE 挂起退出循环
        fakeNowNanos += 12_000_000_000L
        composeTestRule.mainClock.advanceTimeBy(12_000L)
        composeTestRule.onNodeWithTag("lyric_line_2").assertIsNotSelected()

        // 5. 在挂起态下执行 hardAlign 寻道回第 1 行，验证 collectLatest 立即抢占并重新点亮第 1 行
        syncClock.hardAlign(targetPositionMs = 2_100L, playing = true, nowNanoTime = fakeNowNanos)
        composeTestRule.mainClock.advanceTimeBy(64L)
        composeTestRule.onNodeWithTag("lyric_line_0").assertIsSelected()
    }
}
