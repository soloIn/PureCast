package com.solo.purecast.core.lyrics

import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val ringBufferDelayMsProvider: () -> Long = { 0L },
    private val lineLeadInMs: Long = DEFAULT_LINE_LEAD_IN_MS,
    private val delayEmaAlpha: Double = DEFAULT_DELAY_EMA_ALPHA
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

    // 💡 时钟版本号 (Finding #1 & #8)：纯 Kotlin 核心字段，仅在离散跳转、Seek 跳变、状态切换和 reset 时递增
    @Volatile
    var mutationVersion: Long = 0L
        private set

    private val _mutationVersionFlow = MutableStateFlow(0L)
    val mutationVersionFlow: StateFlow<Long> = _mutationVersionFlow.asStateFlow()

    private fun notifyMutation() {
        mutationVersion++
        _mutationVersionFlow.value = mutationVersion
    }

    // 💡 记录当前推演时间，用于暂停瞬间防止时钟倒退
    private var lastInterpolatedTimeMs: Long = 0L

    // 💡 环形缓冲区延迟 16ms 帧级缓存与指数移动平均 (EMA) 滤波器状态 (Finding 2)
    private var cachedRingDelayMs: Long = 0L
    private var filteredRingDelayMs: Double = -1.0
    private var lastDelayFetchNanoTime: Long = 0L

    /**
     * 💡 获取经 16ms 帧级缓存与 EMA 低通滤波平滑后的环形缓冲区延迟 (Finding 2)
     * 同一 VSYNC 渲染帧 (16ms) 内多次调用直接复用缓存，杜绝重复 JNI 开销；
     * EMA (alpha = 0.15) 滤除 USB isoc URB 突发读取导致的阶跃锯齿。
     */
    private fun getEffectiveRingDelayMs(nowNanoTime: Long = System.nanoTime()): Long {
        val elapsedNano = nowNanoTime - lastDelayFetchNanoTime
        val isCacheExpired = lastDelayFetchNanoTime == 0L || elapsedNano >= FRAME_CACHE_WINDOW_NANO || elapsedNano < 0L
        if (isCacheExpired) {
            val rawDelay = ringBufferDelayMsProvider().coerceIn(0L, 500L).toDouble()
            filteredRingDelayMs = if (filteredRingDelayMs < 0.0) {
                // 💡 首次采样或 reset 后直通初始化，保证冷启动 0 延迟瞬时收敛
                rawDelay
            } else {
                delayEmaAlpha * rawDelay + (1.0 - delayEmaAlpha) * filteredRingDelayMs
            }
            cachedRingDelayMs = Math.round(filteredRingDelayMs)
            lastDelayFetchNanoTime = nowNanoTime
        }
        return cachedRingDelayMs
    }

    /**
     * 💡 显式时钟硬对齐 (Hard Align)
     * 用于切歌换代、显式 Seek 寻道完成或重连时，瞬时排空历史插值缓存，
     * 毫秒级硬重置锚点时间，彻底杜绝向前拖动滞后与跨曲目残存。
     */
    fun hardAlign(targetPositionMs: Long, playing: Boolean, nowNanoTime: Long = System.nanoTime()) {
        val clamped = targetPositionMs.coerceAtLeast(0L)
        val ringDelayMs = getEffectiveRingDelayMs(nowNanoTime)
        reportedPositionMs = clamped
        // 💡 统一时间基准 (Finding 11)：将 lastInterpolatedTimeMs 统一初始化为 (targetPositionMs - ringDelayMs).coerceAtLeast(0L)
        // 确保与播放推演中的 hardwareAdjustedTime 基准严格咬合，杜绝硬对齐瞬间时钟断层与滞后
        lastInterpolatedTimeMs = (clamped - ringDelayMs).coerceAtLeast(0L)
        // 💡 硬件出声延迟对齐：开播时 (pos == 0L 且处于播放态) 锚点推迟 100ms，对齐声卡 100ms 硬件预充出声时钟
        anchorNanoTime = if (playing) {
            if (clamped == 0L) nowNanoTime + 100_000_000L else nowNanoTime
        } else 0L
        isPlaying = playing
        notifyMutation()
        Logger.lyrics.i(TAG, "时钟硬对齐完成 (Hard Align): target=${clamped}ms, playing=$playing, ringDelay=${ringDelayMs}ms")
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
        val stateChanged = (playing != isPlaying)
        if (!playing && isPlaying) {
            // 💡 播放转暂停/缓冲 (Finding 11)：暂停态冻结值严格保持为暂停那一刻的 lastInterpolatedTimeMs，
            // 杜绝因叠加未扣除硬件延迟的 newPositionMs 引发暂停瞬间时钟向前跳变 +ringDelayMs 的缺陷
            reportedPositionMs = newPositionMs
            anchorNanoTime = 0L
            if (lastInterpolatedTimeMs == 0L) {
                val ringDelayMs = getEffectiveRingDelayMs(nowNanoTime)
                lastInterpolatedTimeMs = (newPositionMs - ringDelayMs).coerceAtLeast(0L)
            }
        } else if (playing && !isPlaying) {
            // 💡 暂停恢复为播放态 (Finding 11)：统一时间基准，将 lastInterpolatedTimeMs 初始化为 (newPositionMs - ringDelayMs).coerceAtLeast(0L)，
            // 消除恢复瞬间硬件延迟未抵扣带来的时钟前倾或断层
            val ringDelayMs = getEffectiveRingDelayMs(nowNanoTime)
            reportedPositionMs = newPositionMs
            lastInterpolatedTimeMs = (newPositionMs - ringDelayMs).coerceAtLeast(0L)
            anchorNanoTime = if (newPositionMs == 0L) nowNanoTime + 100_000_000L else nowNanoTime
        } else if (!playing && !isPlaying) {
            // 💡 保持暂停状态：底层上报可能随网络心跳微调 reportedPositionMs，保持 anchorNanoTime = 0L 且 lastInterpolatedTimeMs 原地冻结
            reportedPositionMs = newPositionMs
            anchorNanoTime = 0L
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
        if (stateChanged) {
            notifyMutation()
        }
    }

    /**
     * 💡 计算当前 VSYNC 渲染帧的推演毫秒时间 (60fps/120fps)
     */
    fun getCurrentInterpolatedTimeMs(nowNanoTime: Long = System.nanoTime()): Long {
        if (!isPlaying || anchorNanoTime == 0L) {
            // 💡 暂停或未启动推流时，时钟原地单调冻结在 lastInterpolatedTimeMs (Finding 11)
            if (lastInterpolatedTimeMs == 0L && reportedPositionMs > 0L) {
                val ringDelayMs = getEffectiveRingDelayMs(nowNanoTime)
                lastInterpolatedTimeMs = (reportedPositionMs - ringDelayMs).coerceAtLeast(0L)
            }
            return lastInterpolatedTimeMs
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

        // 💡 精准扣除 USB 环形缓冲区未消费数据物理延迟 (通常为 100ms~250ms)，经 16ms 缓存与 EMA 平滑
        val ringDelayMs = getEffectiveRingDelayMs(nowNanoTime)
        val hardwareAdjustedTime = (rawTimeMs - ringDelayMs).coerceAtLeast(0L)

        // 💡 保证推演时钟绝对单调不倒退：当底层上报因调度略微落后时，原地微等几十毫秒待其追平，绝不逆向回跳
        val monotonic = maxOf(lastInterpolatedTimeMs, hardwareAdjustedTime)
        lastInterpolatedTimeMs = monotonic
        return monotonic
    }

    /**
     * 💡 计算某行歌词应当被激活（高亮/滚动）的生效时间戳 (毫秒)
     * 支持基于 Apple TV 规范的行预先激活机制 (Lead-in / Anticipation)：
     * 1. 首行歌词在前奏期结束前 leadInMs 提前激活并居中放大，为用户提供视线先导；
     * 2. 无逐字时间戳的普通行歌词：提前 leadInMs 进行高亮与滚动，对齐动画过渡时长；
     * 3. 含逐字时间戳的卡拉OK行歌词：若与上一行间距充足 (>= leadInMs)，提前 leadInMs 激活并处于暗色就绪态；
     *    若与上一行间距紧凑 (< leadInMs)，严格等待上一行最后音节扫光完毕后再激活下一行，绝不提前截断前一句人声。
     */
    fun getEffectiveStartMs(
        index: Int,
        lines: List<LyricLine>,
        leadInMs: Long = lineLeadInMs
    ): Long {
        if (index !in lines.indices) return 0L
        val line = lines[index]
        if (leadInMs <= 0L) return line.startMs

        if (index == 0) {
            return (line.startMs - leadInMs).coerceAtLeast(0L)
        }

        val prev = lines[index - 1]
        val prevWords = prev.words
        val rawEffectiveStart = if (!prevWords.isNullOrEmpty()) {
            val prevVocalsEnd = prevWords.last().endMs
            if (line.startMs > prevVocalsEnd) {
                // 上一行有逐字时间戳且已唱完：确保不截断上一行最后音节的扫光
                val earlyStart = line.startMs - leadInMs
                maxOf(prevVocalsEnd, earlyStart)
            } else {
                // 存在和声或交叠行
                (line.startMs - leadInMs).coerceAtLeast(0L)
            }
        } else {
            // 普通行：检查两句间距是否小于提前量 (紧凑行间距)
            val lineGap = line.startMs - prev.startMs
            if (lineGap > 0L && lineGap < leadInMs) {
                // 💡 修复 Finding 1: 紧凑行间距下两句相隔过短 (< leadInMs)，禁止提前过激吞噬上一行
                // 延迟至当前行实际起始时间 (line.startMs) 切换，确保上一句获得完整展示
                line.startMs
            } else {
                // 两句间距充足，正常提前 leadInMs 激活以配合平滑滚动动画
                (line.startMs - leadInMs).coerceAtLeast(0L)
            }
        }

        // 💡 修复 Finding 1: 严格硬约束下一行激活时间绝不能早于上一行开唱时间 (prev.startMs)
        // 当两句相距极近 (< leadInMs) 时，防止下一行倒挂提前激活而彻底吞噬跳过上一行显示
        return maxOf(prev.startMs, rawEffectiveStart)
    }

    /**
     * 💡 针对当前推演时间检索当前高亮行并计算逐字/逐行进度比例
     */
    fun calculateActiveLineState(
        timeline: LyricTimeline,
        currentTimeMs: Long,
        leadInMs: Long = lineLeadInMs
    ): ActiveLineState {
        val lines = timeline.lines
        if (lines.isEmpty()) {
            return ActiveLineState(lineIndex = -1, lineProgressFraction = 0f)
        }

        // 前奏期判定 —— 若当前时间尚未到达第一句歌词的提前起始时间，明确返回 -1
        val firstEffectiveStart = getEffectiveStartMs(0, lines, leadInMs)
        if (currentTimeMs < firstEffectiveStart) {
            return ActiveLineState(lineIndex = -1, lineProgressFraction = 0f)
        }

        // 基于单调递增的 effectiveStartMs 进行二分搜索，找到最后一个 effectiveStartMs <= currentTimeMs 的行
        var low = 0
        var high = lines.size - 1
        var candidateIndex = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val effectiveStart = getEffectiveStartMs(mid, lines, leadInMs)
            if (effectiveStart <= currentTimeMs) {
                candidateIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        var activeIndex = candidateIndex

        // 💡 检查是否有交叠行或和声行：
        // 仅在当前候选行已经开始（currentTimeMs >= candidateLine.startMs）且已超出其 endMs 时，
        // 检查相邻行是否精确包含 currentTimeMs；若处于候选行的提前预卷期 (currentTimeMs < candidateLine.startMs)，
        // 则严格保持候选行为激活行，绝不回退至前一行而破坏提前量！
        val candidateLine = lines[candidateIndex]
        if (currentTimeMs >= candidateLine.startMs && currentTimeMs > candidateLine.endMs) {
            for (offset in -1..1) {
                val testIdx = candidateIndex + offset
                if (testIdx in lines.indices && testIdx != candidateIndex) {
                    val l = lines[testIdx]
                    if (currentTimeMs in l.startMs..l.endMs) {
                        activeIndex = testIdx
                        break
                    }
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
        cachedRingDelayMs = 0L
        filteredRingDelayMs = -1.0
        lastDelayFetchNanoTime = 0L
        notifyMutation()
    }

    companion object {
        private const val TAG = "LyricsSyncClock"
        const val DEFAULT_MAX_EXTRAPOLATION_MS = 1000L
        const val DEFAULT_JUMP_THRESHOLD_MS = 350L
        /**
         * 💡 帧级延迟缓存窗口时间 (16ms = 16_000_000ns):
         * 对齐 60fps VSYNC 单帧周期，同一渲染帧内多次 UI 绘制读取直接复用缓存，
         * 将 JNI 底层调用频次降低 75% 以上。
         */
        const val FRAME_CACHE_WINDOW_NANO = 16_000_000L

        /**
         * 💡 指数移动平均 (EMA) 平滑系数:
         * alpha = 0.15 构成低通滤波器，有效滤除 USB isoc URB 突发排队抖动带来的高频锯齿，
         * 同时兼顾硬件物理延迟变化的平滑跟踪。
         */
        const val DEFAULT_DELAY_EMA_ALPHA = 0.15

        /**
         * 💡 歌词行提前高亮/滚动视线先导时间 (Lead-in Time):
         * 符合 Apple TV 10-Foot UI 规范与人体工学视线先导习惯。
         * 提前 280ms 触发下一行激活与平滑滚动 (配合 480ms 阻尼曲线)，使文字在开唱前夕完成居中与 1.08x 弹性放大，
         * 彻底消除“唱出来了界面才开始缓慢移动”的滞后感。
         */
        const val DEFAULT_LINE_LEAD_IN_MS = 280L
    }
}
