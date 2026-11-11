package com.solo.purecast.ui

import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.model.PlaybackError
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.ui.components.HardwarePillHintKind
import com.solo.purecast.ui.components.resolveHardwarePillHint
import com.solo.purecast.ui.screen.BannerSeverity
import com.solo.purecast.ui.screen.resolveUiErrorBanner
import com.solo.purecast.ui.screen.toMainTvUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💡 Unit U6：MainTvScreen 与 SignalPathPill UI 状态投影及错误分级仲裁单元测试
 */
class MainTvScreenUiStateTest {

    @Test
    fun errorBannerArbitratesHighestPriorityErrorAndClearsOnRecovery() {
        val snapshot = PlayerSessionSnapshot(
            errors = mapOf(
                ErrorCode.REMOTE_COMMAND_TIMEOUT to PlaybackError(
                    code = ErrorCode.REMOTE_COMMAND_TIMEOUT,
                    message = "遥控响应超时"
                ),
                ErrorCode.NETWORK_STREAM_ERROR to PlaybackError(
                    code = ErrorCode.NETWORK_STREAM_ERROR,
                    message = "网络流波动"
                ),
                ErrorCode.USB_DEVICE_DETACHED to PlaybackError(
                    code = ErrorCode.USB_DEVICE_DETACHED,
                    message = "外置 USB DAC 已断开"
                )
            )
        )

        val banner = resolveUiErrorBanner(snapshot)
        assertNotNull(banner)
        assertEquals(ErrorCode.USB_DEVICE_DETACHED, banner!!.code)
        assertEquals(BannerSeverity.WARNING_BLOCKING, banner.severity)

        val transientOnly = snapshot.copy(
            errors = mapOf(
                ErrorCode.REMOTE_COMMAND_UNSUPPORTED to PlaybackError(
                    code = ErrorCode.REMOTE_COMMAND_UNSUPPORTED,
                    message = "UPnP 不支持切歌"
                )
            )
        )
        val transientBanner = resolveUiErrorBanner(transientOnly)
        assertNotNull(transientBanner)
        assertEquals(BannerSeverity.SOFT_HINT, transientBanner!!.severity)

        val recovered = transientOnly.copy(errors = emptyMap())
        assertNull(resolveUiErrorBanner(recovered))
    }

    @Test
    fun signalPathPillEmitsSoftHintsForPermissionPendingAndReconfiguringOnly() {
        val reconfig = resolveHardwarePillHint(HardwarePipelineState.AUDIO_RECONFIGURING)
        assertNotNull(reconfig)
        assertEquals(HardwarePillHintKind.RECONFIGURING, reconfig!!.kind)

        val pending = resolveHardwarePillHint(HardwarePipelineState.PERMISSION_PENDING)
        assertNotNull(pending)
        assertEquals(HardwarePillHintKind.PERMISSION_PENDING, pending!!.kind)

        val attached = resolveHardwarePillHint(HardwarePipelineState.ATTACHED)
        assertNotNull(attached)
        assertEquals(HardwarePillHintKind.PERMISSION_PENDING, attached!!.kind)

        assertNull(resolveHardwarePillHint(HardwarePipelineState.EXCLUSIVE_ACTIVE))
        assertNull(resolveHardwarePillHint(HardwarePipelineState.RELEASED))
    }

    @Test
    fun transitioningSessionMaintainsExpandedCoverAndActiveControlsWithoutIdleFlash() {
        val snap = PlayerSessionSnapshot(
            sessionId = 99L,
            businessState = BusinessSessionState.TRANSITIONING,
            hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            progressPositionMs = 45_000L
        )
        val uiState = snap.toMainTvUiState()
        assertEquals(PlaybackStatus.PLAYING, uiState.playbackStatus)
        assertFalse(uiState.isIdle)
        assertTrue(uiState.isCoverExpanded)
        assertTrue(uiState.isTransitioning)
    }
}
