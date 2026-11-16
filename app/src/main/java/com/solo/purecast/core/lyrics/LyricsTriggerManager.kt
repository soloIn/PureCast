package com.solo.purecast.core.lyrics

import com.solo.purecast.data.lyrics.LyricsCacheRepository
import com.solo.purecast.data.lyrics.LyricsWaterfallFetcher
import com.solo.purecast.model.BusinessSessionState
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.LyricUiState
import com.solo.purecast.model.PlayerSessionSnapshot
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 💡 歌词触发、防抖、世代隔离与生命周期管理器
 * 遵循 R1-R4 与 R8 规范：
 * 350ms 切歌防抖、单调曲目代数熔断、30s 失败冷却及延迟时长补丁静默重校准。
 */
class LyricsTriggerManager(
    private val scope: CoroutineScope,
    private val cacheRepository: LyricsCacheRepository,
    private val waterfallFetcher: LyricsWaterfallFetcher,
    private val debounceDelayMs: Long = DEFAULT_DEBOUNCE_MS
) {

    private val _lyricUiState = MutableStateFlow<LyricUiState>(LyricUiState.Idle)
    val lyricUiState: StateFlow<LyricUiState> = _lyricUiState.asStateFlow()

    @Volatile
    private var currentTrackGeneration: Long = -1L

    private var inFlightFetchJob: Job? = null
    private var inFlightRecalibrateJob: Job? = null

    // 💡 30 秒失败退避黑名单 (Key -> ExpirationTimestampMs)
    private val failureCooldownMap = ConcurrentHashMap<String, Long>()

    // 记录首次成功检索时的参考时长，用于识别后续增量补丁
    private var initialFetchDurationMs: Long = 0L

    /**
     * 💡 接收全局播放快照变更，驱动歌词生命周期状态机
     */
    fun onSessionSnapshotChanged(snapshot: PlayerSessionSnapshot) {
        val businessState = snapshot.businessState
        val metadata = snapshot.metadata
        val generation = snapshot.trackGeneration

        // 1. 待机与停止豁免 (R4)
        if (businessState == BusinessSessionState.IDLE || businessState == BusinessSessionState.STOPPED) {
            resetToIdle()
            return
        }

        // 2. 实时直播流豁免 (R4)
        if (metadata.durationState == DurationState.LIVE) {
            cancelInFlightJobs()
            currentTrackGeneration = generation
            _lyricUiState.value = LyricUiState.NotFound(generation)
            Logger.lyrics.d(TAG, "实时直播流豁免歌词检索: generation=$generation")
            return
        }

        // 3. 最小有效元数据校验 (R1)
        if (!isMetadataValid(metadata)) {
            resetToIdle()
            return
        }

        // 4. 曲目代数变更检测 (切歌或新曲目)
        if (generation != currentTrackGeneration) {
            handleTrackBoundaryChanged(snapshot)
            return
        }

        // 5. 运行时时长增量补丁静默重校准 (R3)
        checkDurationPatchRecalibration(snapshot)
    }

    private fun handleTrackBoundaryChanged(snapshot: PlayerSessionSnapshot) {
        val generation = snapshot.trackGeneration
        val metadata = snapshot.metadata

        // 立即熔断并取消旧世代所有在途任务 (R2)
        cancelInFlightJobs()
        currentTrackGeneration = generation
        initialFetchDurationMs = metadata.durationMs

        _lyricUiState.value = LyricUiState.Loading(generation)

        inFlightFetchJob = scope.launch {
            // 350ms 防抖等待
            delay(debounceDelayMs)

            // 世代校验：防抖期间若再次切歌，静默熔断
            if (generation != currentTrackGeneration) {
                Logger.lyrics.d(TAG, "切歌防抖熔断: jobGen=$generation, currentGen=$currentTrackGeneration")
                return@launch
            }

            val artist = metadata.artist
            val title = metadata.title
            val durationMs = metadata.durationMs
            val cacheKey = cacheRepository.computeCacheKey(artist, title, durationMs)

            // 检查 30s 冷却退避
            val cooldownUntil = failureCooldownMap[cacheKey] ?: 0L
            if (System.currentTimeMillis() < cooldownUntil) {
                Logger.lyrics.d(TAG, "处于 30s 失败冷却期，跳过重复网络检索: key=$cacheKey")
                if (generation == currentTrackGeneration) {
                    _lyricUiState.value = LyricUiState.NotFound(generation)
                }
                return@launch
            }

            Logger.lyrics.i(TAG, "防抖结束，开始检索歌词: generation=$generation, $artist - $title")
            val timeline = waterfallFetcher.fetch(artist, title, durationMs)

            // 再次世代校验：网络返回后若代数已过时，彻底丢弃
            if (generation != currentTrackGeneration) {
                Logger.lyrics.d(TAG, "网络返回但世代已失效，丢弃结果: jobGen=$generation")
                return@launch
            }

            if (timeline == null) {
                // 记录 30s 冷却退避
                failureCooldownMap[cacheKey] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
                _lyricUiState.value = LyricUiState.NotFound(generation)
            } else if (timeline.isInstrumental) {
                _lyricUiState.value = LyricUiState.Instrumental(generation)
            } else {
                _lyricUiState.value = LyricUiState.Success(
                    trackGeneration = generation,
                    timeline = timeline
                )
            }
        }
    }

    /**
     * 💡 时长延迟到达检测与静默重校准 (R3)
     */
    private fun checkDurationPatchRecalibration(snapshot: PlayerSessionSnapshot) {
        val currentState = _lyricUiState.value
        if (currentState !is LyricUiState.Success) return

        val newDurationMs = snapshot.metadata.durationMs
        // 若最初是 0/未知，而现在补齐了大于 0 的固定时长
        if (initialFetchDurationMs <= 0L && newDurationMs > 0L) {
            val currentTimeline = currentState.timeline
            val diff = abs(currentTimeline.durationMs - newDurationMs)

            // 偏差大于 3 秒，后台静默重检索校准
            if (diff > 3000L && inFlightRecalibrateJob == null) {
                Logger.lyrics.i(TAG, "检测到时长延迟补丁且偏差大于 3s (diff=${diff}ms)，启动静默重校准")
                val generation = snapshot.trackGeneration
                val artist = snapshot.metadata.artist
                val title = snapshot.metadata.title

                inFlightRecalibrateJob = scope.launch {
                    val recalibrated = waterfallFetcher.fetch(artist, title, newDurationMs)
                    if (recalibrated != null && generation == currentTrackGeneration) {
                        Logger.lyrics.i(TAG, "静默重校准成功，平滑置换新歌词时间轴")
                        _lyricUiState.value = LyricUiState.Success(
                            trackGeneration = generation,
                            timeline = recalibrated,
                            activeLineIndex = currentState.activeLineIndex,
                            lineProgressFraction = currentState.lineProgressFraction
                        )
                    }
                    inFlightRecalibrateJob = null
                }
            }
            initialFetchDurationMs = newDurationMs
        }
    }

    private fun isMetadataValid(metadata: TrackMetadata): Boolean {
        val title = metadata.title.trim()
        val artist = metadata.artist.trim()

        if (title.isEmpty() || title == TrackMetadata.DEFAULT_TITLE || title == "等待音频投屏接入") {
            return false
        }
        if (artist.isEmpty() || artist == TrackMetadata.DEFAULT_ARTIST || artist.startsWith("AirPlay:")) {
            return false
        }
        return true
    }

    private fun resetToIdle() {
        cancelInFlightJobs()
        currentTrackGeneration = -1L
        initialFetchDurationMs = 0L
        _lyricUiState.value = LyricUiState.Idle
    }

    private fun cancelInFlightJobs() {
        inFlightFetchJob?.cancel()
        inFlightFetchJob = null
        inFlightRecalibrateJob?.cancel()
        inFlightRecalibrateJob = null
    }

    companion object {
        private const val TAG = "LyricsTrigger"
        const val DEFAULT_DEBOUNCE_MS = 350L
        const val FAILURE_COOLDOWN_MS = 30_000L
    }
}
