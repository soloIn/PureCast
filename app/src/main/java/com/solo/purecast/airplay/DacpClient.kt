package com.solo.purecast.airplay

import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * 💡 DacpClient: Apple DACP (Digital Audio Control Protocol) 遥控协议客户端
 * 用于将 TV 遥控器的多媒体按键 (播放、暂停、上一曲、下一曲) 反向同步至 iPhone / Mac 音乐源
 *
 * 核心设计：
 * 1. 严格遵循 Apple DACP 规范: GET /ctrl-int/1/<command>，请求头携带 Active-Remote: <token>
 * 2. 动态端口适应: 兼容 iOS 随机分配的临时端口 (由 mDNS _dacp._tcp 动态发现注入) 与 macOS 默认 3689 端口
 * 3. 待发指令容错: 若端口尚未解析完成即收到按键，暂存最新指令并在端口解析就绪后即刻补发
 */
class DacpClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DacpClient"

        /**
         * 💡 构建合规的 DACP 请求 URL
         */
        fun buildUrl(ip: String, port: Int, command: String): String {
            return "http://$ip:$port/ctrl-int/1/$command"
        }
    }

    private var clientIp: String? = null
    private var dacpPort: Int = 0
    private var activeRemoteToken: String? = null
    private var pendingCommand: String? = null

    /**
     * 更新当前 AirPlay 会话的 DACP 连接凭据
     */
    fun updateSession(ip: String, port: Int, token: String) {
        clientIp = ip
        dacpPort = port
        activeRemoteToken = token
        Logger.airplay.i(TAG, "更新 DACP 会话凭据: ip=$ip, port=$port, token=$token")
    }

    /**
     * 💡 动态注入通过 mDNS 解析出的发送端真实 DACP 监听端口
     */
    fun updatePort(port: Int) {
        if (port > 0) {
            dacpPort = port
            Logger.airplay.i(TAG, "DACP 真实端口解析就绪: $port")

            // 💡 若存在因端口未就绪而暂存的按键指令，立即自动重发
            val cmd = pendingCommand
            if (cmd != null) {
                pendingCommand = null
                Logger.airplay.i(TAG, "执行暂存的 DACP 指令: [$cmd]")
                sendCommand(cmd)
            }
        }
    }

    fun hasActiveSession(): Boolean = !clientIp.isNullOrEmpty()

    fun getCurrentPort(): Int = dacpPort

    fun clearSession() {
        clientIp = null
        dacpPort = 0
        activeRemoteToken = null
        pendingCommand = null
        Logger.airplay.i(TAG, "清空 DACP 会话凭据")
    }

    fun playPause() = sendCommand("playpause")
    fun play() = sendCommand("play")
    fun pause() = sendCommand("pause")
    fun next() = sendCommand("nextitem")
    fun previous() = sendCommand("previtem")
    fun stop() = sendCommand("stop")

    private fun sendCommand(command: String) {
        val ip = clientIp
        if (ip.isNullOrEmpty()) {
            Logger.airplay.w(TAG, "未连接 AirPlay 发送端，忽略 DACP 指令 [$command]")
            return
        }

        val port = if (dacpPort > 0) dacpPort else 3689
        val token = activeRemoteToken

        // 💡 如果尚未从 mDNS 解析出真实端口，暂存指令以备端口就绪后补发，但同时先尝试向当前端口 (或默认 3689) 发送一次
        if (dacpPort <= 0) {
            pendingCommand = command
            Logger.airplay.i(TAG, "DACP 动态端口未就绪，暂存 [$command] 并先向 3689 端口试探")
        }

        scope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val urlString = buildUrl(ip, port, command)
                val url = URL(urlString)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 1500
                    if (!token.isNullOrEmpty()) {
                        setRequestProperty("Active-Remote", token)
                    }
                    setRequestProperty("User-Agent", "iTunes/12.11 (Windows; N)")
                }

                val responseCode = conn.responseCode
                Logger.airplay.i(TAG, "发送 DACP 指令 [$command] 至 $ip:$port 响应码: $responseCode")
            } catch (e: Exception) {
                Logger.airplay.w(TAG, "发送 DACP 指令 [$command] 异常: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }
}
