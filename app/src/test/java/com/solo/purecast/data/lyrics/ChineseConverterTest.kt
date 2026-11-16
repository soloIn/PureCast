package com.solo.purecast.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 💡 繁体到简体中文转换单元测试
 */
class ChineseConverterTest {

    @Test
    fun `toSimplified converts traditional lyrics to simplified accurately`() {
        val traditionalSample = "故事的小黃花 從出生那年就飄著 童年的蕩鞦韆 隨記憶一直晃到現在"
        val expected = "故事的小黄花 从出生那年就飘着 童年的荡秋千 随记忆一直晃到现在"
        val actual = ChineseConverter.toSimplified(traditionalSample)
        assertEquals(expected, actual)
    }

    @Test
    fun `toSimplified preserves already simplified characters untouched`() {
        val simplifiedSample = "故事的小黄花 从出生那年就飘着"
        val actual = ChineseConverter.toSimplified(simplifiedSample)
        assertEquals(simplifiedSample, actual)
    }

    @Test
    fun `toSimplified handles common music lyric characters`() {
        val trad = "我們這裏聽音樂讓愛難邊會當對終經給轉帶麼誰傷淚開風飛夢離變總結實寶貝親獨萬裡著時為國動發學長機現電問頭線雙隻條門車陽陰見視覺讀書寫畫樂詞語詩韻響聲調"
        val simp = "我们这里听音乐让爱难边会当对终经给转带么谁伤泪开风飞梦离变总结实宝贝亲独万里着时为国动发学长机现电问头线双只条门车阳阴见视觉读书写画乐词语诗韵响声调"
        assertEquals(simp, ChineseConverter.toSimplified(trad))
    }

    @Test
    fun `toSimplified handles empty string`() {
        assertEquals("", ChineseConverter.toSimplified(""))
    }
}
