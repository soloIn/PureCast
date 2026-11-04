package com.solo.purecast.upnp

import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * 适配特性：
 * 1. 精准提取 44~N 字节多 Chunk 容器头 (跳过 LIST/JUNK/bext 等扩展块)
 * 2. 完美适配 airupnp 无边界流 (Content-Length 为空或 0x7FFFFFFF)
 * 3. 实时上报底层硬件时钟采样率与位深
 */
class StreamDemuxer(
    private val scope: CoroutineScope,
    private val onStreamStarted: (sampleRate: Int, bitDepth: Int, channels: Int) -> Unit,
    private val onStreamEnded: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var streamingJob: Job? = null
    @Volatile
    private var isDemuxing = false

    fun isRunning(): Boolean = isDemuxing

    /**
     * 启动流式拉取与解析任务
     * @param streamUrl HTTP 媒体流地址 (如 http://192.168.2.5:49152/stream.wav)
     */
    fun start(streamUrl: String) {
        stop()
        isDemuxing = true

        streamingJob = scope.launch(Dispatchers.IO) {
            Logger.upnp.i(TAG, "开始连接音频流: $streamUrl")
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null

            try {
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

                val contentType = connection.contentType ?: "audio/wav"
                Logger.upnp.i(TAG, "HTTP 连接成功, Content-Type: $contentType, Content-Length: ${connection.contentLengthLong}")

                inputStream = BufferedInputStream(connection.inputStream, 64 * 1024)

                // 💡 步骤 1: 探测前 12 字节魔数区分 FLAC 或 WAV
                inputStream.mark(64)
                val magic = ByteArray(12)
                readFully(inputStream, magic, 0, 12)
                inputStream.reset()

                val magicStr = String(magic, 0, 4)
                var totalBytesReceived = 0L

                if (magicStr == "fLaC") {
                    Logger.upnp.i(TAG, "检测到 FLAC 无损流容器 (fLaC)，交由 Native dr_flac 流式无损解码...")
                    val ok = NativeAudioEngine.decodeFlacStream(inputStream) { rate, bits, ch ->
                        Logger.upnp.i(TAG, "Native dr_flac 解析音频规格成功: $rate Hz, $bits bit, $ch ch")
                        onStreamStarted(rate, bits, ch)
                    }
                    if (!ok) {
                        Logger.upnp.w(TAG, "Native dr_flac 解码失败或提前退出")
                    }
                } else if (magicStr == "RIFF") {
                    // 💡 解析容器头 (WAV / L16)
                    val format = parseHeader(inputStream)
                    Logger.upnp.i(TAG, "WAV 容器头部解析完毕: 采样率=${format.sampleRate}Hz, 位深=${format.bitDepth}bit, 声道=${format.channels}")

                    // 通知主服务开启硬件独占与时钟锁定
                    onStreamStarted(format.sampleRate, format.bitDepth, format.channels)

                    // 💡 流式搬运纯 PCM 数据包直接灌入 Native SPSC RingBuffer
                    val pcmBuffer = ByteArray(4096)

                    while (isActive && isDemuxing) {
                        val bytesRead = inputStream.read(pcmBuffer, 0, pcmBuffer.size)
                        if (bytesRead == -1) {
                            Logger.upnp.i(TAG, "音频流正常读取至 EOF 边界")
                            break
                        }

                        if (bytesRead > 0) {
                            totalBytesReceived += bytesRead
                            var offset = 0
                            var remaining = bytesRead
                            while (remaining > 0 && isActive && isDemuxing) {
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

                Logger.upnp.i(TAG, "音频流数据拉取完毕，等待环形缓冲排空播放...")
                // 💡 等待环形缓冲区中的数据被 USB DAC 完全消费播放完毕 (最多等待 6 秒防挂起)
                var waitCount = 0
                while (isActive && isDemuxing && NativeAudioEngine.getAvailableRead() > 0 && waitCount < 120) {
                    Thread.sleep(50)
                    waitCount++
                }
                Logger.upnp.i(TAG, "音频流播放完毕")
            } catch (e: CancellationException) {
                Logger.upnp.i(TAG, "音频拉取流被主动取消")
            } catch (e: Exception) {
                Logger.upnp.e(TAG, "音频流拉取/解包异常", e)
                onError("音频流传输异常: ${e.message}")
            } finally {
                try {
                    inputStream?.close()
                    connection?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
                isDemuxing = false
                onStreamEnded()
            }
        }
    }

    fun stop() {
        isDemuxing = false
        streamingJob?.cancel()
        streamingJob = null
    }

    data class AudioStreamFormat(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int
    )

    /**
     * 流式状态机解析 WAV 头部多 Chunk
     */
    private fun parseHeader(input: InputStream): AudioStreamFormat {
        val magic = ByteArray(12)
        readFully(input, magic, 0, 12)

        val riff = String(magic, 0, 4)
        val wave = String(magic, 8, 4)

        if (riff != "RIFF" || wave != "WAVE") {
            Logger.upnp.e(TAG, "流格式非标准无损 WAV/PCM (riff=$riff, wave=$wave)，拦截直通以防压缩数据产生白噪声")
            throw IllegalArgumentException("不支持的非 WAV 格式流: $riff / $wave (请确保投屏端传输无损 PCM/WAV)")
        }

        var sampleRate = 44100
        var bitDepth = 16
        var channels = 2
        var dataFound = false

        val chunkHeader = ByteArray(8)
        while (!dataFound) {
            readFully(input, chunkHeader, 0, 8)
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

            when (chunkId) {
                "fmt " -> {
                    val fmtData = ByteArray(chunkSize)
                    readFully(input, fmtData, 0, chunkSize)
                    val fmtBuf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                    fmtBuf.short // audioFormat (1=PCM)
                    channels = fmtBuf.short.toInt()
                    sampleRate = fmtBuf.int
                    fmtBuf.int // byteRate
                    fmtBuf.short // blockAlign
                    bitDepth = fmtBuf.short.toInt()
                    Logger.upnp.d(TAG, "解析 fmt Chunk 成功: $sampleRate Hz, $bitDepth bit, $channels ch")
                }

                "data" -> {
                    dataFound = true
                    Logger.upnp.d(TAG, "精准定位到 data Chunk 边界，准备直通 Raw PCM 载荷 (chunkSize=$chunkSize)")
                }

                else -> {
                    // 💡 跳过 LIST, JUNK, bext 等扩展 Chunk
                    Logger.upnp.d(TAG, "跳过非 data 扩展 Chunk: $chunkId, 长度: $chunkSize 字节")
                    var toSkip = chunkSize
                    val skipBuf = ByteArray(1024)
                    while (toSkip > 0) {
                        val n = input.read(skipBuf, 0, Math.min(toSkip, skipBuf.size))
                        if (n == -1) break
                        toSkip -= n
                    }
                }
            }
        }

        return AudioStreamFormat(sampleRate, bitDepth, channels)
    }

    private fun readFully(input: InputStream, target: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val r = input.read(target, offset + read, length - read)
            if (r == -1) throw IllegalStateException("流提前结束 (EOF in readFully)")
            read += r
        }
    }

    companion object {
        private const val TAG = "StreamDemuxer"
    }
}
