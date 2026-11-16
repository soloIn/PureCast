package com.solo.purecast.data.lyrics

import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger

/**
 * 💡 严格阶梯瀑布流式歌词检索协调器
 * 严格遵循 KTD1 与用户决策：
 * L1/L2 缓存 -> AMLL TTML (1.5s) -> LRCLIB get (1.5s) -> LRCLIB search (2.0s) 依次单线请求
 */
class LyricsWaterfallFetcher(
    private val cacheRepository: LyricsCacheRepository,
    private val amllDataSource: AmllDataSource,
    private val lrclibDataSource: LrclibDataSource
) {

    suspend fun fetch(artist: String, title: String, durationMs: Long): LyricTimeline? {
        val cleanArtist = MetadataNormalizer.normalizeArtist(artist)
        val cleanTitle = MetadataNormalizer.normalizeTitle(title)

        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return null

        val cacheKey = cacheRepository.computeCacheKey(cleanArtist, cleanTitle, durationMs)

        // 1. 优先查缓存 (L1 内存 0ms / L2 磁盘 10-20ms)
        val cached = cacheRepository.get(cacheKey)
        if (cached != null) {
            Logger.lyrics.d(TAG, "两级缓存命中: $cleanArtist - $cleanTitle (format=${cached.format})")
            return cached
        }

        Logger.lyrics.i(TAG, "启动严格阶梯瀑布流检索: $cleanArtist - $cleanTitle (duration=${durationMs}ms)")

        // 2. 阶梯 Step 1: AMLL TTML 逐字高精库 (硬超时 1.5s)
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
                    cacheRepository.put(cacheKey, amllHit)
                    return amllHit
                } else {
                    Logger.lyrics.w(TAG, "AMLL 候选评分未达标 (${score.totalScore}分): ${score.vetoReason}，降级至 Step 2")
                }
            }
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "AMLL 检索异常，平滑降级至 Step 2", e)
        }

        // 3. 阶梯 Step 2: LRCLIB 精确匹配四元组 (硬超时 1.5s)
        try {
            val lrclibExactHit = lrclibDataSource.getExact(cleanArtist, cleanTitle, durationMs)
            if (lrclibExactHit != null) {
                Logger.lyrics.i(TAG, "阶梯 Step 2 (LRCLIB /api/get) 精确命中: $cleanArtist - $cleanTitle")
                cacheRepository.put(cacheKey, lrclibExactHit)
                return lrclibExactHit
            }
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "LRCLIB 精确匹配异常，平滑降级至 Step 3", e)
        }

        // 4. 阶梯 Step 3: LRCLIB 模糊搜索决胜 (硬超时 2.0s)
        try {
            val lrclibFuzzyHit = lrclibDataSource.searchFuzzy(cleanArtist, cleanTitle, durationMs)
            if (lrclibFuzzyHit != null) {
                Logger.lyrics.i(TAG, "阶梯 Step 3 (LRCLIB /api/search) 模糊命中最佳候选: $cleanArtist - $cleanTitle")
                cacheRepository.put(cacheKey, lrclibFuzzyHit)
                return lrclibFuzzyHit
            }
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "LRCLIB 模糊搜索异常", e)
        }

        Logger.lyrics.w(TAG, "阶梯瀑布流全链路未命中有效歌词: $cleanArtist - $cleanTitle")
        return null
    }

    companion object {
        private const val TAG = "LyricsWaterfall"
    }
}
