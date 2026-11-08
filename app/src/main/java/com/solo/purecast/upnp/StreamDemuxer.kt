package com.solo.purecast.upnp

import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * 💡 PureCast HTTP 音频流无损解包器 (Stream Demuxer)
 * 支持以零拷贝、零延迟方式解析 HTTP WAV / Raw PCM 流容器头部，
 * 并以高效流水线将纯 PCM 字节直通至 C++ SPSC 无锁环形缓冲区。
 *
 * 核心特性：
 * 1. 精准提取 44~N 字节多 Chunk 容器头，深度流式解析 LIST INFO（INAM 歌名、IART 艺术家、IPRD 专辑）
 * 2. 完美适配 airupnp 无边界流 (Content-Length 为空或 0x7FFFFFFF)
 * 3. 统计已消费传输 PCM 采样字节数，精确上报播放时间戳 RelTime
 * 4. 实时上报底层硬件时钟采样率与位深
 */
class StreamDemuxer(
    private val scope: CoroutineScope,
    private val onStreamStarted: (sampleRate: Int, bitDepth: Int, channels: Int, codec: String) -> Unit,
    private val onStreamEnded: () -> Unit,
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
    private var lastPositionUpdateMs: Long = 0L

    fun isRunning(): Boolean = isDemuxing
    fun getCurrentUri(): String = currentStreamUrl

    /**
     * 💡 获取当前已流式传输/播放的时间戳 (秒)
     */
    fun getCurrentPlayPositionSeconds(): Long {
        val bps = bytesPerSecond
        if (bps > 0L && totalBytesStreamed > 0L) {
            return totalBytesStreamed / bps
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
        val thisSession = sessionToken.incrementAndGet()
        progressJob?.cancel()
        progressJob = null
        streamingJob?.cancel()
        streamingJob = null

        isDemuxing = true
        currentStreamUrl = streamUrl
        totalBytesStreamed = 0L
        bytesPerSecond = 0L
        totalDurationSeconds = 0L
        flacStartTimeMs = 0L

        // 💡 启动播放进度定期更新任务 (每 500ms 刷新一次)
        progressJob = scope.launch(Dispatchers.Default) {
            while (isActive && isDemuxing && thisSession == sessionToken.get()) {
                val posMs = getCurrentPlayPositionSeconds() * 1000L
                onPositionUpdated?.invoke(posMs)
                delay(500)
            }
        }

        streamingJob = scope.launch(Dispatchers.IO) {
            Logger.upnp.i(TAG, "开始连接音频流 (session=$thisSession): $streamUrl")
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null

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

                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("HTTP 请求失败: 状态码 $responseCode")
                }

                if (thisSession != sessionToken.get()) return@launch
                val contentType = connection.contentType ?: "audio/wav"
                Logger.upnp.i(TAG, "HTTP 连接成功 (session=$thisSession), Content-Type: $contentType, Content-Length: ${connection.contentLengthLong}")

                inputStream = BufferedInputStream(connection.inputStream, 64 * 1024)

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
                    Logger.upnp.i(TAG, "检测到 FLAC 无损流容器 (fLaC)，交由 Native dr_flac 流式无损解码...")
                    flacStartTimeMs = System.currentTimeMillis()
                    val ok = NativeAudioEngine.decodeFlacStream(inputStream) { rate, bits, ch ->
                        if (thisSession == sessionToken.get()) {
                            Logger.upnp.i(TAG, "Native dr_flac 解析音频规格成功: $rate Hz, $bits bit, $ch ch")
                            bytesPerSecond = (rate * ch * (bits / 8)).toLong()
                            onStreamStarted(rate, bits, ch, "FLAC")
                        }
                    }
                    if (!ok) {
                        Logger.upnp.w(TAG, "Native dr_flac 解码失败或提前退出 (session=$thisSession)")
                    }
                } else if (magicStr == "RIFF") {
                    // 💡 解析容器头 (WAV / L16)
                    val format = parseHeader(inputStream)
                    if (thisSession != sessionToken.get()) return@launch
                    Logger.upnp.i(
                        TAG,
                        "WAV 容器头部解析完毕: 采样率=${format.sampleRate}Hz, 位深=${format.bitDepth}bit, 声道=${format.channels}, dataSize=${format.dataSize}"
                    )

                    // 计算每秒字节数
                    bytesPerSecond = (format.sampleRate * format.channels * (format.bitDepth / 8)).toLong()
                    if (format.dataSize > 0L && format.dataSize != 0x7FFFFFFFL && bytesPerSecond > 0L) {
                        totalDurationSeconds = format.dataSize / bytesPerSecond
                        Logger.upnp.i(TAG, "已计算出音频总时长: ${totalDurationSeconds}秒")
                    }

                    // 通知主服务开启硬件独占与时钟锁定
                    if (thisSession == sessionToken.get()) {
                        onStreamStarted(format.sampleRate, format.bitDepth, format.channels, "WAV")
                    }

                    // 💡 流式搬运纯 PCM 数据包直接灌入 Native SPSC RingBuffer
                    val pcmBuffer = ByteArray(4096)

                    while (isActive && isDemuxing && thisSession == sessionToken.get()) {
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
                                val written = NativeAudioEngine.writePcm(pcmBuffer, offset, remaining)
                                if (written > 0) {
                                    offset += written
                                    remaining -= written
                                } else {
                                    // 💡 环形缓冲满载时微休眠 2ms 等待等时 URB 消费，绝对禁止丢字节以防破坏 16-bit 采样字节对齐
                                    Thread.sleep(2)
                                }
                            }
                        }
                    }
                } else {
                    Logger.upnp.e(TAG, "未知的音频流魔数: $magicStr，拦截直通以防杂音")
                    throw IllegalArgumentException("不支持的非 WAV/FLAC 格式流: $magicStr")
                }

                Logger.upnp.i(TAG, "音频流数据拉取完毕，等待环形缓冲排空播放 (session=$thisSession)...")
                // 💡 等待环形缓冲区中的数据被 USB DAC 完全消费播放完毕 (最多等待 6 秒防挂起)
                var waitCount = 0
                while (isActive && isDemuxing && thisSession == sessionToken.get() && NativeAudioEngine.getAvailableRead() > 0 && waitCount < 120) {
                    delay(50)
                    waitCount++
                }
                if (thisSession == sessionToken.get()) {
                    Logger.upnp.i(TAG, "音频流播放完毕 (session=$thisSession)")
                }
            } catch (e: CancellationException) {
                Logger.upnp.i(TAG, "音频拉取流被主动取消 (session=$thisSession)")
            } catch (e: Exception) {
                Logger.upnp.e(TAG, "音频流拉取/解包异常 (session=$thisSession)", e)
                if (thisSession == sessionToken.get()) {
                    onError("音频流传输异常: ${e.message}")
                }
            } finally {
                try {
                    inputStream?.close()
                    connection?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
                // 💡 仅当前活跃会话有权修改全局状态与触发流结束回调，杜绝快速切歌时旧协程误杀新流
                if (thisSession == sessionToken.get()) {
                    progressJob?.cancel()
                    progressJob = null
                    isDemuxing = false
                    onStreamEnded()
                    Logger.upnp.i(TAG, "活跃会话 (session=$thisSession) 正常结束并回调 onStreamEnded")
                } else {
                    Logger.upnp.i(TAG, "旧协程 (session=$thisSession) 资源清理完成，当前活跃会话为 ${sessionToken.get()}，跳过全局状态重置")
                }
            }
        }
    }

    fun stop() {
        sessionToken.incrementAndGet()
        isDemuxing = false
        currentStreamUrl = ""
        progressJob?.cancel()
        progressJob = null
        streamingJob?.cancel()
        streamingJob = null
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

    companion object {
        private const val TAG = "StreamDemuxer"
    }
}
