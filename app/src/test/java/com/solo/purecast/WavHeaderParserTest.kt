package com.solo.purecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 💡 U5 核心流解包单测: WAV 容器头部多 Chunk 状态机解析验证
 */
class WavHeaderParserTest {

    data class WavInfo(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val headerLength: Int
    )

    private fun parseWavStream(input: InputStream): WavInfo {
        val buffer = ByteArray(1024)
        var totalRead = 0

        // 至少读取前 12 字节校验 RIFF WAVE
        fun readFully(target: ByteArray, offset: Int, length: Int) {
            var read = 0
            while (read < length) {
                val r = input.read(target, offset + read, length - read)
                if (r == -1) throw IllegalArgumentException("Premature EOF in WAV stream")
                read += r
            }
            totalRead += length
        }

        val riffHeader = ByteArray(12)
        readFully(riffHeader, 0, 12)

        val riffTag = String(riffHeader, 0, 4)
        val waveTag = String(riffHeader, 8, 4)
        require(riffTag == "RIFF" && waveTag == "WAVE") { "Invalid RIFF/WAVE container: riff=$riffTag, wave=$waveTag" }

        var sampleRate = 44100
        var bitDepth = 16
        var channels = 2
        var dataFound = false

        val chunkHeader = ByteArray(8)
        while (!dataFound) {
            readFully(chunkHeader, 0, 8)
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

            when (chunkId) {
                "fmt " -> {
                    val fmtData = ByteArray(chunkSize)
                    readFully(fmtData, 0, chunkSize)
                    val fmtBuf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                    val format = fmtBuf.short.toInt()
                    channels = fmtBuf.short.toInt()
                    sampleRate = fmtBuf.int
                    fmtBuf.int // byteRate
                    fmtBuf.short // blockAlign
                    bitDepth = fmtBuf.short.toInt()
                }

                "data" -> {
                    dataFound = true
                    // 到达有效 PCM 载荷边界
                }

                else -> {
                    // 💡 跳过扩展 Chunk (如 LIST, JUNK, bext 等)
                    var toSkip = chunkSize
                    val skipBuf = ByteArray(512)
                    while (toSkip > 0) {
                        val n = input.read(skipBuf, 0, Math.min(toSkip, skipBuf.size))
                        if (n == -1) break
                        toSkip -= n
                        totalRead += n
                    }
                }
            }
        }

        return WavInfo(sampleRate, bitDepth, channels, totalRead)
    }

    @Test
    fun testStandard44ByteWavHeader() {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(10044) // file size
        bb.put("WAVE".toByteArray())

        // fmt chunk (16 bytes)
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort()) // PCM
        bb.putShort(2.toShort()) // 2 channels
        bb.putInt(44100) // 44.1kHz
        bb.putInt(44100 * 2 * 2) // byte rate
        bb.putShort(4.toShort()) // block align
        bb.putShort(16.toShort()) // 16 bits

        // data chunk
        bb.put("data".toByteArray())
        bb.putInt(10000)

        val stream = ByteArrayInputStream(bb.array())
        val info = parseWavStream(stream)

        assertEquals(44100, info.sampleRate)
        assertEquals(16, info.bitDepth)
        assertEquals(2, info.channels)
        assertEquals(44, info.headerLength)
    }

    @Test
    fun testWavHeaderWithExtraListChunk() {
        // 模拟带 LIST 扩展 Chunk 的非标准 WAV (常见于某些 DLNA 控制端或转码服务器)
        val extraInfo = "LISTInfoTextExtra".toByteArray()
        val totalSize = 44 + 8 + extraInfo.size
        val bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        bb.put("RIFF".toByteArray())
        bb.putInt(10000 + totalSize)
        bb.put("WAVE".toByteArray())

        // fmt chunk
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort()) // PCM
        bb.putShort(2.toShort()) // 2 channels
        bb.putInt(96000) // 96kHz 高解析
        bb.putInt(96000 * 2 * 3) // byte rate
        bb.putShort(6.toShort()) // block align
        bb.putShort(24.toShort()) // 24 bits

        // extra LIST chunk
        bb.put("LIST".toByteArray())
        bb.putInt(extraInfo.size)
        bb.put(extraInfo)

        // data chunk
        bb.put("data".toByteArray())
        bb.putInt(10000)

        val stream = ByteArrayInputStream(bb.array())
        val info = parseWavStream(stream)

        assertEquals(96000, info.sampleRate)
        assertEquals(24, info.bitDepth)
        assertEquals(2, info.channels)
        assertEquals(totalSize, info.headerLength)
    }
}
