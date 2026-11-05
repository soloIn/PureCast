package com.solo.purecast.airplay

import android.content.Context
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * 💡 AirPlayServerManager: 原生 AirPlay 1 (RAOP) 接收引擎管理器
 * 统筹调度：
 * 1. JmDnsDiscoveryService: _airplay._tcp 与 _raop._tcp 零配置局域网服务广播
 * 2. NativeAudioEngine: C++20 RTSP 状态机、RTP JitterBuffer、AES-128 解密与 ALAC 解码
 * 3. AirPlayKeyDecryptor: RSA-2048 私钥解密 AES 会话主密钥
 * 4. DacpClient: DACP 遥控通道双向反控
 */
class AirPlayServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStreamRequestedListener: (sampleRate: Int, bitDepth: Int, channels: Int, clientIp: String, dacpPort: Int, token: String) -> Boolean,
    private val onStreamStoppedListener: () -> Unit,
    private val onMetadataReceivedListener: (title: String, artist: String, album: String, durationMs: Long, coverArt: ByteArray?) -> Unit,
    private val onProgressUpdatedListener: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "AirPlayServerManager"
        const val DEFAULT_RTSP_PORT = 5000
    }

    private val jmdnsDiscoveryService = JmDnsDiscoveryService(context)
    val dacpClient = DacpClient(scope)

    private var isRunning = false
    private var currentPort = DEFAULT_RTSP_PORT
    private var currentDeviceName = "PureCast"

    fun start(deviceName: String, port: Int = DEFAULT_RTSP_PORT): Boolean {
        if (isRunning) {
            Logger.airplay.w(TAG, "AirPlay 服务已处于运行状态，无需重复启动")
            return true
        }

        currentDeviceName = deviceName
        currentPort = port
        Logger.airplay.i(TAG, "正在启动原生 AirPlay 1 服务 (名称: $deviceName, RTSP 端口: $port)...")

        val nativeStarted = NativeAudioEngine.startAirPlayServer(port, object : NativeAirPlayCallback {
            override fun onStreamRequested(
                sampleRate: Int,
                bitDepth: Int,
                channels: Int,
                clientIp: String,
                aesKeyBase64: String,
                aesIvBase64: String,
                dacpId: String,
                activeRemoteToken: String,
                dacpPort: Int
            ): Boolean {
                Logger.airplay.i(TAG, "收到 AirPlay 推流请求: $clientIp, rate=$sampleRate, depth=$bitDepth, ch=$channels")

                // 1. RSA 私钥解密 AES 会话主密钥
                if (aesKeyBase64.isNotEmpty() && aesIvBase64.isNotEmpty()) {
                    try {
                        val decryptedKey = AirPlayKeyDecryptor.decryptRsaAesKey(aesKeyBase64)
                        val ivBytes = Base64.getDecoder().decode(aesIvBase64)
                        if (decryptedKey != null && ivBytes != null) {
                            Logger.airplay.i(TAG, "成功解密 AES-128 会话密钥与 IV，注入底层协议栈")
                            NativeAudioEngine.setAirPlayCryptoKey(decryptedKey, ivBytes)
                        } else {
                            Logger.airplay.e(TAG, "解密 AES 密钥失败，可能导致音频静音或解码异常")
                        }
                    } catch (e: Exception) {
                        Logger.airplay.e(TAG, "处理 AirPlay 密钥注入异常", e)
                    }
                }

                // 2. 更新 DACP 反向控制凭证
                if (clientIp.isNotEmpty()) {
                    dacpClient.updateSession(clientIp, dacpPort, activeRemoteToken)
                }

                // 3. 回调上层申请音频焦点与 USB 声卡独占
                return onStreamRequestedListener(sampleRate, bitDepth, channels, clientIp, dacpPort, activeRemoteToken)
            }

            override fun onStreamStopped() {
                Logger.airplay.i(TAG, "AirPlay 推流连接断开或停止")
                dacpClient.clearSession()
                onStreamStoppedListener()
            }

            override fun onChallengeRequested(challengeBase64: String): String? {
                val localIp = jmdnsDiscoveryService.getLocalIpv4Address()
                val macStr = jmdnsDiscoveryService.getMacAddressFormatted()
                if (localIp == null) {
                    Logger.airplay.e(TAG, "本地 IP 不可用，无法计算 Apple-Response")
                    return null
                }
                val macBytes = ByteArray(6) { idx ->
                    macStr.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
                }
                val response = AirPlayKeyDecryptor.solveAppleChallenge(challengeBase64, localIp, macBytes)
                Logger.airplay.i(TAG, "计算 Apple-Challenge 响应: challenge=$challengeBase64, response=$response")
                return response
            }

            override fun onMetadataReceived(
                title: String,
                artist: String,
                album: String,
                durationMs: Long,
                coverArt: ByteArray?
            ) {
                Logger.airplay.i(TAG, "AirPlay 收到 DMAP 元数据: title=$title, artist=$artist, album=$album, coverSize=${coverArt?.size ?: 0}")
                onMetadataReceivedListener(title, artist, album, durationMs, coverArt)
            }

            override fun onProgressUpdated(positionMs: Long, durationMs: Long) {
                onProgressUpdatedListener(positionMs, durationMs)
            }
        })

        if (!nativeStarted) {
            Logger.airplay.e(TAG, "启动 Native RAOP 服务失败")
            return false
        }

        // 启动 JmDns Bonjour 广播
        scope.launch(Dispatchers.IO) {
            jmdnsDiscoveryService.startBroadcast(deviceName, 7000, port)
        }
        isRunning = true
        Logger.airplay.i(TAG, "原生 AirPlay 1 服务已完全就绪")
        return true
    }

    fun stop() {
        if (!isRunning) return
        Logger.airplay.i(TAG, "正在停止 AirPlay 1 服务...")
        scope.launch(Dispatchers.IO) {
            jmdnsDiscoveryService.stopBroadcast()
        }
        NativeAudioEngine.stopAirPlayServer()
        dacpClient.clearSession()
        isRunning = false
        Logger.airplay.i(TAG, "AirPlay 1 服务已完全停止")
    }

    fun updateDeviceName(newName: String) {
        currentDeviceName = newName
        if (isRunning) {
            scope.launch(Dispatchers.IO) {
                jmdnsDiscoveryService.stopBroadcast()
                jmdnsDiscoveryService.startBroadcast(newName, 7000, currentPort)
            }
        }
    }

    fun isRunning(): Boolean = isRunning
}
