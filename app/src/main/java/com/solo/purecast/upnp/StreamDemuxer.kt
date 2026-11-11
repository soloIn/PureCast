package com.solo.purecast.upnp

import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 💡 HTTP 音频流解复用音频输出网关，解耦 Native JNI 调用以便 JVM 单测注入桩实现
 */
interface DemuxerAudioGateway {
    fun writePcm(data: ByteArray, offset: Int, length: Int, expectedEpoch: Long): Int
    fun getBufferAvailable(): Int
    fun getAvailableRead(): Int
    fun getFlacDecodedFrames(): Long
    fun resetFlacDecodedFrames()
    fun decodeFlacStream(
        inputStream: InputStream,
        callback: NativeAudioEngine.FlacFormatCallback
    ): Boolean
}

/**
 * 💡 生产环境默认直连 Native C++ AudioEngine 的音频网关
 */
object NativeDemuxerAudioGateway : DemuxerAudioGateway {
    override fun writePcm(data: ByteArray, offset: Int, length: Int, expectedEpoch: Long): Int =
        NativeAudioEngine.writePcm(data, offset, length, expectedEpoch)

    override fun getBufferAvailable(): Int = NativeAudioEngine.getBufferAvailable()
    override fun getAvailableRead(): Int = NativeAudioEngine.getAvailableRead()
    override fun getFlacDecodedFrames(): Long = NativeAudioEngine.getFlacDecodedFrames()
    override fun resetFlacDecodedFrames() = NativeAudioEngine.resetFlacDecodedFrames()
    override fun decodeFlacStream(
        inputStream: InputStream,
        callback: NativeAudioEngine.FlacFormatCallback
    ): Boolean = NativeAudioEngine.decodeFlacStream(inputStream, callback)
}

/**
 * 💡 从容器头提取的流媒体音频规格与真实时长语义
 */
data class DemuxedStreamSpec(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val codec: String,
    val durationMs: Long,
    val durationState: DurationState,
    val seekCapability: SeekCapability
)

internal fun resolveWavDurationSpec(dataSize: Long, bytesPerSecond: Long): Triple<Long, DurationState, SeekCapability> {
    return when {
        dataSize >= 0x7FFFFFFFL -> Triple(0L, DurationState.LIVE, SeekCapability.UNSEEKABLE)
        dataSize > 0L && bytesPerSecond > 0L -> {
            val durMs = (dataSize * 1000L) / bytesPerSecond
            Triple(durMs, DurationState.FIXED, SeekCapability.SEEKABLE)
        }
        else -> Triple(0L, DurationState.UNKNOWN, SeekCapability.UNSEEKABLE)
    }
}

internal fun resolveFlacDurationSpec(sampleRate: Int, totalPcmFrames: Long): Triple<Long, DurationState, SeekCapability> {
    return if (sampleRate > 0 && totalPcmFrames > 0L) {
        val durMs = (totalPcmFrames * 1000L) / sampleRate.toLong()
        Triple(durMs, DurationState.FIXED, SeekCapability.SEEKABLE)
    } else {
        Triple(0L, DurationState.UNKNOWN, SeekCapability.UNSEEKABLE)
    }
}

/**
 * 💡 PureCast HTTP 音频流无损解包器 (Stream Demuxer)
 *
 * 核心事务契约（R1/R4/U3）：
 * 1. 解析容器头后同步阻塞完成硬件重配并获取 [expectedEpoch] (`<= 0` 则直接中止解码)；
 * 2. 携带 `expectedEpoch` 写入 PCM；一旦返回 `< 0`（`-2` 世代过期或 `-1` 停止）立即终止写入，绝不越代覆盖；
 * 3. 首次写入有效 PCM 时通知 [onFirstDataPacket]，推动协调器跃迁至 PLAYING；
 * 4. 严格识别 `>= 0x7FFFFFFF` 无边界流为 `DurationState.LIVE` + `SeekCapability.UNSEEKABLE`。
 */
class StreamDemuxer(
    private val scope: CoroutineScope,
    private val audioGateway: DemuxerAudioGateway = NativeDemuxerAudioGateway,
    private val onAcquireStreamEpoch: (spec: DemuxedStreamSpec) -> Long,
    private val onFirstDataPacket: (epoch: Long, spec: DemuxedStreamSpec) -> Unit,
    private val onStreamEnded: (abortedByEpoch: Boolean, finalEpoch: Long) -> Unit,
    private val onError: (String) -> Unit,
    private val onMetadataParsed: ((title: String?, artist: String?, album: String?) -> Unit)? = null,
    private val onPositionUpdated: ((positionMs: Long) -> Unit)? = null
) {

    private var streamingJob: Job? = null
    private var progressJob: Job? = null
    private val sessionToken = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var isDemuxing = false
    @Volatile
    private var currentStreamUrl: String = ""
    @Volatile
    private var activeConnection: HttpURLConnection? = null
    @Volatile
    private var activeInputStream: InputStream? = null

    // 💡 播放进度时间统计状态
    @Volatile
    private var totalBytesStreamed: Long = 0L
    @Volatile
    private var bytesPerSecond: Long = 0L
    @Volatile
    private var totalDurationSeconds: Long = 0L
    @Volatile
    private var flacStartTimeMs: Long = 0L
    @Volatile
    private var flacTotalFrames: Long = 0L
    @Volatile
    private var currentSampleRate: Int = 0
    @Volatile
    private var currentBitDepth: Int = 0
    @Volatile
    private var currentChannels: Int = 0
    @Volatile
    private var isFlacStream: Boolean = false

    fun isRunning(): Boolean = isDemuxing
    fun getCurrentUri(): String = currentStreamUrl

    /**
     * 💡 获取当前已流式传输/播放的时间戳 (秒)
     */
    fun getCurrentPlayPositionSeconds(): Long {
        val bps = bytesPerSecond
        if (bps > 0L) {
            if (isFlacStream && currentSampleRate > 0) {
                val decodedFrames = audioGateway.getFlacDecodedFrames()
                val bytesPerFrame = currentChannels * (currentBitDepth / 8)
                val bufferedFrames = if (bytesPerFrame > 0) (audioGateway.getAvailableRead() / bytesPerFrame).toLong() else 0L
                val playedFrames = (decodedFrames - bufferedFrames).coerceAtLeast(0L)
                return playedFrames / currentSampleRate.toLong()
            } else if (totalBytesStreamed > 0L) {
                val playedBytes = (totalBytesStreamed - audioGateway.getAvailableRead().toLong()).coerceAtLeast(0L)
                return playedBytes / bps
            }
        }
        if (flacStartTimeMs > 0L && isDemuxing) {
            return (System.currentTimeMillis() - flacStartTimeMs) / 1000L
        }
        return 0L
    }

    /**
     * 💡 获取当前音轨总时长 (秒)，若流为实时直播流 (AirPlay / 广播) 则返回 0
     */
    fun getTotalDurationSeconds(): Long = totalDurationSeconds

    /**
     * 启动流式拉取与解析任务
     * @param streamUrl HTTP 媒体流地址 (如 http://192.168.2.5:49152/stream.wav)
     */
    fun start(streamUrl: String) {
        val previousJob = streamingJob
        val oldConn = activeConnection
        val oldStream = activeInputStream
        activeConnection = null
        activeInputStream = null

        // 💡 快速断开旧连接与输入流，促使阻塞在 JNI 的 dr_flac 或 read 立即抛异常退出
        closeDetachedStreamAsync(oldConn, oldStream)

        val thisSession = sessionToken.incrementAndGet()
        progressJob?.cancel()
        progressJob = null

        isDemuxing = true
        currentStreamUrl = streamUrl
        totalBytesStreamed = 0L
        bytesPerSecond = 0L
        totalDurationSeconds = 0L
        flacStartTimeMs = 0L
        flacTotalFrames = 0L
        currentSampleRate = 0
        currentBitDepth = 0
        currentChannels = 0
        isFlacStream = false

        // 💡 启动播放进度定期更新任务 (每 500ms 刷新一次)
        progressJob = scope.launch(Dispatchers.Default) {
            var lastEmittedPosMs = -1L
            while (isActive && isDemuxing && thisSession == sessionToken.get()) {
                val posMs = getCurrentPlayPositionSeconds() * 1000L
                if (posMs != lastEmittedPosMs) {
                    lastEmittedPosMs = posMs
                    onPositionUpdated?.invoke(posMs)
                }
                delay(500)
            }
        }

        streamingJob = scope.launch(Dispatchers.IO) {
            // 💡 确保前序 native 解码任务彻底退出，消除快速切歌时计数器与 JNI 资源的并发竞争
            try {
                previousJob?.cancelAndJoin()
            } catch (e: Exception) {
                // ignore
            }
            if (thisSession != sessionToken.get()) return@launch

            audioGateway.resetFlacDecodedFrames()

            Logger.upnp.i(TAG, "开始连接音频流 (session=$thisSession): $streamUrl")
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var abortedByEpoch = false
            var acquiredEpoch = -1L

            try {
                if (thisSession != sessionToken.get()) return@launch
                val url = URL(streamUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PureCast-UPnP-Renderer/1.0")
                    setRequestProperty("Connection", "keep-alive")
                }
                activeConnection = connection

                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("HTTP 请求失败: 状态码 $responseCode")
                }

                if (thisSession != sessionToken.get()) return@launch
                val contentType = connection.contentType ?: "audio/wav"
                Logger.upnp.i(TAG, "HTTP 连接成功 (session=$thisSession), Content-Type: $contentType, Content-Length: ${connection.contentLengthLong}")

                inputStream = BufferedInputStream(connection.inputStream, 64 * 1024)
                activeInputStream = inputStream

                // 💡 步骤 1: 探测前 12 字节魔数区分 FLAC 或 WAV
                inputStream.mark(64)
                val magic = ByteArray(12)
                val readOk = readFully(inputStream, magic, 0, 12)
                if (!readOk) {
                    Logger.upnp.w(TAG, "流在读取容器头部时被对端关闭 (通常为客户端探测连接或快速切歌)")
                    return@launch
                }
                inputStream.reset()

                val magicStr = String(magic, 0, 4)

                if (magicStr == "fLaC") {
                    isFlacStream = true
                    Logger.upnp.i(TAG, "检测到 FLAC 无损流容器 (fLaC)，交由 Native dr_flac 流式无损解码...")
                    flacStartTimeMs = System.currentTimeMillis()
                    var currentSpec: DemuxedStreamSpec? = null
                    val ok = audioGateway.decodeFlacStream(
                        inputStream,
                        object : NativeAudioEngine.FlacFormatCallback {
                            override fun onFormatReady(
                                sampleRate: Int,
                                bitDepth: Int,
                                channels: Int,
                                totalPcmFrames: Long
                            ): Long {
                                if (thisSession != sessionToken.get()) {
                                    abortedByEpoch = true
                                    return -1L
                                }
                                currentSampleRate = sampleRate
                                currentBitDepth = bitDepth
                                currentChannels = channels
                                flacTotalFrames = totalPcmFrames
                                bytesPerSecond = (sampleRate.toLong() * channels * (bitDepth / 8))
                                val (durMs, durState, seekCap) = resolveFlacDurationSpec(sampleRate, totalPcmFrames)
                                totalDurationSeconds = durMs / 1000L
                                val spec = DemuxedStreamSpec(
                                    sampleRate = sampleRate,
                                    bitDepth = bitDepth,
                                    channels = channels,
                                    codec = "FLAC",
                                    durationMs = durMs,
                                    durationState = durState,
                                    seekCapability = seekCap
                                )
                                currentSpec = spec
                                acquiredEpoch = onAcquireStreamEpoch(spec)
                                if (acquiredEpoch <= 0L) {
                                    abortedByEpoch = true
                                }
                                return acquiredEpoch
                            }

                            override fun onFirstPcmWritten() {
                                val spec = currentSpec ?: return
                                if (thisSession == sessionToken.get() && acquiredEpoch > 0L) {
                                    onFirstDataPacket(acquiredEpoch, spec)
                                }
                            }
                        }
                    )
                    if (!ok) {
                        abortedByEpoch = true
                        Logger.upnp.w(TAG, "Native dr_flac 解码失败、世代过期或提前退出 (session=$thisSession)")
                    }
                } else if (magicStr == "RIFF") {
                    isFlacStream = false
                    val format = parseHeader(inputStream)
                    if (thisSession != sessionToken.get()) {
                        abortedByEpoch = true
                        return@launch
                    }
                    currentSampleRate = format.sampleRate
                    currentBitDepth = format.bitDepth
                    currentChannels = format.channels
                    flacTotalFrames = 0L

                    bytesPerSecond = (format.sampleRate.toLong() * format.channels * (format.bitDepth / 8))
                    val (durMs, durState, seekCap) = resolveWavDurationSpec(format.dataSize, bytesPerSecond)
                    totalDurationSeconds = durMs / 1000L
                    Logger.upnp.i(
                        TAG,
                        "WAV 容器头部解析完毕: ${format.sampleRate}Hz/${format.bitDepth}bit/${format.channels}ch, dataSize=${format.dataSize}, durationState=$durState"
                    )

                    val spec = DemuxedStreamSpec(
                        sampleRate = format.sampleRate,
                        bitDepth = format.bitDepth,
                        channels = format.channels,
                        codec = "WAV",
                        durationMs = durMs,
                        durationState = durState,
                        seekCapability = seekCap
                    )
                    acquiredEpoch = onAcquireStreamEpoch(spec)
                    if (acquiredEpoch <= 0L) {
                        abortedByEpoch = true
                        Logger.upnp.w(TAG, "WAV 获取硬件世代失败 ($acquiredEpoch)，中止推流 (session=$thisSession)")
                        return@launch
                    }

                    val pcmBuffer = ByteArray(32768)
                    var firstPacketEmitted = false

                    while (isActive && isDemuxing && thisSession == sessionToken.get() && !abortedByEpoch) {
                        val bytesRead = inputStream.read(pcmBuffer, 0, pcmBuffer.size)
                        if (bytesRead == -1) {
                            Logger.upnp.i(TAG, "音频流正常读取至 EOF 边界 (session=$thisSession)")
                            break
                        }

                        if (bytesRead > 0) {
                            totalBytesStreamed += bytesRead
                            var offset = 0
                            var remaining = bytesRead
                            while (remaining > 0 && isActive && isDemuxing && thisSession == sessionToken.get()) {
                                val written = audioGateway.writePcm(pcmBuffer, offset, remaining, acquiredEpoch)
                                if (written > 0) {
                                    if (!firstPacketEmitted) {
                                        firstPacketEmitted = true
                                        onFirstDataPacket(acquiredEpoch, spec)
                                    }
                                    offset += written
                                    remaining -= written
                                } else if (written == -2 || written == -1) {
                                    // 💡 收到 -2 世代过期或 -1 引擎停止立即跳出，严禁阻塞或跨代覆写
                                    Logger.upnp.w(TAG, "WAV writePcm 因世代过期或引擎停止中止写入: ret=$written, epoch=$acquiredEpoch")
                                    abortedByEpoch = true
                                    break
                                } else {
                                    val avail = audioGateway.getBufferAvailable()
                                    val needed = (remaining - avail).coerceAtLeast(0)
                                    val waitMs = if (bytesPerSecond > 0L && needed > 0) {
                                        ((needed.toLong() * 1000L) / bytesPerSecond).coerceIn(5L, 20L)
                                    } else {
                                        5L
                                    }
                                    delay(waitMs)
                                }
                            }
                        }
                    }
                } else {
                    Logger.upnp.e(TAG, "未知的音频流魔数: $magicStr，拦截直通以防杂音")
                    throw IllegalArgumentException("不支持的非 WAV/FLAC 格式流: $magicStr")
                }

                if (!abortedByEpoch && thisSession == sessionToken.get()) {
                    Logger.upnp.i(TAG, "音频流数据拉取完毕，等待环形缓冲排空播放 (session=$thisSession)...")
                    var waitCount = 0
                    while (isActive && isDemuxing && thisSession == sessionToken.get() && audioGateway.getAvailableRead() > 0 && waitCount < 120) {
                        delay(50)
                        waitCount++
                    }
                    delay(25)
                    if (thisSession == sessionToken.get()) {
                        Logger.upnp.i(TAG, "音频流播放完毕 (session=$thisSession)")
                    }
                }
            } catch (e: CancellationException) {
                abortedByEpoch = true
                Logger.upnp.i(TAG, "音频拉取流被主动取消 (session=$thisSession)")
            } catch (e: Exception) {
                Logger.upnp.e(TAG, "音频流拉取/解包异常 (session=$thisSession)", e)
                if (thisSession == sessionToken.get() && !abortedByEpoch) {
                    onError("音频流传输异常: ${e.message}")
                }
            } finally {
                if (activeConnection === connection) activeConnection = null
                if (activeInputStream === inputStream) activeInputStream = null
                try {
                    inputStream?.close()
                    connection?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
                if (thisSession == sessionToken.get()) {
                    progressJob?.cancel()
                    progressJob = null
                    isDemuxing = false
                    onStreamEnded(abortedByEpoch, acquiredEpoch)
                    Logger.upnp.i(TAG, "活跃会话 (session=$thisSession) 结束 (abortedByEpoch=$abortedByEpoch)")
                } else {
                    Logger.upnp.i(TAG, "旧协程 (session=$thisSession) 资源清理完成，当前活跃会话为 ${sessionToken.get()}，跳过全局状态重置")
                }
            }
        }
    }

    fun stop() {
        sessionToken.incrementAndGet()
        detachAndCloseActiveStreamAsync()
        isDemuxing = false
        currentStreamUrl = ""
        isFlacStream = false
        progressJob?.cancel()
        progressJob = null
        streamingJob?.cancel()
        streamingJob = null
        audioGateway.resetFlacDecodedFrames()
    }

    data class AudioStreamFormat(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val dataSize: Long = 0L
    )

    /**
     * 流式状态机解析 WAV 头部多 Chunk，并提取 LIST INFO 内嵌元数据
     */
    private fun parseHeader(input: InputStream): AudioStreamFormat {
        val magic = ByteArray(12)
        if (!readFully(input, magic, 0, 12)) throw IllegalStateException("流提前结束 (EOF in readFully)")

        val riff = String(magic, 0, 4)
        val wave = String(magic, 8, 4)

        if (riff != "RIFF" || wave != "WAVE") {
            Logger.upnp.e(TAG, "流格式非标准无损 WAV/PCM (riff=$riff, wave=$wave)，拦截直通以防压缩数据产生白噪声")
            throw IllegalArgumentException("不支持的非 WAV 格式流: $riff / $wave (请确保投屏端传输无损 PCM/WAV)")
        }

        var sampleRate = 44100
        var bitDepth = 16
        var channels = 2
        var dataSize = 0L
        var dataFound = false

        val chunkHeader = ByteArray(8)
        while (!dataFound) {
            if (!readFully(input, chunkHeader, 0, 8)) throw IllegalStateException("流提前结束 (EOF in readFully)")
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            when (chunkId) {
                "fmt " -> {
                    val fmtLen = chunkSize.toInt()
                    val fmtData = ByteArray(fmtLen)
                    if (!readFully(input, fmtData, 0, fmtLen)) throw IllegalStateException("流提前结束 (EOF in readFully)")
                    val fmtBuf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                    fmtBuf.short // audioFormat (1=PCM)
                    channels = fmtBuf.short.toInt()
                    sampleRate = fmtBuf.int
                    fmtBuf.int // byteRate
                    fmtBuf.short // blockAlign
                    bitDepth = fmtBuf.short.toInt()
                    Logger.upnp.d(TAG, "解析 fmt Chunk 成功: $sampleRate Hz, $bitDepth bit, $channels ch")
                }

                "LIST" -> {
                    // 💡 深度流式解析 WAV LIST INFO 内嵌元数据
                    Logger.upnp.d(TAG, "检测到 LIST 扩展 Chunk: $chunkSize 字节，准备提取内嵌文本标签...")
                    if (chunkSize >= 4L) {
                        val listTypeBytes = ByteArray(4)
                        if (readFully(input, listTypeBytes, 0, 4)) {
                            val listType = String(listTypeBytes, 0, 4)
                            var bytesRemaining = chunkSize - 4L

                            if (listType == "INFO") {
                                Logger.upnp.i(TAG, "解析 WAV LIST INFO 元数据块...")
                                var parsedTitle: String? = null
                                var parsedArtist: String? = null
                                var parsedAlbum: String? = null

                                while (bytesRemaining >= 8L) {
                                    val subHeader = ByteArray(8)
                                    if (!readFully(input, subHeader, 0, 8)) break
                                    val subId = String(subHeader, 0, 4)
                                    val subSize = ByteBuffer.wrap(subHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                                    bytesRemaining -= 8L

                                    if (subSize < 0L || subSize > bytesRemaining) {
                                        Logger.upnp.w(TAG, "异常 subChunk 尺寸: subId=$subId, subSize=$subSize, 剩余=$bytesRemaining")
                                        skipBytes(input, bytesRemaining)
                                        bytesRemaining = 0L
                                        break
                                    }

                                    val subLen = subSize.toInt()
                                    val subData = ByteArray(subLen)
                                    if (!readFully(input, subData, 0, subLen)) {
                                        bytesRemaining = 0L
                                        break
                                    }
                                    bytesRemaining -= subSize

                                    // RIFF 规范：若 subSize 为奇数，后附 1 字节对齐填充
                                    if (subSize % 2L != 0L && bytesRemaining > 0L) {
                                        input.read()
                                        bytesRemaining -= 1L
                                    }

                                    val text = String(subData, Charsets.UTF_8).trimEnd { it == '\u0000' || it.isWhitespace() }
                                    when (subId) {
                                        "INAM" -> {
                                            Logger.upnp.i(TAG, "提取到内嵌歌曲标题 INAM: $text")
                                            parsedTitle = text
                                        }
                                        "IART" -> {
                                            Logger.upnp.i(TAG, "提取到内嵌艺术家 IART: $text")
                                            parsedArtist = text
                                        }
                                        "IPRD" -> {
                                            Logger.upnp.i(TAG, "提取到内嵌专辑 IPRD: $text")
                                            parsedAlbum = text
                                        }
                                        else -> {
                                            Logger.upnp.d(TAG, "跳过 LIST INFO 子项: $subId, 内容=$text")
                                        }
                                    }
                                }

                                if (!parsedTitle.isNullOrEmpty() || !parsedArtist.isNullOrEmpty() || !parsedAlbum.isNullOrEmpty()) {
                                    onMetadataParsed?.invoke(parsedTitle, parsedArtist, parsedAlbum)
                                }
                            } else {
                                skipBytes(input, bytesRemaining)
                            }
                        }
                    }
                }

                "data" -> {
                    dataFound = true
                    dataSize = chunkSize
                    Logger.upnp.d(TAG, "精准定位到 data Chunk 边界，准备直通 Raw PCM 载荷 (chunkSize=$chunkSize)")
                }

                else -> {
                    // 💡 跳过 JUNK, bext 等扩展 Chunk
                    Logger.upnp.d(TAG, "跳过非 data 扩展 Chunk: $chunkId, 长度: $chunkSize 字节")
                    skipBytes(input, chunkSize)
                }
            }
        }

        return AudioStreamFormat(sampleRate, bitDepth, channels, dataSize)
    }

    private fun skipBytes(input: InputStream, length: Long) {
        var toSkip = length
        val skipBuf = ByteArray(minOf(toSkip, 4096L).toInt())
        while (toSkip > 0L) {
            val n = input.read(skipBuf, 0, minOf(toSkip, skipBuf.size.toLong()).toInt())
            if (n == -1) break
            toSkip -= n.toLong()
        }
    }

    private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val r = input.read(target, offset + read, length - read)
            if (r == -1) return false
            read += r
        }
        return true
    }

    private fun detachAndCloseActiveStreamAsync() {
        val oldConn = activeConnection
        val oldStream = activeInputStream
        activeConnection = null
        activeInputStream = null
        closeDetachedStreamAsync(oldConn, oldStream)
    }

    private fun closeDetachedStreamAsync(conn: HttpURLConnection?, stream: InputStream?) {
        if (conn == null && stream == null) return
        scope.launch(Dispatchers.IO) {
            try {
                stream?.close()
                conn?.disconnect()
            } catch (e: Exception) {
                Logger.upnp.w(TAG, "关闭分离 HTTP 流异常: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "StreamDemuxer"
    }
}
