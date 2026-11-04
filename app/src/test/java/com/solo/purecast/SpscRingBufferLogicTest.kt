package com.solo.purecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 💡 U2 核心算法单元测试: 环形缓冲容量对齐与物理子帧 LSB 补零对齐逻辑
 */
class SpscRingBufferLogicTest {

    @Test
    fun testPowerOfTwoCapacityCalculation() {
        // 验证容量自动向上对齐至 2^N
        val requestedCapacity = 500_000
        var capacity = 1
        while (capacity < requestedCapacity) {
            capacity = capacity shl 1
        }
        assertEquals(524288, capacity) // 512KB = 2^19
        assertEquals(0, capacity and (capacity - 1)) // 必须是 2 的幂次
    }

    @Test
    fun testPcm16ToS24_3LE_Padding() {
        // 验证 16-bit 采样向 24-bit (3 字节) 子帧的 LSB 补零 (sample_16 << 8)
        val sample16Max = 0x7FFF.toShort() // +32767

        // 模拟 Little-Endian 打包
        val sample24 = sample16Max.toInt() shl 8
        val b0 = (sample24 and 0xFF).toByte()
        val b1 = ((sample24 shr 8) and 0xFF).toByte()
        val b2 = ((sample24 shr 16) and 0xFF).toByte()

        assertEquals(0x00.toByte(), b0) // 最低有效位补零
        assertEquals(0xFF.toByte(), b1) // 低 8 位
        assertEquals(0x7F.toByte(), b2) // 高 8 位
    }

    @Test
    fun testPcm16ToS32_LE_Padding() {
        // 💡 验证 16-bit 采样向 32-bit (4 字节) 子帧的高位对齐与低位补零 (sample_16 << 16)
        val sample16Max = 0x7FFF.toShort() // +32767

        val sample32 = sample16Max.toInt() shl 16
        val b0 = (sample32 and 0xFF).toByte()
        val b1 = ((sample32 shr 8) and 0xFF).toByte()
        val b2 = ((sample32 shr 16) and 0xFF).toByte()
        val b3 = ((sample32 shr 24) and 0xFF).toByte()

        assertEquals(0x00.toByte(), b0) // 最低有效位补零
        assertEquals(0x00.toByte(), b1) // 次低有效位补零
        assertEquals(0xFF.toByte(), b2) // 采样低 8 位
        assertEquals(0x7F.toByte(), b3) // 采样高 8 位

        // 验证负最大值 (-32768 = 0x8000)
        val sample16Min = (-32768).toShort()
        val sample32Min = sample16Min.toInt() shl 16
        val minB0 = (sample32Min and 0xFF).toByte()
        val minB1 = ((sample32Min shr 8) and 0xFF).toByte()
        val minB2 = ((sample32Min shr 16) and 0xFF).toByte()
        val minB3 = ((sample32Min shr 24) and 0xFF).toByte()

        assertEquals(0x00.toByte(), minB0)
        assertEquals(0x00.toByte(), minB1)
        assertEquals(0x00.toByte(), minB2)
        assertEquals(0x80.toByte(), minB3)
    }

    @Test
    fun testVolumeAttenuationIfOnlyShifted8BitsIn32BitSlot() {
        // 💡 验证反模式: 若在 32-bit 物理槽中仅左移 8 位，音量将衰减 48dB (256倍)
        val sample16 = 32767
        val correct32 = sample16 shl 16
        val buggy32 = sample16 shl 8

        val ratio = correct32.toDouble() / buggy32.toDouble()
        assertEquals(256.0, ratio, 0.001)

        val attenuationDb = 20 * Math.log10(ratio)
        assertTrue("衰减必须为 48.16dB", Math.abs(attenuationDb - 48.16) < 0.1)
    }
}
