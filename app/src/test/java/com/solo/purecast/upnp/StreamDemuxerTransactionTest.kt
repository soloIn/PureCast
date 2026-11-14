package com.solo.purecast.upnp

import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.FastPathNativeBridge
import com.solo.purecast.core.session.HardwareFastPathController
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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
    fun soapPauseToPlayPreservesEpochAndBufferAndSeekOnLiveReturns701() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fakeNative = FakeNativeFastPath().apply { currentEpoch = 5L }
        val fastPath = HardwareFastPathController(fakeNative)
        val events = mutableListOf<SessionEventEnvelope<SessionEventPayload>>()
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
}
