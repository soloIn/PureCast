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
    fun isStreaming(): Boolean = true
    fun isExplicitlyPaused(): Boolean = !isStreaming()
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

    override fun isStreaming(): Boolean = NativeAudioEngine.isStreaming()
    override fun isExplicitlyPaused(): Boolean = NativeAudioEngine.isExplicitlyPaused()
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
        Triple(durMs, DurationState.FIXED, SeekCapability.UNSEEKABLE)
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
    private val onAcquireStreamEpoch: (spec: DemuxedStreamSpec) -> Long = { 1L },
    private val onFirstDataPacket: (epoch: Long, spec: DemuxedStreamSpec) -> Unit = { _, _ -> },
    private val onStreamEnded: (abortedByEpoch: Boolean, finalEpoch: Long) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {},
    private val onMetadataParsed: ((title: String?, artist: String?, album: String?) -> Unit)? = null,
    private val onPositionUpdated: ((positionMs: Long) -> Unit)? = null,
    private val onSeekFlush: (() -> Long)? = null,
    private val onStreamRevokedMidPlayback: (() -> Boolean)? = null
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
    private var seekBaseBytes: Long = 0L
    @Volatile
    private var wavDataStartOffsetBytes: Long = 0L
    @Volatile
    private var wavDataSizeBytes: Long = 0L
    @Volatile
    private var bytesPerSecond: Long = 0L
    @Volatile
    private var totalDurationSeconds: Long = 0L
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
    @Volatile
    private var acquiredEpoch: Long = 0L

    fun isRunning(): Boolean = isDemuxing
    fun getCurrentUri(): String = currentStreamUrl

    /**
     * 💡 获取当前已流式传输/播放的时间戳 (毫秒)，具备亚秒级精度
     */
    fun getCurrentPlayPositionMs(): Long {
        val bps = bytesPerSecond
        if (bps > 0L) {
            if (isFlacStream && currentSampleRate > 0) {
                val decodedFrames = audioGateway.getFlacDecodedFrames()
                val bytesPerFrame = currentChannels * (currentBitDepth / 8)
                val bufferedFrames = if (bytesPerFrame > 0) (audioGateway.getAvailableRead() / bytesPerFrame).toLong() else 0L
                val playedFrames = (decodedFrames - bufferedFrames).coerceAtLeast(0L)
                val rate = currentSampleRate.toLong()
                return (playedFrames / rate) * 1000L + ((playedFrames % rate) * 1000L) / rate
            } else if (totalBytesStreamed > 0L || seekBaseBytes > 0L) {
                val playedBytes = (seekBaseBytes + totalBytesStreamed - audioGateway.getAvailableRead().toLong()).coerceAtLeast(0L)
                return (playedBytes / bps) * 1000L + ((playedBytes % bps) * 1000L) / bps
            }
        }
        return 0L
    }

    /**
     * 💡 获取当前已流式传输/播放的时间戳 (秒)
     */
    fun getCurrentPlayPositionSeconds(): Long = getCurrentPlayPositionMs() / 1000L

    /**
     * 💡 获取当前音轨总时长 (秒)，若流为实时直播流 (AirPlay / 广播) 则返回 0
     */
    fun getTotalDurationSeconds(): Long = totalDurationSeconds

    /**
     * 💡 HTTP Range 寻道
     */
    fun seekTo(targetPositionMs: Long): Boolean {
        if (!isDemuxing || isFlacStream || bytesPerSecond <= 0L || totalDurationSeconds <= 0L) {
            return false
        }
        val url = currentStreamUrl
        if (url.isEmpty()) return false

        val bytesPerFrame = currentChannels * (currentBitDepth / 8)
        if (bytesPerFrame <= 0) return false

        val durMs = totalDurationSeconds * 1000L
        val clampedMs = targetPositionMs.coerceIn(0L, durMs)
        val pcmOffset = ((clampedMs * bytesPerSecond) / 1000L)
        var alignedPcmOffset = (pcmOffset / bytesPerFrame) * bytesPerFrame

        // 💡 防御 HTTP 416: 若寻道目标接近或超出曲目尾部 (EOF)，进行安全边界钳位，保留至少安全帧缓冲，杜绝 Range 越界触发 416
        val maxSafePcmOffset = if (wavDataSizeBytes > 0L) {
            (wavDataSizeBytes - bytesPerFrame * 32L).coerceAtLeast(0L)
        } else {
            (((durMs - 500L).coerceAtLeast(0L) * bytesPerSecond) / 1000L)
        }
        if (alignedPcmOffset > maxSafePcmOffset) {
            alignedPcmOffset = (maxSafePcmOffset / bytesPerFrame) * bytesPerFrame
        }

        // 💡 立即设置 seekBaseBytes 并通知 UI 目标位置，杜绝在网络建连期间外部读取旧偏移导致进度条回弹 (P0 寻道修复)
        seekBaseBytes = alignedPcmOffset
        totalBytesStreamed = 0L
        onPositionUpdated?.invoke(clampedMs)

        // 💡 防御协程竞争：先取消先前的 streamingJob 与 progressJob，并递增会话 token 隔离旧连接
        val prevJob = streamingJob
        prevJob?.cancel()
        val thisSession = sessionToken.incrementAndGet()
        progressJob?.cancel()
        progressJob = null
        detachAndCloseActiveStreamAsync()

        streamingJob = scope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var abortedByEpoch = false
            var seekEpoch = acquiredEpoch
            try {
                if (thisSession != sessionToken.get()) return@launch
                val connUrl = URL(url)
                val rangeHeader = "bytes=${wavDataStartOffsetBytes + alignedPcmOffset}-"
                Logger.upnp.i(TAG, "执行 HTTP Range Seek: target=${clampedMs}ms, offset=$alignedPcmOffset, header=$rangeHeader")
                connection = (connUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PureCast-UPnP-Renderer/1.0")
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Range", rangeHeader)
                }
                activeConnection = connection
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode == 206) {
                    // 💡 P0 修复：防御迟到 206 竞态。若网络建连期间触发了新寻道或协程已取消，立即丢弃，严禁调用 onSeekFlush 冲刷有效 Epoch！
                    if (!isActive || thisSession != sessionToken.get()) {
                        Logger.upnp.w(TAG, "Seek HTTP 206 响应到达但会话已过期 (session=$thisSession, current=${sessionToken.get()})，放弃冲刷")
                        abortedByEpoch = true
                        return@launch
                    }
                    inputStream = BufferedInputStream(connection.inputStream, 64 * 1024)
                    activeInputStream = inputStream

                    seekEpoch = onSeekFlush?.invoke() ?: acquiredEpoch
                    this@StreamDemuxer.acquiredEpoch = seekEpoch

                    // 💡 寻道成功建立 206 传输后，重启进度轮询循环，彻底解决 seekTo 后播放进度永久冻结的缺陷 (P0)
                    startProgressTicker(thisSession)

                    val currentSpec = DemuxedStreamSpec(
                        sampleRate = currentSampleRate,
                        bitDepth = currentBitDepth,
                        channels = currentChannels,
                        codec = "WAV",
                        durationMs = durMs,
                        durationState = DurationState.FIXED,
                        seekCapability = SeekCapability.SEEKABLE
                    )
                    val hasDataSizeLimit = wavDataSizeBytes > 0L && wavDataSizeBytes < 0x7FFFFFFFL
                    abortedByEpoch = streamWavPcmLoop(
                        streamUrl = url,
                        thisSession = thisSession,
                        initialConnection = connection,
                        initialInputStream = inputStream,
                        spec = currentSpec,
                        hasDataSizeLimit = hasDataSizeLimit,
                        wavDataSizeBytes = wavDataSizeBytes,
                        logPrefix = "Seek",
                        currentEpochProvider = { seekEpoch },
                        onConnectionSwapped = { newConn, newStream ->
                            val prevConn = connection
                            val prevStream = inputStream
                            connection = newConn
                            inputStream = newStream
                            if (thisSession == sessionToken.get()) {
                                activeConnection = newConn
                                activeInputStream = newStream
                            } else {
                                if (activeConnection === prevConn) activeConnection = null
                                if (activeInputStream === prevStream) activeInputStream = null
                            }
                        }
                    )

                    if (!abortedByEpoch && thisSession == sessionToken.get()) {
                        Logger.upnp.i(TAG, "Seek 音频流拉取完毕，等待排空 (session=$thisSession)...")
                        var waitCount = 0
                        var pausedWaitCount = 0
                        while (isActive && isDemuxing && thisSession == sessionToken.get() && audioGateway.getAvailableRead() > 0 && waitCount < 120) {
                            if (audioGateway.isExplicitlyPaused()) {
                                delay(50)
                                if (++pausedWaitCount > 12000) { // 💡 P1-3 修复：暂停态等待上限 10 分钟 (600s)，对齐协调器挂起回收契约
                                    abortedByEpoch = true
                                    break
                                }
                                continue
                            }
                            delay(50)
                            waitCount++
                        }
                        delay(25)
                    }
                } else {
                    Logger.upnp.w(TAG, "HTTP Range Seek 返回非 206 状态码: $responseCode")
                    abortedByEpoch = true
                    if (thisSession == sessionToken.get()) {
                        onError("HTTP Range Seek 失败: 响应码 $responseCode")
                    }
                }
            } catch (e: CancellationException) {
                abortedByEpoch = true
            } catch (e: Exception) {
                Logger.upnp.w(TAG, "HTTP Range Seek 异常", e)
                abortedByEpoch = true
                if (thisSession == sessionToken.get()) {
                    onError("HTTP Range Seek 传输异常: ${e.message}")
                }
            } finally {
                if (activeConnection === connection) activeConnection = null
                if (activeInputStream === inputStream) activeInputStream = null
                try { inputStream?.close() } catch (e: Exception) { Logger.upnp.d(TAG, "关闭 Seek 输入流异常: ${e.message}") }
                try { connection?.disconnect() } catch (e: Exception) { Logger.upnp.d(TAG, "断开 Seek HTTP 连接异常: ${e.message}") }
                // 💡 完整收尾：同步重置 isDemuxing 并触发 onStreamEnded，防止 Seek 后自然播完进度冻结 (B-1)
                if (thisSession == sessionToken.get()) {
                    progressJob?.cancel()
                    progressJob = null
                    isDemuxing = false
                    onStreamEnded(abortedByEpoch, seekEpoch)
                    Logger.upnp.i(TAG, "Seek 活跃会话 (session=$thisSession) 结束 (abortedByEpoch=$abortedByEpoch)")
                }
            }
        }
        return true
    }

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
        seekBaseBytes = 0L // 💡 P1 修复：切歌换曲启动新流时彻底重置 seekBaseBytes，杜绝继承上一首曲目的寻道基数导致播放进度漂移
        bytesPerSecond = 0L
        totalDurationSeconds = 0L
        flacTotalFrames = 0L
        currentSampleRate = 0
        currentBitDepth = 0
        currentChannels = 0
        isFlacStream = false

        // 💡 启动播放进度定期更新任务 (每 500ms 刷新一次)
        startProgressTicker(thisSession)

        streamingJob = scope.launch(Dispatchers.IO) {
            // 💡 确保前序 native 解码任务彻底退出，消除快速切歌时计数器与 JNI 资源的并发竞争
            try {
                previousJob?.cancelAndJoin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.upnp.d(TAG, "等待前序拉流任务取消结束时捕获异常: ${e.message}")
            }
            if (thisSession != sessionToken.get()) return@launch

            audioGateway.resetFlacDecodedFrames()

            Logger.upnp.i(TAG, "开始连接音频流 (session=$thisSession): $streamUrl")
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var abortedByEpoch = false
            this@StreamDemuxer.acquiredEpoch = -1L

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
                if ((responseCode == 404 || responseCode == 410) && onStreamRevokedMidPlayback?.invoke() == true) {
                    Logger.upnp.i(TAG, "对端已回收当前临时流 URI (initial GET status=$responseCode, session=$thisSession)，保持会话等待新推流")
                    abortedByEpoch = true
                    return@launch
                }
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
                                val epoch = onAcquireStreamEpoch(spec)
                                this@StreamDemuxer.acquiredEpoch = epoch
                                if (epoch <= 0L) {
                                    abortedByEpoch = true
                                }
                                return epoch
                            }

                            override fun onFirstPcmWritten() {
                                val spec = currentSpec ?: return
                                val ep = this@StreamDemuxer.acquiredEpoch
                                if (thisSession == sessionToken.get() && ep > 0L) {
                                    onFirstDataPacket(ep, spec)
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
                    wavDataStartOffsetBytes = format.headerLengthBytes
                    wavDataSizeBytes = format.dataSize
                    currentSampleRate = format.sampleRate
                    currentBitDepth = format.bitDepth
                    currentChannels = format.channels
                    flacTotalFrames = 0L

                    bytesPerSecond = (format.sampleRate.toLong() * format.channels * (format.bitDepth / 8))
                    val (durMs, durState, seekCap) = resolveWavDurationSpec(format.dataSize, bytesPerSecond)
                    totalDurationSeconds = durMs / 1000L
                    Logger.upnp.i(
                        TAG,
                        "WAV 容器头部解析完毕: ${format.sampleRate}Hz/${format.bitDepth}bit/${format.channels}ch, dataSize=${format.dataSize}, headerLen=${format.headerLength}, durationState=$durState"
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
                    val epoch = onAcquireStreamEpoch(spec)
                    this@StreamDemuxer.acquiredEpoch = epoch
                    if (epoch <= 0L) {
                        abortedByEpoch = true
                        Logger.upnp.w(TAG, "WAV 获取硬件世代失败 ($epoch)，中止推流 (session=$thisSession)")
                        return@launch
                    }

                    val wavDataSizeBytes = format.dataSize
                    val hasDataSizeLimit = wavDataSizeBytes > 0L && wavDataSizeBytes < 0x7FFFFFFFL
                    abortedByEpoch = streamWavPcmLoop(
                        streamUrl = streamUrl,
                        thisSession = thisSession,
                        initialConnection = connection,
                        initialInputStream = inputStream,
                        spec = spec,
                        hasDataSizeLimit = hasDataSizeLimit,
                        wavDataSizeBytes = wavDataSizeBytes,
                        logPrefix = "WAV",
                        currentEpochProvider = { this@StreamDemuxer.acquiredEpoch },
                        onConnectionSwapped = { newConn, newStream ->
                            val prevConn = connection
                            val prevStream = inputStream
                            connection = newConn
                            inputStream = newStream
                            if (thisSession == sessionToken.get()) {
                                activeConnection = newConn
                                activeInputStream = newStream
                            } else {
                                if (activeConnection === prevConn) activeConnection = null
                                if (activeInputStream === prevStream) activeInputStream = null
                            }
                        }
                    )
                } else {
                    Logger.upnp.e(TAG, "未知的音频流魔数: $magicStr，拦截直通以防杂音")
                    throw IllegalArgumentException("不支持的非 WAV/FLAC 格式流: $magicStr")
                }

                if (!abortedByEpoch && thisSession == sessionToken.get()) {
                    Logger.upnp.i(TAG, "音频流数据拉取完毕，等待环形缓冲排空播放 (session=$thisSession)...")
                    var waitCount = 0
                    var pausedWaitCount = 0
                    var lastAvailableRead = -1
                    var stableCount = 0
                    while (isActive && isDemuxing && thisSession == sessionToken.get() && audioGateway.getAvailableRead() > 0 && waitCount < 120) {
                        // 💡 暂停态下底层 fill_isoc_transfer 填零静音，环形缓冲被冻结不会被消费。
                        // 此时若累计 waitCount 会导致 6 秒超时误判 EOF 强停。暂停期间挂起循环等待，不计入排空超时。
                        if (audioGateway.isExplicitlyPaused()) {
                            delay(50)
                            if (++pausedWaitCount > 12000) { // 💡 P1-3: 暂停态等待上限 10 分钟 (600s)，对齐协调器挂起回收契约
                                abortedByEpoch = true
                                break
                            }
                            continue
                        }

                        val avail = audioGateway.getAvailableRead()
                        // 💡 尾部排空优化：若剩余字节不足单微帧或处于极低水位（<= 1024 字节）且连续 300ms 无变化，判定为已播完
                        if (avail <= 1024) {
                            if (avail == lastAvailableRead) {
                                if (++stableCount >= 6) { // 6 * 50ms = 300ms
                                    Logger.upnp.i(TAG, "环形缓冲尾部残余不足单微帧/极低水位已停止消费 ($avail 字节)，提早判定排空完成")
                                    break
                                }
                            } else {
                                lastAvailableRead = avail
                                stableCount = 0
                            }
                        }

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
                // 💡 异常报错后标记 abortedByEpoch = true，避免 finally 块再重复派发虚假的自然播完 (EOF) 事件
                abortedByEpoch = true
                Logger.upnp.e(TAG, "音频流拉取/解包异常 (session=$thisSession)", e)
                if (thisSession == sessionToken.get()) {
                    onError("音频流传输异常: ${e.message}")
                }
            } finally {
                if (activeConnection === connection) activeConnection = null
                if (activeInputStream === inputStream) activeInputStream = null
                try {
                    inputStream?.close()
                    connection?.disconnect()
                } catch (e: Exception) {
                    Logger.upnp.d(TAG, "清理音频流连接异常: ${e.message}")
                }
                if (thisSession == sessionToken.get()) {
                    progressJob?.cancel()
                    progressJob = null
                    isDemuxing = false
                    // 💡 防御空流假播完：区分 FLAC（通过 Native 统计解码帧数）与 WAV（通过读取流累加字节），
                    // 若两者均未产生任何实际推流或未获取到合法硬件世代，强制标记为中断，杜绝误触发切歌
                    val finalBytesStreamed = totalBytesStreamed
                    val finalFlacFrames = audioGateway.getFlacDecodedFrames()
                    val hasStreamedData = if (isFlacStream) {
                        finalFlacFrames > 0L
                    } else {
                        finalBytesStreamed > 0L
                    }
                    val isAborted = abortedByEpoch || !hasStreamedData || this@StreamDemuxer.acquiredEpoch <= 0L
                    onStreamEnded(isAborted, this@StreamDemuxer.acquiredEpoch)
                    Logger.upnp.i(TAG, "活跃会话 (session=$thisSession) 结束 (abortedByEpoch=$isAborted, isFlac=$isFlacStream, totalBytesStreamed=$finalBytesStreamed, flacFrames=$finalFlacFrames)")
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
        totalBytesStreamed = 0L
        seekBaseBytes = 0L // 💡 P1 修复：停止推流时同步清零寻道基数
        isFlacStream = false
        progressJob?.cancel()
        progressJob = null
        streamingJob?.cancel()
        streamingJob = null
        audioGateway.resetFlacDecodedFrames()
    }

    /**
     * 💡 帧对齐 WAV PCM 流式读取、背压写入、暂停态断流挂起与 HTTP Range 自动断点续传共用循环
     */
    private suspend fun streamWavPcmLoop(
        streamUrl: String,
        thisSession: Long,
        initialConnection: HttpURLConnection,
        initialInputStream: InputStream,
        spec: DemuxedStreamSpec,
        hasDataSizeLimit: Boolean,
        wavDataSizeBytes: Long,
        logPrefix: String,
        currentEpochProvider: () -> Long,
        onConnectionSwapped: (HttpURLConnection?, InputStream?) -> Unit
    ): Boolean {
        var connection: HttpURLConnection? = initialConnection
        var inputStream: InputStream? = initialInputStream
        val bytesPerFrame = (spec.channels * (spec.bitDepth / 8)).coerceAtLeast(1)
        val maxChunkBytes = (16384 / bytesPerFrame).coerceAtLeast(1) * bytesPerFrame
        val minWriteChunk = (4096 / bytesPerFrame).coerceAtLeast(1) * bytesPerFrame
        val pcmBuffer = ByteArray(maxChunkBytes + bytesPerFrame)
        var carryLen = 0
        var firstPacketEmitted = false
        var abortedByEpoch = false
        var reconnectAttempts = 0

        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false && isDemuxing && thisSession == sessionToken.get() && !abortedByEpoch) {
            val logicalStreamed = seekBaseBytes + totalBytesStreamed
            if (hasDataSizeLimit && logicalStreamed >= wavDataSizeBytes) {
                Logger.upnp.i(TAG, "已到达 WAV dataSize 边界 ($logicalStreamed/$wavDataSizeBytes 字节)，停止继续读取 (session=$thisSession)")
                break
            }

            val maxToRead = if (hasDataSizeLimit) {
                (wavDataSizeBytes - logicalStreamed - carryLen.toLong()).coerceAtMost(maxChunkBytes.toLong()).toInt()
            } else {
                maxChunkBytes
            }
            if (maxToRead <= 0) {
                break
            }

            val bytesRead: Int = try {
                val r = inputStream!!.read(pcmBuffer, carryLen, maxToRead)
                if (r == -1) {
                    if (hasDataSizeLimit && (logicalStreamed + carryLen.toLong()) < wavDataSizeBytes) {
                        throw java.io.EOFException("unexpected end of stream")
                    }
                    Logger.upnp.i(TAG, "音频流正常读取至 EOF 边界 (session=$thisSession)")
                    break
                }
                r
            } catch (ioe: java.io.IOException) {
                if (kotlin.coroutines.coroutineContext[Job]?.isActive == false || !isDemuxing || thisSession != sessionToken.get()) {
                    abortedByEpoch = true
                    break
                }
                try { inputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
                connection = null
                inputStream = null
                onConnectionSwapped(null, null)

                var disconnectedWhilePaused = false
                if (audioGateway.isExplicitlyPaused()) {
                    disconnectedWhilePaused = true
                    Logger.upnp.i(TAG, "音频流在暂停期间被对端断开，挂起等待恢复播放后自动续传 (session=$thisSession, reason=${ioe.message})")
                    var pausedWaitCount = 0
                    while (kotlin.coroutines.coroutineContext[Job]?.isActive != false && isDemuxing && thisSession == sessionToken.get() && audioGateway.isExplicitlyPaused()) {
                        delay(50)
                        if (++pausedWaitCount > 12000) {
                            abortedByEpoch = true
                            break
                        }
                    }
                    if (abortedByEpoch || kotlin.coroutines.coroutineContext[Job]?.isActive == false || !isDemuxing || thisSession != sessionToken.get()) {
                        abortedByEpoch = true
                        break
                    }
                    reconnectAttempts = 0
                }

                val resumeDataOffset = seekBaseBytes + totalBytesStreamed
                val canRangeReconnect = hasDataSizeLimit &&
                    wavDataStartOffsetBytes > 0L &&
                    resumeDataOffset >= 0L &&
                    resumeDataOffset < wavDataSizeBytes

                if (!canRangeReconnect) {
                    throw ioe
                }

                var reconnected = false
                var reachedTrueEof = false
                var lastError: Exception = ioe
                while (reconnectAttempts < MAX_RANGE_RECONNECT_ATTEMPTS && kotlin.coroutines.coroutineContext[Job]?.isActive != false && isDemuxing && thisSession == sessionToken.get()) {
                    val backoffMs = if (disconnectedWhilePaused && reconnectAttempts == 0) {
                        0L
                    } else {
                        RANGE_RECONNECT_BACKOFF_MS[reconnectAttempts.coerceAtMost(RANGE_RECONNECT_BACKOFF_MS.lastIndex)]
                    }
                    if (backoffMs > 0L) {
                        delay(backoffMs)
                    }
                    if (kotlin.coroutines.coroutineContext[Job]?.isActive == false || !isDemuxing || thisSession != sessionToken.get()) {
                        abortedByEpoch = true
                        break
                    }
                    reconnectAttempts++
                    val nextByteOffset = wavDataStartOffsetBytes + (seekBaseBytes + totalBytesStreamed)
                    var nextConn: HttpURLConnection? = null
                    try {
                        Logger.upnp.i(TAG, "尝试 HTTP Range 断点续传 (session=$thisSession, attempt=$reconnectAttempts/$MAX_RANGE_RECONNECT_ATTEMPTS, range=bytes=$nextByteOffset-)")
                        nextConn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 15000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "PureCast-UPnP-Renderer/1.0")
                            setRequestProperty("Connection", "keep-alive")
                            setRequestProperty("Range", "bytes=$nextByteOffset-")
                        }
                        onConnectionSwapped(nextConn, null)
                        nextConn.connect()
                        if (kotlin.coroutines.coroutineContext[Job]?.isActive == false || !isDemuxing || thisSession != sessionToken.get()) {
                            try { nextConn.disconnect() } catch (_: Exception) {}
                            onConnectionSwapped(null, null)
                            abortedByEpoch = true
                            break
                        }
                        val code = nextConn.responseCode
                        if (code == HttpURLConnection.HTTP_PARTIAL) {
                            val nextIn = BufferedInputStream(nextConn.inputStream, 64 * 1024)
                            if (kotlin.coroutines.coroutineContext[Job]?.isActive == false || !isDemuxing || thisSession != sessionToken.get()) {
                                try { nextIn.close() } catch (_: Exception) {}
                                try { nextConn.disconnect() } catch (_: Exception) {}
                                onConnectionSwapped(null, null)
                                abortedByEpoch = true
                                break
                            }
                            connection = nextConn
                            inputStream = nextIn
                            carryLen = 0
                            onConnectionSwapped(nextConn, nextIn)
                            Logger.upnp.i(TAG, "HTTP Range 断点续传成功 (session=$thisSession, attempt=$reconnectAttempts, offset=$nextByteOffset)")
                            reconnected = true
                            break
                        } else {
                            try { nextConn.disconnect() } catch (_: Exception) {}
                            onConnectionSwapped(null, null)
                            if (code == 416) {
                                // 💡 HTTP 416 (Range Not Satisfiable) 表示对端实际音频流已到达真实 EOF（常见于动态转码 WAV 头部估算 dataSize 略大于实际解码长度）
                                Logger.upnp.i(TAG, "HTTP Range 续传返回 416，判定对端音频流已到达真实 EOF (session=$thisSession, streamed=${seekBaseBytes + totalBytesStreamed}/$wavDataSizeBytes)")
                                reachedTrueEof = true
                                break
                            }
                            if ((code == 404 || code == 410) && onStreamRevokedMidPlayback?.invoke() == true) {
                                Logger.upnp.i(TAG, "对端已回收当前临时流 URI (status=$code, session=$thisSession)，保持会话等待新推流")
                                abortedByEpoch = true
                                break
                            }
                            if (code == 404 || code == 410 || code == HttpURLConnection.HTTP_OK) {
                                Logger.upnp.w(TAG, "HTTP Range 断点续传不可恢复 (status=$code, session=$thisSession)，立即停止重试")
                                throw IllegalStateException("HTTP 请求失败: 状态码 $code")
                            }
                            lastError = IllegalStateException("HTTP 请求失败: 状态码 $code")
                        }
                    } catch (e: CancellationException) {
                        try { nextConn?.disconnect() } catch (_: Exception) {}
                        throw e
                    } catch (e: IllegalStateException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        try { nextConn?.disconnect() } catch (_: Exception) {}
                        onConnectionSwapped(null, null)
                        lastError = e
                    }
                }
                if (abortedByEpoch || reachedTrueEof) break
                if (!reconnected) {
                    throw lastError
                }
                continue
            }

            if (bytesRead > 0) {
                val totalAvailable = carryLen + bytesRead
                val alignedLen = (totalAvailable / bytesPerFrame) * bytesPerFrame
                val newCarryLen = totalAvailable - alignedLen
                var offset = 0
                var remaining = alignedLen
                while (remaining > 0 && kotlin.coroutines.coroutineContext[Job]?.isActive != false && isDemuxing && thisSession == sessionToken.get()) {
                    val avail = audioGateway.getBufferAvailable()
                    val targetMin = minOf(remaining, minWriteChunk)
                    if (avail < targetMin) {
                        if (audioGateway.isExplicitlyPaused()) {
                            delay(50)
                            continue
                        }
                        val needed = (targetMin - avail).coerceAtLeast(bytesPerFrame)
                        val waitMs = if (bytesPerSecond > 0L) {
                            ((needed.toLong() * 1000L) / bytesPerSecond).coerceIn(10L, 50L)
                        } else {
                            10L
                        }
                        delay(waitMs)
                        continue
                    }
                    val toWrite = (minOf(remaining, avail, maxChunkBytes) / bytesPerFrame) * bytesPerFrame
                    val currentEp = currentEpochProvider()
                    val written = audioGateway.writePcm(pcmBuffer, offset, toWrite, currentEp)
                    if (written > 0) {
                        totalBytesStreamed += written // 💡 仅在实际写入环形缓冲区后累加，杜绝预累加引发的 ~186ms 进度抖动
                        reconnectAttempts = 0
                        if (!firstPacketEmitted) {
                            firstPacketEmitted = true
                            onFirstDataPacket(currentEp, spec)
                        }
                        offset += written
                        remaining -= written
                    } else if (written < 0) {
                        // 💡 修复 Finding 7: 区分世代抢占 (-2) 与真实管道故障 (-1, -3)
                        if (written == -2) {
                            Logger.upnp.i(TAG, "$logPrefix writePcm 收到世代过期 (-2)，安全中止本轮写入: epoch=$currentEp")
                        } else {
                            Logger.upnp.e(TAG, "$logPrefix writePcm 遭遇底层管道致命错误: ret=$written, epoch=$currentEp")
                            val errorMsg = if (written == -1) "Audio pipeline closed" else "Audio latch timeout"
                            onError(errorMsg)
                        }
                        abortedByEpoch = true
                        break
                    } else {
                        delay(15L)
                    }
                }
                if (newCarryLen > 0) {
                    System.arraycopy(pcmBuffer, alignedLen, pcmBuffer, 0, newCarryLen)
                }
                carryLen = newCarryLen
            }
        }
        return abortedByEpoch
    }

    private fun startProgressTicker(session: Long) {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Default) {
            var lastEmittedPosMs = -1L
            while (isActive && isDemuxing && session == sessionToken.get()) {
                val posMs = getCurrentPlayPositionMs()
                if (posMs != lastEmittedPosMs) {
                    lastEmittedPosMs = posMs
                    onPositionUpdated?.invoke(posMs)
                }
                delay(500)
            }
        }
    }

    data class AudioStreamFormat(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val dataSize: Long = 0L,
        val headerLength: Int = 44,
        val headerLengthBytes: Long = headerLength.toLong()
    )

    /**
     * 流式状态机解析 WAV 头部多 Chunk，并提取 LIST INFO 内嵌元数据
     */
    internal fun parseHeader(input: InputStream): AudioStreamFormat {
        var totalBytesRead = 0L
        fun readFullyTracked(target: ByteArray, offset: Int, length: Int): Boolean {
            val ok = readFully(input, target, offset, length)
            if (ok) totalBytesRead += length.toLong()
            return ok
        }
        fun skipFullyTracked(length: Long) {
            skipFully(input, length)
            totalBytesRead += length
        }

        val magic = ByteArray(12)
        if (!readFullyTracked(magic, 0, 12)) throw IllegalStateException("流提前结束 (EOF in readFully)")

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
            if (!readFullyTracked(chunkHeader, 0, 8)) throw IllegalStateException("流提前结束 (EOF in readFully)")
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L) throw IllegalStateException("fmt Chunk 长度不足 16 字节: $chunkSize")
                    val fmtData = ByteArray(16)
                    if (!readFullyTracked(fmtData, 0, 16)) throw IllegalStateException("流提前结束 (EOF in readFully)")
                    val fmtBuf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = fmtBuf.short.toInt() and 0xFFFF
                    channels = fmtBuf.short.toInt()
                    sampleRate = fmtBuf.int
                    fmtBuf.int // byteRate
                    fmtBuf.short // blockAlign
                    bitDepth = fmtBuf.short.toInt()
                    // 💡 强校验线性整数 PCM (0x0001) 或 WAVE_FORMAT_EXTENSIBLE (0xFFFE 且 SubFormat 为 PCM)，
                    // 拦截 IEEE Float (3) / ADPCM 等非 PCM WAV，防止直接灌入 USB DAC 产生白噪音
                    val extraFmt = chunkSize - 16L
                    if (audioFormat == 0xFFFE) {
                        if (extraFmt < 24L) {
                            throw IllegalArgumentException("无效的 WAVE_FORMAT_EXTENSIBLE fmt 扩展长度: $extraFmt")
                        }
                        val extData = ByteArray(24)
                        if (!readFullyTracked(extData, 0, 24)) throw IllegalStateException("流提前结束 (EOF in readFully)")
                        val subFormatTag = ByteBuffer.wrap(extData, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        if (subFormatTag != 1) {
                            Logger.upnp.e(TAG, "不支持的 WAVE_FORMAT_EXTENSIBLE SubFormat=0x${subFormatTag.toString(16)}，拦截直通")
                            throw IllegalArgumentException("不支持的非整数 PCM WAV 扩展格式: 0x${subFormatTag.toString(16)}")
                        }
                        val remainingExtra = extraFmt - 24L
                        if (remainingExtra > 0L) {
                            skipFullyTracked(remainingExtra)
                        }
                    } else if (audioFormat != 1) {
                        Logger.upnp.e(TAG, "不支持的 WAV audioFormat=$audioFormat（仅支持线性整数 PCM=1），拦截直通以防白噪音")
                        throw IllegalArgumentException("不支持的非 PCM WAV 格式: audioFormat=$audioFormat")
                    } else if (extraFmt > 0L) {
                        skipFullyTracked(extraFmt)
                    }
                    Logger.upnp.d(TAG, "解析 fmt Chunk 成功: format=$audioFormat, $sampleRate Hz, $bitDepth bit, $channels ch")
                    if (chunkSize % 2L != 0L) {
                        skipFullyTracked(1L)
                    }
                }

                "LIST" -> {
                    // 💡 深度流式解析 WAV LIST INFO 内嵌元数据
                    Logger.upnp.d(TAG, "检测到 LIST 扩展 Chunk: $chunkSize 字节，准备提取内嵌文本标签...")
                    if (chunkSize < 4L) {
                        skipFullyTracked(chunkSize)
                        if (chunkSize % 2L != 0L) {
                            skipFullyTracked(1L)
                        }
                    } else {
                        val listTypeBytes = ByteArray(4)
                        if (readFullyTracked(listTypeBytes, 0, 4)) {
                            val listType = String(listTypeBytes, 0, 4)
                            var bytesRemaining = chunkSize - 4L

                            if (listType == "INFO") {
                                Logger.upnp.i(TAG, "解析 WAV LIST INFO 元数据块...")
                                var parsedTitle: String? = null
                                var parsedArtist: String? = null
                                var parsedAlbum: String? = null

                                while (bytesRemaining >= 8L) {
                                    val subHeader = ByteArray(8)
                                    if (!readFullyTracked(subHeader, 0, 8)) break
                                    val subId = String(subHeader, 0, 4)
                                    val subSize = ByteBuffer.wrap(subHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                                    bytesRemaining -= 8L

                                    if (subSize < 0L || subSize > bytesRemaining || subSize > 65536L) {
                                        Logger.upnp.w(TAG, "异常 subChunk 尺寸或超过安全上限 64KB: subId=$subId, subSize=$subSize, 剩余=$bytesRemaining")
                                        val toSkip = if (subSize in 0L..bytesRemaining) subSize else bytesRemaining
                                        skipFullyTracked(toSkip)
                                        bytesRemaining -= toSkip
                                        // 💡 修复 Finding 12: RIFF/WAV 规范严格要求 subChunk 按 2 字节对齐。
                                        // 当跳过的 subChunk 长度为奇数时，必须补跳 1 字节 padding，否则后续 subHeader 指针错位 1 字节导致元数据解析全部损坏
                                        if (toSkip % 2L != 0L && bytesRemaining > 0L) {
                                            skipFullyTracked(1L)
                                            bytesRemaining -= 1L
                                        }
                                        continue
                                    }

                                    val subLen = subSize.toInt()
                                    val subData = ByteArray(subLen)
                                    if (!readFullyTracked(subData, 0, subLen)) {
                                        bytesRemaining = 0L
                                        break
                                    }
                                    bytesRemaining -= subSize

                                    // RIFF 规范：若 subSize 为奇数，后附 1 字节对齐填充
                                    if (subSize % 2L != 0L && bytesRemaining > 0L) {
                                        skipFullyTracked(1L)
                                        bytesRemaining -= 1L
                                    }

                                    val text = String(subData, Charsets.UTF_8).trimEnd { it == '\u0000' || it.isWhitespace() }
                                    when (subId) {
                                        "INAM" -> parsedTitle = text
                                        "IART" -> parsedArtist = text
                                        "IPRD" -> parsedAlbum = text
                                        else -> {
                                            Logger.upnp.d(TAG, "跳过 LIST INFO 子项: $subId, 内容=$text")
                                        }
                                    }
                                }

                                if (!parsedTitle.isNullOrEmpty() || !parsedArtist.isNullOrEmpty() || !parsedAlbum.isNullOrEmpty()) {
                                    onMetadataParsed?.invoke(parsedTitle, parsedArtist, parsedAlbum)
                                }
                                if (bytesRemaining > 0L) {
                                    skipFullyTracked(bytesRemaining)
                                }
                            } else {
                                skipFullyTracked(bytesRemaining)
                            }
                            if (chunkSize % 2L != 0L) {
                                skipFullyTracked(1L)
                            }
                        }
                    }
                }

                "data" -> {
                    dataFound = true
                    dataSize = chunkSize
                    Logger.upnp.d(TAG, "精准定位到 data Chunk 边界，准备直通 Raw PCM 载荷 (chunkSize=$chunkSize, headerLen=$totalBytesRead)")
                }

                else -> {
                    // 💡 跳过 JUNK, bext 等扩展 Chunk，若为奇数则跳过对齐填充
                    Logger.upnp.d(TAG, "跳过非 data 扩展 Chunk: $chunkId, 长度: $chunkSize 字节")
                    skipFullyTracked(chunkSize)
                    if (chunkSize % 2L != 0L) {
                        skipFullyTracked(1L)
                    }
                }
            }
        }

        return AudioStreamFormat(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            dataSize = dataSize,
            headerLength = totalBytesRead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            headerLengthBytes = totalBytesRead
        )
    }

    private fun skipFully(input: InputStream, length: Long) {
        var toSkip = length
        val skipBuf = ByteArray(minOf(toSkip, 4096L).toInt())
        while (toSkip > 0L) {
            val n = input.read(skipBuf, 0, minOf(toSkip, skipBuf.size.toLong()).toInt())
            if (n == -1) throw IllegalStateException("流提前结束 (EOF in skipFully: 仍需跳过 $toSkip 字节)")
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
            } catch (e: Exception) {
                Logger.upnp.w(TAG, "关闭分离输入流异常: ${e.message}")
            }
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                Logger.upnp.w(TAG, "断开分离 HTTP 连接异常: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "StreamDemuxer"
        private const val MAX_RANGE_RECONNECT_ATTEMPTS = 3
        private val RANGE_RECONNECT_BACKOFF_MS = longArrayOf(150L, 400L, 800L)
    }
}
