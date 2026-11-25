package com.solo.purecast.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💡 流媒体元数据清洗与模糊匹配归一化器单元测试
 */
class MetadataNormalizerTest {

    @Test
    fun `normalizeTitle strips parenthesis version tags and remaster suffixes`() {
        assertEquals("晴天", MetadataNormalizer.normalizeTitle("晴天 (Live 2004)"))
        assertEquals("hotel california", MetadataNormalizer.normalizeTitle("Hotel California - Remastered 2024"))
        assertEquals("something", MetadataNormalizer.normalizeTitle("Something (2019 Mix)"))
        assertEquals("yesterday", MetadataNormalizer.normalizeTitle("Yesterday [Deluxe Edition]"))
        assertEquals("安静", MetadataNormalizer.normalizeTitle("安静（伴奏版）"))
        assertEquals("夜曲", MetadataNormalizer.normalizeTitle("夜曲【官方无损】"))
        assertEquals("baby one more time", MetadataNormalizer.normalizeTitle("...Baby One More Time"))
        assertEquals("love story", MetadataNormalizer.normalizeTitle("Love Story (Taylor's Version)"))
    }

    @Test
    fun `normalizeTitle handles feat variations and trailing hyphens`() {
        assertEquals("see you again", MetadataNormalizer.normalizeTitle("See You Again (feat. Charlie Puth)"))
        assertEquals("empire state of mind", MetadataNormalizer.normalizeTitle("Empire State of Mind ft. Alicia Keys"))
        assertEquals("levitating", MetadataNormalizer.normalizeTitle("Levitating - Featuring DaBaby"))
    }

    @Test
    fun `normalizeTitle with pure punctuation or whitespace returns empty`() {
        assertEquals("", MetadataNormalizer.normalizeTitle(""))
        assertEquals("", MetadataNormalizer.normalizeTitle("   "))
        assertEquals("", MetadataNormalizer.normalizeTitle("---...///~~~"))
    }

    @Test
    fun `normalizeArtist cleans feat tags and standardizes separators`() {
        assertEquals("eagles", MetadataNormalizer.normalizeArtist("Eagles feat. Someone"))
        assertEquals("ed sheeran", MetadataNormalizer.normalizeArtist("Ed Sheeran ft. Justin Bieber"))
        assertEquals("the beatles billy preston", MetadataNormalizer.normalizeArtist("The Beatles, Billy Preston"))
        assertEquals("周杰伦 蔡依林", MetadataNormalizer.normalizeArtist("周杰伦 / 蔡依林"))
        assertEquals("spears britney", MetadataNormalizer.normalizeArtist("Spears, Britney"))
        assertEquals("coldplay bts", MetadataNormalizer.normalizeArtist("Coldplay & BTS"))
    }

    @Test
    fun `isPlaceholderArtist identifies generic or unknown artist strings`() {
        assertTrue(MetadataNormalizer.isPlaceholderArtist(""))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("   "))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("Unknown"))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("Unknown Artist"))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("<unknown>"))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("未知"))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("未知歌手"))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("群星"))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("Various Artists"))
        assertTrue(MetadataNormalizer.isPlaceholderArtist("VA"))

        assertFalse(MetadataNormalizer.isPlaceholderArtist("The Beatles"))
        assertFalse(MetadataNormalizer.isPlaceholderArtist("周杰伦"))
    }

    @Test
    fun `similarity calculations handle inverted western names and dirty subtitles`() {
        // 完全匹配
        assertEquals(1.0f, MetadataNormalizer.calculateSimilarity("Beatles", "Beatles"), 0.001f)

        // 空串
        assertEquals(0.0f, MetadataNormalizer.calculateSimilarity("", "Beatles"), 0.001f)
        assertEquals(0.0f, MetadataNormalizer.calculateSimilarity("Beatles", ""), 0.001f)

        // 西方倒置姓名通过 TokenSetRatio 应获极高匹配度 (接近 1.0f)
        val invertedScore = MetadataNormalizer.calculateTokenSetSimilarity("Spears, Britney", "Britney Spears")
        assertTrue("Inverted name similarity should be > 0.9, actual=$invertedScore", invertedScore >= 0.9f)

        // 包含附加脏信息的候选歌手通过 TokenSetRatio 依然高分
        val dirtyCandidateScore = MetadataNormalizer.calculateTokenSetSimilarity(
            "Britney Spears",
            "Spears, Britney - Baby One More Time (Audio)"
        )
        assertTrue("Token set ratio should handle dirty field, actual=$dirtyCandidateScore", dirtyCandidateScore >= 0.9f)
    }

    @Test
    fun `buildQuerySpec preserves exact version tags and punctuation alongside cleaned view`() {
        val spec = MetadataNormalizer.buildQuerySpec(
            artist = "  AC/DC  ",
            title = "Come Together   (2019 Mix)",
            album = "Abbey Road (2019 Mix)",
            durationMs = 260000L
        )
        assertEquals("AC/DC", spec.exactArtist)
        assertEquals("ac dc", spec.cleanArtist)
        assertEquals("Come Together (2019 Mix)", spec.exactTitle)
        assertEquals("come together", spec.cleanTitle)
        assertEquals("Abbey Road (2019 Mix)", spec.exactAlbum)
        assertEquals("abbey road", spec.cleanAlbum)
        assertTrue(spec.hasDistinctCleanedForm)
        assertFalse(spec.isInstrumentalHint)

        val instSpec = MetadataNormalizer.buildQuerySpec("周杰伦", "安静（伴奏版）")
        assertTrue(instSpec.isInstrumentalHint)
        assertEquals("安静", instSpec.cleanTitle)
    }
}
