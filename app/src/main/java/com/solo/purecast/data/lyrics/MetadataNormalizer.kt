package com.solo.purecast.data.lyrics

import me.xdrop.fuzzywuzzy.FuzzySearch

/**
 * 💡 流媒体元数据清洗与归一化工具类
 * 借鉴 Lyrimuse 设计：剥离多源流媒体推流中的版本冗余、合作者标签及特殊括号标点，
 * 并结合工业级 JavaWuzzy NLP 模糊字符串匹配，提升歌词检索命中率与打分稳定性。
 */
object MetadataNormalizer {

    // 匹配各类常见的版本、现场、混音、官方标签模式
    private val VERSION_AND_EXTRA_PATTERNS = listOf(
        Regex("""\s*[\(\[\{（【].*?(feat\.?|ft\.?|featuring|live|remaster(?:ed)?|remix|version|deluxe|edition|edit|ost|官方|无损|伴奏|伴奏版|纯音乐).*?[\)\]\}）】]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–—]\s*(feat\.?|ft\.?|live|remaster(?:ed)?|remix|version|ost).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    )

    // 全角转半角映射表或替换字符（增加省略号、连续句点、单双引号）
    private val PUNCTUATION_REGEX = Regex("""[·・•,，:：;；/／\-_—~～\(\)\[\]【】（）"“”'‘’`\.\.…]+""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val FEAT_REGEX = Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)

    // 占位歌手识别集合
    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown", "unknown artist", "<unknown>", "未知", "未知歌手", "群星", "various artists", "va"
    )

    /**
     * 💡 判断是否为未知或占位歌手
     */
    fun isPlaceholderArtist(artist: String): Boolean {
        val clean = artist.trim().lowercase()
        return clean.isEmpty() || clean in PLACEHOLDER_ARTISTS
    }

    /**
     * 💡 清洗歌曲标题
     * 例如："晴天 (Live 2004)" -> "晴天"
     * "...Baby One More Time" -> "baby one more time"
     * "Hotel California - Remastered 2024" -> "Hotel California"
     */
    fun normalizeTitle(rawTitle: String): String {
        var result = rawTitle.trim()
        if (result.isEmpty()) return ""

        for (pattern in VERSION_AND_EXTRA_PATTERNS) {
            result = pattern.replace(result, "")
        }

        result = PUNCTUATION_REGEX.replace(result, " ")
        result = MULTI_SPACE_REGEX.replace(result, " ").trim()

        return result.lowercase()
    }

    /**
     * 💡 清洗歌手名称
     * 例如："Eagles feat. Someone" -> "Eagles"
     * "Spears, Britney" -> "spears britney"
     * "周杰伦 / 蔡依林" -> 保留主歌手或空格规范化
     */
    fun normalizeArtist(rawArtist: String): String {
        var result = rawArtist.trim()
        if (result.isEmpty()) return ""

        // 移除 feat / ft
        result = FEAT_REGEX.replace(result, "")

        // 将斜杠、逗号合作者统一切换为标准空格
        result = PUNCTUATION_REGEX.replace(result, " ")
        result = MULTI_SPACE_REGEX.replace(result, " ").trim()

        return result.lowercase()
    }

    /**
     * 💡 计算两字符串的综合相似度 (0.0f ~ 1.0f)
     * 基于 JavaWuzzy 的 WeightedRatio (WRatio)：
     * 综合评估完全匹配、局部包含（Partial Ratio）、词序无关（Token Sort）与长度惩罚，天然抵抗副标题及拼写容差。
     */
    fun calculateSimilarity(s1: String, s2: String): Float {
        val str1 = s1.trim().lowercase()
        val str2 = s2.trim().lowercase()

        if (str1 == str2) return 1.0f
        if (str1.isEmpty() || str2.isEmpty()) return 0.0f

        val ratio = FuzzySearch.weightedRatio(str1, str2)
        return (ratio / 100.0f).coerceIn(0.0f, 1.0f)
    }

    /**
     * 💡 计算两字符串的词集交集相似度 (0.0f ~ 1.0f)
     * 基于 JavaWuzzy 的 TokenSetRatio：
     * 天然支持西方倒置姓名（如 "Spears, Britney" vs "Britney Spears"）、
     * 合作者多歌手组合、以及候选歌手字段混入歌名脏数据（如 "Spears, Britney - Baby One More Time"）。
     */
    fun calculateTokenSetSimilarity(s1: String, s2: String): Float {
        val str1 = s1.trim().lowercase()
        val str2 = s2.trim().lowercase()

        if (str1 == str2) return 1.0f
        if (str1.isEmpty() || str2.isEmpty()) return 0.0f

        val ratio = FuzzySearch.tokenSetRatio(str1, str2)
        return (ratio / 100.0f).coerceIn(0.0f, 1.0f)
    }
}
