package com.solo.purecast.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.components.PlaybackControls
import com.solo.purecast.ui.screen.MainTvScreen
import com.solo.purecast.ui.theme.PureCastTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 💡 Android TV 大屏 Compose UI 与 D-Pad 焦点回归真机测试 (#30)
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainTvScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun ensureOnboardingCompletedAndCleanIdleState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SettingsRepository(context).setOnboardingCompleted(true)
        PlaybackService.instance?.stopPlayback()
    }

    @Test
    fun mainTvScreen_idleStateShowsGuideAndSettingsTakesDpadConfirm() {
        var settingsClicks = 0
        composeTestRule.setContent {
            PureCastTheme {
                MainTvScreen(
                    onTogglePlayPause = {},
                    onPreviousTrack = {},
                    onNextTrack = {},
                    onOpenSettings = { settingsClicks++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("AirPlay:", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("上一曲").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("下一曲").assertIsNotEnabled()
        // 💡 androidx.tv.material3.Surface 走 D-Pad DirectionCenter + Semantics OnClick 语义交互而非手机 Touch 点击
        composeTestRule.onNodeWithContentDescription("设置")
            .assertIsEnabled()
            .assertHasClickAction()
            .requestFocus()
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        assertEquals("待机界面按遥控确认键点击设置按钮应触发设置导航回调", 1, settingsClicks)
    }

    @Test
    fun playbackControls_stateTransitionsRetainTransportEnabledAndShiftIcon() {
        var status by mutableStateOf(PlaybackStatus.PLAYING)
        var nextClicks = 0
        var playPauseClicks = 0
        var settingsClicks = 0
        val playPauseRequester = FocusRequester()
        val settingsRequester = FocusRequester()

        composeTestRule.setContent {
            PureCastTheme {
                PlaybackControls(
                    playbackStatus = status,
                    onPlayPauseClick = { playPauseClicks++ },
                    onPreviousClick = {},
                    onNextClick = {
                        nextClicks++
                        status = PlaybackStatus.PAUSED
                    },
                    onSettingsClick = { settingsClicks++ },
                    playPauseFocusRequester = playPauseRequester,
                    settingsFocusRequester = settingsRequester
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. PLAYING 下存在“暂停”，经 D-Pad 确认点击下一曲切到 PAUSED 仍保持播控启用，焦点与交互不丢失
        composeTestRule.onNodeWithContentDescription("暂停").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("下一曲")
            .assertIsEnabled()
            .requestFocus()
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()
        assertEquals(1, nextClicks)
        assertEquals(PlaybackStatus.PAUSED, status)

        composeTestRule.onNodeWithContentDescription("下一曲").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("上一曲").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("播放")
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertEquals(1, playPauseClicks)

        // 2. 回退到 IDLE 后禁用播控按钮并保留设置按钮可响应遥控激活
        status = PlaybackStatus.IDLE
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("下一曲").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("上一曲").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("设置")
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertEquals(1, settingsClicks)
    }
}
