package com.solo.purecast.data.lyrics

import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 💡 U1 歌词领域模型与两级缓存单元测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsCacheRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var repository: LyricsCacheRepository

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("lyrics_cache")
        repository = LyricsCacheRepository(
            diskCacheDir = cacheDir,
            maxMemoryEntries = 2,
            maxDiskEntries = 5
        )
    }

    @Test
    fun `computeCacheKey applies 3-second bucketing and case normalization`() {
        val key1 = repository.computeCacheKey("Jay Chou", "晴天", 269100L)
        val key2 = repository.computeCacheKey("  jay chou  ", "晴天", 269900L)
        // 269100 / 3000 = 89, 269900 / 3000 = 89 -> 同一分桶
        assertEquals(key1, key2)

        val key3 = repository.computeCacheKey("Jay Chou", "晴天", 274000L)
        // 274000 / 3000 = 91 -> 不同分桶
        assertTrue(key1 != key3)
    }

    @Test
    fun `L1 cache hits immediately without reading disk`() {
        val timeline = sampleTimeline()
        val key = repository.computeCacheKey("Eagles", "Hotel California", 391000L)

        repository.put(key, timeline)

        // 验证 L1 命中
        val hit = repository.get(key)
        assertNotNull(hit)
        assertEquals(timeline.source, hit?.source)
        assertEquals(timeline.lines.size, hit?.lines?.size)
        assertEquals("Welcome to the Hotel California", hit?.lines?.get(0)?.mainText)
        assertEquals("欢迎来到加州旅馆", hit?.lines?.get(0)?.translationText)
    }

    @Test
    fun `L2 disk cache loads and backfills L1 on memory eviction`() {
        val t1 = sampleTimeline("Song 1")
        val t2 = sampleTimeline("Song 2")
        val t3 = sampleTimeline("Song 3")

        val k1 = repository.computeCacheKey("Artist", "Song 1", 100000L)
        val k2 = repository.computeCacheKey("Artist", "Song 2", 100000L)
        val k3 = repository.computeCacheKey("Artist", "Song 3", 100000L)

        // L1 只有 2 个容量，写入 3 个后 k1 会被从 L1 驱逐到 L2 磁盘
        repository.put(k1, t1)
        repository.put(k2, t2)
        repository.put(k3, t3)

        // 再次获取 k1，应从磁盘成功读出并重填 L1
        val hit1 = repository.get(k1)
        assertNotNull(hit1)
        assertEquals("Song 1", hit1?.lines?.get(0)?.mainText)
    }

    @Test
    fun `corrupted disk cache file is deleted and returns null`() {
        val key = "corrupted_key_123"
        val badFile = File(cacheDir, "$key.json")
        badFile.writeText("{ this is invalid json content !!!")

        assertTrue(badFile.exists())
        val result = repository.get(key)
        assertNull(result)
        // 损坏文件应已被自动清理
        assertFalse(badFile.exists())
    }

    @Test
    fun `serialization preserves words syllable timestamps and translations`() {
        val timeline = sampleTimeline()
        val json = repository.serializeToJson(timeline)
        val restored = repository.deserializeFromJson(json)

        assertNotNull(restored)
        assertEquals(timeline.source, restored?.source)
        assertEquals(timeline.format, restored?.format)
        assertEquals(timeline.durationMs, restored?.durationMs)
        assertEquals(1, restored?.lines?.size)

        val line = restored!!.lines[0]
        assertEquals(0, line.lineIndex)
        assertEquals(1000L, line.startMs)
        assertEquals(5000L, line.endMs)
        assertEquals("Welcome to the Hotel California", line.mainText)
        assertEquals("欢迎来到加州旅馆", line.translationText)
        assertNotNull(line.words)
        assertEquals(2, line.words?.size)
        assertEquals("Welcome", line.words?.get(0)?.text)
        assertEquals(1000L, line.words?.get(0)?.startMs)
        assertEquals(2000L, line.words?.get(0)?.endMs)
    }

    private fun sampleTimeline(title: String = "Welcome to the Hotel California"): LyricTimeline {
        return LyricTimeline(
            source = "AMLL",
            format = LyricFormat.TTML_SYLLABLE,
            isInstrumental = false,
            durationMs = 391000L,
            lines = listOf(
                LyricLine(
                    lineIndex = 0,
                    startMs = 1000L,
                    endMs = 5000L,
                    mainText = title,
                    translationText = "欢迎来到加州旅馆",
                    words = listOf(
                        LyricWord("Welcome", 1000L, 2000L),
                        LyricWord("California", 2000L, 5000L)
                    )
                )
            )
        )
    }
}
