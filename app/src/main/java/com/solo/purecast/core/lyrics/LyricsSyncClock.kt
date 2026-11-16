package com.solo.purecast.core.lyrics

import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import kotlin.math.abs

/**
 * 💡 单调时钟高频帧间线性插值与回弹抑制引擎
 * 遵循 R10、R11 与 Finding 3, 11, 14 契约：
 * 1. 抹平播放器 500ms~1000ms 离散进度上报，向 Compose TV 提供 60fps/120fps 平滑连续时间轴；
 * 2. 250ms 死区方向性拦截，放行合法快退 (Finding 3)；
 * 3. 暂停瞬间单调冻结，杜绝时钟向后回跳 (Finding 3)；
 * 4. 前奏期 (Intro) 明确返回 -1，杜绝未开口提前高亮与焦点错位 (Finding 11, 14)；
 * 5. 基于 startMs 单调递增的容错二分查找，稳健支持交叠行与和声行 (Finding 14)。
 */
class LyricsSyncClock(
    private val maxExtrapolationMs: Long = DEFAULT_MAX_EXTRAPOLATION_MS,
    private val seekThresholdMs: Long = DEFAULT_SEEK_THRESHOLD_MS,
    private val seekDeadbandDurationMs: Long = DEFAULT_SEEK_DEADBAND_MS
) {

    data class ActiveLineState(
        val lineIndex: Int,
        val lineProgressFraction: Float,
        val activeWordIndex: Int = -1,
        val wordProgressFraction: Float = 0f
    )

    private var reportedPositionMs: Long = 0L
    private var anchorNanoTime: Long = 0L
    private var isPlaying: Boolean = false

    private var lastSeekTimestampNano: Long = 0L
    private var seekTargetPositionMs: Long = 0L
    private var isSeekForward: Boolean = true

    // 💡 记录最高推演时间，用于暂停瞬间防止时钟倒退
    private var lastInterpolatedTimeMs: Long = 0L

    /**
     * 💡 接收播放器底层进度推送更新
     */
    fun updatePosition(
        newPositionMs: Long,
        playing: Boolean,
        nowNanoTime: Long = System.nanoTime()
    ) {
        val delta = newPositionMs - reportedPositionMs

        // 1. 检测大跨度进度突变 (Seek 寻道)
        if (abs(delta) > seekThresholdMs) {
            isSeekForward = delta > 0
            lastSeekTimestampNano = nowNanoTime
            seekTargetPositionMs = newPositionMs
            Logger.lyrics.i(
                TAG,
                "检测到进度跳变 (Seek): $reportedPositionMs -> $newPositionMs (delta=${delta}ms, forward=$isSeekForward)"
            )
        } else {
            // 2. 检查是否处于 250ms Seek 回弹抑制死区内 (Finding 3)
            val elapsedSinceSeekMs = (nowNanoTime - lastSeekTimestampNano) / 1_000_000L
            if (elapsedSinceSeekMs < seekDeadbandDurationMs) {
                // 仅拦截与 Seek 意图反向的残余旧包回跳抖动，放行合法连续快退/快进
                val isJitterBounce = if (isSeekForward) {
                    newPositionMs < seekTargetPositionMs
                } else {
                    newPositionMs > seekTargetPositionMs
                }
                if (isJitterBounce) {
                    Logger.lyrics.d(
                        TAG,
                        "Seek 死区拦截回跳抖动包: target=${seekTargetPositionMs}ms, incoming=${newPositionMs}ms, forward=$isSeekForward"
                    )
                    return
                }
            }
        }

        // 3. 暂停瞬间单调冻结防回跳 (Finding 3)
        if (!playing && isPlaying) {
            // 从播放转为暂停：以当前推演出的平滑时间或上报时间的最大值冻结，杜绝时钟瞬间倒退
            val frozenTime = maxOf(newPositionMs, lastInterpolatedTimeMs)
            reportedPositionMs = frozenTime
            lastInterpolatedTimeMs = frozenTime
        } else {
            reportedPositionMs = newPositionMs
            lastInterpolatedTimeMs = newPositionMs
        }

        anchorNanoTime = nowNanoTime
        isPlaying = playing
    }

    /**
     * 💡 计算当前 VSYNC 渲染帧的推演毫秒时间 (60fps/120fps)
     */
    fun getCurrentInterpolatedTimeMs(nowNanoTime: Long = System.nanoTime()): Long {
        if (!isPlaying || anchorNanoTime == 0L) {
            // 暂停或未启动推流时，时钟原地冻结
            return reportedPositionMs
        }

        val elapsedMs = (nowNanoTime - anchorNanoTime) / 1_000_000L
        // 限制最大外推硬上限（防止播放器卡顿引起过度跑偏）
        val extrapolatedMs = elapsedMs.coerceIn(0L, maxExtrapolationMs)
        val calculated = reportedPositionMs + extrapolatedMs

        // 保持单调性不减
        val monotone = maxOf(lastInterpolatedTimeMs, calculated)
        lastInterpolatedTimeMs = monotone
        return monotone
    }

    /**
     * 💡 针对当前推演时间检索当前高亮行并计算逐字/逐行进度比例
     * 遵循前奏期明确返回 -1 (Finding 11, 14) 及基于 startMs 的单调二分定位 (Finding 14)
     */
    fun calculateActiveLineState(
        timeline: LyricTimeline,
        currentTimeMs: Long
    ): ActiveLineState {
        val lines = timeline.lines
        if (lines.isEmpty()) {
            return ActiveLineState(lineIndex = -1, lineProgressFraction = 0f)
        }

        // 💡 Finding 11 & 14: 前奏期判定 —— 若当前时间尚未到达第一句歌词的起始时间，明确返回 -1
        if (currentTimeMs < lines[0].startMs) {
            return ActiveLineState(lineIndex = -1, lineProgressFraction = 0f)
        }

        // 💡 Finding 14: 基于单调递增的 startMs 进行二分搜索，找到最后一个 startMs <= currentTimeMs 的行
        var low = 0
        var high = lines.size - 1
        var candidateIndex = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= currentTimeMs) {
                candidateIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        // 检查是否有交叠行或和声行，若存在优先选取精确包含 currentTimeMs 的行
        var activeIndex = candidateIndex
        for (offset in -1..1) {
            val testIdx = candidateIndex + offset
            if (testIdx in lines.indices) {
                val l = lines[testIdx]
                if (currentTimeMs in l.startMs..l.endMs) {
                    activeIndex = testIdx
                    break
                }
            }
        }

        val activeLine = lines[activeIndex]
        val lineDuration = (activeLine.endMs - activeLine.startMs).coerceAtLeast(1L)
        val lineElapsed = (currentTimeMs - activeLine.startMs).coerceIn(0L, lineDuration)
        val lineProgressFraction = (lineElapsed.toFloat() / lineDuration).coerceIn(0f, 1f)

        // 2. 音节逐字时间戳解析 (若存在逐字标记)
        var activeWordIndex = -1
        var wordProgressFraction = 0f

        val words = activeLine.words
        if (!words.isNullOrEmpty()) {
            for (j in words.indices) {
                val w = words[j]
                if (currentTimeMs in w.startMs..w.endMs) {
                    activeWordIndex = j
                    val wDuration = (w.endMs - w.startMs).coerceAtLeast(1L)
                    val wElapsed = (currentTimeMs - w.startMs).coerceIn(0L, wDuration)
                    wordProgressFraction = (wElapsed.toFloat() / wDuration).coerceIn(0f, 1f)
                    break
                } else if (currentTimeMs > w.endMs) {
                    activeWordIndex = j
                    wordProgressFraction = 1f
                }
            }
        }

        return ActiveLineState(
            lineIndex = activeIndex,
            lineProgressFraction = lineProgressFraction,
            activeWordIndex = activeWordIndex,
            wordProgressFraction = wordProgressFraction
        )
    }

    /**
     * 💡 计算单个音节/字的流式扫光进度 (0.0f ~ 1.0f)
     */
    fun calculateWordProgress(word: LyricWord, currentTimeMs: Long): Float {
        if (currentTimeMs >= word.endMs) return 1f
        if (currentTimeMs <= word.startMs) return 0f
        val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
        return ((currentTimeMs - word.startMs).toFloat() / duration).coerceIn(0f, 1f)
    }

    fun reset() {
        reportedPositionMs = 0L
        anchorNanoTime = 0L
        isPlaying = false
        lastSeekTimestampNano = 0L
        seekTargetPositionMs = 0L
        isSeekForward = true
        lastInterpolatedTimeMs = 0L
    }

    companion object {
        private const val TAG = "LyricsSyncClock"
        const val DEFAULT_MAX_EXTRAPOLATION_MS = 1200L
        const val DEFAULT_SEEK_THRESHOLD_MS = 1500L
        const val DEFAULT_SEEK_DEADBAND_MS = 250L
    }
}
