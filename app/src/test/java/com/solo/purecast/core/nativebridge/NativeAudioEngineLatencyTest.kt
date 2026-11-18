package com.solo.purecast.core.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 💡 环形缓冲区未消费字节与物理输出延迟换算单元测试
 */
class NativeAudioEngineLatencyTest {

    @Test
    fun `calculateLatencyMs computes correct millisecond delay for 16-bit 44_1kHz stereo`() {
        // 44100 Hz * 2 channels * 2 bytes = 176400 bytes/sec
        // 17640 bytes = 100ms
        val delayMs = NativeAudioEngine.calculateLatencyMs(
            unconsumedBytes = 17640,
            sampleRate = 44100,
            bitDepth = 16,
            channels = 2
        )
        assertEquals(100L, delayMs)
    }

    @Test
    fun `calculateLatencyMs computes correct millisecond delay for 24-bit 96kHz stereo`() {
        // 96000 Hz * 2 channels * 3 bytes = 576000 bytes/sec
        // 57600 bytes = 100ms
        val delayMs = NativeAudioEngine.calculateLatencyMs(
            unconsumedBytes = 57600,
            sampleRate = 96000,
            bitDepth = 24,
            channels = 2
        )
        assertEquals(100L, delayMs)
    }

    @Test
    fun `calculateLatencyMs handles invalid or zero parameters safely`() {
        assertEquals(0L, NativeAudioEngine.calculateLatencyMs(0, 44100, 16, 2))
        assertEquals(0L, NativeAudioEngine.calculateLatencyMs(1000, 0, 16, 2))
        assertEquals(0L, NativeAudioEngine.calculateLatencyMs(1000, 44100, 0, 2))
        assertEquals(0L, NativeAudioEngine.calculateLatencyMs(1000, 44100, 16, 0))
    }
}
