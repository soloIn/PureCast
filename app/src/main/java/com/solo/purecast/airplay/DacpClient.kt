package com.solo.purecast.airplay

import com.solo.purecast.core.session.RemoteCommandType
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    @Volatile
    private var clientIp: String? = null
    @Volatile
    private var dacpPort: Int = 0
    @Volatile
    private var activeRemoteToken: String? = null
    @Volatile
    private var pendingCommand: String? = null
    @Volatile
    var onPortRefreshRequested: (suspend (failedPort: Int) -> Int?)? = null

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

    /**
     * 💡 设置当前播放时间戳 (ms)，用于 AirPlay 远端精准寻道
     */
    suspend fun setPlayingTime(positionMs: Long): Result<Unit> =
        sendCommandAndAwait("setproperty?dacp.playingtime=$positionMs")

    /**
     * 💡 串行执行挂起式 DACP 控制指令（供 [com.solo.purecast.core.session.PlaybackSessionCoordinator] 使用），
     * 返回真实网络响应成功/失败回执，确保连按切歌指令不并发交错且支持 5000ms 超时判定。
     */
    suspend fun executeRemoteCommand(commandType: RemoteCommandType): Result<Unit> {
        val command = when (commandType) {
            RemoteCommandType.PLAY -> "play"
            RemoteCommandType.PAUSE -> "pause"
            RemoteCommandType.PLAY_PAUSE -> "playpause"
            RemoteCommandType.NEXT -> "nextitem"
            RemoteCommandType.PREVIOUS -> "previtem"
        }
        return sendCommandAndAwait(command)
    }

    suspend fun sendCommandAndAwait(command: String): Result<Unit> {
        val params = prepareCommandParams(command)
            ?: return Result.failure(IllegalStateException("DACP 会话未连接，无法发送指令 [$command]"))
        return withContext(Dispatchers.IO) {
            val firstResult = executeHttpCommand(params.first, params.second, params.third, command)
            if (firstResult.isSuccess) {
                // 💡 指令已通过当前端口（含 3689 默认探测端口）成功执行，立即清空暂存防止 mDNS 回调重复发令
                if (pendingCommand == command) {
                    pendingCommand = null
                }
                return@withContext firstResult
            }

            // 💡 iOS 重连或切换会话后会更换随机 DACP 端口，旧端口会立刻报 ConnectException；
            // 触发 mDNS 强制刷新端口（显式排除已失败的旧端口）并立即重试一次，彻底解决 TV 遥控器按钮间歇失灵。
            val refreshedPort = onPortRefreshRequested?.invoke(params.second)
            if (refreshedPort != null && refreshedPort > 0 && refreshedPort != params.second) {
                dacpPort = refreshedPort
                Logger.airplay.i(TAG, "DACP 端口已刷新 (${params.second} -> $refreshedPort)，自动重试指令 [$command]")
                val retryResult = executeHttpCommand(params.first, refreshedPort, params.third, command)
                if (retryResult.isSuccess && pendingCommand == command) {
                    pendingCommand = null
                }
                return@withContext retryResult
            }
            firstResult
        }
    }

    private fun sendCommand(command: String) {
        scope.launch(Dispatchers.IO) {
            sendCommandAndAwait(command)
        }
    }

    private fun prepareCommandParams(command: String): Triple<String, Int, String?>? {
        val ip = clientIp
        if (ip.isNullOrEmpty()) {
            Logger.airplay.w(TAG, "未连接 AirPlay 发送端，忽略 DACP 指令 [$command]")
            return null
        }
        val port = if (dacpPort > 0) dacpPort else 3689
        val token = activeRemoteToken
        if (dacpPort <= 0) {
            pendingCommand = command
            Logger.airplay.i(TAG, "DACP 动态端口未就绪，暂存 [$command] 并先向 3689 端口试探")
        }
        return Triple(ip, port, token)
    }

    private fun executeHttpCommand(
        ip: String,
        port: Int,
        token: String?,
        command: String
    ): Result<Unit> {
        var conn: HttpURLConnection? = null
        return try {
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
            if (responseCode in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("DACP 指令 [$command] HTTP 响应失败: $responseCode"))
            }
        } catch (e: Exception) {
            Logger.airplay.w(TAG, "发送 DACP 指令 [$command] 异常: ${e.message}")
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
