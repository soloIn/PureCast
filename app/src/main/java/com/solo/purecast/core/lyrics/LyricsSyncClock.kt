package com.solo.purecast.core.lyrics

import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import kotlin.math.abs

/**
 * 💡 单调时钟高频帧间线性插值引擎
 * 严格遵照“PureCast 播放进度是唯一真实来源”的设计原则：
 * 1. 抹平播放器 250ms~500ms 离散进度上报，向 Compose TV 提供 60fps 平滑连续时间轴；
 * 2. 任何大于 350ms 的进度跳变（iPhone 拖动寻道/切歌/快进/快退）均立即 0 延迟瞬时重定位，彻底杜绝向前拖动滞后；
 * 3. 暂停瞬间单调冻结，恢复瞬间平滑接续；
 * 4. 前奏期 (Intro) 明确返回 -1，未开口时不提前高亮；
 * 5. 基于 startMs 单调递增的容错二分查找，稳健支持交叠行与和声行。
 */
class LyricsSyncClock(
    private val maxExtrapolationMs: Long = DEFAULT_MAX_EXTRAPOLATION_MS,
    private val jumpThresholdMs: Long = DEFAULT_JUMP_THRESHOLD_MS,
    private val ringBufferDelayMsProvider: () -> Long = { 0L }
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

    // 💡 记录当前推演时间，用于暂停瞬间防止时钟倒退
    private var lastInterpolatedTimeMs: Long = 0L

    /**
     * 💡 显式时钟硬对齐 (Hard Align)
     * 用于切歌换代、显式 Seek 寻道完成或重连时，瞬时排空历史插值缓存，
     * 毫秒级硬重置锚点时间，彻底杜绝向前拖动滞后与跨曲目残存。
     */
    fun hardAlign(targetPositionMs: Long, playing: Boolean, nowNanoTime: Long = System.nanoTime()) {
        val clamped = targetPositionMs.coerceAtLeast(0L)
        reportedPositionMs = clamped
        lastInterpolatedTimeMs = clamped
        // 💡 硬件出声延迟对齐：开播时 (pos == 0L 且处于播放态) 锚点推迟 100ms，对齐声卡 100ms 硬件预充出声时钟
        anchorNanoTime = if (playing) {
            if (clamped == 0L) nowNanoTime + 100_000_000L else nowNanoTime
        } else 0L
        isPlaying = playing
        Logger.lyrics.i(TAG, "时钟硬对齐完成 (Hard Align): target=${clamped}ms, playing=$playing")
    }

    /**
     * 💡 接收播放器底层进度推送更新
     * 保持与 PureCast 进度条和 iPhone 播放进度严格一致
     */
    fun updatePosition(
        newPositionMs: Long,
        playing: Boolean,
        nowNanoTime: Long = System.nanoTime()
    ) {
        val currentEstimatedTime = if (isPlaying && anchorNanoTime > 0L) {
            val elapsedMs = (nowNanoTime - anchorNanoTime).coerceAtLeast(0L) / 1_000_000L
            reportedPositionMs + elapsedMs.coerceIn(0L, maxExtrapolationMs)
        } else {
            reportedPositionMs
        }

        val jumpDelta = abs(newPositionMs - currentEstimatedTime)

        // 💡 修复开播初期 iPhone 迟到的极小进度上报误判为 Seek 倒退的缺陷：
        // 切歌开播初始 3.5 秒内，若新上报的进度极小 (<= 1500ms) 且落后于当前已平滑推演的时钟，
        // 判定为推流端未就绪的延迟校准包，绝不触发向后 Seek 硬重置；将推演基准重设为当前推演时间，维持单调平滑推进，根除永久滞后 1.5 秒的根因！
        val isInitialWarmupLag = currentEstimatedTime <= 3500L && newPositionMs <= 1500L && newPositionMs < currentEstimatedTime
        if (isInitialWarmupLag) {
            Logger.lyrics.d(
                TAG,
                "开播初期推流端进度滞后 (currentEst=${currentEstimatedTime}ms, incoming=${newPositionMs}ms)，重设基准为当前推演时间，维持单调平滑推进"
            )
            reportedPositionMs = currentEstimatedTime
            anchorNanoTime = nowNanoTime
            return
        }

        // 1. 检测进度跳变 (iPhone 拖动寻道，无论是向前还是向后拖动超过阈值)
        if (jumpDelta > jumpThresholdMs) {
            Logger.lyrics.i(
                TAG,
                "进度跳变寻道 (Seek): $currentEstimatedTime -> $newPositionMs (delta=${newPositionMs - currentEstimatedTime}ms)"
            )
            hardAlign(newPositionMs, playing, nowNanoTime)
            return
        }

        // 2. 状态切换或正常推进更新
        if (!playing && isPlaying) {
            // 播放转暂停/缓冲：若伴随向前跳变则无条件硬采纳新值，杜绝 maxOf 误杀向前拖动；仅在微小步进时防时钟倒退
            val frozenTime = if (newPositionMs < lastInterpolatedTimeMs - jumpThresholdMs) {
                newPositionMs
            } else {
                maxOf(newPositionMs, lastInterpolatedTimeMs)
            }
            reportedPositionMs = frozenTime
            lastInterpolatedTimeMs = frozenTime
            anchorNanoTime = 0L
        } else if (playing && !isPlaying) {
            // 暂停转播放：重新锚定当前时间 (若在 0ms 开播推迟 100ms 预充)
            reportedPositionMs = newPositionMs
            lastInterpolatedTimeMs = newPositionMs
            anchorNanoTime = if (newPositionMs == 0L) nowNanoTime + 100_000_000L else nowNanoTime
        } else {
            // 正常连续推流更新基准：加入 50ms 负向微抖动保护，防止网络或调度偶发抖动引发扫光顿挫
            // 💡 若仍处于 100ms 预充期 (newPositionMs == 0L 且 nowNanoTime < anchorNanoTime)，保留未来锚点不被提前覆盖
            val isPrefillStillPending = (newPositionMs == 0L && nowNanoTime < anchorNanoTime)
            reportedPositionMs = maxOf(newPositionMs, currentEstimatedTime - 50L)
            if (!isPrefillStillPending) {
                anchorNanoTime = nowNanoTime
            }
        }

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

        // 💡 nowNanoTime 可能早于 anchorNanoTime (当处于 100ms 硬件预充期时)，此时推演增量钳制为 0
        val elapsedNano = nowNanoTime - anchorNanoTime
        val rawTimeMs = if (elapsedNano <= 0L) {
            reportedPositionMs
        } else {
            val elapsedMs = elapsedNano / 1_000_000L
            val extrapolatedMs = elapsedMs.coerceIn(0L, maxExtrapolationMs)
            reportedPositionMs + extrapolatedMs
        }

        // 💡 精准扣除 USB 环形缓冲区未消费数据物理延迟 (通常为 100ms~250ms)，确保 UI 高亮与扬声器声音毫秒级咬合
        val ringDelayMs = ringBufferDelayMsProvider().coerceIn(0L, 500L)
        val hardwareAdjustedTime = (rawTimeMs - ringDelayMs).coerceAtLeast(0L)

        // 💡 保证推演时钟绝对单调不倒退：当底层上报因调度略微落后时，原地微等几十毫秒待其追平，绝不逆向回跳
        val monotonic = maxOf(lastInterpolatedTimeMs, hardwareAdjustedTime)
        lastInterpolatedTimeMs = monotonic
        return monotonic
    }

    /**
     * 💡 针对当前推演时间检索当前高亮行并计算逐字/逐行进度比例
     */
    fun calculateActiveLineState(
        timeline: LyricTimeline,
        currentTimeMs: Long
    ): ActiveLineState {
        val lines = timeline.lines
        if (lines.isEmpty()) {
            return ActiveLineState(lineIndex = -1, lineProgressFraction = 0f)
        }

        // 前奏期判定 —— 若当前时间尚未到达第一句歌词的起始时间，明确返回 -1
        if (currentTimeMs < lines[0].startMs) {
            return ActiveLineState(lineIndex = -1, lineProgressFraction = 0f)
        }

        // 基于单调递增的 startMs 进行二分搜索，找到最后一个 startMs <= currentTimeMs 的行
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

        // 音节逐字时间戳解析 (若存在逐字标记)
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
        lastInterpolatedTimeMs = 0L
    }

    companion object {
        private const val TAG = "LyricsSyncClock"
        const val DEFAULT_MAX_EXTRAPOLATION_MS = 1000L
        const val DEFAULT_JUMP_THRESHOLD_MS = 350L
    }
}
