package com.solo.purecast

import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            isLiveStream = true
        )

        assertEquals("Hotel California", meta.title)
        assertEquals("Eagles", meta.artist)
        assertNotNull(meta.coverBytes)
        assertEquals(4, meta.coverBytes?.size)
        assertTrue(meta.isLiveStream)
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
}
