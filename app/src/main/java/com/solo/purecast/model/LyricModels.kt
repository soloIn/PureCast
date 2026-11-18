package com.solo.purecast.model

/**
 * 💡 歌词格式枚举
 */
enum class LyricFormat {
    /** AMLL TTML 格式：支持音节级（逐字）高精时间戳与双语翻译 */
    TTML_SYLLABLE,

    /** LRC 逐字打点（Enhanced LRC）：支持词级时间戳 */
    LRC_WORD_SYNC,

    /** 标准 LRC：逐行时间戳 */
    LRC_LINE_SYNC,

    /** 纯文本歌词：无时间戳，仅支持静态浏览 */
    PLAIN_TEXT
}

/**
 * 💡 歌词 UI 状态机（供 TV UI 只读订阅与渲染）
 */
sealed interface LyricUiState {
    /** 空闲/待机状态 */
    object Idle : LyricUiState

    /** 正在检索或加载歌词 */
    data class Loading(val trackGeneration: Long) : LyricUiState

    /** 成功获取并就绪时间轴 */
    data class Success(
        val trackGeneration: Long,
        val timeline: LyricTimeline,
        val activeLineIndex: Int = 0,
        val lineProgressFraction: Float = 0f
    ) : LyricUiState

    /** 纯音乐（Instrumental），大屏显示专属艺术排版 */
    data class Instrumental(val trackGeneration: Long) : LyricUiState

    /** 全链路未检索到有效歌词 */
    data class NotFound(val trackGeneration: Long) : LyricUiState
}

/**
 * 💡 单个音节/字的时间戳信息（用于逐字卡拉OK平滑流式擦除点亮）
 */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 💡 歌词单行领域模型
 */
data class LyricLine(
    val lineIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val mainText: String,
    val translationText: String? = null,
    val words: List<LyricWord>? = null
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)

    val hasWords: Boolean
        get() = !words.isNullOrEmpty()

    val hasTranslation: Boolean
        get() = !translationText.isNullOrBlank()
}

/**
 * 💡 不可变完整歌词时间轴模型（Single Source of Truth）
 */
data class LyricTimeline(
    val source: String,
    val format: LyricFormat,
    val isInstrumental: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    val durationMs: Long = 0L
) {
    val isEmpty: Boolean
        get() = lines.isEmpty() && !isInstrumental

    val lineCount: Int
        get() = lines.size
}
