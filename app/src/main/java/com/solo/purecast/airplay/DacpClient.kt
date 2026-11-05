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
 */
class DacpClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DacpClient"
    }

    private var clientIp: String? = null
    private var dacpPort: Int = 0
    private var activeRemoteToken: String? = null

    /**
     * 更新当前 AirPlay 会话的 DACP 连接凭据
     */
    fun updateSession(ip: String, port: Int, token: String) {
        clientIp = ip
        dacpPort = port
        activeRemoteToken = token
        Logger.airplay.i(TAG, "更新 DACP 会话凭据: ip=$ip, port=$port, token=$token")
    }

    fun clearSession() {
        clientIp = null
        dacpPort = 0
        activeRemoteToken = null
        Logger.airplay.i(TAG, "清空 DACP 会话凭据")
    }

    fun playPause() = sendCommand("playpause")
    fun play() = sendCommand("play")
    fun pause() = sendCommand("pause")
    fun next() = sendCommand("nextitem")
    fun previous() = sendCommand("previtem")
    fun stop() = sendCommand("stop")

    private fun sendCommand(command: String) {
        val ip = clientIp ?: return
        val port = if (dacpPort > 0) dacpPort else 3689 // DACP 默认端口
        val token = activeRemoteToken

        scope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val urlString = "http://$ip:$port/ctrl-int/1/$command?session-id=1"
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
