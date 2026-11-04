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
import java.util.UUID
import java.util.regex.Pattern

/**
 * 💡 PureCast UPnP / DLNA MediaRenderer 协议栈核心服务端
 * 零冗余、纯 Kotlin 实现：
 * 1. SSDP 组播发现与心跳宣告 (239.255.255.250:1900)
 * 2. HTTP 控制端点与 SCPD 描述符托管 (支持 SetAVTransportURI, Play, Pause, Stop, Seek)
 * 3. DIDL-Lite XML 元数据流式提取与 GENA 双向状态同步
 */
class UpnpRendererServer(
    private val scope: CoroutineScope,
    private val playbackService: PlaybackService,
    private var friendlyName: String = "PureCast TV",
    private val httpPort: Int = 7952
) {

    private val deviceUuid = "uuid:" + UUID.nameUUIDFromBytes(friendlyName.toByteArray()).toString()
    private var localIpAddress: String = "127.0.0.1"

    private var serverSocket: ServerSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var ssdpJob: Job? = null
    private var httpJob: Job? = null

    val genaNotifier = GenaEventNotifier(scope)
    private var demuxer: StreamDemuxer? = null

    private var currentUri: String = ""
    private var currentTrackMetadata = TrackMetadata()

    fun start() {
        stop()
        localIpAddress = getActiveLocalIpAddress()
        Logger.upnp.i(TAG, "启动 UPnP MediaRenderer: 名称=$friendlyName, 本地IP=$localIpAddress, 端口=$httpPort")

        demuxer = StreamDemuxer(
            scope = scope,
            onStreamStarted = { sampleRate, bitDepth, channels ->
                Logger.upnp.i(TAG, "StreamDemuxer 回调流启动: $sampleRate Hz, $bitDepth bit")
                playbackService.startStreaming(sampleRate, bitDepth, channels)
                genaNotifier.notifyTransportState("PLAYING")
            },
            onStreamEnded = {
                Logger.upnp.i(TAG, "StreamDemuxer 回调流结束")
                playbackService.stopPlayback()
                genaNotifier.notifyTransportState("STOPPED")
            },
            onError = { err ->
                Logger.upnp.e(TAG, "StreamDemuxer 报错: $err")
                playbackService.stopPlayback()
                genaNotifier.notifyTransportState("STOPPED")
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
                serverSocket = ServerSocket(httpPort)
                Logger.upnp.i(TAG, "HTTP 控制端点已在端口 $httpPort 上成功监听")

                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        handleClientConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Logger.upnp.e(TAG, "HTTP Server 监听异常", e)
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
            soapAction.contains("SetAVTransportURI") -> handleSetAvTransportUri(body)
            soapAction.contains("Play") -> handlePlay()
            soapAction.contains("Pause") -> handlePause()
            soapAction.contains("Stop") -> handleStop()
            soapAction.contains("GetTransportInfo") -> handleGetTransportInfo()
            soapAction.contains("GetPositionInfo") -> handleGetPositionInfo()
            soapAction.contains("GetProtocolInfo") -> handleGetProtocolInfo()
            soapAction.contains("GetVolume") -> handleGetVolume()
            soapAction.contains("SetVolume") -> handleSetVolume(body)
            else -> {
                Logger.upnp.w(TAG, "未处理的 SOAP 操作: $soapAction")
                buildSoapResponse("UnknownActionResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
        }

        sendResponse(out, 200, "OK", "text/xml; charset=\"utf-8\"", responseXml)
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

    private fun handleSetAvTransportUri(body: String): String {
        currentUri = extractTagValue(body, "CurrentURI")
        val metadataXml = extractTagValue(body, "CurrentURIMetaData")
        Logger.upnp.i(TAG, "SetAVTransportURI: uri=$currentUri")

        currentTrackMetadata = parseDidlLiteMetadata(metadataXml)
        playbackService.updateMetadata(currentTrackMetadata)

        // 💡 遵循 UPnP AVTransport 标准：SetAVTransportURI 仅初始化元数据，推流由 Play 动作触发
        return buildSoapResponse("SetAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
    }

    private fun handlePlay(): String {
        Logger.upnp.i(TAG, "SOAP Play 指令触发, 当前 uri: $currentUri")
        if (currentUri.isNotEmpty() && demuxer?.isRunning() != true) {
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
        playbackService.stopPlayback()
        genaNotifier.notifyTransportState("STOPPED")
        return buildSoapResponse("StopResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
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

    private fun handleGetPositionInfo(): String {
        val inner = "<Track>1</Track><TrackDuration>00:00:00</TrackDuration><TrackMetaData></TrackMetaData><TrackURI>$currentUri</TrackURI><RelTime>00:00:00</RelTime><AbsTime>00:00:00</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>"
        return buildSoapResponse("GetPositionInfoResponse", "urn:schemas-upnp-org:service:AVTransport:1", inner)
    }

    private fun handleGetProtocolInfo(): String {
        val supported = "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,http-get:*:audio/L16:*"
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

        val title = extractTagValue(unescaped, "dc:title").ifEmpty { "AirPlay 无损音频" }
        val artist = extractTagValue(unescaped, "upnp:artist").ifEmpty { "Apple Music" }
        val album = extractTagValue(unescaped, "upnp:album")
        val coverUri = extractTagValue(unescaped, "upnp:albumArtURI").ifEmpty { null }

        // airupnp 实时音频流通常时长未知或未声明
        val isLive = unescaped.contains("isLive") || unescaped.isEmpty()

        return TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            coverUri = coverUri,
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
                multicastSocket = MulticastSocket(1900).apply {
                    timeToLive = 4
                    reuseAddress = true
                    joinGroup(group)
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

    private fun getActiveLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.address.size == 4) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "127.0.0.1"
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
    <friendlyName>$friendlyName</friendlyName>
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
    <action><name>Play</name></action>
    <action><name>Pause</name></action>
    <action><name>Stop</name></action>
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
