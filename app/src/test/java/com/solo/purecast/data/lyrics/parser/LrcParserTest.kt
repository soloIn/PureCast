package com.solo.purecast.data.lyrics.parser

import com.solo.purecast.model.LyricFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💡 U3 LRC 解析引擎单元测试
 */
class LrcParserTest {

    @Test
    fun `parse standard line sync LRC with offset`() {
        val lrcContent = """
            [ti:晴天]
            [ar:周杰伦]
            [offset:500]
            [00:10.00]故事的小黄花
            [00:15.50]从出生那年就飘着
            [00:20.00]童年的荡秋千
        """.trimIndent()

        val timeline = LrcParser.parse(lrcContent, "LRCLIB")

        assertNotNull(timeline)
        assertEquals(LyricFormat.LRC_LINE_SYNC, timeline?.format)
        assertEquals(3, timeline?.lines?.size)

        // 验证 offset +500ms 生效：00:10.00 (10000) + 500 = 10500
        val line1 = timeline!!.lines[0]
        assertEquals(10500L, line1.startMs)
        assertEquals("故事的小黄花", line1.mainText)
        assertEquals(16000L, line1.endMs) // 下一行 15500 + 500 = 16000

        val line2 = timeline.lines[1]
        assertEquals(16000L, line2.startMs)
        assertEquals("从出生那年就飘着", line2.mainText)
    }

    @Test
    fun `parse enhanced LRC with word level sync tags`() {
        val enhancedLrc = """
            [00:05.00]<00:05.00>Hello <00:06.00>world <00:07.50>
            [00:08.00]<00:08.00>PureCast <00:09.00>TV <00:10.50>
        """.trimIndent()

        val timeline = LrcParser.parse(enhancedLrc, "LRCLIB")

        assertNotNull(timeline)
        assertEquals(LyricFormat.LRC_WORD_SYNC, timeline?.format)
        assertEquals(2, timeline?.lines?.size)

        val line1 = timeline!!.lines[0]
        assertEquals("Hello world ", line1.mainText)
        assertNotNull(line1.words)
        assertEquals(2, line1.words?.size)
        assertEquals("Hello ", line1.words?.get(0)?.text)
        assertEquals(5000L, line1.words?.get(0)?.startMs)
        assertEquals(6000L, line1.words?.get(0)?.endMs)
    }

    @Test
    fun `parse enhanced LRC with leading text before first sync tag preserves leading word`() {
        val enhancedLrc = """
            [01:23.45]Hello <01:24.00>world <01:25.50>
        """.trimIndent()

        val timeline = LrcParser.parse(enhancedLrc, "LRCLIB")

        assertNotNull(timeline)
        assertEquals(LyricFormat.LRC_WORD_SYNC, timeline?.format)
        val line = timeline!!.lines[0]
        assertEquals("Hello world ", line.mainText)
        assertEquals(2, line.words?.size)

        // 验证引导词 Hello 不被静默丢弃，且继承行起始时间 83450ms
        val word0 = line.words!![0]
        assertEquals("Hello ", word0.text)
        assertEquals(83450L, word0.startMs)
        assertEquals(84000L, word0.endMs)

        val word1 = line.words!![1]
        assertEquals("world ", word1.text)
        assertEquals(84000L, word1.startMs)
        assertEquals(85500L, word1.endMs)
    }

    @Test
    fun `parse multi timestamp lines expands to sorted separate entries`() {
        val multiTimeLrc = """
            [00:10.00][00:30.00]副歌重复句
        """.trimIndent()

        val timeline = LrcParser.parse(multiTimeLrc, "LRCLIB")

        assertNotNull(timeline)
        assertEquals(2, timeline?.lines?.size)
        assertEquals(10000L, timeline?.lines?.get(0)?.startMs)
        assertEquals(30000L, timeline?.lines?.get(1)?.startMs)
        assertEquals("副歌重复句", timeline?.lines?.get(0)?.mainText)
        assertEquals("副歌重复句", timeline?.lines?.get(1)?.mainText)
    }

    @Test
    fun `plain text without timestamps returns null`() {
        val plainText = """
            这是第一行歌词
            这是第二行歌词
        """.trimIndent()

        val timeline = LrcParser.parse(plainText, "LRCLIB")

        assertNull(timeline)
    }

    @Test
    fun `same timestamp consecutive lines merged as translation and endMs is preserved`() {
        val dualLangLrc = """
            [00:10.00]Yellow
            [00:10.00]黄色
            [00:20.00]Look at the stars
            [00:20.00]仰望星空
        """.trimIndent()

        val timeline = LrcParser.parse(dualLangLrc, "LRCLIB")

        assertNotNull(timeline)
        assertEquals(2, timeline?.lines?.size)

        val line1 = timeline!!.lines[0]
        assertEquals("Yellow", line1.mainText)
        assertEquals("黄色", line1.translationText)
        assertEquals(10000L, line1.startMs)
        assertEquals(20000L, line1.endMs) // 验证没有被压扁成 10500ms，而是正常指向下一句 20000ms

        val line2 = timeline.lines[1]
        assertEquals("Look at the stars", line2.mainText)
        assertEquals("仰望星空", line2.translationText)
        assertEquals(20000L, line2.startMs)
    }

    @Test
    fun `interlude end empty timestamp sets previous line endMs and does not create empty line`() {
        val interludeLrc = """
            [00:10.00]前奏结束唱第一句
            [00:18.00]
            [00:30.00]间奏结束唱第二句
        """.trimIndent()

        val timeline = LrcParser.parse(interludeLrc, "LRCLIB")

        assertNotNull(timeline)
        assertEquals(2, timeline?.lines?.size) // 空行被合并为间奏结束符，不独立占行

        val line1 = timeline!!.lines[0]
        assertEquals("前奏结束唱第一句", line1.mainText)
        assertEquals(10000L, line1.startMs)
        assertEquals(18000L, line1.endMs) // 空时间戳赋给了上一行的 endMs
    }

    @Test
    fun `empty content returns null`() {
        val result = LrcParser.parse("   ")
        assertNull(result)
    }

    @Test
    fun `null literal content returns null`() {
        assertNull(LrcParser.parse("null"))
        assertNull(LrcParser.parse("NULL"))
        assertNull(LrcParser.parse("  null  "))
        assertNull(LrcParser.parse("[00:10.00]null"))
    }

    @Test
    fun `negative offset clamps startMs to non-negative zero`() {
        val lrc = """
            [offset:-5000]
            [00:02.00]开场句
            [00:10.00]第二句
        """.trimIndent()
        val timeline = LrcParser.parse(lrc, "LRCLIB")
        assertNotNull(timeline)
        assertEquals(0L, timeline!!.lines[0].startMs)
        assertEquals(5000L, timeline.lines[1].startMs)
    }

    @Test
    fun `negative offset does not shift enhanced word timestamps forward`() {
        val lrc = """
            [offset:-5000]
            [00:02.00]<00:02.00>Hello <00:03.00>world <00:06.00>end
            [00:10.00]Next
        """.trimIndent()
        val timeline = LrcParser.parse(lrc, "LRCLIB")
        assertNotNull(timeline)
        val words = timeline!!.lines[0].words!!
        assertEquals(3, words.size)
        assertEquals(0L, words[0].startMs)
        assertEquals(0L, words[0].endMs)
        assertEquals(0L, words[1].startMs)
        assertEquals(1000L, words[1].endMs)
        assertEquals(1000L, words[2].startMs)
        assertEquals(5000L, words[2].endMs)
    }

    @Test
    fun `plain text consisting only of null returns null`() {
        val badContent = """
            null
            NULL
        """.trimIndent()
        assertNull(LrcParser.parse(badContent))
    }
}
