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

                // 💡 步骤 1: 解析容器头 (WAV / L16)
                val format = parseHeader(inputStream)
                Logger.upnp.i(TAG, "容器头部解析完毕: 采样率=${format.sampleRate}Hz, 位深=${format.bitDepth}bit, 声道=${format.channels}")

                // 通知主服务开启硬件独占与时钟锁定
                onStreamStarted(format.sampleRate, format.bitDepth, format.channels)

                // 💡 步骤 2: 流式搬运纯 PCM 数据包直接灌入 Native SPSC RingBuffer
                val pcmBuffer = ByteArray(4096)
                var totalBytesReceived = 0L

                while (isActive && isDemuxing) {
                    val bytesRead = inputStream.read(pcmBuffer, 0, pcmBuffer.size)
                    if (bytesRead == -1) {
                        Logger.upnp.i(TAG, "音频流正常读取至 EOF 边界")
                        break
                    }

                    if (bytesRead > 0) {
                        totalBytesReceived += bytesRead
                        // 零拷贝直接写入 C++ 环形缓冲
                        val written = NativeAudioEngine.writePcm(pcmBuffer, 0, bytesRead)
                        if (written < bytesRead) {
                            // 环形缓冲满载时微休眠 2ms 等待等时 URB 消费
                            Thread.sleep(2)
                            val remaining = bytesRead - written
                            NativeAudioEngine.writePcm(pcmBuffer, written, remaining)
                        }
                    }
                }

                Logger.upnp.i(TAG, "音频流传输完成，总共传输 $totalBytesReceived 字节 PCM 载荷")
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
            Logger.upnp.w(TAG, "未检测到标准 RIFF/WAVE 标识符 (riff=$riff, wave=$wave)，默认回退至 44.1kHz/16bit/2ch")
            return AudioStreamFormat(44100, 16, 2)
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
