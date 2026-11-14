package com.solo.purecast.model

import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.MetadataPatchEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.TrackBoundaryEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💡 U1 领域模型、7 态硬件枚举与信封契约单元测试
 */
class PlaybackStateTest {

    @Test
    fun `BusinessSessionState maps cleanly to PlaybackStatus`() {
        assertEquals(PlaybackStatus.IDLE, BusinessSessionState.IDLE.toPlaybackStatus())
        assertEquals(PlaybackStatus.PLAYING, BusinessSessionState.PLAYING.toPlaybackStatus())
        // 💡 TRANSITIONING 对外严格呈现为 PLAYING，防止切歌/重配期间 UI 闪回停顿
        assertEquals(PlaybackStatus.PLAYING, BusinessSessionState.TRANSITIONING.toPlaybackStatus())
        assertEquals(PlaybackStatus.PAUSED, BusinessSessionState.PAUSED.toPlaybackStatus())
        assertEquals(PlaybackStatus.STOPPED, BusinessSessionState.STOPPED.toPlaybackStatus())
    }

    @Test
    fun `HardwarePipelineState defines complete 7 states`() {
        val states = HardwarePipelineState.entries.toSet()
        assertEquals(7, states.size)
        assertTrue(states.contains(HardwarePipelineState.DISCONNECTED))
        assertTrue(states.contains(HardwarePipelineState.ATTACHED))
        assertTrue(states.contains(HardwarePipelineState.PERMISSION_PENDING))
        assertTrue(states.contains(HardwarePipelineState.RELEASED))
        assertTrue(states.contains(HardwarePipelineState.EXCLUSIVE_MUTED))
        assertTrue(states.contains(HardwarePipelineState.EXCLUSIVE_ACTIVE))
        assertTrue(states.contains(HardwarePipelineState.AUDIO_RECONFIGURING))
    }

    @Test
    fun `FlushReason native codes match C++ FlushReason enum`() {
        assertEquals(0, FlushReason.EXPLICIT_PAUSE.nativeCode)
        assertEquals(1, FlushReason.FLUSH_SWITCH.nativeCode)
        assertEquals(2, FlushReason.STOP_OR_TEARDOWN.nativeCode)
        assertEquals(3, FlushReason.PREEMPT.nativeCode)
    }

    @Test
    fun `TrackMetadata defensively copies coverBytes on construction, read, and copy`() {
        val raw = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val metadata = TrackMetadata(
            title = "Hotel California",
            artist = "Eagles",
            coverBytes = raw,
            durationMs = 391_000L,
            durationState = DurationState.FIXED,
            seekCapability = SeekCapability.SEEKABLE,
            trackUri = "http://192.168.1.10/song.flac"
        )

        // 修改外部原始数组不能污染模型
        raw[0] = 0x7F
        val firstRead = metadata.coverBytes!!
        assertNotSame(raw, firstRead)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), firstRead)
        // 同一个不可变模型实例读取引用稳定（避免 Compose 重组误触发解码），显式拷贝才返回新数组
        assertSame(firstRead, metadata.coverBytes)

        val explicitCopy = metadata.copyCoverBytes()!!
        explicitCopy[1] = 0x6F
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), metadata.coverBytes!!)
        assertNotSame(firstRead, explicitCopy)

        // copy() 传入外部新数组同样隔离外部写入，且未修改 coverBytes 时沿用冻结副本并保持 coverHash 一致
        val externalUpdated = byteArrayOf(0x10, 0x20)
        val withNewCover = metadata.copy(coverBytes = externalUpdated)
        externalUpdated[0] = 0x00
        assertArrayEquals(byteArrayOf(0x10, 0x20), withNewCover.coverBytes!!)

        val copied = metadata.copy(title = "Desperado")
        assertEquals("Desperado", copied.title)
        assertEquals(metadata.coverHash, copied.coverHash)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), copied.coverBytes!!)
        assertNotEquals(metadata, copied)
    }

    @Test
    fun `PlayerSessionSnapshot derives playbackStatus strictly from businessState`() {
        val snapshot = PlayerSessionSnapshot(
            sessionId = 42L,
            deviceGeneration = 3L,
            transportState = TransportPipeState.INTERRUPTED,
            businessState = BusinessSessionState.TRANSITIONING,
            hardwareState = HardwarePipelineState.EXCLUSIVE_MUTED,
            activeProtocol = StreamProtocol.AIRPLAY_1,
            errors = mapOf(
                ErrorCode.USB_DEVICE_DETACHED to PlaybackError(
                    code = ErrorCode.USB_DEVICE_DETACHED,
                    message = "DAC detached",
                    recoverable = true,
                    timestampMs = 1000L
                )
            )
        )

        assertEquals(PlaybackStatus.PLAYING, snapshot.playbackStatus)
        assertFalse(snapshot.errors.isEmpty())
    }

    @Test
    fun `SessionEventEnvelope and MetadataPatchEvent preserve strong isolation`() {
        val patchRaw = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val patch = MetadataPatchEvent(trackGeneration = 7L, title = "Track A", coverBytes = patchRaw)
        patchRaw[0] = 0x00
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), patch.coverBytes!!)

        val envelope = SessionEventEnvelope(
            origin = EventOrigin.UPNP,
            sessionId = 5L,
            deviceGeneration = 2L,
            sequence = 11L,
            timestampElapsedMs = 9999L,
            payload = TrackBoundaryEvent(
                trackKey = "http://example.com/1.wav",
                trackGeneration = 7L,
                initialDurationState = DurationState.FIXED,
                seekCapability = SeekCapability.SEEKABLE
            )
        )
        assertEquals(EventOrigin.UPNP, envelope.origin)
        assertEquals(7L, envelope.payload.trackGeneration)
    }
}
