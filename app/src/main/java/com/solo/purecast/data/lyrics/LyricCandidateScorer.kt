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
        // 1. 纯音乐意图判定（保留原始标题信息，严禁拿候选伴奏版反推原唱为纯音乐）
        val expectedIsInst = isInstrumentalTitle(expectedTitle)
        val candidateTitleIsInst = isInstrumentalTitle(candidateTitle)
        val candidateReportedInst = isInstrumental || isInstrumentalReported

        // 💡 关键防误判护栏：待播曲目本身不是伴奏/纯音乐，而候选标题明确是伴奏/卡拉OK/纯音乐版 -> 一票否决
        if (!expectedIsInst && candidateTitleIsInst) {
            return ScoreResult(
                totalScore = 0.0f,
                isPassed = false,
                isInstrumental = false,
                titleScore = 0.0f,
                artistScore = 0.0f,
                durationScore = 0.0f,
                formatScore = 0.0f,
                vetoReason = "原唱曲目拒绝匹配伴奏/纯音乐版本候选: $candidateTitle"
            )
        }

        val cleanExpTitle = MetadataNormalizer.normalizeTitle(expectedTitle)
        val cleanCandTitle = MetadataNormalizer.normalizeTitle(candidateTitle)
        val cleanExpArtist = MetadataNormalizer.normalizeArtist(expectedArtist)
        val cleanCandArtist = MetadataNormalizer.normalizeArtist(candidateArtist)
        val isArtistUnknown = MetadataNormalizer.isPlaceholderArtist(expectedArtist)

        // 💡 续集/分部编号强校验（例如 "Love the Way You Lie, Pt. 2" 绝不允许命中 "Love the Way You Lie" Part 1）
        val expPartTag = extractPartTag(cleanExpTitle)
        val candPartTag = extractPartTag(cleanCandTitle)
        if (expPartTag != candPartTag) {
            return ScoreResult(
                totalScore = 0.0f,
                isPassed = false,
                isInstrumental = false,
                titleScore = 0.0f,
                artistScore = 0.0f,
                durationScore = 0.0f,
                formatScore = 0.0f,
                vetoReason = "续集/分部编号不匹配: expected=$expPartTag, candidate=$candPartTag"
            )
        }

        // 2. 歌名相似度 (若歌手已知为 40 分；若歌手未知，自适应提升权重至 60 分)
        val titleSim = MetadataNormalizer.calculateSimilarity(cleanExpTitle, cleanCandTitle)
        val titleScore = if (isArtistUnknown) titleSim * 60.0f else titleSim * 40.0f

        // 3. 歌手相似度 (若歌手未知，自适应分数为 0；已知时采用 tokenSetRatio 天然容忍倒置、脏数据与多歌手)
        val artistSim = if (isArtistUnknown) 0.0f else MetadataNormalizer.calculateTokenSetSimilarity(cleanExpArtist, cleanCandArtist)
        val artistScore = artistSim * 30.0f

        // 4. 时长容差与智能一票否决裁决 (若歌手已知满分 20 分；若歌手未知自适应满分 30 分)
        val durationWeightMultiplier = if (isArtistUnknown) 1.5f else 1.0f
        var durationScore = 0.0f
        var vetoReason: String? = null

        if (expectedDurationMs > 0 && candidateDurationMs > 0) {
            val deltaMs = expectedDurationMs - candidateDurationMs

            // 💡 智能尾奏容忍机制 (Finding 2)：
            // 仅对携带歌词时间轴的非纯音乐候选启用尾奏容忍；远端纯音乐标记条目无末句推导时长，必须严格满足 ±5s 时长匹配
            val isHighMetadataMatch = if (isArtistUnknown) {
                titleScore >= 50.0f
            } else {
                titleScore >= 34.0f && artistScore >= 24.0f
            }
            val isAcceptableOutro = !candidateReportedInst && deltaMs in 0L..60_000L && isHighMetadataMatch

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

        // 5. 格式优先级加权 (10 分)：纯音乐候选格式分严格为 0，确保同列表中任意合格同步歌词永远优先胜出
        val formatScore = if (candidateReportedInst) {
            0.0f
        } else {
            when (candidateFormat) {
                LyricFormat.TTML_SYLLABLE -> 10.0f
                LyricFormat.LRC_WORD_SYNC -> 7.0f
                LyricFormat.LRC_LINE_SYNC -> 4.0f
                LyricFormat.PLAIN_TEXT -> 0.0f
            }
        }

        val totalScore = titleScore + artistScore + durationScore + formatScore
        val isPassed = totalScore >= PASSING_THRESHOLD
        val resolvedInstrumental = isPassed && (expectedIsInst || candidateReportedInst)

        return ScoreResult(
            totalScore = totalScore,
            isPassed = isPassed,
            isInstrumental = resolvedInstrumental,
            titleScore = titleScore,
            artistScore = artistScore,
            durationScore = durationScore,
            formatScore = formatScore
        )
    }

    private val INSTRUMENTAL_EN_REGEX = Regex(
        """\b(?:instrumental|karaoke|off[\s-]?vocal|backing\s+track)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isInstrumentalTitle(title: String): Boolean {
        if (title.isBlank()) return false
        return INSTRUMENTAL_EN_REGEX.containsMatchIn(title) ||
            title.contains("纯音乐") ||
            title.contains("伴奏")
    }

    private val PART_TAG_REGEX = Regex("""\b(?:pt|part)\s*(\d+|ii|iii|iv|v)\b""", RegexOption.IGNORE_CASE)

    private fun extractPartTag(normalizedTitle: String): String? {
        val match = PART_TAG_REGEX.find(normalizedTitle) ?: return null
        return when (val raw = match.groupValues[1].lowercase()) {
            "1", "i" -> null
            "ii" -> "2"
            "iii" -> "3"
            "iv" -> "4"
            "v" -> "5"
            else -> raw
        }
    }
}
