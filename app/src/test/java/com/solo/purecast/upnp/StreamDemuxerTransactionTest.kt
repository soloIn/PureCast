package com.solo.purecast.upnp

import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.FastPathNativeBridge
import com.solo.purecast.core.session.HardwareFastPathController
import com.solo.purecast.core.session.MetadataPatchEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamSetupEvent
import com.solo.purecast.core.session.StreamStopEvent
import com.solo.purecast.core.session.TrackCompleteEvent
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.TrackBoundaryEvent
import com.solo.purecast.core.session.TrackSwitchEvent
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 💡 Unit U3 测试：验证 StreamDemuxer 事务式世代锁定与 UpnpRendererServer 无状态化行为
 */
class StreamDemuxerTransactionTest {

    private class FakeAudioGateway(
        private val writeResultOverride: Int? = null,
        private val throwOnWrite: Exception? = null
    ) : DemuxerAudioGateway {
        val writtenEpochs = CopyOnWriteArrayList<Long>()
        val writtenBytesTotal = AtomicInteger(0)
        val writeCallCount = AtomicInteger(0)
        var simulatedAvailableRead = 0
        var flacFrames = 0L
        var simulatedIsStreaming = true

        override fun isStreaming(): Boolean = simulatedIsStreaming

        override fun writePcm(data: ByteArray, offset: Int, length: Int, expectedEpoch: Long): Int {
            throwOnWrite?.let { throw it }
            writeCallCount.incrementAndGet()
            writtenEpochs += expectedEpoch
            if (writeResultOverride != null) {
                return writeResultOverride
            }
            writtenBytesTotal.addAndGet(length)
            return length
        }

        override fun getBufferAvailable(): Int = 65536
        override fun getAvailableRead(): Int = simulatedAvailableRead
        override fun getFlacDecodedFrames(): Long = flacFrames
        override fun resetFlacDecodedFrames() {
            flacFrames = 0L
        }

        override fun decodeFlacStream(
            inputStream: java.io.InputStream,
            callback: NativeAudioEngine.FlacFormatCallback
        ): Boolean {
            val epoch = callback.onFormatReady(96000, 24, 2, 960000L)
            if (epoch <= 0L) {
                return false
            }
            writtenEpochs += epoch
            flacFrames = 960000L
            callback.onFirstPcmWritten()
            return true
        }
    }

    private class FakeNativeFastPath : FastPathNativeBridge {
        var currentEpoch = 10L
        var isPaused = false
        val muteCalls = mutableListOf<FlushReason>()
        var resumeCalls = 0

        override fun emergencyMuteAndFlush(reason: FlushReason): Long {
            muteCalls += reason
            if (reason != FlushReason.EXPLICIT_PAUSE) {
                currentEpoch += 1L
            } else {
                isPaused = true
            }
            return currentEpoch
        }

        override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long {
            currentEpoch += 1L
            return currentEpoch
        }

        override fun getCurrentBufferEpoch(): Long = currentEpoch

        override fun resumeStream(): Int {
            isPaused = false
            resumeCalls++
            return 0
        }

        override fun isStreaming(): Boolean = !isPaused
    }

    private fun buildWavStreamBytes(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        pcmPayloadSize: Int,
        headerDataSizeOverride: Long? = null
    ): ByteArray {
        val reportedDataSize = (headerDataSizeOverride ?: pcmPayloadSize.toLong()).toInt()
        val totalSize = 44 + pcmPayloadSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt((totalSize - 8).coerceAtLeast(36))
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1.toShort()) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        val byteRate = sampleRate * channels * (bitDepth / 8)
        buf.putInt(byteRate)
        buf.putShort((channels * (bitDepth / 8)).toShort())
        buf.putShort(bitDepth.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(reportedDataSize)
        val pcm = ByteArray(pcmPayloadSize) { (it and 0x7F).toByte() }
        buf.put(pcm)
        return buf.array()
    }

    private fun serveOnceOverHttp(scope: CoroutineScope, payload: ByteArray, blockUntilClosed: Boolean = false): Pair<String, ServerSocket> {
        val server = ServerSocket(0)
        val port = server.localPort
        val t = Thread {
            try {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = client.getOutputStream()
                    val hdr = if (blockUntilClosed) {
                        "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                    }
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    out.write(payload)
                    out.flush()
                    if (blockUntilClosed) {
                        val deadline = System.currentTimeMillis() + 2000L
                        while (!server.isClosed && System.currentTimeMillis() < deadline) {
                            Thread.sleep(20)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
        return "http://127.0.0.1:$port/test.wav" to server
    }

    @Test
    fun wavHeaderAcquiresEpochSynchronouslyBeforeFirstWritePcmAndEmitsFirstPacket() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = FakeAudioGateway()
        val callTimeline = CopyOnWriteArrayList<String>()
        var capturedSpec: DemuxedStreamSpec? = null
        var endedFlag: Boolean? = null

        val wavBytes = buildWavStreamBytes(sampleRate = 96000, bitDepth = 24, channels = 2, pcmPayloadSize = 4096)
        val (url, serverSocket) = serveOnceOverHttp(scope, wavBytes)

        try {
            val demuxer = StreamDemuxer(
                scope = scope,
                audioGateway = gateway,
                onAcquireStreamEpoch = { spec ->
                    capturedSpec = spec
                    callTimeline += "ACQUIRE_EPOCH(sr=${spec.sampleRate},bit=${spec.bitDepth},writes=${gateway.writeCallCount.get()})"
                    42L
                },
                onFirstDataPacket = { epoch, _ ->
                    callTimeline += "FIRST_PACKET(ep=$epoch,bytes=${gateway.writtenBytesTotal.get()})"
                },
                onStreamEnded = { aborted, ep ->
                    endedFlag = aborted
                    callTimeline += "ENDED(aborted=$aborted,ep=$ep)"
                },
                onError = { err -> callTimeline += "ERROR:$err" }
            )

            demuxer.start(url)
            val deadline = System.currentTimeMillis() + 5000L
            while (endedFlag == null && System.currentTimeMillis() < deadline) {
                delay(20)
            }

            assertEquals(false, endedFlag)
            assertEquals(42L, gateway.writtenEpochs.first())
            assertTrue("Expected at least one timeline item", callTimeline.size >= 3)
            assertEquals("ACQUIRE_EPOCH(sr=96000,bit=24,writes=0)", callTimeline[0])
            assertTrue(callTimeline[1].startsWith("FIRST_PACKET(ep=42,bytes="))
            assertEquals(DurationState.FIXED, capturedSpec?.durationState)
            assertEquals(SeekCapability.SEEKABLE, capturedSpec?.seekCapability)
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun unboundedWav0x7FFFFFFFMarksLiveDurationAndUnseekable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = FakeAudioGateway()
        var capturedSpec: DemuxedStreamSpec? = null
        var ended = false

        val wavBytes = buildWavStreamBytes(
            sampleRate = 44100,
            bitDepth = 16,
            channels = 2,
            pcmPayloadSize = 1024,
            headerDataSizeOverride = 0x7FFFFFFFL
        )
        val (url, serverSocket) = serveOnceOverHttp(scope, wavBytes)

        try {
            val demuxer = StreamDemuxer(
                scope = scope,
                audioGateway = gateway,
                onAcquireStreamEpoch = { spec ->
                    capturedSpec = spec
                    7L
                },
                onFirstDataPacket = { _, _ -> },
                onStreamEnded = { _, _ -> ended = true },
                onError = {}
            )

            demuxer.start(url)
            val deadline = System.currentTimeMillis() + 5000L
            while (!ended && System.currentTimeMillis() < deadline) {
                delay(20)
            }

            assertTrue(ended)
            assertEquals(DurationState.LIVE, capturedSpec?.durationState)
            assertEquals(SeekCapability.UNSEEKABLE, capturedSpec?.seekCapability)
            assertEquals(0L, capturedSpec?.durationMs)
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun staleEpochMinusTwoAbortsWriteLoopWithoutSpinOrFalseComplete() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = FakeAudioGateway(writeResultOverride = -2)
        var abortedResult: Boolean? = null
        var firstPacketCount = 0

        val wavBytes = buildWavStreamBytes(44100, 16, 2, pcmPayloadSize = 8192)
        val (url, serverSocket) = serveOnceOverHttp(scope, wavBytes)

        try {
            val demuxer = StreamDemuxer(
                scope = scope,
                audioGateway = gateway,
                onAcquireStreamEpoch = { 15L },
                onFirstDataPacket = { _, _ -> firstPacketCount++ },
                onStreamEnded = { aborted, _ -> abortedResult = aborted },
                onError = {}
            )

            demuxer.start(url)
            val deadline = System.currentTimeMillis() + 5000L
            while (abortedResult == null && System.currentTimeMillis() < deadline) {
                delay(20)
            }

            assertEquals(true, abortedResult)
            assertEquals(0, firstPacketCount)
            assertEquals(1, gateway.writeCallCount.get())
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun flacCallbackRespectsEpochGateAndFirstPcmNotification() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = FakeAudioGateway()
        var firstPacketTriggered = false

        val flacCallbackReject = object : NativeAudioEngine.FlacFormatCallback {
            override fun onFormatReady(sampleRate: Int, bitDepth: Int, channels: Int, totalPcmFrames: Long): Long = -1L
            override fun onFirstPcmWritten() {
                firstPacketTriggered = true
            }
        }
        assertFalse(gateway.decodeFlacStream(ByteArrayInputStream(ByteArray(0)), flacCallbackReject))
        assertFalse(firstPacketTriggered)

        val flacCallbackAccept = object : NativeAudioEngine.FlacFormatCallback {
            override fun onFormatReady(sampleRate: Int, bitDepth: Int, channels: Int, totalPcmFrames: Long): Long = 88L
            override fun onFirstPcmWritten() {
                firstPacketTriggered = true
            }
        }
        assertTrue(gateway.decodeFlacStream(ByteArrayInputStream(ByteArray(0)), flacCallbackAccept))
        assertTrue(firstPacketTriggered)
        assertEquals(listOf(88L), gateway.writtenEpochs.toList())
        scope.cancel()
    }

    @Test
    fun flacStreamNaturalCompletionTriggersNonAbortedComplete() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var endedFlag: Boolean? = null
        val fakeGateway = FakeAudioGateway().apply {
            flacFrames = 44100L * 5
        }
        val demuxer = StreamDemuxer(
            scope = scope,
            audioGateway = fakeGateway,
            onAcquireStreamEpoch = { 1L },
            onStreamEnded = { aborted, _ ->
                endedFlag = aborted
            }
        )

        val (streamUrl, serverSocket) = serveOnceOverHttp(
            scope,
            "fLaC".toByteArray(Charsets.US_ASCII) + ByteArray(1024),
            blockUntilClosed = true
        )
        try {
            demuxer.start(streamUrl)
            val deadline = System.currentTimeMillis() + 5000L
            while (endedFlag == null && System.currentTimeMillis() < deadline) {
                delay(20)
            }

            // 验证 FLAC 播完不会被误判为 aborted，正常触发非中止完成
            assertEquals("FLAC 曲目播完必须触发非中止完成 (hasStreamedData=true)", false, endedFlag)
        } finally {
            try { serverSocket.close() } catch (_: Exception) {}
            demuxer.stop()
            scope.cancel()
        }
    }

    @Test
    fun soapPauseToPlayPreservesEpochAndBufferAndSeekOnLiveReturns701() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fakeNative = FakeNativeFastPath().apply { currentEpoch = 5L }
        val fastPath = HardwareFastPathController(fakeNative)
        val events = java.util.concurrent.CopyOnWriteArrayList<SessionEventEnvelope<SessionEventPayload>>()
        val sidGen = AtomicLong(0L)
        val seqGen = AtomicLong(0L)
        val wavBytes = buildWavStreamBytes(44100, 16, 2, pcmPayloadSize = 4096)
        val (url, serverSocket) = serveOnceOverHttp(scope, wavBytes, blockUntilClosed = true)

        try {
            val server = UpnpRendererServer(
                scope = scope,
                dispatchEvent = { events += it },
                allocateSessionId = { sidGen.incrementAndGet() },
                allocateSequence = { seqGen.incrementAndGet() },
                onAcquireHardwareForStream = { _, _, _, _ ->
                    fakeNative.currentEpoch
                },
                snapshotProvider = {
                    PlayerSessionSnapshot(
                        sessionId = 1L,
                        activeProtocol = StreamProtocol.UPNP_DLNA,
                        businessState = BusinessSessionState.PLAYING
                    )
                },
                fastPath = fastPath,
                audioGateway = FakeAudioGateway()
            )

            // 1. 推送含 isLive 标签 URI 并触发 Seek 校验 701 Fault
            server.processSoapActionForTest(
                "SetAVTransportURI",
                "<CurrentURI>$url</CurrentURI><CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;Live Radio&lt;/dc:title&gt;&lt;res protocolInfo=&quot;http-get:*:audio/wav:*&quot;&gt;$url&lt;/res&gt;isLive&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"
            )
            val seekResp = server.processSoapActionForTest("Seek", "<Unit>REL_TIME</Unit><Target>00:01:00</Target>")
            assertTrue("LIVE 流应返回 SOAP 701 故障", seekResp.contains("<errorCode>701</errorCode>"))

            // 2. 启动 Play 后等待解复用运行起来并写出首帧 PCM，然后执行 SOAP Pause -> Play
            server.processSoapActionForTest("Play", "")
            val startWaitDeadline = System.currentTimeMillis() + 2000L
            while (events.none { it.payload is FirstDataPacketEvent } && System.currentTimeMillis() < startWaitDeadline) {
                delay(10)
            }
            assertTrue("Play 后应在限时内发送 FirstDataPacketEvent", events.any { it.payload is FirstDataPacketEvent })

            val epochBeforePause = fakeNative.currentEpoch
            val resumesBeforeSecondPlay = fakeNative.resumeCalls
            server.processSoapActionForTest("Pause", "")
            assertEquals("Pause 不应递增 epoch", epochBeforePause, fakeNative.currentEpoch)
            assertTrue(events.any { it.payload is ExplicitPauseEvent })
            assertEquals(FlushReason.EXPLICIT_PAUSE, fakeNative.muteCalls.last())

            server.processSoapActionForTest("Play", "")
            assertEquals("Pause -> Play 恢复绝不应递增 epoch", epochBeforePause, fakeNative.currentEpoch)
            assertTrue("Pause -> Play 应发出 StreamResumeEvent", events.any { it.payload is StreamResumeEvent })
            assertEquals(resumesBeforeSecondPlay + 1, fakeNative.resumeCalls)

            server.processSoapActionForTest("Stop", "")
            assertTrue(events.any { it.payload is StreamStopEvent })
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun protocolPackagesHaveZeroReferenceToPlaybackService() {
        val upnpDir = File("src/main/java/com/solo/purecast/upnp")
        val airplayDir = File("src/main/java/com/solo/purecast/airplay")
        val resolvedUpnp = if (upnpDir.exists()) upnpDir else File("app/src/main/java/com/solo/purecast/upnp")
        val resolvedAirplay = if (airplayDir.exists()) airplayDir else File("app/src/main/java/com/solo/purecast/airplay")

        val files = (resolvedUpnp.listFiles()?.toList().orEmpty()) + (resolvedAirplay.listFiles()?.toList().orEmpty())
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val content = file.readText()
            assertFalse("${file.name} 严禁引用 PlaybackService", content.contains("PlaybackService"))
        }
    }

    @Test
    fun `SetAVTransportURI with identical URI replays track cleanly with flush and TrackBoundaryEvent`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fakeNative = FakeNativeFastPath()
        val fastPath = HardwareFastPathController(fakeNative)
        val events = CopyOnWriteArrayList<SessionEventEnvelope<SessionEventPayload>>()
        val sidGen = AtomicLong(0L)
        val seqGen = AtomicLong(0L)

        val wavBytes = buildWavStreamBytes(44100, 16, 2, 4096)
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverThread = Thread {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (true) {
                            val l = reader.readLine() ?: break
                            if (l.isEmpty()) break
                        }
                        val out = client.getOutputStream()
                        out.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII)
                        )
                        out.write(wavBytes)
                        out.flush()
                    }
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val server = UpnpRendererServer(
                scope = scope,
                dispatchEvent = { events += it },
                allocateSessionId = { sidGen.incrementAndGet() },
                allocateSequence = { seqGen.incrementAndGet() },
                onAcquireHardwareForStream = { _, _, _, _ -> fakeNative.currentEpoch },
                snapshotProvider = {
                    PlayerSessionSnapshot(
                        sessionId = 1L,
                        activeProtocol = StreamProtocol.UPNP_DLNA,
                        businessState = BusinessSessionState.PLAYING
                    )
                },
                fastPath = fastPath,
                audioGateway = FakeAudioGateway()
            )

            val uri = "http://127.0.0.1:$port/replay.wav"
            val soapUriBody = "<InstanceID>0</InstanceID><CurrentURI>$uri</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
            server.processSoapActionForTest("SetAVTransportURI", soapUriBody)
            server.processSoapActionForTest("Play", "")

            val deadline1 = System.currentTimeMillis() + 5000L
            while (events.none { it.payload is FirstDataPacketEvent } && System.currentTimeMillis() < deadline1) {
                delay(20)
            }
            assertEquals(1, events.count { it.payload is FirstDataPacketEvent })
            val beforeReplayCount = events.size

            // 发送完全相同 URI 的 SetAVTransportURI 请求重播 (#26)
            server.processSoapActionForTest("SetAVTransportURI", soapUriBody)
            assertTrue("同 URI 重播必须执行 FLUSH_SWITCH", fakeNative.muteCalls.contains(FlushReason.FLUSH_SWITCH))
            assertTrue(
                "同 URI 重播必须发射 TrackSwitchEvent",
                events.drop(beforeReplayCount).any { it.payload is TrackSwitchEvent }
            )
            assertTrue(
                "同 URI 重播必须发射 TrackBoundaryEvent 重置曲目进度",
                events.drop(beforeReplayCount).any { it.payload is TrackBoundaryEvent }
            )

            server.processSoapActionForTest("Play", "")
            val deadline2 = System.currentTimeMillis() + 5000L
            while (events.count { it.payload is FirstDataPacketEvent } < 2 && System.currentTimeMillis() < deadline2) {
                delay(20)
            }
            assertEquals("同 URI 重播应再次触发 FirstDataPacketEvent", 2, events.count { it.payload is FirstDataPacketEvent })
            server.processSoapActionForTest("Stop", "")
            Unit
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun `StreamDemuxer marks abortedByEpoch true when stream throws unexpected exception`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = FakeAudioGateway(throwOnWrite = IllegalStateException("usb write aborted"))
        var endedAborted: Boolean? = null
        var recordedError: String? = null

        val wavBytes = buildWavStreamBytes(44100, 16, 2, 4096)
        val (url, serverSocket) = serveOnceOverHttp(scope, wavBytes)

        try {
            val demuxer = StreamDemuxer(
                scope = scope,
                audioGateway = gateway,
                onAcquireStreamEpoch = { 77L },
                onFirstDataPacket = { _, _ -> },
                onStreamEnded = { aborted, _ -> endedAborted = aborted },
                onError = { err -> recordedError = err }
            )
            demuxer.start(url)
            val deadline = System.currentTimeMillis() + 5000L
            while (endedAborted == null && System.currentTimeMillis() < deadline) {
                delay(20)
            }
            assertEquals("流读取/写入抛出异常必须将 abortedByEpoch 标为 true (#17)", true, endedAborted)
            assertTrue(recordedError != null)
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun `StreamDemuxer pauses buffer drain without completing stream when audio is paused`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = FakeAudioGateway()
        gateway.simulatedAvailableRead = 4096
        gateway.simulatedIsStreaming = false // 💡 模拟暂停/静音态，底层填零保活不消费有效 PCM

        var endedAborted: Boolean? = null
        val wavBytes = buildWavStreamBytes(44100, 16, 2, 4096)
        val (url, serverSocket) = serveOnceOverHttp(scope, wavBytes)

        try {
            val demuxer = StreamDemuxer(
                scope = scope,
                audioGateway = gateway,
                onAcquireStreamEpoch = { 88L },
                onFirstDataPacket = { _, _ -> },
                onStreamEnded = { aborted, _ -> endedAborted = aborted },
                onError = { }
            )
            demuxer.start(url)
            // 等待数据拉取完毕进入排空循环并挂起等待
            delay(300)
            // 💡 验证：在暂停态下，排空循环绝不能因为 waitCount 超时而误判 EOF
            assertEquals("暂停态排空循环中绝不能提前派发 onStreamEnded", null, endedAborted)

            // 模拟恢复播放，且环形缓冲区排空
            gateway.simulatedIsStreaming = true
            gateway.simulatedAvailableRead = 0

            val deadline = System.currentTimeMillis() + 3000L
            while (endedAborted == null && System.currentTimeMillis() < deadline) {
                delay(20)
            }
            assertEquals("恢复流式消费且环形缓冲排空后正常自然结束", false, endedAborted)
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun `seekTo restarts progress ticker and keeps reporting position updates`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = FakeAudioGateway()
        // 构造较长的 WAV 流 (44.1kHz 16bit 2ch，每个采样 4 字节，每秒 176400 字节，生成 60 秒的 WAV)
        val sampleRate = 44100
        val bitDepth = 16
        val channels = 2
        val bytesPerSec = sampleRate * channels * (bitDepth / 8)
        val totalSec = 60
        val pcmSize = bytesPerSec * totalSec
        val wavBytes = buildWavStreamBytes(sampleRate, bitDepth, channels, pcmSize)

        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverThread = Thread {
            while (!serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    Thread {
                        client.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            var rangeHeader: String? = null
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Range:", ignoreCase = true)) {
                                    rangeHeader = line.substringAfter(":").trim()
                                }
                            }
                            val out = s.getOutputStream()
                            val (header, startInt) = if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                                val startByte = rangeHeader.substringAfter("=").substringBefore("-").trim().toLongOrNull() ?: 0L
                                val start = startByte.toInt().coerceIn(0, wavBytes.size)
                                val length = wavBytes.size - start
                                val respHeader = "HTTP/1.1 206 Partial Content\r\n" +
                                    "Content-Type: audio/wav\r\n" +
                                    "Content-Range: bytes $startByte-${wavBytes.size - 1}/${wavBytes.size}\r\n" +
                                    "Content-Length: $length\r\n" +
                                    "Connection: close\r\n\r\n"
                                respHeader to start
                            } else {
                                val respHeader = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: audio/wav\r\n" +
                                    "Content-Length: ${wavBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                respHeader to 0
                            }
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            out.flush()
                            val chunkSize = 32768
                            var sent = startInt
                            while (sent < wavBytes.size && !s.isClosed) {
                                val toWrite = minOf(chunkSize, wavBytes.size - sent)
                                out.write(wavBytes, sent, toWrite)
                                out.flush()
                                sent += toWrite
                                Thread.sleep(150)
                            }
                        }
                    }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val url = "http://127.0.0.1:$port/long_stream.wav"
            val positionUpdates = CopyOnWriteArrayList<Long>()
            var firstPacketReceived = false
            var seekFlushCalled = false

            val demuxer = StreamDemuxer(
                scope = scope,
                audioGateway = gateway,
                onAcquireStreamEpoch = { 10L },
                onFirstDataPacket = { _, _ -> firstPacketReceived = true },
                onStreamEnded = { _, _ -> },
                onError = { },
                onPositionUpdated = { pos -> positionUpdates += pos },
                onSeekFlush = {
                    seekFlushCalled = true
                    11L
                }
            )

            demuxer.start(url)
            val deadline1 = System.currentTimeMillis() + 3000L
            while (!firstPacketReceived && System.currentTimeMillis() < deadline1) {
                delay(20)
            }
            assertTrue("Demuxer 正常就绪并完成首帧写入", firstPacketReceived)

            val seekResult = demuxer.seekTo(25000L)
            assertTrue("seekTo 调用成功", seekResult)

            // 💡 验证：seekTo 入口立即将 seekBaseBytes 更新至目标范围，当前进度立即 >= 24000ms
            val posImmediately = demuxer.getCurrentPlayPositionMs()
            assertTrue("寻道后即时进度必须反映目标位置 (pos=$posImmediately)", posImmediately >= 24000L)

            // 等待网络建立 206 传输并触发 onSeekFlush
            val deadline2 = System.currentTimeMillis() + 3000L
            while (!seekFlushCalled && System.currentTimeMillis() < deadline2) {
                delay(20)
            }
            assertTrue("onSeekFlush 回调被成功触发", seekFlushCalled)

            // 💡 核心验证：seekTo 之后，progressTicker 循环必须继续运行并推送后续进度更新 (解决进度永久冻结 Bug)
            val countBeforeWait = positionUpdates.size
            val deadline3 = System.currentTimeMillis() + 3000L
            while (positionUpdates.size <= countBeforeWait && System.currentTimeMillis() < deadline3) {
                delay(20)
            }
            val countAfterWait = positionUpdates.size
            assertTrue(
                "seekTo 后 progressJob 必须持续运行推送进度 (before=$countBeforeWait, after=$countAfterWait)",
                countAfterWait > countBeforeWait
            )
            assertTrue(
                "后续进度更新必须基于 seek 位置推进 (lastPos=${positionUpdates.last()})",
                positionUpdates.last() >= 25000L
            )
        } finally {
            serverSocket.close()
            scope.cancel()
        }
    }

    @Test
    fun `soapGetPositionInfoEscapesAmpersandInUrlAndDidlLiteRecursivelyUnescapesCover`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = java.util.concurrent.CopyOnWriteArrayList<SessionEventEnvelope<SessionEventPayload>>()
        val sidGen = AtomicLong(0L)
        val seqGen = AtomicLong(0L)

        val server = UpnpRendererServer(
            scope = scope,
            dispatchEvent = { events += it },
            allocateSessionId = { sidGen.incrementAndGet() },
            allocateSequence = { seqGen.incrementAndGet() },
            onAcquireHardwareForStream = { _, _, _, _ -> 1L },
            snapshotProvider = {
                PlayerSessionSnapshot(
                    sessionId = 1L,
                    activeProtocol = StreamProtocol.UPNP_DLNA,
                    businessState = BusinessSessionState.PLAYING
                )
            },
            fastPath = HardwareFastPathController(FakeNativeFastPath()),
            audioGateway = FakeAudioGateway()
        )

        // 1. 设置带有多个 & 参数的 URL 与带双重转义 &amp;amp; 封面的 DIDL-Lite
        val testUrl = "http://192.168.1.100:8080/music/track?id=123&format=flac&token=xyz"
        val didlMetadata = "&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;" +
                "&lt;item&gt;&lt;dc:title&gt;Test Track&lt;/dc:title&gt;" +
                "&lt;upnp:albumArtURI&gt;http://example.com/cover?id=100&amp;amp;size=large&lt;/upnp:albumArtURI&gt;" +
                "&lt;/item&gt;&lt;/DIDL-Lite&gt;"

        server.processSoapActionForTest(
            "SetAVTransportURI",
            "<CurrentURI>$testUrl</CurrentURI><CurrentURIMetaData>$didlMetadata</CurrentURIMetaData>"
        )

        // 2. 验证 GetPositionInfoResponse 包含转义后的 &amp;，杜绝非法 XML
        val posInfoResponse = server.processSoapActionForTest("GetPositionInfo", "")
        assertTrue("SOAP 响应必须对 URL 中包含的 & 进行转义", posInfoResponse.contains("id=123&amp;format=flac&amp;token=xyz"))
        assertFalse("SOAP 响应绝不能出现未转义的 bare '&'", posInfoResponse.contains("id=123&format"))

        // 3. 验证解析得到的元数据中的 coverUri 已被完全解转义
        val meta = server.getCurrentTrackMetadataForTest()
        assertEquals("http://example.com/cover?id=100&size=large", meta.coverUri)

        scope.cancel()
    }

    @Test
    fun `wavListInfoChunkWithOversizedSubLengthIsSafelySkipped`() {
        // 构造一个带有超大 subSize (> 64KB) 的 LIST INFO 块，验证解析不抛异常且安全跳过
        val bos = ByteArrayOutputStream()
        val dataOut = DataOutputStream(bos)

        // RIFF Header
        bos.write("RIFF".toByteArray())
        val riffSizePos = bos.size()
        dataOut.writeInt(Integer.reverseBytes(0)) // 占位
        bos.write("WAVE".toByteArray())

        // fmt Chunk (16 bytes)
        bos.write("fmt ".toByteArray())
        dataOut.writeInt(Integer.reverseBytes(16))
        dataOut.writeShort(java.lang.Short.reverseBytes(1).toInt()) // PCM
        dataOut.writeShort(java.lang.Short.reverseBytes(2).toInt()) // 2 ch
        dataOut.writeInt(Integer.reverseBytes(44100))
        dataOut.writeInt(Integer.reverseBytes(44100 * 2 * 2))
        dataOut.writeShort(java.lang.Short.reverseBytes(4).toInt())
        dataOut.writeShort(java.lang.Short.reverseBytes(16).toInt())

        // LIST Chunk (包含异常 70000 字节声明的 subChunk，实际 bytesRemaining=20 字节)
        val listPayloadRemaining = 20
        val listSize = 4 + 8 + listPayloadRemaining // INFO (4) + XXXX sub header (8) + 20 字节负载
        bos.write("LIST".toByteArray())
        dataOut.writeInt(Integer.reverseBytes(listSize))
        bos.write("INFO".toByteArray())

        // 超大 subChunk: ID = "XXXX", subSize = 70000L (> 65536)
        bos.write("XXXX".toByteArray())
        dataOut.writeInt(Integer.reverseBytes(70000))
        // 写入 20 字节实际 payload 供安全跳过
        bos.write(ByteArray(listPayloadRemaining))

        // data Chunk (0 字节)
        bos.write("data".toByteArray())
        dataOut.writeInt(Integer.reverseBytes(0))

        val wavBytes = bos.toByteArray()
        val demuxer = StreamDemuxer(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            audioGateway = FakeAudioGateway(),
            onFirstDataPacket = { _, _ -> },
            onAcquireStreamEpoch = { 1L },
            onStreamEnded = { _, _ -> },
            onError = { }
        )

        val inStream = ByteArrayInputStream(wavBytes)
        val format = demuxer.parseHeader(inStream)
        assertEquals(44100, format.sampleRate)
        assertEquals(16, format.bitDepth)
        assertEquals(2, format.channels)
    }
}
