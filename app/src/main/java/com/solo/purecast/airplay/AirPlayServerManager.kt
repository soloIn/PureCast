package com.solo.purecast.airplay

import android.content.Context
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.core.session.ProtocolResumeReopenResult
import com.solo.purecast.core.session.ProtocolSessionBridge
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        private const val TEARDOWN_INITIAL_PROBE_DELAY_MS = 300L
        private const val SUSPEND_HEARTBEAT_INTERVAL_MS = 3_000L
        private const val DACP_RECONNECT_GRACE_MS = 1_500L
        private const val DACP_PROBE_TIMEOUT_MS = 500L
    }

    constructor(
        context: Context,
        scope: CoroutineScope,
        sessionBridge: ProtocolSessionBridge,
        clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    ) : this(
        context = context,
        scope = scope,
        dispatchEvent = { envelope -> sessionBridge.dispatch(envelope) },
        allocateSessionId = { sessionBridge.nextSessionId() },
        allocateSequence = { sessionBridge.nextSequence() },
        onAcquireHardwareForStream = { sampleRate, bitDepth, channels, codec ->
            sessionBridge.acquireHardwareForStream(
                StreamProtocol.AIRPLAY_1,
                sampleRate,
                bitDepth,
                channels,
                codec
            )
        },
        clockMs = clockMs
    )

    private val jmdnsDiscoveryService = JmDnsDiscoveryService(context)
    val dacpClient = DacpClient(scope).apply {
        onPortRefreshRequested = { failedPort ->
            jmdnsDiscoveryService.refreshDacpPortNow(excludePort = failedPort)
        }
    }

    private var isRunning = false
    private var currentPort = DEFAULT_RTSP_PORT
    private var currentDeviceName = "PureCast"
    private val serverLock = Any()
    private val dacpLivenessLock = Any()
    private var broadcastJob: Job? = null
    private var dacpLivenessJob: Job? = null
    private var dacpRemovedVerifyJob: Job? = null
    @Volatile
    private var probingSessionId: Long = 0L
    @Volatile
    private var pendingBroadcastIp: java.net.InetAddress? = null

    @Volatile
    var isRtpActive: Boolean = false
        private set

    private fun cancelDacpLivenessMonitor() {
        synchronized(dacpLivenessLock) {
            probingSessionId = 0L
            dacpLivenessJob?.cancel()
            dacpLivenessJob = null
            dacpRemovedVerifyJob?.cancel()
            dacpRemovedVerifyJob = null
        }
    }

    /**
     * 💡 DACP 存活探活裁决器：
     * RTSP TEARDOWN 到达后，不依赖不确定的前置状态与时间间隔，统一由 DACP 端口存活状态裁决：
     * 1) 无已解析 DACP 端点（不支持 DACP 的发端）-> 立即 hardStop=true 断开会话；
     * 2) 延迟 300ms 首次探活失败 -> 约 0.3s 立即 hardStop=true 断开会话；
     * 3) 首次探活成功 -> 发射 hardStop=false 进入/维持 10 分钟 PAUSED 保持态，并每 3s 轻探一次；
     * 4) 休眠期探活失败或收到 mDNS _dacp._tcp 下线 -> 经 1500ms 换 ID 续播宽限确认后立即 hardStop=true 断开会话。
     */
    private fun startDacpLivenessDecision(globalSessionId: Long) {
        if (globalSessionId <= 0L) return
        if (!dacpClient.hasResolvedDacpEndpoint()) {
            Logger.airplay.i(TAG, "TEARDOWN 时无可用 DACP 端点，直接判定为停止: sid=$globalSessionId")
            nativeDispatcher.emitTeardownDecision(globalSessionId, hardStop = true, reason = "no-dacp-endpoint")
            return
        }
        synchronized(dacpLivenessLock) {
            dacpLivenessJob?.cancel()
            dacpRemovedVerifyJob?.cancel()
            dacpRemovedVerifyJob = null
            probingSessionId = globalSessionId
            dacpLivenessJob = scope.launch {
                try {
                    kotlinx.coroutines.delay(TEARDOWN_INITIAL_PROBE_DELAY_MS)
                    if (isRtpActive || !nativeDispatcher.hasSession(globalSessionId)) {
                        return@launch
                    }
                    val initialAlive = dacpClient.probeSessionAlive(DACP_PROBE_TIMEOUT_MS)
                    if (isRtpActive || !nativeDispatcher.hasSession(globalSessionId)) {
                        return@launch
                    }
                    if (!initialAlive) {
                        nativeDispatcher.emitTeardownDecision(
                            globalSessionId = globalSessionId,
                            hardStop = true,
                            reason = "teardown-dacp-dead"
                        )
                        return@launch
                    }
                } finally {
                    synchronized(dacpLivenessLock) {
                        if (probingSessionId == globalSessionId) {
                            probingSessionId = 0L
                        }
                    }
                }

                // 💡 DACP 存活：确认为仅暂停休眠，进入/保持 10 分钟 PAUSED 会话并启动低频心跳
                nativeDispatcher.emitTeardownDecision(
                    globalSessionId = globalSessionId,
                    hardStop = false,
                    reason = "teardown-dacp-alive"
                )

                while (!isRtpActive && nativeDispatcher.hasSession(globalSessionId)) {
                    kotlinx.coroutines.delay(SUSPEND_HEARTBEAT_INTERVAL_MS)
                    if (isRtpActive || !nativeDispatcher.hasSession(globalSessionId)) {
                        break
                    }
                    val alive = dacpClient.probeSessionAlive(DACP_PROBE_TIMEOUT_MS)
                    if (!alive) {
                        // 💡 1500ms 换 DACP-ID 续播宽限窗口，覆盖 iOS 暂停恢复前 ~0.98s 重连窗口
                        kotlinx.coroutines.delay(DACP_RECONNECT_GRACE_MS)
                        if (!isRtpActive && nativeDispatcher.hasSession(globalSessionId)) {
                            val retryAlive = dacpClient.probeSessionAlive(DACP_PROBE_TIMEOUT_MS)
                            if (!retryAlive && !isRtpActive && nativeDispatcher.hasSession(globalSessionId)) {
                                nativeDispatcher.emitTeardownDecision(
                                    globalSessionId = globalSessionId,
                                    hardStop = true,
                                    reason = "suspend-heartbeat-dead"
                                )
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private val nativeDispatcher: AirPlayNativeEventDispatcher = AirPlayNativeEventDispatcher(
        dispatchEvent = dispatchEvent,
        allocateSessionId = allocateSessionId,
        allocateSequence = allocateSequence,
        onAcquireHardwareForStream = onAcquireHardwareForStream,
        onDecryptAndInjectCryptoKey = { aesKeyBase64, aesIvBase64 ->
            injectCryptoKey(aesKeyBase64, aesIvBase64)
        },
        onUpdateDacpTarget = { clientIp, dacpId, activeRemoteToken, dacpPort ->
            cancelDacpLivenessMonitor()
            if (clientIp.isNotEmpty()) {
                dacpClient.updateSession(clientIp, dacpPort, activeRemoteToken)
                jmdnsDiscoveryService.resolveDacpPort(
                    dacpId = dacpId,
                    clientIp = clientIp,
                    onPortResolved = { resolvedPort ->
                        dacpClient.updatePort(resolvedPort)
                    },
                    onServiceRemoved = { serviceName ->
                        // 💡 仅在 RTSP/RTP 通道已拆且已度过首次 300ms 探测期的休眠保持态作为快速触发通道：
                        // 立即主动探活核验，确认不可达后走 1500ms 换 ID 续播宽限，过滤 mDNS 假抖动与续播误杀
                        if (!isRtpActive && probingSessionId == 0L) {
                            val targetSid = nativeDispatcher.getCurrentGlobalSessionId()
                            if (targetSid > 0L) {
                                synchronized(dacpLivenessLock) {
                                    if (isRtpActive || probingSessionId != 0L) return@synchronized
                                    dacpRemovedVerifyJob?.cancel()
                                    dacpRemovedVerifyJob = scope.launch {
                                        if (dacpClient.probeSessionAlive(DACP_PROBE_TIMEOUT_MS)) {
                                            Logger.airplay.d(TAG, "忽略 mDNS 瞬态下线抖动（DACP 探活仍在线）: $serviceName")
                                            return@launch
                                        }
                                        kotlinx.coroutines.delay(DACP_RECONNECT_GRACE_MS)
                                        if (!isRtpActive && nativeDispatcher.hasSession(targetSid)) {
                                            if (!dacpClient.probeSessionAlive(DACP_PROBE_TIMEOUT_MS)) {
                                                nativeDispatcher.emitTeardownDecision(
                                                    globalSessionId = targetSid,
                                                    hardStop = true,
                                                    reason = "mdns-removed-verified:$serviceName"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        },
        onSolveChallenge = { challengeBase64 ->
            solveAppleChallenge(challengeBase64)
        },
        onRtpActiveChanged = { active ->
            isRtpActive = active
            if (active) {
                cancelDacpLivenessMonitor()
            }
        },
        onStreamTeardownRequested = { globalSid ->
            startDacpLivenessDecision(globalSid)
        },
        onInvalidateProgressAnchor = {
            NativeAudioEngine.invalidateAirPlayProgressAnchor()
        },
        clockMs = clockMs
    )

    fun start(deviceName: String, port: Int = DEFAULT_RTSP_PORT): Boolean = synchronized(serverLock) {
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

        // 💡 修复 Finding 1: 启动 JmDns Bonjour 广播前取消未完成任务，并记录目标 IP 防止冷启动 NetworkCallback 重复打断
        pendingBroadcastIp = jmdnsDiscoveryService.getLocalIpv4Address()
        broadcastJob?.cancel()
        broadcastJob = scope.launch(Dispatchers.IO) {
            jmdnsDiscoveryService.startBroadcast(deviceName, 7000, port)
        }
        isRunning = true
        Logger.airplay.i(TAG, "原生 AirPlay 1 服务已完全就绪")
        return true
    }

    fun stop() = synchronized(serverLock) {
        if (!isRunning) return
        Logger.airplay.i(TAG, "正在停止 AirPlay 1 服务...")
        cancelDacpLivenessMonitor()
        nativeDispatcher.resetSessions()
        broadcastJob?.cancel()
        broadcastJob = null
        pendingBroadcastIp = null
        // 💡 使用独立于 lifecycleScope 的下线线程发送 mDNS Goodbye 包，避免 Service.onDestroy() 取消协程导致下线广播被中断
        jmdnsDiscoveryService.stopBroadcastDetached()
        NativeAudioEngine.stopAirPlayServer()
        dacpClient.clearSession()
        isRunning = false
        Logger.airplay.i(TAG, "AirPlay 1 服务已完全停止")
    }

    /**
     * 💡 永久释放服务及其底层 mDNS 协程作用域（在宿主服务销毁时调用）
     */
    fun destroy() = synchronized(serverLock) {
        stop()
        jmdnsDiscoveryService.destroy()
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

    fun updateDeviceName(newName: String) = synchronized(serverLock) {
        currentDeviceName = newName
        if (isRunning) {
            pendingBroadcastIp = jmdnsDiscoveryService.getLocalIpv4Address()
            broadcastJob?.cancel()
            broadcastJob = scope.launch(Dispatchers.IO) {
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
    fun restartIfIpChanged(deviceName: String) = synchronized(serverLock) {
        if (!isRunning) {
            Logger.airplay.i(TAG, "AirPlay 服务尚未启动，直接执行冷启动流程")
            start(deviceName, currentPort)
            return
        }

        val currentBoundIp = jmdnsDiscoveryService.getCurrentBoundIp()
        val newIp = jmdnsDiscoveryService.getLocalIpv4Address()

        // 💡 若针对同一目标 IP 的启动/重启任务已在执行中，严禁反复取消重来（否则冷启动 10ms 内触发多次注册→立刻 Goodbye 毒化 iOS mDNS 缓存）
        if (newIp != null && broadcastJob?.isActive == true && pendingBroadcastIp == newIp) {
            return
        }

        val needRestart = newIp != null && (newIp != currentBoundIp || !jmdnsDiscoveryService.isRunning())
        if (needRestart) {
            Logger.airplay.i(TAG, "检测到有效局域网 IP 变动 (${currentBoundIp?.hostAddress} -> ${newIp?.hostAddress})，重新启动 AirPlay 广播...")
            pendingBroadcastIp = newIp
            broadcastJob?.cancel()
            broadcastJob = scope.launch(Dispatchers.IO) {
                jmdnsDiscoveryService.stopBroadcast()
                kotlinx.coroutines.delay(300)
                jmdnsDiscoveryService.startBroadcast(deviceName, 7000, currentPort)
            }
        }
    }

    /**
     * 💡 彻底清理 DACP 会话与凭据 (由协调器在会话彻底超时或主动停止时调用)
     */
    fun clearDacpSession() {
        Logger.airplay.i(TAG, "彻底清理 DACP 会话凭据与目标监听")
        cancelDacpLivenessMonitor()
        jmdnsDiscoveryService.clearDacpTarget()
        dacpClient.clearSession()
    }

    /**
     * 💡 抢占或 10 分钟挂起超时时仅清理当前 AirPlay 流与 DACP 会话，保留 RTSP/mDNS 在线待连
     */
    fun preemptActiveSession(targetGlobalSessionId: Long? = null) {
        if (targetGlobalSessionId != null && !nativeDispatcher.hasSession(targetGlobalSessionId)) {
            Logger.airplay.d(TAG, "忽略过期 AirPlay 抢占指令: targetSid=$targetGlobalSessionId")
            return
        }
        val wasRtpActive = isRtpActive
        Logger.airplay.i(TAG, "中止当前活跃 AirPlay 会话并保留 mDNS/RTSP 监听 (targetSid=$targetGlobalSessionId, wasRtpActive=$wasRtpActive)")
        // 💡 仅在 RTP/RTSP 仍活跃（如跨协议抢占或播放中主动停止）时才强制拆连接、刷静音并通知对端 stop；
        // 若对端已主动 TEARDOWN（wasRtpActive=false），底层已完成停流与静音，避免与 TEARDOWN 200 OK 竞态或误停手机播放。
        if (wasRtpActive) {
            NativeAudioEngine.preemptAirPlayClient()
            NativeAudioEngine.emergencyMuteAndFlush(FlushReason.PREEMPT.nativeCode)
            dacpClient.stop()
        }
        nativeDispatcher.resetSessions()
        clearDacpSession()
    }

    /**
     * 💡 P0 #2 重开 USB DAC 后校验 AirPlay 底层管线是否仍可恢复，并同步刷新 Native RTP active_epoch
     */
    fun resumeAfterDeviceReopen(sessionId: Long, newEpoch: Long): ProtocolResumeReopenResult {
        if (!isRunning || !nativeDispatcher.hasSession(sessionId)) {
            Logger.airplay.w(TAG, "AirPlay 重开声卡校验失败：会话不存在 (sid=$sessionId)")
            return ProtocolResumeReopenResult.PRODUCER_DEAD
        }
        val alive = NativeAudioEngine.updateAirPlayActiveEpoch(newEpoch)
        if (!alive) {
            Logger.airplay.w(TAG, "AirPlay 重开声卡校验失败：Native RTSP/RTP 管线已失活 (sid=$sessionId, epoch=$newEpoch)")
            isRtpActive = false
            return ProtocolResumeReopenResult.PRODUCER_DEAD
        }
        isRtpActive = true
        Logger.airplay.i(TAG, "AirPlay 重开声卡并同步 epoch=$newEpoch 成功，恢复推流")
        return ProtocolResumeReopenResult.STREAMING_READY
    }

    fun isRunning(): Boolean = isRunning

    /**
     * 💡 通知 AirPlay 会话即将触发 TV 端寻道指令，标记 4000ms 响应窗口，防止 FLUSH 冲刷归零进度
     */
    fun notifySeekTriggered() {
        nativeDispatcher.markPendingSeek()
    }
}
