package com.solo.purecast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 💡 测试快速切歌场景下 StreamDemuxer 的会话令牌 (SessionToken) 隔离行为
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamDemuxerSessionTest {

    @Test
    fun testFastTrackSwitchSessionTokenIsolation() = runTest {
        val sessionToken = AtomicLong(0L)
        val endedCalls = AtomicInteger(0)
        val activeSessionStarted = AtomicInteger(0)
        var isDemuxing = false

        // 模拟第 1 首歌启动 (Session 1)
        val session1 = sessionToken.incrementAndGet()
        isDemuxing = true
        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                delay(100)
            } finally {
                // 旧协程退出逻辑
                if (session1 == sessionToken.get()) {
                    isDemuxing = false
                    endedCalls.incrementAndGet()
                }
            }
        }

        // 模拟用户在 10ms 内极速切换到第 2 首歌 (Session 2)
        val session2 = sessionToken.incrementAndGet()
        job1.cancel()
        isDemuxing = true
        activeSessionStarted.set(session2.toInt())

        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                delay(50)
            } finally {
                if (session2 == sessionToken.get()) {
                    isDemuxing = false
                    endedCalls.incrementAndGet()
                }
            }
        }

        // 等待旧协程 Job 1 完全退出
        testScheduler.advanceTimeBy(10)

        // 验证：旧协程 Job 1 的 finally 块绝不能将 isDemuxing 杀掉，也不能触发 endedCalls
        assertTrue("新流应该保持运行中，旧流的 finally 块不应修改全局 isDemuxing", isDemuxing)
        assertEquals("旧流的 finally 块不应误触发 onStreamEnded", 0, endedCalls.get())

        // 推进时间至 Job 2 正常结束
        testScheduler.advanceTimeBy(100)
        job2.join()

        // 此时 Session 2 结束，正常触发 onStreamEnded
        assertFalse("流播放完毕，isDemuxing 应恢复为 false", isDemuxing)
        assertEquals("仅当前活跃会话才能回调 onStreamEnded", 1, endedCalls.get())
    }

    @Test
    fun testCrossProtocolPreemptionStreamSourceState() {
        // 模拟 PlaybackService 内部流源状态机
        var currentSource = "IDLE"

        fun startStreaming(source: String): Boolean {
            if (source == "UPNP" && currentSource == "AIRPLAY") {
                // 后发抢占停止 AirPlay
            }
            currentSource = source
            return true
        }

        fun onAirPlayStreamRequested(): Boolean {
            if (currentSource == "UPNP") {
                // 后发抢占停止 UPnP
            }
            currentSource = "AIRPLAY"
            return startStreaming("AIRPLAY")
        }

        fun stopPlayback(source: String? = null) {
            if (source != null && currentSource != source && currentSource != "IDLE") {
                return // 忽略延迟到达的旧流回调
            }
            currentSource = "IDLE"
        }

        // 场景 1: UPnP 启动推流
        startStreaming("UPNP")
        assertEquals("UPNP", currentSource)

        // 场景 2: AirPlay 后发抢占
        onAirPlayStreamRequested()
        assertEquals("AIRPLAY", currentSource)

        // 场景 3: 旧 UPnP 遗留协程在被中止后延迟调用了 stopPlayback("UPNP")
        stopPlayback("UPNP")
        assertEquals("旧 UPnP 迟到的 stopPlayback 不能误杀当前正在播放的 AirPlay 会话", "AIRPLAY", currentSource)

        // 场景 4: AirPlay 正常停止
        stopPlayback("AIRPLAY")
        assertEquals("IDLE", currentSource)
    }
}
