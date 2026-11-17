package com.solo.purecast.data.lyrics

import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.data.lyrics.remote.NeteaseDataSource
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * 💡 严格阶梯瀑布流式歌词检索与简体中文翻译增强协调器
 * 1. 优先查 L1/L2 本地缓存；
 * 2. 阶梯 Step 1: AMLL TTML 逐字高精库 (1.5s)；
 * 3. 阶梯 Step 2: LRCLIB 精确匹配四元组 (1.5s)；
 * 4. 阶梯 Step 3: LRCLIB 模糊搜索决胜 (2.0s)；
 * 5. 阶梯 Step 4: 网易云海量中文库兜底检索 (2.0s)；
 * 6. 翻译增强：若命中的歌词缺少翻译，自动通过网易云补充简体中文逐句翻译。
 */
class LyricsWaterfallFetcher(
    private val cacheRepository: LyricsCacheRepository,
    private val amllDataSource: AmllDataSource,
    private val lrclibDataSource: LrclibDataSource,
    private val neteaseDataSource: NeteaseDataSource? = null
) {

    // 💡 In-flight 请求单飞去重映射表，杜绝同一曲目的并发重复请求
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<LyricTimeline?>>()

    suspend fun fetch(
        artist: String,
        title: String,
        durationMs: Long,
        album: String = ""
    ): LyricTimeline? = coroutineScope {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanTitle.isBlank()) return@coroutineScope null

        val cacheKey = cacheRepository.computeCacheKey(cleanArtist, cleanTitle, durationMs)

        // 1. 优先查缓存 (L1 内存 0ms / L2 磁盘 10-20ms)
        val cached = cacheRepository.get(cacheKey)
        if (cached != null) {
            Logger.lyrics.d(TAG, "两级缓存命中: $cleanArtist - $cleanTitle (format=${cached.format})")
            return@coroutineScope cached
        }

        // 2. In-flight 单飞去重检查 (Finding 5)
        val existingDeferred = inFlightRequests[cacheKey]
        if (existingDeferred != null && existingDeferred.isActive) {
            Logger.lyrics.d(TAG, "复用在途 In-flight 请求: $cleanArtist - $cleanTitle")
            return@coroutineScope existingDeferred.await()
        }

        val newDeferred = async {
            try {
                executeWaterfall(cleanArtist, cleanTitle, durationMs, album, cacheKey)
            } finally {
                inFlightRequests.remove(cacheKey)
            }
        }

        inFlightRequests[cacheKey] = newDeferred
        newDeferred.await()
    }

    private suspend fun executeWaterfall(
        cleanArtist: String,
        cleanTitle: String,
        durationMs: Long,
        album: String,
        cacheKey: String
    ): LyricTimeline? {
        Logger.lyrics.i(TAG, "启动严格阶梯瀑布流检索: $cleanArtist - $cleanTitle (duration=${durationMs}ms, album=$album)")

        // 阶梯 Step 1: AMLL TTML 逐字高精库 (硬超时 1.5s，需已知歌手)
        if (cleanArtist.isNotBlank()) {
            try {
                val amllHit = amllDataSource.fetch(cleanArtist, cleanTitle)
                if (amllHit != null) {
                    val score = LyricCandidateScorer.evaluate(
                        expectedArtist = cleanArtist,
                        expectedTitle = cleanTitle,
                        expectedDurationMs = durationMs,
                        candidateArtist = cleanArtist,
                        candidateTitle = cleanTitle,
                        candidateDurationMs = amllHit.durationMs,
                        candidateFormat = amllHit.format
                    )

                    if (score.isPassed) {
                        Logger.lyrics.i(TAG, "阶梯 Step 1 (AMLL TTML) 命中且打分通过 (${score.totalScore}分): $cleanArtist - $cleanTitle")
                        val enriched = enrichWithTranslation(amllHit, cleanArtist, cleanTitle, durationMs)
                        cacheRepository.put(cacheKey, enriched)
                        return enriched
                    } else {
                        Logger.lyrics.w(TAG, "AMLL 候选评分未达标 (${score.totalScore}分): ${score.vetoReason}，降级至 Step 2")
                    }
                }
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "AMLL 检索异常，平滑降级至 Step 2", e)
            }
        }

        var fallbackPlainTimeline: LyricTimeline? = null

        // 阶梯 Step 2: LRCLIB 精确匹配四元组 (硬超时 1.5s，需已知歌手)
        if (cleanArtist.isNotBlank()) {
            try {
                val lrclibExactHit = lrclibDataSource.getExact(
                    artist = cleanArtist,
                    title = cleanTitle,
                    durationMs = durationMs,
                    album = album
                )
                if (lrclibExactHit != null) {
                    if (lrclibExactHit.format != LyricFormat.PLAIN_TEXT || lrclibExactHit.isInstrumental) {
                        Logger.lyrics.i(TAG, "阶梯 Step 2 (LRCLIB /api/get) 精确命中时间戳歌词: $cleanArtist - $cleanTitle")
                        val enriched = enrichWithTranslation(lrclibExactHit, cleanArtist, cleanTitle, durationMs)
                        cacheRepository.put(cacheKey, enriched)
                        return enriched
                    } else {
                        Logger.lyrics.d(TAG, "阶梯 Step 2 命中纯文本无时间戳歌词，暂存并继续寻找时间戳歌词: $cleanArtist - $cleanTitle")
                        fallbackPlainTimeline = lrclibExactHit
                    }
                }
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "LRCLIB 精确匹配异常，平滑降级至 Step 3", e)
            }
        }

        // 阶梯 Step 3: LRCLIB 模糊搜索决胜 (硬超时 2.0s)
        try {
            val lrclibFuzzyHit = lrclibDataSource.searchFuzzy(
                artist = cleanArtist,
                title = cleanTitle,
                album = album,
                durationMs = durationMs
            )
            if (lrclibFuzzyHit != null) {
                if (lrclibFuzzyHit.format != LyricFormat.PLAIN_TEXT || lrclibFuzzyHit.isInstrumental) {
                    Logger.lyrics.i(TAG, "阶梯 Step 3 (LRCLIB /api/search) 模糊命中时间戳歌词: $cleanArtist - $cleanTitle")
                    val enriched = enrichWithTranslation(lrclibFuzzyHit, cleanArtist, cleanTitle, durationMs)
                    cacheRepository.put(cacheKey, enriched)
                    return enriched
                } else if (fallbackPlainTimeline == null) {
                    fallbackPlainTimeline = lrclibFuzzyHit
                }
            }
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "LRCLIB 模糊搜索异常，降级至网易云源", e)
        }

        // 阶梯 Step 4: 网易云高精中文库兜底检索 (硬超时 2.0s)
        if (neteaseDataSource != null) {
            try {
                val neteaseHit = neteaseDataSource.fetch(cleanArtist, cleanTitle, durationMs)
                if (neteaseHit != null) {
                    Logger.lyrics.i(TAG, "阶梯 Step 4 (网易云音乐) 命中带翻译歌词: $cleanArtist - $cleanTitle")
                    cacheRepository.put(cacheKey, neteaseHit)
                    return neteaseHit
                }
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "网易云歌词检索异常", e)
            }
        }

        // 💡 若时间戳歌词全链路未命中，降级采纳暂存的纯文本无时间戳歌词兜底
        if (fallbackPlainTimeline != null) {
            Logger.lyrics.i(TAG, "时间戳歌词全链路未命中，降级采纳纯文本歌词: $cleanArtist - $cleanTitle")
            val enriched = enrichWithTranslation(fallbackPlainTimeline, cleanArtist, cleanTitle, durationMs)
            cacheRepository.put(cacheKey, enriched)
            return enriched
        }

        Logger.lyrics.w(TAG, "阶梯瀑布流全链路未命中有效歌词: $cleanArtist - $cleanTitle")
        return null
    }

    /**
     * 💡 简体中文翻译自动增强补齐
     */
    private suspend fun enrichWithTranslation(
        timeline: LyricTimeline,
        artist: String,
        title: String,
        durationMs: Long
    ): LyricTimeline {
        val netease = neteaseDataSource ?: return timeline
        val hasAnyTranslation = timeline.lines.any { !it.translationText.isNullOrBlank() }
        if (hasAnyTranslation || timeline.isInstrumental || timeline.lines.isEmpty()) {
            return timeline
        }

        return try {
            val transLines = netease.fetchTranslationTimeline(artist, title, durationMs)
            if (!transLines.isNullOrEmpty()) {
                val enriched = timeline.lines.map { line ->
                    val matched = netease.findBestMatchingTranslation(line.startMs, transLines)
                    if (matched != null) {
                        line.copy(translationText = matched)
                    } else {
                        line
                    }
                }
                Logger.lyrics.i(TAG, "成功通过网易云补齐简体中文逐句翻译: $artist - $title (行数=${transLines.size})")
                timeline.copy(lines = enriched)
            } else {
                timeline
            }
        } catch (e: Exception) {
            Logger.lyrics.d(TAG, "尝试补齐翻译异常，保留原歌词: ${e.message}")
            timeline
        }
    }

    companion object {
        private const val TAG = "LyricsWaterfall"
    }
}
