package com.solo.purecast.data.lyrics

import com.solo.purecast.model.LyricFormat
import kotlin.math.abs

/**
 * 💡 候选歌词多维打分决策引擎
 * 严格对齐 R7 与 Lyrimuse 架构规范：
 * 歌名相似度 (40分) + 歌手相似度 (30分) + 时长容差 (20分) + 格式权重 (10分)。
 * 设 65 分准入门槛，支持纯音乐优先判定与尾奏智能容差。
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
    ) {
        val accepted: Boolean
            get() = isPassed
    }

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
        isInstrumental: Boolean = false,
        isInstrumentalReported: Boolean = false
    ): ScoreResult {
        // 1. 优先纯音乐判定（在进行任何标题剥离前执行，杜绝归一化冲刷关键字）
        val isInst = isInstrumental || isInstrumentalReported ||
            isInstrumentalTitle(expectedTitle) ||
            isInstrumentalTitle(candidateTitle)

        if (isInst) {
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
        val isArtistUnknown = MetadataNormalizer.isPlaceholderArtist(expectedArtist)

        // 2. 歌名相似度 (若歌手已知为 40 分；若歌手未知，自适应提升权重至 60 分)
        val titleSim = MetadataNormalizer.calculateSimilarity(cleanExpTitle, cleanCandTitle)
        val titleScore = if (isArtistUnknown) titleSim * 60.0f else titleSim * 40.0f

        // 3. 歌手相似度 (若歌手未知，自适应分数为 0)
        val artistSim = if (isArtistUnknown) 0.0f else MetadataNormalizer.calculateSimilarity(cleanExpArtist, cleanCandArtist)
        val artistScore = artistSim * 30.0f

        // 4. 时长容差与智能一票否决裁决 (若歌手已知满分 20 分；若歌手未知自适应满分 30 分)
        val durationWeightMultiplier = if (isArtistUnknown) 1.5f else 1.0f
        var durationScore = 0.0f
        var vetoReason: String? = null

        if (expectedDurationMs > 0 && candidateDurationMs > 0) {
            val deltaMs = expectedDurationMs - candidateDurationMs

            // 💡 智能尾奏容忍机制 (Finding 2)：
            // 若歌词由末句推导时长，且 candidateDurationMs < expectedDurationMs（典型的歌曲尾奏空白），
            // 只要歌名和歌手相似度极高 (>=85% 或歌手未知时歌名极高) 且尾奏时差在 60 秒以内，判定为合法尾奏，不予一票否决！
            val isHighMetadataMatch = if (isArtistUnknown) {
                titleScore >= 50.0f
            } else {
                titleScore >= 34.0f && artistScore >= 24.0f
            }
            val isAcceptableOutro = deltaMs in 0L..60_000L && isHighMetadataMatch

            if (isAcceptableOutro) {
                // 合法尾奏，根据尾奏时长给予递减时长分
                val baseDur = when {
                    deltaMs <= 5000L -> 18.0f
                    deltaMs <= 15000L -> 14.0f
                    deltaMs <= 30000L -> 10.0f
                    else -> 6.0f
                }
                durationScore = baseDur * durationWeightMultiplier
            } else if (abs(deltaMs) > 5000L) {
                // 歌词比音轨更长超过 5 秒（严重跨版本/加长版），或非高匹配歌曲短超过 5 秒：硬性一票否决
                vetoReason = "时长偏差超标一票否决: delta=${deltaMs}ms"
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
            } else if (abs(deltaMs) <= 1000L) {
                durationScore = 20.0f * durationWeightMultiplier
            } else if (abs(deltaMs) <= 2000L) {
                durationScore = 16.0f * durationWeightMultiplier
            } else if (abs(deltaMs) <= 3000L) {
                durationScore = 10.0f * durationWeightMultiplier
            } else {
                durationScore = 5.0f * durationWeightMultiplier
            }
        } else {
            // 时长未知时给保底分
            durationScore = 10.0f * durationWeightMultiplier
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

    fun isInstrumentalTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("instrumental") ||
            lower.contains("纯音乐") ||
            lower.contains("伴奏") ||
            lower.contains("karaoke") ||
            lower.contains("off vocal") ||
            lower.contains("backing track")
    }
}
