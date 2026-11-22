package com.solo.purecast.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solo.purecast.core.lyrics.LyricsSyncClock
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.ui.lyrics.LyricsStreamView
import com.solo.purecast.ui.lyrics.SmoothLyricLineRow
import com.solo.purecast.ui.lyrics.WordSyncKaraokeText
import com.solo.purecast.ui.screen.SettingsScreen
import com.solo.purecast.ui.theme.PureCastTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 💡 SettingsScreen 存储与缓存清理、协议开关及歌词核心组件 Compose UI 回归测试
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsAndLyricsInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
        syncClock.onHardSync(positionMs = 1_000L, isPlaying = false)

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
                    isPlaying = false,
                    primaryColor = Color.White
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
        syncClock.onHardSync(positionMs = 2_000L, isPlaying = false)
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
}
