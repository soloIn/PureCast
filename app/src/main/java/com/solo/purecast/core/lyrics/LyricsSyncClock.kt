package com.solo.purecast.core.lyrics

import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import kotlin.math.abs

/**
 * 💡 单调时钟高频帧间线性插值与回弹抑制引擎
 * 遵循 R10、R11 与 KTD4 契约：
 * 抹平播放器 500ms~1000ms 离散进度上报，向 Compose TV 提供 60fps/120fps 平滑连续时间轴，
 * 并以 250ms 死区抑制网络缓冲引起的向后微跳回弹。
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

    /**
     * 💡 接收播放器底层进度推送更新
     */
    fun updatePosition(
        newPositionMs: Long,
        playing: Boolean,
        nowNanoTime: Long = System.nanoTime()
    ) {
        val delta = newPositionMs - reportedPositionMs

        // 1. 检查是否处于 250ms Seek 回弹抑制死区内 (R11, AE5)
        val elapsedSinceSeekMs = (nowNanoTime - lastSeekTimestampNano) / 1_000_000L
        if (elapsedSinceSeekMs < seekDeadbandDurationMs) {
            // 若新到达的进度落后于当前 Seek 目标进度（典型的网络 JitterBuffer 回跳），执行死区拦截
            if (newPositionMs < seekTargetPositionMs) {
                Logger.lyrics.d(TAG, "Seek 死区拦截回跳抖动包: target=${seekTargetPositionMs}ms, incoming=${newPositionMs}ms")
                return
            }
        }

        // 2. 检测大跨度进度突变 (Seek 寻道)
        if (abs(delta) > seekThresholdMs) {
            Logger.lyrics.i(TAG, "检测到进度跳变 (Seek): $reportedPositionMs -> $newPositionMs (delta=${delta}ms)")
            lastSeekTimestampNano = nowNanoTime
            seekTargetPositionMs = newPositionMs
        }

        reportedPositionMs = newPositionMs
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

        return reportedPositionMs + extrapolatedMs
    }

    /**
     * 💡 针对当前推演时间二分检索当前高亮行并计算逐字/逐行进度比例
     */
    fun calculateActiveLineState(
        timeline: LyricTimeline,
        currentTimeMs: Long
    ): ActiveLineState {
        val lines = timeline.lines
        if (lines.isEmpty()) {
            return ActiveLineState(lineIndex = 0, lineProgressFraction = 0f)
        }

        // 1. 二分查找定位所在行
        var low = 0
        var high = lines.size - 1
        var targetIndex = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lines[mid]

            if (currentTimeMs < line.startMs) {
                high = mid - 1
            } else if (currentTimeMs >= line.endMs) {
                low = mid + 1
            } else {
                targetIndex = mid
                break
            }
        }

        // 若落在行与行之间的间隙，定位到上一行以保持驻留视觉
        val activeIndex = when {
            targetIndex != -1 -> targetIndex
            high >= 0 -> high
            else -> 0
        }.coerceIn(0, lines.size - 1)

        val activeLine = lines[activeIndex]
        val lineDuration = (activeLine.endMs - activeLine.startMs).coerceAtLeast(1L)
        val lineElapsed = (currentTimeMs - activeLine.startMs).coerceIn(0L, lineDuration)
        val lineProgressFraction = (lineElapsed.toFloat() / lineDuration).coerceIn(0f, 1f)

        // 2. 音节逐字时间戳解析 (若存在)
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
    }

    companion object {
        private const val TAG = "LyricsSyncClock"
        const val DEFAULT_MAX_EXTRAPOLATION_MS = 1200L
        const val DEFAULT_SEEK_THRESHOLD_MS = 1500L
        const val DEFAULT_SEEK_DEADBAND_MS = 250L
    }
}
