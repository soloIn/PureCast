package com.solo.purecast.airplay

import android.content.Context
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * 💡 AirPlayServerManager: 无状态原生 AirPlay 1 (RAOP) 接收引擎管理器
 * 职责严格收敛为：mDNS Bonjour 广播、RSA/AES 解密密钥注入、DACP 客户端托管与向协调器上报带序号事件
 */
class AirPlayServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dispatchEvent: (SessionEventEnvelope<SessionEventPayload>) -> Unit,
    private val allocateSessionId: () -> Long,
    private val allocateSequence: () -> Long,
    private val onAcquireHardwareForStream: (sampleRate: Int, bitDepth: Int, channels: Int, codec: String) -> Long,
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
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

    @Volatile
    var isRtpActive: Boolean = false
        private set

    private val nativeDispatcher = AirPlayNativeEventDispatcher(
        dispatchEvent = dispatchEvent,
        allocateSessionId = allocateSessionId,
        allocateSequence = allocateSequence,
        onAcquireHardwareForStream = onAcquireHardwareForStream,
        onDecryptAndInjectCryptoKey = { aesKeyBase64, aesIvBase64 ->
            injectCryptoKey(aesKeyBase64, aesIvBase64)
        },
        onUpdateDacpTarget = { clientIp, dacpId, activeRemoteToken, dacpPort ->
            if (clientIp.isNotEmpty()) {
                dacpClient.updateSession(clientIp, dacpPort, activeRemoteToken)
                jmdnsDiscoveryService.resolveDacpPort(dacpId, clientIp) { resolvedPort ->
                    dacpClient.updatePort(resolvedPort)
                }
            }
        },
        onSolveChallenge = { challengeBase64 ->
            solveAppleChallenge(challengeBase64)
        },
        onRtpActiveChanged = { active ->
            isRtpActive = active
        },
        clockMs = clockMs
    )

    fun start(deviceName: String, port: Int = DEFAULT_RTSP_PORT): Boolean {
        if (isRunning) {
            Logger.airplay.w(TAG, "AirPlay 服务已处于运行状态，无需重复启动")
            return true
        }

        currentDeviceName = deviceName
        currentPort = port
        Logger.airplay.i(TAG, "正在启动原生 AirPlay 1 服务 (名称: $deviceName, RTSP 端口: $port)...")

        val nativeStarted = NativeAudioEngine.startAirPlayServer(port, nativeDispatcher)

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
        nativeDispatcher.resetSessions()
        scope.launch(Dispatchers.IO) {
            jmdnsDiscoveryService.stopBroadcast()
        }
        NativeAudioEngine.stopAirPlayServer()
        dacpClient.clearSession()
        isRunning = false
        Logger.airplay.i(TAG, "AirPlay 1 服务已完全停止")
    }

    private fun injectCryptoKey(aesKeyBase64: String, aesIvBase64: String) {
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
        } else {
            Logger.airplay.i(TAG, "推流未携带 AES 会话密钥 (明文/非加密流)，重置底层解密上下文")
            NativeAudioEngine.setAirPlayCryptoKey(ByteArray(0), ByteArray(0))
        }
    }

    private fun solveAppleChallenge(challengeBase64: String): String? {
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

    fun updateDeviceName(newName: String) {
        currentDeviceName = newName
        if (isRunning) {
            scope.launch(Dispatchers.IO) {
                jmdnsDiscoveryService.stopBroadcast()
                jmdnsDiscoveryService.startBroadcast(newName, 7000, currentPort)
            }
        }
    }

    /**
     * 💡 获取当前 JmDNS 绑定的 IP 地址
     */
    fun getCurrentBoundIp(): java.net.InetAddress? = jmdnsDiscoveryService.getCurrentBoundIp()

    /**
     * 💡 动态检测并响应网络 IP 变更 (如开机冷启动后 Wi-Fi 建立连接)
     */
    fun restartIfIpChanged(deviceName: String) {
        val currentBoundIp = jmdnsDiscoveryService.getCurrentBoundIp()
        val newIp = jmdnsDiscoveryService.getLocalIpv4Address()

        val needRestart = newIp != null && (newIp != currentBoundIp || !jmdnsDiscoveryService.isRunning())
        if (needRestart) {
            Logger.airplay.i(TAG, "检测到有效局域网 IP 变动 (${currentBoundIp?.hostAddress} -> ${newIp?.hostAddress})，重新启动 AirPlay 广播...")
            scope.launch(Dispatchers.IO) {
                jmdnsDiscoveryService.stopBroadcast()
                kotlinx.coroutines.delay(500)
                jmdnsDiscoveryService.startBroadcast(deviceName, 7000, currentPort)
            }
        } else if (!isRunning) {
            start(deviceName)
        }
    }

    /**
     * 💡 彻底清理 DACP 会话与凭据 (由协调器在会话彻底超时或主动停止时调用)
     */
    fun clearDacpSession() {
        Logger.airplay.i(TAG, "彻底清理 DACP 会话凭据与目标监听")
        jmdnsDiscoveryService.clearDacpTarget()
        dacpClient.clearSession()
    }

    /**
     * 💡 抢占或 10 分钟挂起超时时仅清理当前 AirPlay 流与 DACP 会话，保留 RTSP/mDNS 在线待连
     */
    fun preemptActiveSession() {
        Logger.airplay.i(TAG, "中止当前活跃 AirPlay 会话并保留 mDNS/RTSP 监听")
        NativeAudioEngine.emergencyMuteAndFlush(com.solo.purecast.model.FlushReason.PREEMPT.nativeCode)
        nativeDispatcher.resetSessions()
        dacpClient.stop()
        clearDacpSession()
    }

    fun isRunning(): Boolean = isRunning
}
