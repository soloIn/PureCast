package com.solo.purecast.data.lyrics

/**
 * 💡 流媒体元数据清洗与归一化工具类
 * 借鉴 Lyrimuse 设计：剥离多源流媒体推流中的版本冗余、合作者标签及特殊括号标点，提升歌词检索命中率与打分稳定性。
 */
object MetadataNormalizer {

    // 匹配各类常见的版本、现场、混音、官方标签模式
    private val VERSION_AND_EXTRA_PATTERNS = listOf(
        Regex("""\s*[\(\[\{（【].*?(feat\.?|ft\.?|featuring|live|remaster(?:ed)?|remix|version|deluxe|edition|edit|ost|官方|无损|伴奏|伴奏版|纯音乐).*?[\)\]\}）】]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–—]\s*(feat\.?|ft\.?|live|remaster(?:ed)?|remix|version|ost).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    )

    // 全角转半角映射表或替换字符
    private val PUNCTUATION_REGEX = Regex("""[·・•,，:：;；/／\-_—~～\(\)\[\]【】（）]""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    /**
     * 💡 清洗歌曲标题
     * 例如："晴天 (Live 2004)" -> "晴天"
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
     * "周杰伦 / 蔡依林" -> 保留主歌手或空格规范化
     */
    fun normalizeArtist(rawArtist: String): String {
        var result = rawArtist.trim()
        if (result.isEmpty()) return ""

        // 移除 feat / ft
        val featRegex = Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
        result = featRegex.replace(result, "")

        // 将斜杠、逗号合作者统一切换为标准空格
        result = PUNCTUATION_REGEX.replace(result, " ")
        result = MULTI_SPACE_REGEX.replace(result, " ").trim()

        return result.lowercase()
    }

    /**
     * 💡 计算两字符串的归一化相似度 (0.0f ~ 1.0f)，基于 Levenshtein 编辑距离
     */
    fun calculateSimilarity(s1: String, s2: String): Float {
        val str1 = s1.trim().lowercase()
        val str2 = s2.trim().lowercase()

        if (str1 == str2) return 1.0f
        if (str1.isEmpty() || str2.isEmpty()) return 0.0f

        val maxLen = maxOf(str1.length, str2.length)
        val distance = levenshteinDistance(str1, str2)

        return (1.0f - distance.toFloat() / maxLen).coerceIn(0.0f, 1.0f)
    }

    /**
     * 💡 经典双行空间优化的 Levenshtein 距离算法
     */
    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        var v0 = IntArray(rhs.length + 1) { it }
        var v1 = IntArray(rhs.length + 1)

        for (i in lhs.indices) {
            v1[0] = i + 1
            for (j in rhs.indices) {
                val cost = if (lhs[i] == rhs[j]) 0 else 1
                v1[j + 1] = minOf(v1[j] + 1, v0[j + 1] + 1, v0[j] + cost)
            }
            val temp = v0
            v0 = v1
            v1 = temp
        }

        return v0[rhs.length]
    }
}
