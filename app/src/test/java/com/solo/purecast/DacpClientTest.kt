package com.solo.purecast

import com.solo.purecast.airplay.DacpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

/**
 * 💡 DacpClientTest: 验证 Apple DACP 遥控协议客户端的 URL 构建、凭证管理与指令规范
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DacpClientTest {

    private val testScope = TestScope()

    @Test
    fun testBuildDacpUrlStandardFormat() {
        // 验证 DACP 标准指令 URL 不包含冗余参数，且路径符合 Apple 规范
        val playPauseUrl = DacpClient.buildUrl("192.168.2.105", 50382, "playpause")
        assertEquals("http://192.168.2.105:50382/ctrl-int/1/playpause", playPauseUrl)

        val nextUrl = DacpClient.buildUrl("192.168.2.105", 50382, "nextitem")
        assertEquals("http://192.168.2.105:50382/ctrl-int/1/nextitem", nextUrl)

        val prevUrl = DacpClient.buildUrl("192.168.2.105", 50382, "previtem")
        assertEquals("http://192.168.2.105:50382/ctrl-int/1/previtem", prevUrl)
    }

    @Test
    fun testSessionUpdateAndDynamicPortResolution() {
        val client = DacpClient(testScope)
        assertFalse(client.hasActiveSession())

        // 初始握手 (RTSP SETUP): 客户端 IP 与 Active-Remote 已知，但 DACP 端口尚在 mDNS 解析中 (为 0)
        client.updateSession("192.168.2.105", 0, "123456789")
        assertTrue(client.hasActiveSession())
        assertEquals(0, client.getCurrentPort())

        // mDNS 解析到真实端口后动态注入
        client.updatePort(52431)
        assertEquals(52431, client.getCurrentPort())

        // 会话重置
        client.clearSession()
        assertFalse(client.hasActiveSession())
        assertEquals(0, client.getCurrentPort())
    }
}
