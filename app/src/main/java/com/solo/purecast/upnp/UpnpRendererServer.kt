package com.solo.purecast.upnp

import com.solo.purecast.core.session.ErrorClearedEvent
import com.solo.purecast.core.session.ErrorReportedEvent
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.HardwareFastPathController
import com.solo.purecast.core.session.HardwareReconfiguredEvent
import com.solo.purecast.core.session.MetadataPatchEvent
import com.solo.purecast.core.session.ProgressUpdateEvent
import com.solo.purecast.core.session.ProtocolResumeReopenResult
import com.solo.purecast.core.session.ProtocolSessionBridge
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamSetupEvent
import com.solo.purecast.core.session.StreamStopEvent
import com.solo.purecast.core.session.TrackBoundaryEvent
import com.solo.purecast.core.session.TrackCompleteEvent
import com.solo.purecast.core.session.TrackSwitchEvent
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.PlaybackError
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.util.Logger
import com.solo.purecast.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * 💡 PureCast 无状态 UPnP / DLNA MediaRenderer 协议栈服务端
 *
 * 架构边界（R1/U3）：
 * 1. 不直接持有或依赖 Android 前台播放保活服务；所有播放态变化统一封包 [SessionEventEnvelope] 上报协调器；
 * 2. SOAP Pause / Stop / Preempt 同步直通 [HardwareFastPathController] 执行无锁快路径静音；
 * 3. `GetTransportInfo` 等查询仅只读投影当前状态快照。
 */
class UpnpRendererServer(
    private val scope: CoroutineScope,
    private val dispatchEvent: (SessionEventEnvelope<SessionEventPayload>) -> Unit,
    private val allocateSessionId: () -> Long,
    private val allocateSequence: () -> Long,
    private val onAcquireHardwareForStream: (sampleRate: Int, bitDepth: Int, channels: Int, codec: String) -> Long,
    private val snapshotProvider: () -> PlayerSessionSnapshot,
    private val fastPath: HardwareFastPathController = HardwareFastPathController.default,
    private val audioGateway: DemuxerAudioGateway = NativeDemuxerAudioGateway,
    private val onWakeUpUi: (() -> Unit)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private var friendlyName: String = "PureCast TV",
    private val httpPort: Int = 7952
) {

    private var deviceUuid = "uuid:" + UUID.nameUUIDFromBytes(friendlyName.toByteArray()).toString()
    private var localIpAddress: String = "127.0.0.1"

    private var serverSocket: ServerSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var ssdpJob: Job? = null
    private var httpJob: Job? = null

    val genaNotifier = GenaEventNotifier(
        scope = scope,
        currentStateProvider = {
            val snap = snapshotProvider()
            if (snap.activeProtocol == StreamProtocol.UPNP_DLNA) {
                when (snap.playbackStatus) {
                    PlaybackStatus.PLAYING -> "PLAYING"
                    PlaybackStatus.PAUSED -> "PAUSED_PLAYBACK"
                    PlaybackStatus.STOPPED, PlaybackStatus.IDLE -> "STOPPED"
                }
            } else {
                "STOPPED"
            }
        }
    )
    private var demuxer: StreamDemuxer? = null

    private val sessionStateLock = Any()
    private var activeUpnpSessionId: Long = 0L
    private var trackGenerationCounter: Long = 0L
    private var sessionSetupDispatched: Boolean = false
    @Volatile
    private var isLocallyPaused: Boolean = false

    @Volatile
    private var currentUri: String = ""

    @Volatile
    private var currentTrackMetadata = TrackMetadata()

    constructor(
        scope: CoroutineScope,
        sessionBridge: ProtocolSessionBridge,
        snapshotProvider: () -> PlayerSessionSnapshot,
        fastPath: HardwareFastPathController = HardwareFastPathController.default,
        audioGateway: DemuxerAudioGateway = NativeDemuxerAudioGateway,
        onWakeUpUi: (() -> Unit)? = null,
        clockMs: () -> Long = { System.currentTimeMillis() },
        friendlyName: String = "PureCast TV",
        httpPort: Int = 7952
    ) : this(
        scope = scope,
        dispatchEvent = { envelope -> sessionBridge.dispatch(envelope) },
        allocateSessionId = { sessionBridge.nextSessionId() },
        allocateSequence = { sessionBridge.nextSequence() },
        onAcquireHardwareForStream = { sampleRate, bitDepth, channels, codec ->
            sessionBridge.acquireHardwareForStream(
                StreamProtocol.UPNP_DLNA,
                sampleRate,
                bitDepth,
                channels,
                codec
            )
        },
        snapshotProvider = snapshotProvider,
        fastPath = fastPath,
        audioGateway = audioGateway,
        onWakeUpUi = onWakeUpUi,
        clockMs = clockMs,
        friendlyName = friendlyName,
        httpPort = httpPort
    )

    /**
     * 💡 获取当前流播放进度秒数
     */
    fun getCurrentPlayPositionSeconds(): Long = demuxer?.getCurrentPlayPositionSeconds() ?: 0L

    fun getActiveIpAddress(): String = localIpAddress

    /**
     * 💡 获取音轨总时长秒数
     */
    fun getTotalDurationSeconds(): Long = demuxer?.getTotalDurationSeconds() ?: 0L

    /**
     * 💡 动态更新 UPnP 设备广播名称并重新广播 SSDP
     */
    fun updateFriendlyName(newName: String) {
        val trimmed = newName.trim().ifEmpty { "PureCast TV" }
        if (friendlyName == trimmed) return

        Logger.upnp.i(TAG, "UPnP 友好名称变更: $friendlyName -> $trimmed")
        scope.launch(Dispatchers.IO) {
            sendSsdpByebye(waitForCompletion = true)
            friendlyName = trimmed
            deviceUuid = "uuid:" + UUID.nameUUIDFromBytes(trimmed.toByteArray()).toString()
            sendSsdpAlive()
            Logger.upnp.i(TAG, "已完成新名称 SSDP 重新广播: $friendlyName, UUID: $deviceUuid")
        }
    }

    fun notifyTransportState(state: String) {
        genaNotifier.notifyTransportState(state)
    }

    /**
     * 💡 主动中止当前 UPnP 解复用器与流传输（如被 AirPlay 抢占或停止广播时调用）
     */
    fun stopPlayback(isPreempted: Boolean = true, targetSessionId: Long? = null) {
        synchronized(sessionStateLock) {
            if (targetSessionId != null && activeUpnpSessionId != targetSessionId) {
                Logger.upnp.d(
                    TAG,
                    "忽略过期 UPnP 停止/抢占指令: targetSid=$targetSessionId, activeSid=$activeUpnpSessionId"
                )
                return
            }
        }
        Logger.upnp.i(TAG, "主动中止 UPnP 流播放 (isPreempted=$isPreempted, targetSid=$targetSessionId)")
        demuxer?.stop()
        val reason = if (isPreempted) FlushReason.PREEMPT else FlushReason.STOP_OR_TEARDOWN
        val stoppedEpoch = fastPath.emergencyMuteAndFlush(reason)
        synchronized(sessionStateLock) {
            isLocallyPaused = false
            val activeSnap = snapshotProvider()
            val canEmitStop = activeUpnpSessionId != 0L && sessionSetupDispatched &&
                (activeSnap.sessionId == activeUpnpSessionId || (!isPreempted && (activeSnap.sessionId == 0L || activeSnap.activeProtocol == StreamProtocol.UPNP_DLNA)))
            if (canEmitStop) {
                emitEventLocked(StreamStopEvent(newEpoch = stoppedEpoch, isPreempted = isPreempted))
            }
            sessionSetupDispatched = false
            if (isPreempted) {
                activeUpnpSessionId = 0L
            }
        }
        notifyTransportState("STOPPED")
    }

    /**
     * 💡 P0 #2 重开 USB DAC 后恢复 UPnP 拉流或复用仍活跃的解复用器
     */
    fun resumeStreamAfterDeviceReopen(sessionId: Long): ProtocolResumeReopenResult {
        val uriToRestart = synchronized(sessionStateLock) {
            if (activeUpnpSessionId == 0L || activeUpnpSessionId != sessionId || currentUri.isBlank()) {
                null
            } else {
                currentUri
            }
        } ?: run {
            Logger.upnp.w(TAG, "UPnP 重开声卡校验失败：会话或 URI 无效 (sid=$sessionId)")
            return ProtocolResumeReopenResult.PRODUCER_DEAD
        }

        val existing = demuxer
        if (existing != null && existing.isRunning() && existing.getCurrentUri() == uriToRestart) {
            Logger.upnp.i(TAG, "UPnP 重开声卡校验通过：Demuxer 仍在推流 (sid=$sessionId)")
            return ProtocolResumeReopenResult.STREAMING_READY
        }

        Logger.upnp.i(TAG, "UPnP 重开声卡后重新拉起 Demuxer 等待首帧 (sid=$sessionId, uri=$uriToRestart)")
        ensureDemuxer().start(uriToRestart)
        return ProtocolResumeReopenResult.RESTARTING
    }

    /**
     * 💡 动态检测并响应网络 IP 变更 (如开机冷启动后 Wi-Fi 建立连接)
     */
    fun restartIfIpChanged(): Boolean {
        val newIp = getActiveLocalIpAddress()
        if (newIp != "127.0.0.1" && (newIp != localIpAddress || localIpAddress == "127.0.0.1")) {
            Logger.upnp.i(TAG, "检测到有效局域网 IP 变动 ($localIpAddress -> $newIp)，重新初始化 UPnP Server...")
            start()
            return true
        }
        return false
    }

    fun start() {
        stop()
        localIpAddress = getActiveLocalIpAddress()
        Logger.upnp.i(TAG, "启动 UPnP MediaRenderer: 名称=$friendlyName, 本地IP=$localIpAddress, 端口=$httpPort")

        demuxer = buildDemuxer()

        startHttpServer()
        startSsdpEngine()
    }

    private fun ensureDemuxer(): StreamDemuxer {
        val existing = demuxer
        if (existing != null) return existing
        return buildDemuxer().also { demuxer = it }
    }

    private fun buildDemuxer(): StreamDemuxer {
        return StreamDemuxer(
            scope = scope,
            audioGateway = audioGateway,
            onAcquireStreamEpoch = { spec ->
                val acquiredEpoch = onAcquireHardwareForStream(spec.sampleRate, spec.bitDepth, spec.channels, spec.codec)
                if (acquiredEpoch <= 0L) {
                    return@StreamDemuxer -1L
                }
                synchronized(sessionStateLock) {
                    val resolvedDurMs = when {
                        spec.durationState == DurationState.LIVE -> 0L
                        spec.durationMs > 0L -> spec.durationMs
                        else -> currentTrackMetadata.durationMs
                    }
                    val resolvedDurState = when {
                        spec.durationState == DurationState.LIVE -> DurationState.LIVE
                        resolvedDurMs > 0L -> DurationState.FIXED
                        else -> spec.durationState
                    }
                    val resolvedSeekCap = when {
                        resolvedDurState == DurationState.LIVE -> SeekCapability.UNSEEKABLE
                        // 💡 尊重解复用器真实寻道能力：对于 FLAC 等当前不支持 Range 寻道的流，严格保持 UNSEEKABLE，杜绝 UI 拖动回弹 (B-2)
                        spec.seekCapability == SeekCapability.UNSEEKABLE -> SeekCapability.UNSEEKABLE
                        resolvedDurState == DurationState.FIXED -> SeekCapability.SEEKABLE
                        else -> spec.seekCapability
                    }
                    currentTrackMetadata = currentTrackMetadata.copy(
                        durationMs = resolvedDurMs,
                        durationState = resolvedDurState,
                        seekCapability = resolvedSeekCap,
                        trackUri = currentUri.ifEmpty { currentTrackMetadata.trackUri }
                    )

                    val activeSnap = snapshotProvider()
                    if (activeUpnpSessionId == 0L || activeSnap.sessionId != activeUpnpSessionId) {
                        activeUpnpSessionId = allocateSessionId()
                        trackGenerationCounter += 1L
                        sessionSetupDispatched = false
                    }

                    if (!sessionSetupDispatched || activeSnap.sessionId != activeUpnpSessionId) {
                        sessionSetupDispatched = true
                        emitEventLocked(
                            StreamSetupEvent(
                                protocol = StreamProtocol.UPNP_DLNA,
                                sampleRate = spec.sampleRate,
                                bitDepth = spec.bitDepth,
                                channels = spec.channels,
                                codec = spec.codec,
                                initialEpoch = acquiredEpoch,
                                trackGeneration = trackGenerationCounter,
                                initialMetadata = currentTrackMetadata
                            )
                        )
                    } else {
                        emitEventLocked(
                            HardwareReconfiguredEvent(
                                sampleRate = spec.sampleRate,
                                bitDepth = spec.bitDepth,
                                channels = spec.channels,
                                epoch = acquiredEpoch
                            )
                        )
                        emitEventLocked(
                            MetadataPatchEvent(
                                trackGeneration = trackGenerationCounter,
                                durationMs = resolvedDurMs,
                                durationState = resolvedDurState,
                                seekCapability = resolvedSeekCap
                            )
                        )
                    }
                }
                acquiredEpoch
            },
            onFirstDataPacket = { epoch, spec ->
                val shouldNotifyPlaying = synchronized(sessionStateLock) {
                    if (activeUpnpSessionId != 0L) {
                        emitEventLocked(
                            FirstDataPacketEvent(
                                epoch = epoch,
                                sampleRate = spec.sampleRate,
                                bitDepth = spec.bitDepth,
                                channels = spec.channels,
                                codec = spec.codec,
                                protocol = StreamProtocol.UPNP_DLNA
                            )
                        )
                    }
                    // 💡 防御暂停/停止/抢占态寻道误报: 仅当该会话仍属于当前活跃 UPnP 会话，且处于有效播放态时，方可向 GENA 广播 PLAYING
                    val snap = snapshotProvider()
                    // 💡 修复 Finding 20: 若协调器已处于 PLAYING，同步复位 isLocallyPaused，解除电视端恢复播放后的本地暂停死锁
                    if (snap.playbackStatus == PlaybackStatus.PLAYING || snap.businessState == BusinessSessionState.PLAYING) {
                        isLocallyPaused = false
                    }
                    val isCurrentSessionActive = activeUpnpSessionId != 0L && snap.sessionId == activeUpnpSessionId
                    val isPlaybackActive = !isLocallyPaused && snap.playbackStatus == PlaybackStatus.PLAYING
                    isCurrentSessionActive && isPlaybackActive
                }
                if (shouldNotifyPlaying) {
                    genaNotifier.notifyTransportState("PLAYING")
                }
            },
            onStreamEnded = { abortedByEpoch, _ ->
                Logger.upnp.i(TAG, "StreamDemuxer 回调流结束 (abortedByEpoch=$abortedByEpoch)")
                if (!abortedByEpoch) {
                    val ep = fastPath.emergencyMuteAndFlush(FlushReason.STOP_OR_TEARDOWN)
                    synchronized(sessionStateLock) {
                        if (activeUpnpSessionId != 0L && snapshotProvider().sessionId == activeUpnpSessionId) {
                            emitEventLocked(TrackCompleteEvent(newEpoch = ep))
                        }
                        sessionSetupDispatched = false
                    }
                    genaNotifier.notifyTransportState("STOPPED")
                }
            },
            onError = { err ->
                Logger.upnp.e(TAG, "StreamDemuxer 报错: $err")
                val ep = fastPath.emergencyMuteAndFlush(FlushReason.STOP_OR_TEARDOWN)
                synchronized(sessionStateLock) {
                    if (activeUpnpSessionId == 0L) {
                        activeUpnpSessionId = allocateSessionId()
                    }
                    emitEventLocked(
                        ErrorReportedEvent(
                            PlaybackError(
                                code = ErrorCode.NETWORK_STREAM_ERROR,
                                message = err,
                                recoverable = true,
                                timestampMs = clockMs()
                            )
                        )
                    )
                    if (snapshotProvider().sessionId == activeUpnpSessionId) {
                        emitEventLocked(StreamStopEvent(newEpoch = ep, isPreempted = false))
                    }
                    sessionSetupDispatched = false
                }
                genaNotifier.notifyTransportState("STOPPED")
            },
            onMetadataParsed = { title, artist, album ->
                Logger.upnp.i(TAG, "从音频流容器提取到内嵌元数据: title=$title, artist=$artist, album=$album")
                synchronized(sessionStateLock) {
                    val isDefaultTitle = currentTrackMetadata.title.isEmpty() ||
                        currentTrackMetadata.title == TrackMetadata.DEFAULT_TITLE ||
                        currentTrackMetadata.title == "AirPlay 无损音频" ||
                        currentTrackMetadata.title == DEFAULT_UPNP_TITLE
                    val isDefaultArtist = currentTrackMetadata.artist.isEmpty() ||
                        currentTrackMetadata.artist == "PureCast UPnP / DLNA 接收端" ||
                        currentTrackMetadata.artist == DEFAULT_UPNP_ARTIST ||
                        currentTrackMetadata.artist == "Apple Music"
                    val isDefaultAlbum = currentTrackMetadata.album.isEmpty() ||
                        currentTrackMetadata.album == "未知专辑"

                    val updatedTitle = if (isDefaultTitle && !title.isNullOrEmpty()) title else currentTrackMetadata.title
                    val updatedArtist = if (isDefaultArtist && !artist.isNullOrEmpty()) artist else currentTrackMetadata.artist
                    val updatedAlbum = if (isDefaultAlbum && !album.isNullOrEmpty()) album else currentTrackMetadata.album

                    currentTrackMetadata = currentTrackMetadata.copy(
                        title = updatedTitle,
                        artist = updatedArtist,
                        album = updatedAlbum
                    )
                    if (activeUpnpSessionId != 0L && trackGenerationCounter > 0L) {
                        emitEventLocked(
                            MetadataPatchEvent(
                                trackGeneration = trackGenerationCounter,
                                title = updatedTitle.ifEmpty { null },
                                artist = updatedArtist.ifEmpty { null },
                                album = updatedAlbum.ifEmpty { null }
                            )
                        )
                    }
                }
            },
            onPositionUpdated = { posMs ->
                synchronized(sessionStateLock) {
                    if (activeUpnpSessionId != 0L && snapshotProvider().sessionId == activeUpnpSessionId) {
                        emitEventLocked(
                            ProgressUpdateEvent(
                                positionMs = posMs,
                                durationMs = currentTrackMetadata.durationMs,
                                trackGeneration = trackGenerationCounter
                            )
                        )
                    }
                }
            },
            onSeekFlush = {
                val isPaused = synchronized(sessionStateLock) {
                    val snap = snapshotProvider()
                    // 💡 修复 Finding 20: 若协调器处于 PLAYING，同步复位 isLocallyPaused
                    if (snap.playbackStatus == PlaybackStatus.PLAYING || snap.businessState == BusinessSessionState.PLAYING) {
                        isLocallyPaused = false
                    }
                    isLocallyPaused || snap.businessState == BusinessSessionState.PAUSED
                }
                val newEpoch = fastPath.emergencyMuteAndFlush(FlushReason.FLUSH_SWITCH)
                // 💡 防御暂停偷跑与进度回弹: 暂停态下 Seek 换代必须维持暂停静音，严禁提前 resumeStream
                if (!isPaused) {
                    fastPath.resumeStream()
                } else {
                    Logger.upnp.i(TAG, "Seek 换代时流处于暂停态，维持底层静音保活，防止底层提前恢复消费引发声音偷跑")
                }
                synchronized(sessionStateLock) {
                    if (activeUpnpSessionId != 0L) {
                        emitEventLocked(
                            TrackSwitchEvent(
                                flushReason = FlushReason.FLUSH_SWITCH,
                                newEpoch = newEpoch,
                                isSeekFlush = true,
                                trackUri = currentUri
                            )
                        )
                    }
                }
                newEpoch
            }
        )
    }

    fun stop() {
        Logger.upnp.i(TAG, "停止 UPnP MediaRenderer 服务...")
        val wasRunning = ssdpJob != null || multicastSocket != null || serverSocket != null
        demuxer?.stop()
        demuxer = null

        if (wasRunning) {
            sendSsdpByebye(waitForCompletion = false)
        }

        ssdpJob?.cancel()
        ssdpJob = null

        httpJob?.cancel()
        httpJob = null

        try {
            multicastSocket?.close()
            multicastSocket = null
        } catch (e: Exception) {
            // ignore
        }

        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startHttpServer() {
        httpJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(httpPort))
                }
                dispatchServiceEvent(ErrorClearedEvent(ErrorCode.SERVER_ERROR))
                Logger.upnp.i(TAG, "HTTP 控制端点已在端口 $httpPort 上成功监听 (reuseAddress=true)")

                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        handleClientConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Logger.upnp.e(TAG, "HTTP Server 监听异常", e)
                    dispatchServiceEvent(
                        ErrorReportedEvent(
                            PlaybackError(
                                code = ErrorCode.SERVER_ERROR,
                                message = "UPnP 服务端口绑定失败 ($httpPort): ${e.message}",
                                recoverable = true,
                                timestampMs = clockMs()
                            )
                        )
                    )
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        try {
            socket.use { s ->
                // 💡 针对连接配置 5000ms 超时防护，防止局域网异常客户端挂起连接耗尽 Dispatchers.IO 线程池
                s.soTimeout = 5000
                val bis = BufferedInputStream(s.getInputStream(), 8192)
                val firstLine = readHttpLine(bis) ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                var line = readHttpLine(bis)
                var contentLength = 0
                while (!line.isNullOrEmpty()) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val k = line.substring(0, colonIdx).trim().lowercase()
                        val v = line.substring(colonIdx + 1).trim()
                        headers[k] = v
                        if (k == "content-length") {
                            contentLength = v.toIntOrNull() ?: 0
                        }
                    }
                    line = readHttpLine(bis)
                }

                val out = s.getOutputStream()
                val maxBodyBytes = 4 * 1024 * 1024
                if (contentLength > maxBodyBytes) {
                    sendResponse(out, 413, "Payload Too Large", "text/plain", "")
                    return
                }

                if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }

                val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
                val bodyBytes: ByteArray = when {
                    isChunked -> {
                        val baos = ByteArrayOutputStream()
                        while (true) {
                            val sizeLine = readHttpLine(bis) ?: break
                            val chunkHeader = sizeLine.trim().split(";")[0]
                            val chunkSize = chunkHeader.toIntOrNull(16) ?: break
                            if (chunkSize == 0) {
                                while (!readHttpLine(bis).isNullOrEmpty()) {}
                                break
                            }
                            if (baos.size() + chunkSize > maxBodyBytes) {
                                sendResponse(out, 413, "Payload Too Large", "text/plain", "")
                                return
                            }
                            val chunkBuf = ByteArray(chunkSize)
                            var readTotal = 0
                            while (readTotal < chunkSize) {
                                val r = bis.read(chunkBuf, readTotal, chunkSize - readTotal)
                                if (r == -1) break
                                readTotal += r
                            }
                            baos.write(chunkBuf, 0, readTotal)
                            readHttpLine(bis) // skip trailing \r\n
                        }
                        baos.toByteArray()
                    }
                    contentLength > 0 -> {
                        val buf = ByteArray(contentLength)
                        var readTotal = 0
                        while (readTotal < contentLength) {
                            val r = bis.read(buf, readTotal, contentLength - readTotal)
                            if (r == -1) break
                            readTotal += r
                        }
                        if (readTotal < contentLength) {
                            buf.copyOf(readTotal)
                        } else {
                            buf
                        }
                    }
                    else -> ByteArray(0)
                }

                val charset = parseCharsetFromContentType(headers["content-type"]) ?: Charsets.UTF_8
                val body = if (bodyBytes.isNotEmpty()) String(bodyBytes, charset) else ""

                when (method) {
                    "GET" -> handleHttpGet(path, out)
                    "POST" -> handleHttpPost(path, headers, body, out)
                    "SUBSCRIBE" -> handleHttpSubscribe(headers, out)
                    "UNSUBSCRIBE" -> handleHttpUnsubscribe(headers, out)
                    else -> sendResponse(out, 405, "Method Not Allowed", "text/plain", "")
                }
            }
        } catch (e: Exception) {
            Logger.upnp.w(TAG, "处理 HTTP 请求异常: ${e.message}")
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (!sawAny) return null
                break
            }
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString(Charsets.US_ASCII.name())
    }

    private fun parseCharsetFromContentType(contentType: String?): java.nio.charset.Charset? {
        if (contentType == null) return null
        val match = Regex("charset\\s*=\\s*\"?([a-zA-Z0-9_-]+)\"?", RegexOption.IGNORE_CASE).find(contentType)
        return match?.groupValues?.get(1)?.let {
            try { java.nio.charset.Charset.forName(it) } catch (_: Exception) { null }
        }
    }

    private fun handleHttpGet(path: String, out: OutputStream) {
        when (path) {
            "/description.xml" -> {
                sendResponse(out, 200, "OK", "text/xml; charset=\"utf-8\"", buildDeviceDescriptionXml())
            }
            "/AVTransport/scpd.xml" -> {
                sendResponse(out, 200, "OK", "text/xml; charset=\"utf-8\"", buildAvTransportScpdXml())
            }
            "/ConnectionManager/scpd.xml" -> {
                sendResponse(out, 200, "OK", "text/xml; charset=\"utf-8\"", buildConnectionManagerScpdXml())
            }
            "/RenderingControl/scpd.xml" -> {
                sendResponse(out, 200, "OK", "text/xml; charset=\"utf-8\"", buildRenderingControlScpdXml())
            }
            else -> {
                sendResponse(out, 404, "Not Found", "text/plain", "Not Found")
            }
        }
    }

    private fun handleHttpPost(path: String, headers: Map<String, String>, body: String, out: OutputStream) {
        val soapAction = headers["soapaction"]?.removeSurrounding("\"") ?: ""
        Logger.upnp.i(TAG, "收到 SOAP Action: $soapAction")

        val responseXml = processSoapActionForTest(soapAction, body)
        val isFault = responseXml.contains("<s:Fault>")
        val statusCode = if (isFault) 500 else 200
        val statusMsg = if (isFault) "Internal Server Error" else "OK"
        sendResponse(out, statusCode, statusMsg, "text/xml; charset=\"utf-8\"", responseXml)
    }

    @androidx.annotation.VisibleForTesting
    internal fun getCurrentTrackMetadataForTest(): TrackMetadata = synchronized(sessionStateLock) { currentTrackMetadata }

    internal fun processSoapActionForTest(soapAction: String, body: String): String {
        return when {
            soapAction.contains("SetNextAVTransportURI") -> handleSetNextAvTransportUri(body)
            soapAction.contains("SetAVTransportURI") -> handleSetAvTransportUri(body)
            soapAction.contains("Play") -> handlePlay()
            soapAction.contains("Pause") -> handlePause()
            soapAction.contains("Stop") -> handleStop()
            soapAction.contains("Seek") -> handleSeek(body)
            soapAction.contains("Next") -> handleNext()
            soapAction.contains("Previous") -> handlePrevious()
            soapAction.contains("GetTransportInfo") -> handleGetTransportInfo()
            soapAction.contains("GetPositionInfo") -> handleGetPositionInfo()
            soapAction.contains("GetProtocolInfo") -> handleGetProtocolInfo()
            soapAction.contains("GetVolume") -> handleGetVolume()
            soapAction.contains("SetVolume") -> handleSetVolume(body)
            else -> {
                Logger.upnp.w(TAG, "未处理的未知 SOAP 操作: $soapAction")
                buildSoapFault(401, "Invalid Action")
            }
        }
    }

    private fun handleHttpSubscribe(headers: Map<String, String>, out: OutputStream) {
        val callback = headers["callback"]
        val sid = headers["sid"]

        if (callback != null) {
            val newSid = genaNotifier.subscribe(callback)
            val headerText = "HTTP/1.1 200 OK\r\n" +
                    "SID: $newSid\r\n" +
                    "TIMEOUT: Second-1800\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(headerText.toByteArray(Charsets.UTF_8))
            out.flush()
        } else if (sid != null) {
            val renewed = genaNotifier.renew(sid)
            if (renewed) {
                val headerText = "HTTP/1.1 200 OK\r\n" +
                        "SID: $sid\r\n" +
                        "TIMEOUT: Second-1800\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(headerText.toByteArray(Charsets.UTF_8))
                out.flush()
            } else {
                sendResponse(out, 412, "Precondition Failed", "text/plain", "")
            }
        }
    }

    private fun handleHttpUnsubscribe(headers: Map<String, String>, out: OutputStream) {
        val sid = headers["sid"]
        if (sid != null) {
            genaNotifier.unsubscribe(sid)
            val headerText = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(headerText.toByteArray(Charsets.UTF_8))
            out.flush()
        } else {
            sendResponse(out, 412, "Precondition Failed", "text/plain", "")
        }
    }

    private fun handleSetNextAvTransportUri(body: String): String {
        val nextUri = extractTagValue(body, "NextURI")
        val nextMetadata = extractTagValue(body, "NextURIMetaData")
        Logger.upnp.i(TAG, "SetNextAVTransportURI: nextUri=$nextUri")
        return buildSoapResponse("SetNextAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handleSetAvTransportUri(body: String): String {
        val uri = extractTagValue(body, "CurrentURI")
        val metadataXml = extractTagValue(body, "CurrentURIMetaData")
        Logger.upnp.i(TAG, "SetAVTransportURI: uri=$uri, hasMetadata=${metadataXml.isNotEmpty()}")

        if (uri.isNotEmpty()) {
            val isSameUriReplay = uri == currentUri
            if (demuxer?.isRunning() == true) {
                demuxer?.stop()
            }
            val switchEpoch = if (sessionSetupDispatched) {
                fastPath.emergencyMuteAndFlush(FlushReason.FLUSH_SWITCH)
            } else {
                0L
            }
            val parsedMeta = if (metadataXml.isNotEmpty() || !isSameUriReplay || currentTrackMetadata.title.isEmpty() || currentTrackMetadata.title == TrackMetadata.DEFAULT_TITLE) {
                parseDidlLiteMetadata(metadataXml, uri, preserveCoverWhenEmpty = isSameUriReplay)
            } else {
                currentTrackMetadata.copy(trackUri = uri)
            }
            synchronized(sessionStateLock) {
                currentUri = uri
                currentTrackMetadata = parsedMeta
                val activeSnap = snapshotProvider()
                val sameActiveSession = sessionSetupDispatched && activeUpnpSessionId != 0L &&
                    (activeSnap.sessionId == activeUpnpSessionId || activeSnap.activeProtocol == StreamProtocol.UPNP_DLNA)
                if (sameActiveSession) {
                    trackGenerationCounter += 1L
                    emitEventLocked(
                        TrackSwitchEvent(
                            flushReason = FlushReason.FLUSH_SWITCH,
                            newEpoch = switchEpoch,
                            trackUri = uri
                        )
                    )
                    emitEventLocked(
                        TrackBoundaryEvent(
                            trackKey = uri,
                            trackGeneration = trackGenerationCounter,
                            initialDurationState = parsedMeta.durationState,
                            seekCapability = parsedMeta.seekCapability,
                            trackUri = uri
                        )
                    )
                    emitFullMetadataPatchLocked(parsedMeta)
                    emitEventLocked(
                        ProgressUpdateEvent(
                            positionMs = 0L,
                            durationMs = parsedMeta.durationMs,
                            trackGeneration = trackGenerationCounter
                        )
                    )
                }
            }
        } else {
            if (metadataXml.isNotEmpty() || currentTrackMetadata.title.isEmpty() || currentTrackMetadata.title == TrackMetadata.DEFAULT_TITLE) {
                val parsedMeta = parseDidlLiteMetadata(metadataXml, currentUri)
                synchronized(sessionStateLock) {
                    currentTrackMetadata = parsedMeta
                    if (sessionSetupDispatched && activeUpnpSessionId != 0L && trackGenerationCounter > 0L) {
                        emitFullMetadataPatchLocked(parsedMeta)
                    }
                }
            }
        }

        // 💡 遵循 UPnP AVTransport 标准：SetAVTransportURI 仅初始化元数据，推流由 Play 动作触发
        return buildSoapResponse("SetAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handlePlay(): String {
        Logger.upnp.i(TAG, "SOAP Play 指令触发, 当前 uri: $currentUri")
        // 💡 收到推流，突破 BAL 尝试自动唤醒大屏播放界面
        onWakeUpUi?.invoke()
        synchronized(sessionStateLock) {
            isLocallyPaused = false
        }

        if (currentUri.isNotEmpty() && (demuxer?.isRunning() != true || demuxer?.getCurrentUri() != currentUri)) {
            fastPath.resumeStream()
            ensureDemuxer().start(currentUri)
        } else {
            fastPath.resumeStream()
            val resumedEpoch = fastPath.getCurrentBufferEpoch()
            synchronized(sessionStateLock) {
                if (activeUpnpSessionId != 0L) {
                    emitEventLocked(
                        StreamResumeEvent(
                            protocol = StreamProtocol.UPNP_DLNA,
                            resumedEpoch = resumedEpoch
                        )
                    )
                }
            }
        }
        genaNotifier.notifyTransportState("PLAYING")
        return buildSoapResponse("PlayResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handlePause(): String {
        Logger.upnp.i(TAG, "SOAP Pause 指令触发")
        // 💡 硬件快路径优先静音，保持 epoch 与环形缓冲不被刷新
        val pausedEpoch = fastPath.emergencyMuteAndFlush(FlushReason.EXPLICIT_PAUSE)
        synchronized(sessionStateLock) {
            isLocallyPaused = true
            if (activeUpnpSessionId != 0L) {
                emitEventLocked(
                    ExplicitPauseEvent(
                        flushReason = FlushReason.EXPLICIT_PAUSE,
                        epoch = pausedEpoch
                    )
                )
            }
        }
        genaNotifier.notifyTransportState("PAUSED_PLAYBACK")
        return buildSoapResponse("PauseResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handleStop(): String {
        Logger.upnp.i(TAG, "SOAP Stop 指令触发")
        stopPlayback(isPreempted = false)
        return buildSoapResponse("StopResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun isLiveOrUnseekable(): Boolean {
        val localMeta = currentTrackMetadata
        val snapMeta = snapshotProvider().metadata
        val durationState = if (localMeta.durationState != DurationState.UNKNOWN) {
            localMeta.durationState
        } else {
            snapMeta.durationState
        }
        val seekCapability = if (localMeta.seekCapability != SeekCapability.UNKNOWN) {
            localMeta.seekCapability
        } else {
            snapMeta.seekCapability
        }
        return durationState == DurationState.LIVE || seekCapability == SeekCapability.UNSEEKABLE
    }

    fun seekToPositionMs(positionMs: Long): Boolean {
        val d = demuxer ?: return false
        return d.seekTo(positionMs)
    }

    private fun parseRelTimeToMs(target: String): Long? {
        val parts = target.trim().split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sParts = parts[2].split(".")
        val s = sParts[0].toLongOrNull() ?: return null
        val ms = if (sParts.size > 1) {
            val fraction = sParts[1].padEnd(3, '0').substring(0, 3)
            fraction.toLongOrNull() ?: 0L
        } else 0L
        return (h * 3600L + m * 60L + s) * 1000L + ms
    }

    /**
     * 💡 SOAP Seek 寻道指令处理
     * 符合 UPnP AVTransport 规范：对于 [DurationState.LIVE] 或 [SeekCapability.UNSEEKABLE] 返回 701 (Transition not available)
     */
    private fun handleSeek(body: String): String {
        val unit = extractTagValue(body, "Unit")
        val target = extractTagValue(body, "Target")
        Logger.upnp.i(TAG, "SOAP Seek 指令: Unit=$unit, Target=$target")

        return if (isLiveOrUnseekable()) {
            Logger.upnp.w(TAG, "实时或不可寻道音频流不支持 Seek 操作，按规范返回 SOAP 701 错误")
            buildSoapFault(701, "Transition not available for live stream")
        } else {
            val targetMs = parseRelTimeToMs(target)
            if (targetMs != null) {
                val ok = seekToPositionMs(targetMs)
                if (!ok) {
                    Logger.upnp.w(TAG, "Seek 寻道执行失败或底层流不支持: $target")
                    return buildSoapFault(701, "Seek operation failed or unsupported for current stream")
                }
            }
            Logger.upnp.i(TAG, "普通流 Seek 寻道请求已接受: $target")
            buildSoapResponse("SeekResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
        }
    }

    /**
     * 💡 SOAP Next 切下一曲指令处理
     */
    private fun handleNext(): String =
        handleTrackStepAction("Next", "NextResponse")

    /**
     * 💡 SOAP Previous 切上一曲指令处理
     */
    private fun handlePrevious(): String =
        handleTrackStepAction("Previous", "PreviousResponse")

    private fun handleTrackStepAction(actionName: String, responseTag: String): String {
        Logger.upnp.i(TAG, "SOAP $actionName 指令触发")
        return if (isLiveOrUnseekable()) {
            Logger.upnp.w(TAG, "实时音频流不支持切歌($actionName)，返回 SOAP 701 错误")
            buildSoapFault(701, "Transition not available for live stream")
        } else {
            buildSoapResponse(responseTag, "urn:schemas-upnp-org:service:AVTransport:1", "")
        }
    }

    private fun emitFullMetadataPatchLocked(parsedMeta: TrackMetadata) {
        emitEventLocked(
            MetadataPatchEvent(
                trackGeneration = trackGenerationCounter,
                title = parsedMeta.title,
                artist = parsedMeta.artist,
                album = parsedMeta.album,
                coverUri = parsedMeta.coverUri,
                durationMs = parsedMeta.durationMs,
                durationState = parsedMeta.durationState,
                seekCapability = parsedMeta.seekCapability
            )
        )
    }

    private fun handleGetTransportInfo(): String {
        val status = snapshotProvider().playbackStatus
        val stateStr = when (status) {
            PlaybackStatus.PLAYING -> "PLAYING"
            PlaybackStatus.PAUSED -> "PAUSED_PLAYBACK"
            else -> "STOPPED"
        }
        val inner = "<CurrentTransportState>$stateStr</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
        return buildSoapResponse("GetTransportInfoResponse", "urn:schemas-upnp-org:service:AVTransport:1", inner)
    }

    /**
     * 💡 实时上报基于已消费采样的精确 RelTime 与总时长
     */
    private fun handleGetPositionInfo(): String {
        val posSec = demuxer?.getCurrentPlayPositionSeconds() ?: 0L
        val h = posSec / 3600
        val m = (posSec % 3600) / 60
        val s = posSec % 60
        val relTime = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)

        val durSecFromDemuxer = demuxer?.getTotalDurationSeconds() ?: 0L
        val durSec = if (durSecFromDemuxer > 0L) durSecFromDemuxer else (currentTrackMetadata.durationMs / 1000L)
        val durStr = if (durSec > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", durSec / 3600, (durSec % 3600) / 60, durSec % 60)
        } else {
            "00:00:00"
        }

        val escapedTrackUri = escapeXml(currentUri)
        val inner = "<Track>1</Track><TrackDuration>$durStr</TrackDuration><TrackMetaData></TrackMetaData><TrackURI>$escapedTrackUri</TrackURI><RelTime>$relTime</RelTime><AbsTime>$relTime</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>"
        return buildSoapResponse("GetPositionInfoResponse", "urn:schemas-upnp-org:service:AVTransport:1", inner)
    }

    private fun unescapeXmlRecursive(text: String): String {
        var current = text
        var iterations = 0
        while (current.contains("&amp;") || current.contains("&lt;") || current.contains("&gt;") || current.contains("&quot;") || current.contains("&apos;")) {
            val next = current
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
            if (next == current || ++iterations >= 5) break
            current = next
        }
        return current
    }

    private fun handleGetProtocolInfo(): String {
        // 💡 声明支持的 Sink 协议格式：
        // 完整原生集成 dr_flac 与 WAV Demuxer，支持 16-bit / 24-bit PCM 直通
        val supported = "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,http-get:*:audio/L16:*,http-get:*:audio/L24:*"
        val inner = "<Source></Source><Sink>$supported</Sink>"
        return buildSoapResponse("GetProtocolInfoResponse", "urn:schemas-upnp-org:service:ConnectionManager:1", inner)
    }

    private fun handleGetVolume(): String {
        val inner = "<CurrentVolume>100</CurrentVolume>"
        return buildSoapResponse("GetVolumeResponse", "urn:schemas-upnp-org:service:RenderingControl:1", inner)
    }

    private fun handleSetVolume(body: String): String {
        return buildSoapResponse("SetVolumeResponse", "urn:schemas-upnp-org:service:RenderingControl:1", "")
    }

    private fun emitEventLocked(payload: SessionEventPayload) {
        val snap = snapshotProvider()
        dispatchEvent(
            SessionEventEnvelope(
                origin = EventOrigin.UPNP,
                sessionId = activeUpnpSessionId,
                deviceGeneration = snap.deviceGeneration,
                sequence = allocateSequence(),
                timestampElapsedMs = clockMs(),
                payload = payload
            )
        )
    }

    private fun dispatchServiceEvent(payload: SessionEventPayload) {
        val snap = snapshotProvider()
        dispatchEvent(
            SessionEventEnvelope(
                origin = EventOrigin.SERVICE,
                sessionId = 0L,
                deviceGeneration = snap.deviceGeneration,
                sequence = allocateSequence(),
                timestampElapsedMs = clockMs(),
                payload = payload
            )
        )
    }

    private fun parseDidlLiteMetadata(
        escapedXml: String,
        trackUri: String = currentUri,
        preserveCoverWhenEmpty: Boolean = false
    ): TrackMetadata {
        val unescaped = escapedXml
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")

        var durationMs = 0L
        val durationPattern = Pattern.compile("duration=[\"'](\\d+):(\\d+):(\\d+)(?:\\.(\\d+))?[\"']", Pattern.CASE_INSENSITIVE)
        val durationMatcher = durationPattern.matcher(unescaped)
        if (durationMatcher.find()) {
            val h = durationMatcher.group(1)?.toLongOrNull() ?: 0L
            val m = durationMatcher.group(2)?.toLongOrNull() ?: 0L
            val s = durationMatcher.group(3)?.toLongOrNull() ?: 0L
            val subStr = durationMatcher.group(4) ?: "0"
            val normalizedMs = subStr.padEnd(3, '0').substring(0, 3)
            val ms = normalizedMs.toLongOrNull() ?: 0L
            durationMs = (h * 3600 + m * 60 + s) * 1000L + ms
        }

        val title = extractTagValue(unescaped, "dc:title")
            .ifEmpty { extractTagValue(unescaped, "title") }
            .ifEmpty { DEFAULT_UPNP_TITLE }

        val artist = extractTagValue(unescaped, "upnp:artist")
            .ifEmpty { extractTagValue(unescaped, "dc:creator") }
            .ifEmpty { extractTagValue(unescaped, "artist") }
            .ifEmpty { "PureCast" }

        val album = extractTagValue(unescaped, "upnp:album")
            .ifEmpty { extractTagValue(unescaped, "album") }

        val rawCover = extractTagValue(unescaped, "upnp:albumArtURI")
            .ifEmpty { extractTagValue(unescaped, "albumArtURI") }
            .ifEmpty { extractTagValue(unescaped, "upnp:icon") }
            .ifEmpty { extractTagValue(unescaped, "icon") }
            .ifEmpty { if (preserveCoverWhenEmpty) currentTrackMetadata.coverUri.orEmpty() else "" }
        val coverUri = unescapeXmlRecursive(rawCover).trim()

        val hasLiveMarker = unescaped.contains("isLive", ignoreCase = true)
        val durationState = when {
            durationMs > 0L -> DurationState.FIXED
            hasLiveMarker -> DurationState.LIVE
            else -> DurationState.UNKNOWN
        }
        val seekCapability = when (durationState) {
            DurationState.FIXED -> SeekCapability.SEEKABLE
            DurationState.LIVE -> SeekCapability.UNSEEKABLE
            DurationState.UNKNOWN -> SeekCapability.UNKNOWN
        }

        Logger.upnp.i(TAG, "解析 DIDL-Lite: title=$title, artist=$artist, album=$album, coverUri=$coverUri, durationMs=$durationMs, durationState=$durationState")

        return TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            coverUri = coverUri.ifEmpty { null },
            durationMs = durationMs,
            durationState = durationState,
            seekCapability = seekCapability,
            trackUri = trackUri.ifEmpty { null }
        )
    }

    private fun extractTagValue(xml: String, tag: String): String {
        val pattern = Pattern.compile("<$tag.*?>(.*?)</$tag>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = pattern.matcher(xml)
        return if (matcher.find()) matcher.group(1)?.trim() ?: "" else ""
    }

    private fun buildSoapResponse(actionResponse: String, serviceUrn: String, innerXml: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$actionResponse xmlns:u="$serviceUrn">
      $innerXml
    </u:$actionResponse>
  </s:Body>
</s:Envelope>""".trimIndent()
    }

    private fun buildSoapFault(code: Int, description: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$code</errorCode>
          <errorDescription>$description</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>""".trimIndent()
    }

    private fun sendResponse(out: OutputStream, code: Int, msg: String, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $msg\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Server: PureCast/1.0 UPnP/1.0 DLNADOC/1.50\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
        out.flush()
    }

    private fun startSsdpEngine() {
        ssdpJob = scope.launch(Dispatchers.IO) {
            try {
                val group = InetAddress.getByName("239.255.255.250")
                val activeInterface = getActiveNetworkInterface()
                multicastSocket = MulticastSocket(1900).apply {
                    timeToLive = 4
                    reuseAddress = true
                    if (activeInterface != null) {
                        try {
                            networkInterface = activeInterface
                            joinGroup(InetSocketAddress(group, 1900), activeInterface)
                            Logger.upnp.i(TAG, "SSDP 成功加入组播组并显式绑定到局域网接口: ${activeInterface.name}")
                        } catch (e: Exception) {
                            Logger.upnp.w(TAG, "显式绑定 activeInterface 失败，回退默认 joinGroup: ${e.message}")
                            joinGroup(group)
                        }
                    } else {
                        joinGroup(group)
                    }
                }

                // 启动周期性广播心跳任务 (每 30 秒)
                launch {
                    while (isActive) {
                        sendSsdpAlive()
                        delay(30_000)
                    }
                }

                val buf = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    multicastSocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                        handleSsdpSearch(msg, packet.address, packet.port)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Logger.upnp.e(TAG, "SSDP 组播监听异常", e)
                }
            }
        }
    }

    private fun handleSsdpSearch(msg: String, senderAddr: InetAddress, senderPort: Int) {
        val st = msg.lines().firstOrNull { it.startsWith("ST:", ignoreCase = true) }?.substringAfter(":")?.trim() ?: ""

        val shouldRespond = st == "ssdp:all" ||
                st == "upnp:rootdevice" ||
                st == "urn:schemas-upnp-org:device:MediaRenderer:1" ||
                st == "urn:schemas-upnp-org:service:AVTransport:1" ||
                st == deviceUuid

        if (shouldRespond) {
            val response = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: http://$localIpAddress:$httpPort/description.xml\r\n" +
                    "SERVER: Android/11 UPnP/1.0 PureCast/1.0\r\n" +
                    "ST: $st\r\n" +
                    "USN: $deviceUuid::$st\r\n\r\n"

            val bytes = response.toByteArray(Charsets.UTF_8)
            val replyPacket = DatagramPacket(bytes, bytes.size, senderAddr, senderPort)
            multicastSocket?.send(replyPacket)
            Logger.upnp.d(TAG, "响应来自 ${senderAddr.hostAddress}:$senderPort 的 M-SEARCH: $st")
        }
    }

    private fun sendSsdpAlive() {
        val targets = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )
        val group = InetAddress.getByName("239.255.255.250")

        for (target in targets) {
            val notify = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$localIpAddress:$httpPort/description.xml\r\n" +
                    "NT: $target\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/11 UPnP/1.0 PureCast/1.0\r\n" +
                    "USN: $deviceUuid::$target\r\n\r\n"

            val bytes = notify.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, group, 1900)
            try {
                multicastSocket?.send(packet)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun sendSsdpByebye(waitForCompletion: Boolean = false) {
        val uuid = deviceUuid
        // 💡 按 UPnP 规范覆盖全部已宣告的 NT/USN 组合（含根设备、UUID 自身、MediaRenderer 与 AVTransport），
        // 显式绑定局域网网卡、设置 TTL=4，并连续发送 3 轮（间隔 100ms）以抵御 Wi-Fi 组播丢包。
        val ntUsnPairs = listOf(
            "upnp:rootdevice" to "$uuid::upnp:rootdevice",
            uuid to uuid,
            "urn:schemas-upnp-org:device:MediaRenderer:1" to "$uuid::urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1" to "$uuid::urn:schemas-upnp-org:service:AVTransport:1"
        )
        val activeInterface = getActiveNetworkInterface()
        // 💡 防御 B-5: 采用独立后台线程发送下线通知，既避免主线程 NetworkOnMainThreadException，也不受 lifecycleScope 取消影响
        val thread = Thread {
            var sock: MulticastSocket? = null
            try {
                val group = InetAddress.getByName("239.255.255.250")
                sock = MulticastSocket().apply {
                    timeToLive = 4
                    if (activeInterface != null) {
                        try {
                            networkInterface = activeInterface
                        } catch (e: Exception) {
                            Logger.upnp.w(TAG, "SSDP byebye 绑定网卡 ${activeInterface.name} 失败，回退默认网卡: ${e.message}")
                        }
                    }
                }
                for (round in 0 until 3) {
                    for ((nt, usn) in ntUsnPairs) {
                        val notify = "NOTIFY * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "NT: $nt\r\n" +
                                "NTS: ssdp:byebye\r\n" +
                                "USN: $usn\r\n\r\n"
                        val bytes = notify.toByteArray(Charsets.UTF_8)
                        val packet = DatagramPacket(bytes, bytes.size, group, 1900)
                        sock.send(packet)
                    }
                    if (round < 2) {
                        try {
                            Thread.sleep(100L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                Logger.upnp.i(TAG, "已完成 3 轮 SSDP ssdp:byebye 下线广播 (UUID=$uuid)")
            } catch (e: Exception) {
                Logger.upnp.w(TAG, "发送 SSDP ssdp:byebye 异常: ${e.message}")
            } finally {
                try {
                    sock?.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        thread.name = "SSDP-ByeBye"
        thread.start()
        if (waitForCompletion) {
            try {
                thread.join(1000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun getActiveNetworkInterface(): NetworkInterface? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sorted = interfaces.sortedWith(compareByDescending { iface ->
                when {
                    iface.name.startsWith("wlan") -> 3
                    iface.name.startsWith("eth") -> 2
                    else -> 0
                }
            })
            for (iface in sorted) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.startsWith("usb") || name.startsWith("dummy") || name.startsWith("p2p") || name.startsWith("tun")) {
                    continue
                }
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (!host.startsWith("127.") && !host.startsWith("169.254.") && !host.startsWith("192.168.48.")) {
                            return iface
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return null
    }

    private fun getActiveLocalIpAddress(): String {
        return NetworkUtils.getLocalIpv4Address()?.hostAddress ?: "127.0.0.1"
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun buildDeviceDescriptionXml(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>${escapeXml(friendlyName)}</friendlyName>
    <manufacturer>Solo PureCast</manufacturer>
    <modelName>PureCast TV Lossless Renderer</modelName>
    <UDN>$deviceUuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/AVTransport/scpd.xml</SCPDURL>
        <controlURL>/AVTransport/control</controlURL>
        <eventSubURL>/AVTransport/events</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/RenderingControl/scpd.xml</SCPDURL>
        <controlURL>/RenderingControl/control</controlURL>
        <eventSubURL>/RenderingControl/events</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/ConnectionManager/scpd.xml</SCPDURL>
        <controlURL>/ConnectionManager/control</controlURL>
        <eventSubURL>/ConnectionManager/events</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>""".trimIndent()
    }

    private fun buildAvTransportScpdXml(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name></action>
    <action><name>SetNextAVTransportURI</name></action>
    <action><name>Play</name></action>
    <action><name>Pause</name></action>
    <action><name>Stop</name></action>
    <action><name>Seek</name></action>
    <action><name>Next</name></action>
    <action><name>Previous</name></action>
    <action><name>GetTransportInfo</name></action>
    <action><name>GetPositionInfo</name></action>
  </actionList>
</scpd>""".trimIndent()
    }

    private fun buildConnectionManagerScpdXml(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name></action>
  </actionList>
</scpd>""".trimIndent()
    }

    private fun buildRenderingControlScpdXml(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name></action>
    <action><name>SetVolume</name></action>
  </actionList>
</scpd>""".trimIndent()
    }

    companion object {
        private const val TAG = "UpnpRendererServer"
        private const val DEFAULT_UPNP_TITLE = "UPnP 音频流"
        private const val DEFAULT_UPNP_ARTIST = "PureCast"
    }
}
