package com.solo.purecast.data.lyrics.parser

import com.solo.purecast.model.LyricFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 💡 U3 AMLL TTML 解析引擎单元测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtmlParserTest {

    @Test
    fun `parseTimestampMs parses multiple TTML time formats accurately`() {
        assertEquals(12345L, TtmlParser.parseTimestampMs("12.345s"))
        assertEquals(12345L, TtmlParser.parseTimestampMs("12.345"))
        assertEquals(83456L, TtmlParser.parseTimestampMs("01:23.456"))
        assertEquals(3723456L, TtmlParser.parseTimestampMs("01:02:03.456"))
    }

    @Test
    fun `parse extracts syllable words and translations while filtering bg and roman`() {
        val ttmlSample = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:tt="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="00:10.000" end="00:15.000">
                            <span begin="00:10.000" end="00:11.500">故事</span>
                            <span begin="00:11.500" end="00:13.000">的小黄花</span>
                            <span tt:role="translation">The little yellow flowers of the story</span>
                            <span tt:role="roman">gu shi de xiao huang hua</span>
                            <span tt:role="bg">(啦啦啦)</span>
                        </p>
                        <p begin="00:15.000" end="00:20.000">
                            <span begin="00:15.000" end="00:17.000">从出生那年就</span>
                            <span begin="00:17.000" end="00:20.000">飘着</span>
                            <span tt:role="translation">Have been drifting since the year I was born</span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val timeline = TtmlParser.parse(ttmlSample, "AMLL")

        assertNotNull(timeline)
        assertEquals(LyricFormat.TTML_SYLLABLE, timeline?.format)
        assertEquals("AMLL", timeline?.source)
        assertFalse(timeline!!.isInstrumental)
        assertEquals(2, timeline.lines.size)

        // 验证第 1 行（逐字行优先以主唱末字 endMs 作为行结束时间，避免被背景和声拖尾拉长）
        val line1 = timeline.lines[0]
        assertEquals(0, line1.lineIndex)
        assertEquals(10000L, line1.startMs)
        assertEquals(13000L, line1.endMs)
        assertEquals("故事的小黄花", line1.mainText)
        assertEquals("The little yellow flowers of the story", line1.translationText)
        assertNotNull(line1.words)
        assertEquals(2, line1.words?.size)
        assertEquals("故事", line1.words?.get(0)?.text)
        assertEquals(10000L, line1.words?.get(0)?.startMs)
        assertEquals(11500L, line1.words?.get(0)?.endMs)
        assertEquals("的小黄花", line1.words?.get(1)?.text)
        assertEquals(11500L, line1.words?.get(1)?.startMs)
        assertEquals(13000L, line1.words?.get(1)?.endMs)

        // 验证第 2 行
        val line2 = timeline.lines[1]
        assertEquals(1, line2.lineIndex)
        assertEquals(15000L, line2.startMs)
        assertEquals(20000L, line2.endMs)
        assertEquals("从出生那年就飘着", line2.mainText)
        assertEquals("Have been drifting since the year I was born", line2.translationText)
    }

    @Test
    fun `corrupted XML fails gracefully and returns null`() {
        val badXml = "<tt><body><div><p begin='00:10'>unclosed tag"
        val result = TtmlParser.parse(badXml)
        assertNull(result)
    }

    @Test
    fun `parse preserves english inter-span spaces filters whitespace spans and trims edges without inserting CJK spaces`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="00:01.000" end="00:05.000">
                            <span begin="00:01.000" end="00:01.500">  Hello</span> <span begin="00:01.500" end="00:02.000">   </span><span begin="00:02.000" end="00:03.000">world</span>
                            <span begin="00:03.000" end="00:04.000">again  </span>
                        </p>
                        <p begin="00:06.000" end="00:09.000">
                            <span begin="00:06.000" end="00:07.000">夜空中</span>
                            <span begin="00:07.000" end="00:08.500">最亮的星</span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val timeline = TtmlParser.parse(ttml, "AMLL")
        assertNotNull(timeline)
        assertEquals(2, timeline!!.lines.size)

        // 英文行：保留水平空格与换行缩进触发的拉丁词空格，过滤纯空白 span，去除首尾空格
        val enLine = timeline.lines[0]
        assertEquals("Hello world again", enLine.mainText)
        assertEquals(3, enLine.words?.size)
        assertEquals("Hello ", enLine.words?.get(0)?.text)
        assertEquals("world ", enLine.words?.get(1)?.text)
        assertEquals("again", enLine.words?.get(2)?.text)
        assertEquals(4000L, enLine.endMs)

        // 中文行：XML 换行缩进绝不误插空格
        val zhLine = timeline.lines[1]
        assertEquals("夜空中最亮的星", zhLine.mainText)
        assertEquals(2, zhLine.words?.size)
        assertEquals("夜空中", zhLine.words?.get(0)?.text)
        assertEquals("最亮的星", zhLine.words?.get(1)?.text)
    }
}
