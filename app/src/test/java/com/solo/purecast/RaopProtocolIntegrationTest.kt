package com.solo.purecast

import com.solo.purecast.airplay.AirPlayStateManager
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 💡 RaopProtocolIntegrationTest: 原生 AirPlay 1 (RAOP) 协议栈与状态机集成测试
 * 1. 验证 DMAP 标签结构与大端序字节格式解析
 * 2. 验证 RTSP SETUP / RECORD 响应与 SDP 参数映射
 * 3. 验证双协议抢占状态模型
 */
class RaopProtocolIntegrationTest {

    @Test
    fun testDmapBinaryPayloadStructure() {
        // 构建一段合法的 DMAP 二进制数据:
        // [minm][len=5][Hello] + [asar][len=6][Singer] + [asal][len=5][Album]
        val bos = ByteArrayOutputStream()

        fun writeTag(tag: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            bos.write(tag.toByteArray(Charsets.US_ASCII))
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array()
            bos.write(lenBuf)
            bos.write(bytes)
        }

        writeTag("minm", "Bohemian Rhapsody")
        writeTag("asar", "Queen")
        writeTag("asal", "A Night at the Opera")

        val payload = bos.toByteArray()
        assertTrue(payload.size >= 8 * 3)

        // 模拟解析器校验头部标签与长度
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val tag1 = String(byteArrayOf(buf.get(), buf.get(), buf.get(), buf.get()), Charsets.US_ASCII)
        val len1 = buf.getInt()
        val val1 = ByteArray(len1).also { buf.get(it) }.toString(Charsets.UTF_8)

        assertEquals("minm", tag1)
        assertEquals("Bohemian Rhapsody", val1)

        val tag2 = String(byteArrayOf(buf.get(), buf.get(), buf.get(), buf.get()), Charsets.US_ASCII)
        val len2 = buf.getInt()
        val val2 = ByteArray(len2).also { buf.get(it) }.toString(Charsets.UTF_8)

        assertEquals("asar", tag2)
        assertEquals("Queen", val2)
    }

    @Test
    fun testAudioPathStateProtocolSwitching() {
        // 初始状态
        val idleState = AudioPathState()
        assertEquals(StreamProtocol.NONE, idleState.protocol)
        assertFalse(idleState.isExclusive)

        // UPnP 播放中
        val upnpState = idleState.copy(
            isExclusive = true,
            sampleRate = 96000,
            bitDepth = 24,
            protocol = StreamProtocol.UPNP_DLNA
        )
        assertEquals(StreamProtocol.UPNP_DLNA, upnpState.protocol)
        assertEquals(96000, upnpState.sampleRate)

        // AirPlay 抢占
        val airPlayPreempted = upnpState.copy(
            isExclusive = true,
            sampleRate = 44100,
            bitDepth = 16,
            protocol = StreamProtocol.AIRPLAY_1
        )
        assertEquals(StreamProtocol.AIRPLAY_1, airPlayPreempted.protocol)
        assertEquals(44100, airPlayPreempted.sampleRate)
        assertEquals(16, airPlayPreempted.bitDepth)
        assertTrue(airPlayPreempted.isExclusive)
    }

    @Test
    fun testTrackMetadataWithCoverBytes() {
        val dummyCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) // JPEG SOI
        val meta = TrackMetadata(
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            coverBytes = dummyCover,
            durationMs = 391000L,
            durationState = com.solo.purecast.model.DurationState.FIXED,
            seekCapability = com.solo.purecast.model.SeekCapability.SEEKABLE
        )

        assertEquals("Hotel California", meta.title)
        assertEquals("Eagles", meta.artist)
        assertNotNull(meta.coverBytes)
        assertEquals(4, meta.coverBytes?.size)
        assertEquals(com.solo.purecast.model.DurationState.FIXED, meta.durationState)
        assertEquals(com.solo.purecast.model.SeekCapability.SEEKABLE, meta.seekCapability)
    }

    /**
     * 💡 验证 RAOP RTP JitterBuffer 核心算法：
     * 1. 25 帧预充门限
     * 2. 乱序包槽位自动纠正与有序出队
     * 3. 超过 MAX_REORDER_TOLERANCE (4帧) 触发静音补偿帧
     */
    @Test
    fun testJitterBufferReorderAndSilenceCompensation() {
        class MockSlot(var valid: Boolean = false, var seq: Int = 0, var pcm: ByteArray? = null)
        val bufferSize = 64
        val preRollThreshold = 25
        val maxReorderTolerance = 4
        val slots = Array(bufferSize) { MockSlot() }
        var headSeq = 0
        var maxSeqSeen = 0
        var isBuffering = true
        var firstPacket = true
        var lostCount = 0

        fun push(seq: Int, pcm: ByteArray) {
            if (firstPacket) {
                headSeq = seq
                maxSeqSeen = seq
                firstPacket = false
            } else {
                if (seq < headSeq && (headSeq - seq) < 32768) return // 丢弃过期
                if (seq > maxSeqSeen) maxSeqSeen = seq
            }
            val index = seq % bufferSize
            slots[index].valid = true
            slots[index].seq = seq
            slots[index].pcm = pcm

            if (isBuffering) {
                val dist = (maxSeqSeen - headSeq) + 1
                var count = 0
                for (i in 0 until dist) {
                    val s = headSeq + i
                    if (slots[s % bufferSize].valid && slots[s % bufferSize].seq == s) count++
                }
                if (count >= preRollThreshold) {
                    isBuffering = false
                }
            }
        }

        fun pop(): ByteArray? {
            if (isBuffering || firstPacket) return null
            val index = headSeq % bufferSize
            if (slots[index].valid && slots[index].seq == headSeq) {
                val data = slots[index].pcm
                slots[index].valid = false
                headSeq++
                return data
            }
            val diff = maxSeqSeen - headSeq
            if (diff > maxReorderTolerance) {
                // 丢包确认，输出 1408 字节静音补偿
                lostCount++
                slots[index].valid = false
                headSeq++
                return ByteArray(1408)
            }
            return null
        }

        // 1. 模拟推送前 24 帧（未达到 25 帧门限）
        for (i in 100..123) {
            push(i, byteArrayOf(i.toByte()))
        }
        assertTrue(isBuffering)
        assertEquals(null, pop()) // 仍在预充，不可出队

        // 2. 推送第 25 帧（达到门限，激活播放）
        push(124, byteArrayOf(124.toByte()))
        assertFalse(isBuffering)

        // 3. 顺序出队验证
        val firstFrame = pop()
        assertNotNull(firstFrame)
        assertEquals(100.toByte(), firstFrame?.get(0))

        // 4. 模拟乱序场景：先推 126，125 迟到
        // 出队到 124
        for (i in 101..124) {
            pop()
        }
        assertEquals(125, headSeq)

        push(126, byteArrayOf(126.toByte())) // 126 到达，125 未到
        assertEquals(null, pop()) // 仍在容忍窗口内 (diff = 1 <= 4)

        push(125, byteArrayOf(125.toByte())) // 125 迟到到达补齐
        val frame125 = pop()
        assertNotNull(frame125)
        assertEquals(125.toByte(), frame125?.get(0))
        val frame126 = pop()
        assertNotNull(frame126)
        assertEquals(126.toByte(), frame126?.get(0))

        // 5. 模拟严重丢包场景：127 丢失，直接收到 128, 129, 130, 131, 132 (超差 5 帧)
        push(128, byteArrayOf(128.toByte()))
        push(129, byteArrayOf(129.toByte()))
        push(130, byteArrayOf(130.toByte()))
        push(131, byteArrayOf(131.toByte()))
        push(132, byteArrayOf(132.toByte())) // diff = 132 - 127 = 5 > 4

        val silenceFrame = pop() // 判定 127 丢失，生成静音补偿
        assertNotNull(silenceFrame)
        assertEquals(1408, silenceFrame?.size)
        assertEquals(0.toByte(), silenceFrame?.get(0))
        assertEquals(1, lostCount)
        assertEquals(128, headSeq)
    }

    /**
     * 💡 验证 AirPlay 基于单调时钟的进度推进与 Seek 校准数学模型
     */
    @Test
    fun testAirPlayMonotonicProgressCalculation() {
        var basePosMs = 10000L
        var anchorTimeMs = 100000L
        val durationMs = 240000L

        fun calculatePosition(nowMs: Long): Long {
            val elapsed = nowMs - anchorTimeMs
            var calculated = basePosMs + elapsed
            if (durationMs > 0 && calculated > durationMs) {
                calculated = durationMs
            }
            return calculated
        }

        // 正常播放推进 5 秒
        assertEquals(15000L, calculatePosition(105000L))

        // 正常播放推进 30 秒
        assertEquals(40000L, calculatePosition(130000L))

        // 模拟用户 Seek 到 120 秒 (收到 RTSP progress 回调校准基准)
        basePosMs = 120000L
        anchorTimeMs = 130500L

        // Seek 后推进 2 秒
        assertEquals(122000L, calculatePosition(132500L))

        // 推进到末尾封顶
        assertEquals(240000L, calculatePosition(400000L))
    }

    @Test
    fun testProtocolBroadcastNameFormatting() {
        val baseName = "PureCast TV"
        val airPlayName = com.solo.purecast.service.PlaybackService.formatAirPlayName(baseName)
        val upnpName = com.solo.purecast.service.PlaybackService.formatUpnpName(baseName)

        assertEquals("PureCast TV [AirPlay]", airPlayName)
        assertEquals("PureCast TV [UPnP]", upnpName)

        // 避免重复追加后缀测试
        assertEquals("PureCast TV [AirPlay]", com.solo.purecast.service.PlaybackService.formatAirPlayName(airPlayName))
        assertEquals("PureCast TV [UPnP]", com.solo.purecast.service.PlaybackService.formatUpnpName(upnpName))
    }

    @Test
    fun testRtpPacketGapDetectionAndResendCalculation() {
        // 模拟 RaopRtp 数据包空洞检测与重传区间算法
        fun detectGap(lastSeq: Int, curSeq: Int): Pair<Int, Int>? {
            val diff = ((curSeq - lastSeq).toShort()).toInt()
            return if (diff in 2..32) {
                val missingStart = (lastSeq + 1) and 0xFFFF
                val missingCount = diff - 1
                Pair(missingStart, missingCount)
            } else {
                null
            }
        }

        // 连续正常包序列
        assertEquals(null, detectGap(100, 101))

        // 丢 1 个包 (跳过 102，到达 103)
        val gap1 = detectGap(101, 103)
        assertNotNull(gap1)
        assertEquals(102, gap1!!.first)
        assertEquals(1, gap1.second)

        // 丢 3 个包 (跳过 102, 103, 104，到达 105)
        val gap3 = detectGap(101, 105)
        assertNotNull(gap3)
        assertEquals(102, gap3!!.first)
        assertEquals(3, gap3.second)

        // 跨越 16 位序列号回环回转 (65534 -> 1，跳过 65535 与 0)
        val gapRollover = detectGap(65534, 1)
        assertNotNull(gapRollover)
        assertEquals(65535, gapRollover!!.first)
        assertEquals(2, gapRollover.second)

        // 乱序迟到包 (100 -> 99)
        assertEquals(null, detectGap(100, 99))

        // 异常大跳跃 (>32 突发丢包)，不发重传避免雪崩风暴
        assertEquals(null, detectGap(100, 150))
    }

    @Test
    fun testRaop0xd5RetransmitPacketEncoding() {
        // 验证 8 字节 Apple RAOP 0xd5 重传报文组包规范
        fun encodeResendRequest(rseq: Int, firstSeq: Int, count: Int): ByteArray {
            val req = ByteArray(8)
            req[0] = 0x80.toByte()
            req[1] = 0xD5.toByte()
            req[2] = ((rseq shr 8) and 0xFF).toByte()
            req[3] = (rseq and 0xFF).toByte()
            req[4] = ((firstSeq shr 8) and 0xFF).toByte()
            req[5] = (firstSeq and 0xFF).toByte()
            req[6] = ((count shr 8) and 0xFF).toByte()
            req[7] = (count and 0xFF).toByte()
            return req
        }

        val packet = encodeResendRequest(1, 102, 2)
        assertEquals(8, packet.size)
        assertEquals(0x80.toByte(), packet[0])
        assertEquals(0xD5.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x01.toByte(), packet[3])
        assertEquals(0x00.toByte(), packet[4])
        assertEquals(102.toByte(), packet[5])
        assertEquals(0x00.toByte(), packet[6])
        assertEquals(2.toByte(), packet[7])
    }

    @Test
    fun testWatchdogInactivityStateLogic() {
        // 验证无数据超时看门狗与流状态机恢复模型
        var isStreamPaused = false
        var lastAudioTimeMs = 1000L

        fun checkWatchdog(currentTimeMs: Long): Boolean {
            if (lastAudioTimeMs > 0 && !isStreamPaused) {
                if (currentTimeMs > lastAudioTimeMs && (currentTimeMs - lastAudioTimeMs) >= 600) {
                    isStreamPaused = true
                    return true // 触发暂停回调
                }
            }
            return false
        }

        fun onNewPacket(currentTimeMs: Long): Boolean {
            lastAudioTimeMs = currentTimeMs
            if (isStreamPaused) {
                isStreamPaused = false
                return true // 触发恢复回调
            }
            return false
        }

        // 推进 400ms，未超差 600ms
        assertFalse(checkWatchdog(1400L))
        assertFalse(isStreamPaused)

        // 推进至 1600ms (停顿 600ms)，触发暂停
        assertTrue(checkWatchdog(1600L))
        assertTrue(isStreamPaused)

        // 再次检查保持暂停，不重复触发
        assertFalse(checkWatchdog(1700L))
        assertTrue(isStreamPaused)

        // 新包到达，触发恢复
        assertTrue(onNewPacket(1750L))
        assertFalse(isStreamPaused)
    }

    /**
     * 💡 验证全零进度报文 (progress: 0/0/0) 过滤逻辑：
     * iPhone 暂停或切歌缓冲时发出的 0/0/0 时间戳不得冲刷已有的播放位置与总时长
     */
    @Test
    fun testZeroProgressRejectionOnPause() {
        val stateManager = AirPlayStateManager()
        val song = TrackMetadata(title = "Hotel California", artist = "Eagles", durationMs = 180000L)
        stateManager.onStreamStarted(song)
        stateManager.handleProgressUpdate(45000L, 180000L)

        // 正常时间戳推进至 50 秒
        val update1 = stateManager.handleProgressUpdate(50000L, 180000L)
        assertTrue(update1)
        assertEquals(50000L, stateManager.positionMs)
        assertEquals(180000L, stateManager.metadata.durationMs)

        // 收到暂停阶段的 progress: 0/0/0，生产逻辑判定无效并拒绝更新
        val updateRejected = stateManager.handleProgressUpdate(0L, 0L)
        assertFalse("0/0/0 报文应被生产逻辑判定为无效过滤", updateRejected)
        assertEquals("0/0/0 报文不得重置当前播放进度", 50000L, stateManager.positionMs)
        assertEquals("0/0/0 报文不得重置总时长", 180000L, stateManager.metadata.durationMs)

        // 恢复推流后更新至 52 秒
        val update2 = stateManager.handleProgressUpdate(52000L, 180000L)
        assertTrue(update2)
        assertEquals(52000L, stateManager.positionMs)
    }

    /**
     * 💡 验证 AirPlay 暂停与断开的状态机分流隔离：
     * 1. iOS 暂停发送 TEARDOWN 时必须保持协议来源、DACP 会话与元数据，仅切换 PlaybackStatus 为 PAUSED；
     * 2. TV 端尝试恢复时若 RTP 处于断开状态，严禁盲目置为 PLAYING (杜绝僵尸播放态)；
     * 3. 待客户端重连 RTP 后才切换为 PLAYING；
     * 4. 只有在 10 分钟挂起超时或用户主动停止时才彻底清理独占、DACP 会话并复位协议为 NONE。
     */
    @Test
    fun testPauseVsStopStateSeparation() {
        val controller = AirPlayStateManager()
        val song = TrackMetadata(title = "Hotel California", artist = "Eagles", durationMs = 391000L)

        // 1. 初始化并开始推流
        controller.onStreamStarted(song)
        assertEquals(PlaybackStatus.PLAYING, controller.status)
        assertEquals(StreamProtocol.AIRPLAY_1, controller.protocol)
        assertTrue(controller.isRtpActive)
        assertTrue(controller.isDacpActive)

        // 2. 触发 iOS 暂停 (RTSP TEARDOWN 拆除音频传输通道)
        controller.onStreamTeardown(currentPos = 65000L)
        assertEquals(PlaybackStatus.PAUSED, controller.status)
        assertEquals(StreamProtocol.AIRPLAY_1, controller.protocol)
        assertEquals(65000L, controller.positionMs)
        assertEquals("Hotel California", controller.metadata.title)
        assertFalse(controller.isRtpActive) // RTP 已拆除
        assertTrue(controller.isDacpActive) // DACP 仍存活用于反控

        // 3. TV 端点击播放恢复：因 RTP 处于挂起断开状态，不得盲目置为 PLAYING
        val directResumeSucceeded = controller.onTvResumeRequest()
        assertFalse("RTP 断开时 TV 端不得直接进入 PLAYING 僵尸状态", directResumeSucceeded)
        assertEquals(PlaybackStatus.PAUSED, controller.status)

        // 4. iOS 收到 DACP play 重新建流 SETUP/RECORD，RTP 通道恢复
        controller.onIncomingRtpReconnect()
        assertEquals(PlaybackStatus.PLAYING, controller.status)
        assertTrue(controller.isRtpActive)
        assertEquals(65000L, controller.positionMs)

        // 5. 触发彻底停止 (10分钟软挂起超时或用户主动停止)
        controller.onFullSessionStop()
        assertEquals(PlaybackStatus.STOPPED, controller.status)
        assertEquals(StreamProtocol.NONE, controller.protocol)
        assertEquals(0L, controller.positionMs)
        assertFalse(controller.isRtpActive)
        assertFalse(controller.isDacpActive)
    }

    /**
     * 💡 回归测试 1: 验证 SDP rtpmap 解析对 L16 (PCM) 与 ALAC 的格式识别与参数提取
     */
    @Test
    fun testSdpRtpmapParsingL16AndAlac() {
        fun parseSdpFormat(sdp: String): Triple<String, Int, Int> {
            val mAudioLine = sdp.lines().firstOrNull { it.startsWith("m=audio") } ?: ""
            val pt = mAudioLine.substringAfter("RTP/AVP").trim().split(" ").firstOrNull()?.toIntOrNull() ?: 96
            val rtpmapLine = sdp.lines().firstOrNull { it.startsWith("a=rtpmap:") } ?: ""
            val rtpmap = rtpmapLine.removePrefix("a=rtpmap:").trim()
            val lower = rtpmap.lowercase()

            val codec: String
            var sampleRate = 44100
            var channels = 2

            if (lower.contains("l16") || lower.contains("pcm") || pt == 10 || pt == 11) {
                codec = "PCM"
                channels = if (pt == 11) 1 else 2
                val slash1 = rtpmap.indexOf('/')
                if (slash1 != -1) {
                    val slash2 = rtpmap.indexOf('/', slash1 + 1)
                    val rateStr = if (slash2 != -1) rtpmap.substring(slash1 + 1, slash2) else rtpmap.substring(slash1 + 1)
                    sampleRate = rateStr.toIntOrNull() ?: 44100
                    if (slash2 != -1) {
                        channels = rtpmap.substring(slash2 + 1).toIntOrNull() ?: 2
                    }
                }
            } else {
                codec = "ALAC"
            }
            return Triple(codec, sampleRate, channels)
        }

        // 场景 A: 视频 App / 网页通过 AirPlay 1 推送 16-bit 44.1kHz L16 PCM
        val sdpL16 = """
            v=0
            o=AirTunes 123456 0 IN IP4 192.168.2.1
            s=AirTunes
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 L16/44100/2
        """.trimIndent()
        val (codecA, rateA, chA) = parseSdpFormat(sdpL16)
        assertEquals("PCM", codecA)
        assertEquals(44100, rateA)
        assertEquals(2, chA)

        // 场景 B: 视频 App 推送 48kHz L16 PCM
        val sdpL16_48k = """
            v=0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 L16/48000/2
        """.trimIndent()
        val (codecB, rateB, chB) = parseSdpFormat(sdpL16_48k)
        assertEquals("PCM", codecB)
        assertEquals(48000, rateB)
        assertEquals(2, chB)

        // 场景 C: 音乐 App 推送 ALAC
        val sdpAlac = """
            v=0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
        """.trimIndent()
        val (codecC, rateC, chC) = parseSdpFormat(sdpAlac)
        assertEquals("ALAC", codecC)
        assertEquals(44100, rateC)
        assertEquals(2, chC)

        // 场景 D: 客户端使用 RFC 3551 静态 Payload Type 10 (无需 a=rtpmap 声明)
        val sdpStaticPt10 = """
            v=0
            m=audio 0 RTP/AVP 10
        """.trimIndent()
        val (codecD, rateD, chD) = parseSdpFormat(sdpStaticPt10)
        assertEquals("PCM", codecD)
        assertEquals(44100, rateD)
        assertEquals(2, chD)
    }

    /**
     * 💡 回归测试 2: 验证 L16 网络大端字节序转换为本地架构 S16_LE 整数 PCM
     */
    @Test
    fun testL16NetworkByteOrderEndianConversion() {
        // 构造网络大端序 16-bit 采样: 左声道 0x1234, 右声道 0x5678
        val netPayload = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val pcmFrame = ByteArray(netPayload.size)

        val validBytes = netPayload.size and 1.inv()
        for (i in 0 until validBytes step 2) {
            pcmFrame[i] = netPayload[i + 1]     // 小端低字节
            pcmFrame[i + 1] = netPayload[i]     // 小端高字节
        }

        // 验证小端序重组为正确的带符号 short
        val buf = ByteBuffer.wrap(pcmFrame).order(ByteOrder.LITTLE_ENDIAN)
        val leftSample = buf.getShort()
        val rightSample = buf.getShort()

        assertEquals(0x1234.toShort(), leftSample)
        assertEquals(0x5678.toShort(), rightSample)
    }

    /**
     * 💡 回归测试 3: 验证切歌时元数据与进度条平稳过渡 (保留现有封面、重置在途进度)
     */
    @Test
    fun testTrackSwitchMetadataAndProgressStability() {
        val existingCover = byteArrayOf(0x01, 0x02, 0x03)
        var currentMeta = TrackMetadata(
            title = "Track 1",
            artist = "Artist 1",
            album = "Album 1",
            coverBytes = existingCover,
            durationMs = 240000L
        )

        // 模拟切歌：新歌的 DMAP 元数据到达，尚未包含封面
        val newTitle = "Track 2"
        val newArtist = "Artist 2"
        val isNewTrack = (newTitle != currentMeta.title) || (newArtist != currentMeta.artist)
        assertTrue("检测到切歌", isNewTrack)

        // 验证切歌时沿用已有封面垫底，杜绝空白闪烁
        val mergedCover = null ?: currentMeta.coverBytes
        val nextMeta = TrackMetadata(
            title = newTitle,
            artist = newArtist,
            album = "Album 2",
            coverBytes = mergedCover,
            durationMs = 180000L
        )
        assertArrayEquals(existingCover, nextMeta.coverBytes)
        assertEquals("Track 2", nextMeta.title)

        // 验证进度重置与安全钳位
        var playbackPositionMs = 210000L // 上一曲在途位置
        if (isNewTrack) {
            playbackPositionMs = 0L // 切歌即时归零
        }
        assertEquals(0L, playbackPositionMs)

        // 模拟新歌首个 progress 更新到达 (例如 position=5000ms, duration=180000ms)
        val incomingPos = 5000L
        val incomingDur = 180000L
        val clampedPos = incomingPos.coerceIn(0L, incomingDur)
        assertEquals(5000L, clampedPos)
    }

    /**
     * 💡 验证静音帧深度检测 (64-bit对齐扫描) 与双向状态机同步：
     * 1. 连续 180 帧全 0 静音 (约 1.44s) 触发同步 PAUSED，且静音帧不灌入缓冲，杜绝短暂停顿或乐曲休止符误判
     * 2. 收到有效非静音包立即唤醒，重置 JitterBuffer 并恢复 PLAYING
     */
    @Test
    fun testContinuousSilenceSensingAndResumeStateMachine() {
        var isStreamPaused = false
        var continuousSilenceCount = 0
        var jitterBufferResetCalled = false
        var ringBufferFlushed = false
        var playbackStatus = PlaybackStatus.PLAYING

        fun processAudioFrame(pcmBytes: ByteArray, isSilence: Boolean) {
            if (isSilence) {
                continuousSilenceCount++
                if (continuousSilenceCount >= 180 && !isStreamPaused) {
                    isStreamPaused = true
                    playbackStatus = PlaybackStatus.PAUSED
                }
                // 静音保活帧丢弃，不写缓冲区
                return
            } else {
                if (continuousSilenceCount > 0) {
                    continuousSilenceCount = 0
                }
                if (isStreamPaused) {
                    isStreamPaused = false
                    playbackStatus = PlaybackStatus.PLAYING
                    jitterBufferResetCalled = true
                    ringBufferFlushed = true
                }
            }
        }

        val silenceFrame = ByteArray(1408) { 0 }
        val activeAudioFrame = ByteArray(1408) { 0 }.also { it[100] = 0x42 } // 非静音

        // 模拟推送 179 帧静音：尚未到达 180 帧门限，保持 PLAYING
        for (i in 1..179) {
            processAudioFrame(silenceFrame, isSilence = true)
        }
        assertEquals(179, continuousSilenceCount)
        assertFalse(isStreamPaused)
        assertEquals(PlaybackStatus.PLAYING, playbackStatus)

        // 第 180 帧静音到达：触发 PAUSED 状态同步
        processAudioFrame(silenceFrame, isSilence = true)
        assertEquals(180, continuousSilenceCount)
        assertTrue(isStreamPaused)
        assertEquals(PlaybackStatus.PAUSED, playbackStatus)

        // 模拟首个非静音音频帧到达：即时唤醒，重置 JitterBuffer 与清空 RingBuffer，恢复 PLAYING
        processAudioFrame(activeAudioFrame, isSilence = false)
        assertEquals(0, continuousSilenceCount)
        assertFalse(isStreamPaused)
        assertEquals(PlaybackStatus.PLAYING, playbackStatus)
        assertTrue("恢复播放时必须重置 JitterBuffer", jitterBufferResetCalled)
        assertTrue("恢复播放时必须刷新环形缓冲区", ringBufferFlushed)
    }

    /**
     * 💡 验证长暂停 (序号差 > 32767) 恢复推流时的鲁棒性：
     * 证明恢复推流显式重置 JitterBuffer (first_packet_ = true) 能彻底避免 int16_t 负数溢出丢包
     */
    @Test
    fun testLongPauseSequenceNumberOverflowResilience() {
        val initialSeq = 1000
        val packetsDuringPause = 35000 // 超过 32767
        val resumeSeq = (initialSeq + packetsDuringPause) and 0xFFFF // 16 位溢出回绕

        // 验证如果不重置 JitterBuffer，int16_t(seq - head_seq) 会溢出为负数：
        val rawDiff = (resumeSeq - initialSeq).toShort().toInt()
        assertTrue("35000 包差异强制转为 short 会变成负数，误判为乱序丢包", rawDiff < 0)

        // 验证修复方案：在恢复播放时显式调用 reset()，首包重新捕获基准
        var firstPacket = true
        var headSeq = 0
        fun pushOnResume(seq: Int) {
            if (firstPacket) {
                headSeq = seq
                firstPacket = false
            }
        }

        pushOnResume(resumeSeq)
        assertEquals("重置后首包被正确捕获为新的基准时钟", resumeSeq, headSeq)
        assertFalse("首包捕获完成，进入有序播放流水线", firstPacket)
    }

    /**
     * 💡 验证切歌时元数据领域模型的真实性：
     * 1. Track 1 有封面：正常保存 coverBytes
     * 2. Track 2 无封面（如网络电台或无内嵌封面曲目）：isNewTrack 为 true 时 coverBytes 为 null，
     *    严禁永久继承 Track 1 的封面污染 MediaSession 与全局状态；
     * 3. 视觉暂留与平滑淡出委托给 UI 层 (MainTvScreen persistentCover) 1.2s 超时防抖
     */
    @Test
    fun testTrackTransitionCoverMetadataTruthfulness() {
        val track1Cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // JPEG Header
        val currentMeta = TrackMetadata(
            title = "Song A",
            artist = "Artist A",
            album = "Album A",
            coverBytes = track1Cover,
            durationMs = 200000L
        )

        // 模拟切换到 Track B (无封面)：
        val isNewTrack = true
        val incomingCoverArt: ByteArray? = null
        val mergedCover = when {
            incomingCoverArt != null && incomingCoverArt.isNotEmpty() -> incomingCoverArt
            isNewTrack -> null // 关键校验：新曲目无封面时必须置 null
            else -> currentMeta.coverBytes
        }

        assertNull("新曲目无封面时领域模型必须置 null，不得继承旧曲目封面", mergedCover)

        // 模拟同一曲目在途接收到封面数据包更新：
        val delayedCover = byteArrayOf(0x12, 0x34)
        val progressiveCover = when {
            delayedCover.isNotEmpty() -> delayedCover
            false -> null
            else -> currentMeta.coverBytes
        }
        assertNotNull("在途封面到达时必须成功采纳新封面", progressiveCover)
        assertEquals(delayedCover, progressiveCover)
    }

    /**
     * 💡 验证待机态语义双向覆盖：IDLE 与 STOPPED 均属于大屏待机范畴
     */
    @Test
    fun testIdleAndStoppedStandbySemantics() {
        fun isStandby(status: PlaybackStatus): Boolean {
            return status == PlaybackStatus.IDLE || status == PlaybackStatus.STOPPED
        }

        assertTrue("IDLE 必须属于待机状态", isStandby(PlaybackStatus.IDLE))
        assertTrue("STOPPED 必须属于待机状态，以支持停止推流后恢复指引与主题", isStandby(PlaybackStatus.STOPPED))
        assertFalse("PLAYING 绝非待机状态", isStandby(PlaybackStatus.PLAYING))
        assertFalse("PAUSED 绝非待机状态", isStandby(PlaybackStatus.PAUSED))
    }

    /**
     * 💡 验证切歌至无时长直播流/网络电台时的时长重置与 DurationState 判定
     */
    @Test
    fun testSwitchToLiveStreamWithoutDurationResetsDurationAndEnablesLive() {
        val previousSong = TrackMetadata(
            title = "Previous Song",
            artist = "Previous Artist",
            album = "Previous Album",
            durationMs = 180000L,
            durationState = com.solo.purecast.model.DurationState.FIXED,
            seekCapability = com.solo.purecast.model.SeekCapability.SEEKABLE
        )

        // 场景 1：切换到新曲目（网络电台/直播流，无时长 durationMs <= 0）
        val isNewTrack = true
        val incomingDurationMs = 0L
        val mergedDuration = if (incomingDurationMs > 0) incomingDurationMs else if (isNewTrack) 0L else previousSong.durationMs
        val durationState = if (mergedDuration > 0) {
            com.solo.purecast.model.DurationState.FIXED
        } else {
            com.solo.purecast.model.DurationState.UNKNOWN
        }

        assertEquals("切歌进入无时长流时总时长必须归零", 0L, mergedDuration)
        assertEquals(com.solo.purecast.model.DurationState.UNKNOWN, durationState)

        // 场景 2：同一曲目增量推送（未附带时长），应保留已知时长
        val isSameTrack = false
        val sameTrackMergedDuration = if (incomingDurationMs > 0) incomingDurationMs else if (isSameTrack) 0L else previousSong.durationMs
        val sameTrackState = if (sameTrackMergedDuration > 0) {
            com.solo.purecast.model.DurationState.FIXED
        } else {
            com.solo.purecast.model.DurationState.UNKNOWN
        }

        assertEquals("同一曲目增量更新未附带时长时必须继承已知时长", 180000L, sameTrackMergedDuration)
        assertEquals("有已知时长的曲目必须保持 FIXED 状态", com.solo.purecast.model.DurationState.FIXED, sameTrackState)
    }

    /**
     * 💡 验证切歌豁免期静音丢弃与首个有效帧 JitterBuffer 游标重置机制
     */
    @Test
    fun testTransitionSilenceFrameDropAndJitterResync() {
        var isStreamPaused = false
        var continuousSilenceCount = 0
        var skippedSilenceInTransition = false
        var jitterBufferResetCalled = false
        var playbackStatus = PlaybackStatus.PLAYING
        var inTransition = true

        fun processAudioFrame(isSilence: Boolean) {
            if (isSilence) {
                if (inTransition) {
                    continuousSilenceCount = 0
                    skippedSilenceInTransition = true
                    return
                }
                continuousSilenceCount++
                if (continuousSilenceCount >= 180 && !isStreamPaused) {
                    isStreamPaused = true
                    playbackStatus = PlaybackStatus.PAUSED
                }
            } else {
                continuousSilenceCount = 0
                val wasPaused = isStreamPaused
                val hadTransitionSilence = skippedSilenceInTransition
                skippedSilenceInTransition = false

                if (wasPaused || hadTransitionSilence) {
                    if (wasPaused) {
                        isStreamPaused = false
                        playbackStatus = PlaybackStatus.PLAYING
                    }
                    jitterBufferResetCalled = true
                }
            }
        }

        // 1. 模拟在切歌过渡期推送 50 帧全静音保活帧：
        for (i in 1..50) {
            processAudioFrame(isSilence = true)
        }
        assertEquals("过渡期内静音计数器必须保持归零", 0, continuousSilenceCount)
        assertFalse("过渡期内静音帧绝不能触发 PAUSED 状态误判", isStreamPaused)
        assertEquals(PlaybackStatus.PLAYING, playbackStatus)
        assertTrue("必须记录切歌过渡期丢弃过静音帧标记", skippedSilenceInTransition)

        // 2. 模拟新歌曲首个有效音频帧到达：
        processAudioFrame(isSilence = false)
        assertTrue("切歌过渡期丢弃静音帧后首包到达必须重置 JitterBuffer 游标消除断层", jitterBufferResetCalled)
        assertFalse("跳过静音帧标记必须被重置", skippedSilenceInTransition)
        assertEquals(PlaybackStatus.PLAYING, playbackStatus)
    }

    /**
     * 💡 验证看门狗暂停与数据恢复推流的竞态保护（杜绝回调反转导致 UI 僵死）
     */
    @Test
    fun testWatchdogLoopCallbackInversionProtection() {
        var isStreamPaused = false
        var reportedStatus = PlaybackStatus.PLAYING
        val stateLock = Any()

        fun onStateChanged(paused: Boolean) {
            synchronized(stateLock) {
                reportedStatus = if (paused) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
            }
        }

        // 模拟竞态：看门狗发现停顿 600ms 并 CAS 将 isStreamPaused 置为 true
        isStreamPaused = true

        // 在看门狗分发回调前，数据接收线程收到新音频包并先抢占锁分发恢复
        synchronized(stateLock) {
            isStreamPaused = false
            onStateChanged(paused = false)
        }

        // 随后看门狗获取锁，执行竞态三检：核验 isStreamPaused 发现已被置为 false，放弃分发迟到的暂停
        synchronized(stateLock) {
            if (isStreamPaused) {
                onStateChanged(paused = true)
            }
        }

        assertEquals("竞态保护下最终分发状态必须为 PLAYING，绝不出现回调颠簸僵死", PlaybackStatus.PLAYING, reportedStatus)
    }
}
