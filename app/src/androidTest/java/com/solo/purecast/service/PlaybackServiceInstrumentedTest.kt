package com.solo.purecast.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.PlaybackStatus
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 💡 PlaybackService 前台服务生命周期与会话协调宿主真机集成测试 (#30)
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceInstrumentedTest {

    @Test
    fun playbackService_startsCleanlyAndHandlesControlCommandsWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PlaybackService.start(context)

        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (PlaybackService.instance == null && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(50L)
        }

        val service = PlaybackService.instance
        assertNotNull("PlaybackService 前台服务应成功启动并就绪", service)

        // 在安全 IDLE 状态下检验遥控与错误清理等公共入口不会崩溃或污染快照
        service?.stopPlayback()
        service?.resumePlayback()
        service?.handleNextTrack()
        service?.clearError(ErrorCode.REMOTE_COMMAND_TIMEOUT)

        val snapshot = PlaybackService.sessionSnapshot.value
        assertTrue(
            "空载初始化态应为 IDLE 或 STOPPED，实际=${snapshot.playbackStatus}",
            snapshot.playbackStatus == PlaybackStatus.IDLE || snapshot.playbackStatus == PlaybackStatus.STOPPED
        )

        // 校验显式停服后清理干爽（真机冒烟后续由 am start 重新拉起服务）
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}
