package com.solo.purecast

import com.solo.purecast.airplay.DacpClient
import com.solo.purecast.core.session.RemoteCommandType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

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

    @Test
    fun testPendingCommandAutoRetryOnPortResolution() {
        val client = DacpClient(testScope)
        // 握手完成，端口尚未发现
        client.updateSession("192.168.2.105", 0, "token123")
        assertTrue(client.hasActiveSession())
        assertEquals(0, client.getCurrentPort())

        // 端口未就绪时发起播放/暂停指令，应暂存指令并先向默认端口发起试探
        client.playPause()

        // 当 mDNS 发现端口就绪后注入，应自动触发暂存指令重发
        client.updatePort(49152)
        assertEquals(49152, client.getCurrentPort())

        // 再次更新同端口不应产生额外暂存指令副作用
        client.updatePort(49152)
        assertEquals(49152, client.getCurrentPort())
    }

    @Test
    fun `executeRemoteCommand sends Active-Remote GET request and maps HTTP 200 vs errors`() = runBlocking {
        val capturedRequests = CopyOnWriteArrayList<List<String>>()
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            repeat(2) { idx ->
                try {
                    server.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            lines += line
                        }
                        capturedRequests += lines
                        val status = if (idx == 0) "200 OK" else "404 Not Found"
                        client.getOutputStream().write(
                            "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII)
                        )
                        client.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val client = DacpClient(testScope)
            client.updateSession("127.0.0.1", port, "9876543210")

            val okRes = client.executeRemoteCommand(RemoteCommandType.NEXT)
            assertTrue("HTTP 200 应返回成功", okRes.isSuccess)
            assertEquals(1, capturedRequests.size)
            assertTrue(capturedRequests[0].first().startsWith("GET /ctrl-int/1/nextitem "))
            assertTrue(capturedRequests[0].any { it.equals("Active-Remote: 9876543210", ignoreCase = true) })

            val errRes = client.executeRemoteCommand(RemoteCommandType.PREVIOUS)
            assertTrue("HTTP 404 应返回 failure", errRes.isFailure)

            // 连接被关闭的端口应稳定返回 Result.failure 而非抛出未捕获异常 (#20)
            server.close()
            val closedRes = client.executeRemoteCommand(RemoteCommandType.PLAY_PAUSE)
            assertTrue(closedRes.isFailure)
        } finally {
            runCatching { server.close() }
            serverThread.join(1000L)
        }
    }

    @Test
    fun `executeRemoteCommand refreshes stale refused port and retries on live port`() = runBlocking {
        // 1. 构造一个已关闭的死端口（模拟 iOS 重连后旧的 63302 端口报 ConnectException）
        val deadSocket = ServerSocket(0)
        val deadPort = deadSocket.localPort
        deadSocket.close()

        // 2. 启动一个新的真实监听端口（模拟 iOS 新会话的实时 DACP 端口）
        val liveServer = ServerSocket(0)
        val livePort = liveServer.localPort
        val capturedRequests = CopyOnWriteArrayList<String>()
        val serverThread = Thread {
            try {
                liveServer.accept().use { client ->
                    val firstLine = client.getInputStream().bufferedReader().readLine() ?: ""
                    capturedRequests += firstLine
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    client.getOutputStream().flush()
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val client = DacpClient(testScope)
            client.updateSession("127.0.0.1", deadPort, "live_token_123")

            val failedPortsReported = CopyOnWriteArrayList<Int>()
            client.onPortRefreshRequested = { failedPort ->
                failedPortsReported += failedPort
                livePort
            }

            val result = client.executeRemoteCommand(RemoteCommandType.PLAY)
            assertTrue("遇死端口刷新到新端口后应重试成功", result.isSuccess)
            assertEquals(listOf(deadPort), failedPortsReported)
            assertEquals("客户端应永久更新为新端口", livePort, client.getCurrentPort())
            assertEquals(1, capturedRequests.size)
            assertTrue(capturedRequests[0].startsWith("GET /ctrl-int/1/play "))
        } finally {
            runCatching { liveServer.close() }
            serverThread.join(1000L)
        }
    }

    @Test
    fun `hasResolvedDacpEndpoint and probeSessionAlive distinguish live vs refused vs missing endpoint`() = runBlocking {
        val client = DacpClient(testScope)
        assertFalse(client.hasResolvedDacpEndpoint())
        assertFalse(client.probeSessionAlive(100L))

        client.updateSession("127.0.0.1", 0, "tok")
        assertFalse(client.hasResolvedDacpEndpoint())
        assertFalse(client.probeSessionAlive(100L))

        val server = ServerSocket(0)
        val livePort = server.localPort
        val acceptThread = Thread {
            runCatching { server.accept().close() }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            client.updatePort(livePort)
            assertTrue(client.hasResolvedDacpEndpoint())
            assertTrue("监听中的 DACP TCP 端口应探活成功", client.probeSessionAlive(300L))
        } finally {
            runCatching { server.close() }
            acceptThread.join(500L)
        }

        // 端口关闭后应毫秒级返回不可达
        assertFalse("关闭后的 DACP TCP 端口应探活失败", client.probeSessionAlive(300L))
    }
}
