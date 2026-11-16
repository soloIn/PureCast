package com.solo.purecast.core.lyrics

import com.solo.purecast.data.lyrics.LyricCandidateScorer
import com.solo.purecast.data.lyrics.LyricsCacheRepository
import com.solo.purecast.data.lyrics.LyricsWaterfallFetcher
import com.solo.purecast.data.lyrics.MetadataNormalizer
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
 * 遵循 R1-R4、R8 规范与 Finding 1, 5, 7, 14 修复：
 * 1. 占位词精准过滤，杜绝无效请求 (Finding 1)；
 * 2. 异步分包增量到达防漏检 (Finding 1)；
 * 3. 防抖后读取最新有效元数据（时长防抹零） (Finding 14)；
 * 4. 延迟时长补丁重校准比分择优与平滑置换 (Finding 7)；
 * 5. 30s 失败冷却黑名单。
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

    @Volatile
    private var lastTriggeredSignature: String = ""

    @Volatile
    private var latestSnapshot: PlayerSessionSnapshot? = null

    private var inFlightFetchJob: Job? = null
    private var inFlightRecalibrateJob: Job? = null

    // 💡 30 秒失败退避黑名单 (Key -> ExpirationTimestampMs)
    private val failureCooldownMap = ConcurrentHashMap<String, Long>()

    // 记录当前生效歌词检索时的参考时长，用于识别后续增量补丁
    private var currentActiveDurationMs: Long = 0L

    /**
     * 💡 接收全局播放快照变更，驱动歌词生命周期状态机
     */
    fun onSessionSnapshotChanged(snapshot: PlayerSessionSnapshot) {
        latestSnapshot = snapshot
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
            lastTriggeredSignature = "LIVE"
            _lyricUiState.value = LyricUiState.NotFound(generation)
            Logger.lyrics.d(TAG, "实时直播流豁免歌词检索: generation=$generation")
            return
        }

        // 3. 最小有效元数据校验 (Finding 1: 拦截协议初始占位词)
        if (!isMetadataValid(metadata)) {
            // 若当前曲目换代且元数据仍为占位词
            if (generation != currentTrackGeneration) {
                cancelInFlightJobs()
                currentTrackGeneration = generation
                lastTriggeredSignature = ""
                if (_lyricUiState.value is LyricUiState.Success || _lyricUiState.value is LyricUiState.Loading) {
                    _lyricUiState.value = LyricUiState.Loading(generation)
                } else {
                    _lyricUiState.value = LyricUiState.Idle
                }
            }
            return
        }

        // 4. 曲目代数或有效元数据签名变更判定 (Finding 1: 防漏检)
        val signature = buildMetadataSignature(metadata)
        val isGenerationChanged = generation != currentTrackGeneration
        val isSignatureChanged = signature != lastTriggeredSignature

        if (isGenerationChanged || isSignatureChanged) {
            handleMetadataReady(snapshot, signature)
            return
        }

        // 5. 运行时时长增量补丁静默重校准 (Finding 7)
        checkDurationPatchRecalibration(snapshot)
    }

    private fun handleMetadataReady(snapshot: PlayerSessionSnapshot, signature: String) {
        val generation = snapshot.trackGeneration
        cancelInFlightJobs()

        currentTrackGeneration = generation
        lastTriggeredSignature = signature
        currentActiveDurationMs = snapshot.metadata.durationMs

        _lyricUiState.value = LyricUiState.Loading(generation)

        inFlightFetchJob = scope.launch {
            // 350ms 切歌防抖等待
            delay(debounceDelayMs)

            // 世代校验：防抖期间若再次切歌，静默熔断
            if (generation != currentTrackGeneration) {
                Logger.lyrics.d(TAG, "切歌防抖熔断: jobGen=$generation, currentGen=$currentTrackGeneration")
                return@launch
            }

            // 💡 Finding 14 修复：防抖结束时从最新的 snapshot 获取 metadata，避免 AirPlay 刚送达的真实时长被抹零！
            val currentMeta = latestSnapshot?.metadata ?: snapshot.metadata
            val artist = currentMeta.artist
            val title = currentMeta.title
            val durationMs = currentMeta.durationMs
            val album = currentMeta.album

            currentActiveDurationMs = durationMs

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

            Logger.lyrics.i(TAG, "防抖结束，开始检索歌词: generation=$generation, $artist - $title (duration=${durationMs}ms, album=$album)")
            val timeline = waterfallFetcher.fetch(artist, title, durationMs, album)

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
     * 💡 时长延迟到达检测与静默重校准 (Finding 7)
     */
    private fun checkDurationPatchRecalibration(snapshot: PlayerSessionSnapshot) {
        val currentState = _lyricUiState.value
        if (currentState !is LyricUiState.Success) return

        val newDurationMs = snapshot.metadata.durationMs
        // 若最初是 0/未知，而现在补齐了大于 0 的有效时长，或现有差值超过 3000ms
        val hasSignificantDurationChange = (currentActiveDurationMs <= 0L && newDurationMs > 0L) ||
            (currentActiveDurationMs > 0L && newDurationMs > 0L && abs(currentActiveDurationMs - newDurationMs) > 3000L)

        if (hasSignificantDurationChange && inFlightRecalibrateJob == null) {
            val generation = snapshot.trackGeneration
            val artist = snapshot.metadata.artist
            val title = snapshot.metadata.title
            val album = snapshot.metadata.album
            val currentTimeline = currentState.timeline

            Logger.lyrics.i(TAG, "检测到时长延迟补丁更新 ($currentActiveDurationMs -> ${newDurationMs}ms)，启动静默重校准比分")

            inFlightRecalibrateJob = scope.launch {
                val recalibrated = waterfallFetcher.fetch(artist, title, newDurationMs, album)

                if (recalibrated != null && generation == currentTrackGeneration) {
                    // 💡 Finding 7 修复：严格打分比对，只有新抓取歌词得分高于当前歌词才予以平滑置换
                    val oldScore = LyricCandidateScorer.evaluate(
                        expectedArtist = artist,
                        expectedTitle = title,
                        expectedDurationMs = newDurationMs,
                        candidateArtist = artist,
                        candidateTitle = title,
                        candidateDurationMs = currentTimeline.durationMs,
                        candidateFormat = currentTimeline.format
                    )
                    val newScore = LyricCandidateScorer.evaluate(
                        expectedArtist = artist,
                        expectedTitle = title,
                        expectedDurationMs = newDurationMs,
                        candidateArtist = artist,
                        candidateTitle = title,
                        candidateDurationMs = recalibrated.durationMs,
                        candidateFormat = recalibrated.format
                    )

                    if (newScore.totalScore > oldScore.totalScore) {
                        Logger.lyrics.i(
                            TAG,
                            "静默重校准胜出 (${newScore.totalScore} > ${oldScore.totalScore}分)，平滑置换歌词时间轴"
                        )
                        currentActiveDurationMs = newDurationMs
                        _lyricUiState.value = LyricUiState.Success(
                            trackGeneration = generation,
                            timeline = recalibrated
                        )
                    } else {
                        Logger.lyrics.d(
                            TAG,
                            "静默重校准新得分未超过旧歌词 (${newScore.totalScore} <= ${oldScore.totalScore}分)，保留现有歌词"
                        )
                    }
                }
                inFlightRecalibrateJob = null
            }
        }
    }

    private fun buildMetadataSignature(metadata: TrackMetadata): String {
        val normTitle = MetadataNormalizer.normalizeTitle(metadata.title)
        val normArtist = MetadataNormalizer.normalizeArtist(metadata.artist)
        return "$normTitle|$normArtist"
    }

    /**
     * 💡 占位词精准黑名单过滤 (Finding 1)
     */
    private fun isMetadataValid(metadata: TrackMetadata): Boolean {
        val title = metadata.title.trim().lowercase()
        val artist = metadata.artist.trim().lowercase()

        if (title.isEmpty() || title == TrackMetadata.DEFAULT_TITLE.lowercase() || title in PLACEHOLDER_TITLES) {
            return false
        }
        if (artist.isEmpty() || artist == TrackMetadata.DEFAULT_ARTIST.lowercase() || artist in PLACEHOLDER_ARTISTS || artist.startsWith("airplay:")) {
            return false
        }
        return true
    }

    /**
     * 💡 手动重置为空闲态并取消在途任务
     */
    fun reset() {
        resetToIdle()
    }

    private fun resetToIdle() {
        cancelInFlightJobs()
        currentTrackGeneration = -1L
        lastTriggeredSignature = ""
        currentActiveDurationMs = 0L
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

        private val PLACEHOLDER_TITLES = setOf(
            "purecast",
            "unknown",
            "unknown title",
            "unknown track",
            "waiting for audio cast",
            "等待音频投屏接入",
            "等待音频推送",
            "no media",
            "airplay",
            "audio cast",
            "bluetooth"
        )

        private val PLACEHOLDER_ARTISTS = setOf(
            "purecast",
            "purecast tv",
            "unknown",
            "unknown artist"
        )
    }
}
