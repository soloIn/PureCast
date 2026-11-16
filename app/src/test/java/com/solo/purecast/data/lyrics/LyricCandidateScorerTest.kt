package com.solo.purecast.data.lyrics

import com.solo.purecast.model.LyricFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 💡 U2 元数据清洗与多维打分决策引擎单元测试
 */
class LyricCandidateScorerTest {

    @Test
    fun `MetadataNormalizer cleans extra version and feat labels`() {
        val cleanTitle1 = MetadataNormalizer.normalizeTitle("晴天 (Live 2004 现场版)")
        assertEquals("晴天", cleanTitle1)

        val cleanTitle2 = MetadataNormalizer.normalizeTitle("Hotel California - Remastered 2024")
        assertEquals("hotel california", cleanTitle2)

        val cleanTitle3 = MetadataNormalizer.normalizeTitle("【官方无损】七里香 (伴奏)")
        assertEquals("七里香", cleanTitle3)

        val cleanArtist = MetadataNormalizer.normalizeArtist("周杰伦 feat. 蔡依林")
        assertEquals("周杰伦", cleanArtist)
    }

    @Test
    fun `perfect match with TTML yields near 100 points and passes`() {
        val result = LyricCandidateScorer.evaluate(
            expectedArtist = "周杰伦",
            expectedTitle = "晴天 (Live)",
            expectedDurationMs = 269000L,
            candidateArtist = "周杰伦",
            candidateTitle = "晴天",
            candidateDurationMs = 269500L, // 偏差 500ms <= 1s
            candidateFormat = LyricFormat.TTML_SYLLABLE
        )

        assertTrue(result.isPassed)
        assertFalse(result.isInstrumental)
        assertEquals(40.0f, result.titleScore, 0.01f)
        assertEquals(30.0f, result.artistScore, 0.01f)
        assertEquals(20.0f, result.durationScore, 0.01f)
        assertEquals(10.0f, result.formatScore, 0.01f)
        assertEquals(100.0f, result.totalScore, 0.01f)
    }

    @Test
    fun `duration deviation exceeding 5 seconds triggers veto rule`() {
        val result = LyricCandidateScorer.evaluate(
            expectedArtist = "Eagles",
            expectedTitle = "Hotel California",
            expectedDurationMs = 391000L, // 6分31秒
            candidateArtist = "Eagles",
            candidateTitle = "Hotel California",
            candidateDurationMs = 420000L, // 7分00秒 (偏差 29秒 > 5秒)
            candidateFormat = LyricFormat.TTML_SYLLABLE
        )

        assertFalse("偏差超 5 秒应一票否决", result.isPassed)
        assertEquals(0.0f, result.totalScore, 0.01f)
        assertNotNull(result.vetoReason)
        assertTrue(result.vetoReason!!.contains("时长偏差超标一票否决"))
    }

    @Test
    fun `unknown duration gets base duration score and can pass on title and artist`() {
        val result = LyricCandidateScorer.evaluate(
            expectedArtist = "周杰伦",
            expectedTitle = "晴天",
            expectedDurationMs = 0L, // 未知时长
            candidateArtist = "周杰伦",
            candidateTitle = "晴天",
            candidateDurationMs = 269000L,
            candidateFormat = LyricFormat.LRC_LINE_SYNC
        )

        assertTrue(result.isPassed)
        assertEquals(40.0f, result.titleScore, 0.01f)
        assertEquals(30.0f, result.artistScore, 0.01f)
        assertEquals(10.0f, result.durationScore, 0.01f) // 保底 10 分
        assertEquals(4.0f, result.formatScore, 0.01f) // LRC_LINE_SYNC 4 分
        assertEquals(84.0f, result.totalScore, 0.01f)
    }

    @Test
    fun `instrumental track is recognized from flag or title`() {
        val result1 = LyricCandidateScorer.evaluate(
            expectedArtist = "Bandari",
            expectedTitle = "The Sounds of Silence",
            expectedDurationMs = 210000L,
            candidateArtist = "Bandari",
            candidateTitle = "The Sounds of Silence",
            candidateDurationMs = 210000L,
            candidateFormat = LyricFormat.PLAIN_TEXT,
            isInstrumentalReported = true
        )
        assertTrue(result1.isPassed)
        assertTrue(result1.isInstrumental)

        val result2 = LyricCandidateScorer.evaluate(
            expectedArtist = "Secret Garden",
            expectedTitle = "Song from a Secret Garden (纯音乐)",
            expectedDurationMs = 180000L,
            candidateArtist = "Secret Garden",
            candidateTitle = "Song from a Secret Garden",
            candidateDurationMs = 180000L,
            candidateFormat = LyricFormat.PLAIN_TEXT
        )
        assertTrue(result2.isPassed)
        assertTrue(result2.isInstrumental)
    }

    @Test
    fun `mismatched candidate fails passing threshold 65`() {
        val result = LyricCandidateScorer.evaluate(
            expectedArtist = "Eagles",
            expectedTitle = "Hotel California",
            expectedDurationMs = 391000L,
            candidateArtist = "Linkin Park",
            candidateTitle = "In the End",
            candidateDurationMs = 216000L,
            candidateFormat = LyricFormat.LRC_LINE_SYNC
        )

        assertFalse(result.isPassed)
        assertEquals(0.0f, result.totalScore, 0.01f) // 触发了时长否决
    }

    @Test
    fun `inverted western name Spears Britney matches Britney Spears with high score`() {
        val result = LyricCandidateScorer.evaluate(
            expectedArtist = "Britney Spears",
            expectedTitle = "Baby One More Time",
            expectedDurationMs = 211000L,
            candidateArtist = "Spears, Britney",
            candidateTitle = "...Baby One More Time",
            candidateDurationMs = 211000L,
            candidateFormat = LyricFormat.LRC_LINE_SYNC
        )

        assertTrue("倒置歌手名应成功通过", result.isPassed)
        assertTrue("歌手相似度得分应接近满分 30分", result.artistScore >= 27.0f)
        assertTrue("歌名相似度得分应接近满分 40分", result.titleScore >= 38.0f)
        assertTrue("总分应远高于 65 分阈值", result.totalScore >= 90.0f)
    }

    @Test
    fun `unknown placeholder artist adapts weights and passes on high title and duration match`() {
        val result = LyricCandidateScorer.evaluate(
            expectedArtist = "",
            expectedTitle = "Baby One More Time",
            expectedDurationMs = 211000L,
            candidateArtist = "Britney Spears",
            candidateTitle = "Baby One More Time",
            candidateDurationMs = 211000L,
            candidateFormat = LyricFormat.LRC_LINE_SYNC
        )

        assertTrue("歌手未知但歌名与时长精准匹配时应通过准入门槛", result.isPassed)
        assertTrue("自适应权重下总分应达到准入门槛", result.totalScore >= 65.0f)
    }
}
