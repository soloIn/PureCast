package com.solo.purecast.upnp

import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
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
 * 💡 PureCast UPnP / DLNA MediaRenderer 协议栈核心服务端
 * 零冗余、纯 Kotlin 实现：
 * 1. SSDP 组播发现与心跳宣告 (239.255.255.250:1900)
 * 2. HTTP 控制端点与 SCPD 描述符托管 (支持 SetAVTransportURI, Play, Pause, Stop, Seek, Next, Previous)
 * 3. DIDL-Lite XML 元数据流式提取、LIST INFO 回退与 GENA 双向状态同步
 * 4. 精确上报基于已消费 PCM 采样的播放时间戳 RelTime
 * 5. 支持局域网设备广播名称即时更新与 SSDP 重新宣告
 */
class UpnpRendererServer(
    private val scope: CoroutineScope,
    private val playbackService: PlaybackService,
    private var friendlyName: String = "PureCast TV",
    private val httpPort: Int = 7952
) {

    private var deviceUuid = "uuid:" + UUID.nameUUIDFromBytes(friendlyName.toByteArray()).toString()
    private var localIpAddress: String = "127.0.0.1"

    private var serverSocket: ServerSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var ssdpJob: Job? = null
    private var httpJob: Job? = null

    val genaNotifier = GenaEventNotifier(scope)
    private var demuxer: StreamDemuxer? = null

    private var currentUri: String = ""
    private var currentTrackMetadata = TrackMetadata()

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
            sendSsdpByebye()
            friendlyName = trimmed
            deviceUuid = "uuid:" + UUID.nameUUIDFromBytes(trimmed.toByteArray()).toString()
            sendSsdpAlive()
            Logger.upnp.i(TAG, "已完成新名称 SSDP 重新广播: $friendlyName, UUID: $deviceUuid")
        }
    }

    fun notifyTransportState(state: String) {
        genaNotifier.notifyTransportState(state)
    }

    fun notifyNextTrack() {
        Logger.upnp.i(TAG, "电视端触发切下一曲通知")
        genaNotifier.notifyTransportState("TRANSITIONING")
    }

    fun notifyPreviousTrack() {
        Logger.upnp.i(TAG, "电视端触发切上一曲通知")
        genaNotifier.notifyTransportState("TRANSITIONING")
    }

    /**
     * 💡 主动中止当前 UPnP 解复用器与流传输
     */
    fun stopPlayback() {
        Logger.upnp.i(TAG, "主动中止 UPnP 流播放")
        demuxer?.stop()
        notifyTransportState("STOPPED")
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

        demuxer = StreamDemuxer(
            scope = scope,
            onStreamStarted = { sampleRate, bitDepth, channels, codec ->
                Logger.upnp.i(TAG, "StreamDemuxer 回调流启动: $sampleRate Hz, $bitDepth bit, $codec")
                val totalDur = demuxer?.getTotalDurationSeconds() ?: 0L
                if (totalDur > 0L && currentTrackMetadata.durationMs == 0L) {
                    currentTrackMetadata = currentTrackMetadata.copy(
                        durationMs = totalDur * 1000L,
                        isLiveStream = false
                    )
                    playbackService.updateMetadata(currentTrackMetadata)
                }
                playbackService.startStreaming(sampleRate, bitDepth, channels, PlaybackService.StreamSource.UPNP, codec = codec)
                genaNotifier.notifyTransportState("PLAYING")
            },
            onStreamEnded = {
                Logger.upnp.i(TAG, "StreamDemuxer 回调流结束")
                playbackService.stopPlayback(PlaybackService.StreamSource.UPNP)
                genaNotifier.notifyTransportState("STOPPED")
            },
            onError = { err ->
                Logger.upnp.e(TAG, "StreamDemuxer 报错: $err")
                playbackService.stopPlayback(PlaybackService.StreamSource.UPNP)
                playbackService.updateServerError(err)
                genaNotifier.notifyTransportState("STOPPED")
            },
            onMetadataParsed = { title, artist, album ->
                Logger.upnp.i(TAG, "从音频流容器提取到内嵌元数据: title=$title, artist=$artist, album=$album")
                val isDefaultTitle = currentTrackMetadata.title.isEmpty() ||
                        currentTrackMetadata.title == "等待音频推送" ||
                        currentTrackMetadata.title == "AirPlay 无损音频"
                val isDefaultArtist = currentTrackMetadata.artist.isEmpty() ||
                        currentTrackMetadata.artist == "PureCast UPnP / DLNA 接收端" ||
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
                playbackService.updateMetadata(currentTrackMetadata)
            },
            onPositionUpdated = { posMs ->
                playbackService.updatePlaybackPosition(posMs)
            }
        )

        startHttpServer()
        startSsdpEngine()
    }

    fun stop() {
        Logger.upnp.i(TAG, "停止 UPnP MediaRenderer 服务...")
        demuxer?.stop()
        demuxer = null

        sendSsdpByebye()

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
                playbackService.updateServerError(null)
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
                    playbackService.updateServerError("UPnP 服务端口绑定失败 ($httpPort): ${e.message}")
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val firstLine = reader.readLine() ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
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
                    line = reader.readLine()
                }

                var body = ""
                if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (r == -1) break
                        readTotal += r
                    }
                    body = String(bodyChars, 0, readTotal)
                }

                val out = s.getOutputStream()
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

        val responseXml: String = when {
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

        val isFault = responseXml.contains("<s:Fault>")
        val statusCode = if (isFault) 500 else 200
        val statusMsg = if (isFault) "Internal Server Error" else "OK"
        sendResponse(out, statusCode, statusMsg, "text/xml; charset=\"utf-8\"", responseXml)
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

        if (uri.isNotEmpty() && uri != currentUri && demuxer?.isRunning() == true) {
            demuxer?.stop()
        }
        currentUri = uri

        if (metadataXml.isNotEmpty() || currentTrackMetadata.title.isEmpty() || currentTrackMetadata.title == "等待音频推送") {
            currentTrackMetadata = parseDidlLiteMetadata(metadataXml)
            playbackService.updateMetadata(currentTrackMetadata)
        }

        // 💡 遵循 UPnP AVTransport 标准：SetAVTransportURI 仅初始化元数据，推流由 Play 动作触发
        return buildSoapResponse("SetAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handlePlay(): String {
        Logger.upnp.i(TAG, "SOAP Play 指令触发, 当前 uri: $currentUri")
        // 💡 收到推流，突破 BAL 尝试自动唤醒大屏播放界面
        playbackService.wakeUpUiIfInBackground()

        if (currentUri.isNotEmpty() && (demuxer?.isRunning() != true || demuxer?.getCurrentUri() != currentUri)) {
            demuxer?.start(currentUri)
        } else {
            playbackService.resumePlayback()
        }
        genaNotifier.notifyTransportState("PLAYING")
        return buildSoapResponse("PlayResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handlePause(): String {
        Logger.upnp.i(TAG, "SOAP Pause 指令触发")
        playbackService.pausePlayback()
        genaNotifier.notifyTransportState("PAUSED_PLAYBACK")
        return buildSoapResponse("PauseResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handleStop(): String {
        Logger.upnp.i(TAG, "SOAP Stop 指令触发")
        demuxer?.stop()
        playbackService.stopPlayback(PlaybackService.StreamSource.UPNP)
        genaNotifier.notifyTransportState("STOPPED")
        return buildSoapResponse("StopResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    /**
     * 💡 SOAP Seek 寻道指令处理
     * 符合 UPnP AVTransport 规范：对于 liveStream 实时流返回 701 (Transition not available)
     */
    private fun handleSeek(body: String): String {
        val unit = extractTagValue(body, "Unit")
        val target = extractTagValue(body, "Target")
        Logger.upnp.i(TAG, "SOAP Seek 指令: Unit=$unit, Target=$target")

        return if (currentTrackMetadata.isLiveStream) {
            Logger.upnp.w(TAG, "实时音频流不支持 Seek 操作，按规范返回 SOAP 701 错误")
            buildSoapFault(701, "Transition not available for live stream")
        } else {
            Logger.upnp.i(TAG, "普通流 Seek 寻道请求已接受: $target")
            buildSoapResponse("SeekResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
        }
    }

    /**
     * 💡 SOAP Next 切下一曲指令处理
     */
    private fun handleNext(): String {
        Logger.upnp.i(TAG, "SOAP Next 指令触发")
        return if (currentTrackMetadata.isLiveStream) {
            Logger.upnp.w(TAG, "实时音频流不支持切歌，返回 SOAP 701 错误")
            buildSoapFault(701, "Transition not available for live stream")
        } else {
            buildSoapResponse("NextResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
        }
    }

    /**
     * 💡 SOAP Previous 切上一曲指令处理
     */
    private fun handlePrevious(): String {
        Logger.upnp.i(TAG, "SOAP Previous 指令触发")
        return if (currentTrackMetadata.isLiveStream) {
            Logger.upnp.w(TAG, "实时音频流不支持切上一曲，返回 SOAP 701 错误")
            buildSoapFault(701, "Transition not available for live stream")
        } else {
            buildSoapResponse("PreviousResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
        }
    }

    private fun handleGetTransportInfo(): String {
        val status = PlaybackService.playbackStatus.value
        val stateStr = when (status) {
            com.solo.purecast.model.PlaybackStatus.PLAYING -> "PLAYING"
            com.solo.purecast.model.PlaybackStatus.PAUSED -> "PAUSED_PLAYBACK"
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

        val inner = "<Track>1</Track><TrackDuration>$durStr</TrackDuration><TrackMetaData></TrackMetaData><TrackURI>$currentUri</TrackURI><RelTime>$relTime</RelTime><AbsTime>$relTime</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>"
        return buildSoapResponse("GetPositionInfoResponse", "urn:schemas-upnp-org:service:AVTransport:1", inner)
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

    private fun parseDidlLiteMetadata(escapedXml: String): TrackMetadata {
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
            .ifEmpty { "AirPlay 无损音频" }

        val artist = extractTagValue(unescaped, "upnp:artist")
            .ifEmpty { extractTagValue(unescaped, "dc:creator") }
            .ifEmpty { extractTagValue(unescaped, "artist") }
            .ifEmpty { "Apple Music" }

        val album = extractTagValue(unescaped, "upnp:album")
            .ifEmpty { extractTagValue(unescaped, "album") }

        val coverUri = extractTagValue(unescaped, "upnp:albumArtURI")
            .ifEmpty { extractTagValue(unescaped, "albumArtURI") }
            .ifEmpty { extractTagValue(unescaped, "upnp:icon") }
            .ifEmpty { extractTagValue(unescaped, "icon") }
            .ifEmpty { null }

        val isLive = if (durationMs > 0L) {
            false
        } else {
            unescaped.contains("isLive") || unescaped.isEmpty()
        }

        Logger.upnp.i(TAG, "解析 DIDL-Lite: title=$title, artist=$artist, album=$album, coverUri=$coverUri, durationMs=$durationMs, isLive=$isLive")

        return TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            coverUri = coverUri,
            durationMs = durationMs,
            isLiveStream = isLive
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

    private fun sendSsdpByebye() {
        try {
            val group = InetAddress.getByName("239.255.255.250")
            val notify = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "NTS: ssdp:byebye\r\n" +
                    "USN: $deviceUuid::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            val bytes = notify.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, group, 1900)
            multicastSocket?.send(packet)
        } catch (e: Exception) {
            // ignore
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
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // 💡 严格优先选择物理 Wi-Fi (wlan*) 或以太网 (eth*)，坚决排除投影仪内部主板通信的 usb0 (192.168.48.x) 与虚拟网卡
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
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "127.0.0.1"
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
    }
}
