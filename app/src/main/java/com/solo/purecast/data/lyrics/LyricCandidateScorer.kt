package com.solo.purecast.data.lyrics

import com.solo.purecast.model.LyricFormat
import kotlin.math.abs

/**
 * 💡 候选歌词多维打分决策引擎
 * 严格对齐 R7 与 Lyrimuse 架构规范：
 * 歌名相似度 (40分) + 歌手相似度 (30分) + 时长容差 (20分) + 格式权重 (10分)。
 * 设 65 分准入门槛，支持纯音乐判定与硬性时长一票否决。
 */
object LyricCandidateScorer {

    const val PASSING_THRESHOLD = 65.0f

    data class ScoreResult(
        val totalScore: Float,
        val isPassed: Boolean,
        val isInstrumental: Boolean,
        val titleScore: Float,
        val artistScore: Float,
        val durationScore: Float,
        val formatScore: Float,
        val vetoReason: String? = null
    )

    /**
     * 💡 对单条候选歌词进行多维打分与准入裁决
     */
    fun evaluate(
        expectedArtist: String,
        expectedTitle: String,
        expectedDurationMs: Long,
        candidateArtist: String,
        candidateTitle: String,
        candidateDurationMs: Long,
        candidateFormat: LyricFormat,
        isInstrumentalReported: Boolean = false
    ): ScoreResult {
        // 1. 纯音乐判定（LRCLIB instrumental 或标题包含纯音乐/Instrumental）
        val isInstrumental = isInstrumentalReported ||
            isInstrumentalTitle(expectedTitle) ||
            isInstrumentalTitle(candidateTitle)

        if (isInstrumental) {
            return ScoreResult(
                totalScore = 100.0f,
                isPassed = true,
                isInstrumental = true,
                titleScore = 40.0f,
                artistScore = 30.0f,
                durationScore = 20.0f,
                formatScore = 10.0f
            )
        }

        val cleanExpTitle = MetadataNormalizer.normalizeTitle(expectedTitle)
        val cleanCandTitle = MetadataNormalizer.normalizeTitle(candidateTitle)
        val cleanExpArtist = MetadataNormalizer.normalizeArtist(expectedArtist)
        val cleanCandArtist = MetadataNormalizer.normalizeArtist(candidateArtist)

        // 2. 歌名相似度 (40 分)
        val titleSim = MetadataNormalizer.calculateSimilarity(cleanExpTitle, cleanCandTitle)
        val titleScore = titleSim * 40.0f

        // 3. 歌手相似度 (30 分)
        val artistSim = MetadataNormalizer.calculateSimilarity(cleanExpArtist, cleanCandArtist)
        val artistScore = artistSim * 30.0f

        // 4. 时长容差与一票否决裁决 (20 分)
        var durationScore = 0.0f
        var vetoReason: String? = null

        if (expectedDurationMs > 0 && candidateDurationMs > 0) {
            val deltaMs = abs(expectedDurationMs - candidateDurationMs)
            if (deltaMs > 5000L) {
                // 💡 偏差大于 5 秒一票否决淘汰，防止跨版本串扰（如现场版 vs 录音室版）
                vetoReason = "时长偏差超标一票否决: delta=${deltaMs}ms > 5000ms"
                return ScoreResult(
                    totalScore = 0.0f,
                    isPassed = false,
                    isInstrumental = false,
                    titleScore = titleScore,
                    artistScore = artistScore,
                    durationScore = 0.0f,
                    formatScore = 0.0f,
                    vetoReason = vetoReason
                )
            } else if (deltaMs <= 1000L) {
                durationScore = 20.0f
            } else if (deltaMs <= 2000L) {
                durationScore = 16.0f
            } else if (deltaMs <= 3000L) {
                durationScore = 10.0f
            } else {
                durationScore = 0.0f
            }
        } else {
            // 时长未知时给保底分 10 分
            durationScore = 10.0f
        }

        // 5. 格式优先级加权 (10 分)
        val formatScore = when (candidateFormat) {
            LyricFormat.TTML_SYLLABLE -> 10.0f
            LyricFormat.LRC_WORD_SYNC -> 7.0f
            LyricFormat.LRC_LINE_SYNC -> 4.0f
            LyricFormat.PLAIN_TEXT -> 0.0f
        }

        val totalScore = titleScore + artistScore + durationScore + formatScore
        val isPassed = totalScore >= PASSING_THRESHOLD

        return ScoreResult(
            totalScore = totalScore,
            isPassed = isPassed,
            isInstrumental = false,
            titleScore = titleScore,
            artistScore = artistScore,
            durationScore = durationScore,
            formatScore = formatScore
        )
    }

    private fun isInstrumentalTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("instrumental") ||
            lower.contains("纯音乐") ||
            lower.contains("伴奏")
    }
}
