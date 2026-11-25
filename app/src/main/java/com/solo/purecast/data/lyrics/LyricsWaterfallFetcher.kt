package com.solo.purecast.data.lyrics

import com.solo.purecast.data.lyrics.remote.AmllDataSource
import com.solo.purecast.data.lyrics.remote.LrclibDataSource
import com.solo.purecast.data.lyrics.remote.NeteaseDataSource
import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // 💡 In-flight 请求单飞去重映射表 + Mutex 消除 check-then-act 并发竞态
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<LyricTimeline?>>()
    private val inFlightMutex = Mutex()

    suspend fun fetch(
        artist: String,
        title: String,
        durationMs: Long,
        album: String = ""
    ): LyricTimeline? = coroutineScope {
        val spec = MetadataNormalizer.buildQuerySpec(
            artist = artist,
            title = title,
            album = album,
            durationMs = durationMs
        )

        if (spec.isInstrumentalHint) {
            Logger.lyrics.i(TAG, "标题命中纯音乐/伴奏特征，直接短路返回纯音乐状态: ${spec.exactArtist} - ${spec.exactTitle}")
            return@coroutineScope LyricTimeline(
                source = "Local",
                format = LyricFormat.LRC_LINE_SYNC,
                isInstrumental = true,
                lines = emptyList(),
                durationMs = spec.durationMs
            )
        }

        if (spec.cleanTitle.isBlank()) return@coroutineScope null

        val cacheKey = cacheRepository.computeCacheKey(spec)

        // 1. 优先查缓存 (L1 内存 0ms / L2 磁盘 10-20ms)
        val cached = cacheRepository.get(cacheKey)
        if (cached != null) {
            Logger.lyrics.d(TAG, "两级缓存命中: ${spec.exactArtist} - ${spec.exactTitle} (format=${cached.format})")
            return@coroutineScope cached
        }

        // 2. In-flight 单飞去重原子检查与登记 (Finding 5)
        // 💡 使用 CoroutineStart.LAZY + invokeOnCompletion 消除 lateinit var 未初始化访问竞态
        val (deferred, isReused) = inFlightMutex.withLock {
            val existing = inFlightRequests[cacheKey]
            if (existing != null && existing.isActive) {
                existing to true
            } else {
                val created = async(start = CoroutineStart.LAZY) {
                    executeWaterfall(spec, cacheKey)
                }
                created.invokeOnCompletion {
                    inFlightRequests.remove(cacheKey, created)
                }
                inFlightRequests[cacheKey] = created
                created to false
            }
        }
        if (isReused) {
            Logger.lyrics.d(TAG, "复用在途 In-flight 请求: ${spec.exactArtist} - ${spec.exactTitle}")
        } else {
            deferred.start()
        }
        deferred.await()
    }

    private suspend fun executeWaterfall(
        spec: LyricsQuerySpec,
        cacheKey: String
    ): LyricTimeline? {
        Logger.lyrics.i(
            TAG,
            "启动严格阶梯瀑布流检索: exact=[${spec.exactArtist} - ${spec.exactTitle}], clean=[${spec.cleanArtist} - ${spec.cleanTitle}] (duration=${spec.durationMs}ms, album=${spec.exactAlbum})"
        )

        var pendingInstrumental: LyricTimeline? = null

        // 阶梯 Step 1: AMLL TTML 逐字高精库 (硬超时 1.5s，需已知歌手)
        if (spec.cleanArtist.isNotBlank()) {
            try {
                val amllResult = amllDataSource.fetchWithMetadata(spec)
                if (amllResult != null) {
                    val amllHit = amllResult.timeline
                    val score = LyricCandidateScorer.evaluate(
                        expectedArtist = spec.exactArtist.ifBlank { spec.cleanArtist },
                        expectedTitle = spec.exactTitle,
                        expectedDurationMs = spec.durationMs,
                        candidateArtist = amllResult.candidateArtist,
                        candidateTitle = amllResult.candidateTitle,
                        candidateDurationMs = amllHit.durationMs,
                        candidateFormat = amllHit.format
                    )

                    if (score.isPassed) {
                        if (score.isInstrumental) {
                            Logger.lyrics.i(TAG, "阶梯 Step 1 (AMLL) 候选标记为纯音乐，暂存并继续查后续有词源: ${spec.exactArtist} - ${spec.exactTitle}")
                            pendingInstrumental = LyricTimeline(
                                source = "AMLL",
                                format = amllHit.format,
                                isInstrumental = true,
                                lines = emptyList(),
                                durationMs = amllHit.durationMs.takeIf { it > 0L } ?: spec.durationMs
                            )
                        } else {
                            Logger.lyrics.i(TAG, "阶梯 Step 1 (AMLL TTML) 命中且打分通过 (${score.totalScore}分): ${spec.exactArtist} - ${spec.exactTitle}")
                            val enriched = enrichWithTranslation(amllHit, spec)
                            cacheRepository.put(cacheKey, enriched)
                            return enriched
                        }
                    } else {
                        Logger.lyrics.w(TAG, "AMLL 候选评分未达标 (${score.totalScore}分): ${score.vetoReason}，降级至 Step 2")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "AMLL 检索异常，平滑降级至 Step 2", e)
            }
        }

        // 阶梯 Step 2: LRCLIB 精确匹配四元组 (优先原文精确，未命中再降级清洗名)
        if (spec.exactArtist.isNotBlank() || spec.cleanArtist.isNotBlank()) {
            try {
                val lrclibExactHit = lrclibDataSource.getExact(spec)
                if (lrclibExactHit != null) {
                    if (lrclibExactHit.isInstrumental) {
                        Logger.lyrics.i(TAG, "阶梯 Step 2 (LRCLIB /api/get) 命中纯音乐标记，暂存并继续查后续有词源: ${spec.exactArtist} - ${spec.exactTitle}")
                        if (pendingInstrumental == null) {
                            pendingInstrumental = lrclibExactHit
                        }
                    } else if (lrclibExactHit.format != LyricFormat.PLAIN_TEXT) {
                        Logger.lyrics.i(TAG, "阶梯 Step 2 (LRCLIB /api/get) 精确命中时间戳歌词: ${spec.exactArtist} - ${spec.exactTitle}")
                        val enriched = enrichWithTranslation(lrclibExactHit, spec)
                        cacheRepository.put(cacheKey, enriched)
                        return enriched
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "LRCLIB 精确匹配异常，平滑降级至 Step 3", e)
            }
        }

        // 阶梯 Step 3: LRCLIB 模糊搜索决胜 (硬超时 2.0s)
        // 💡 模糊搜索仅采纳有效同步歌词，不产出纯音乐结论（避免社区模糊条目误标纯音乐）
        try {
            val lrclibFuzzyHit = lrclibDataSource.searchFuzzy(spec)
            if (lrclibFuzzyHit != null && !lrclibFuzzyHit.isInstrumental && lrclibFuzzyHit.format != LyricFormat.PLAIN_TEXT) {
                Logger.lyrics.i(TAG, "阶梯 Step 3 (LRCLIB /api/search) 模糊命中时间戳歌词: ${spec.exactArtist} - ${spec.exactTitle}")
                val enriched = enrichWithTranslation(lrclibFuzzyHit, spec)
                cacheRepository.put(cacheKey, enriched)
                return enriched
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.lyrics.w(TAG, "LRCLIB 模糊搜索异常，降级至网易云源", e)
        }

        // 阶梯 Step 4: 网易云高精中文库兜底检索 (硬超时 2.0s)
        if (neteaseDataSource != null) {
            try {
                val neteaseHit = neteaseDataSource.fetch(spec)
                if (neteaseHit != null) {
                    if (!neteaseHit.isInstrumental && neteaseHit.format != LyricFormat.PLAIN_TEXT) {
                        Logger.lyrics.i(TAG, "阶梯 Step 4 (网易云音乐) 命中带翻译歌词: ${spec.exactArtist} - ${spec.exactTitle}")
                        cacheRepository.put(cacheKey, neteaseHit)
                        return neteaseHit
                    } else if (neteaseHit.isInstrumental && pendingInstrumental == null) {
                        pendingInstrumental = neteaseHit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.lyrics.w(TAG, "网易云歌词检索异常", e)
            }
        }

        // 💡 延迟采纳机制：仅当全瀑布流均未找到有效同步歌词，且此前存在高置信度远端纯音乐判定时才返回纯音乐，
        // 且仅写入 L1 内存缓存（不落盘 L2），避免社区数据修正前永久锁死。
        if (pendingInstrumental != null) {
            Logger.lyrics.i(TAG, "全瀑布流未命中同步歌词，采纳暂存纯音乐判定 (source=${pendingInstrumental.source}): ${spec.exactArtist} - ${spec.exactTitle}")
            cacheRepository.putMemoryOnly(cacheKey, pendingInstrumental)
            return pendingInstrumental
        }

        Logger.lyrics.w(TAG, "阶梯瀑布流全链路未命中有效时间戳歌词: ${spec.exactArtist} - ${spec.exactTitle}")
        return null
    }

    /**
     * 💡 简体中文翻译自动增强补齐
     */
    private suspend fun enrichWithTranslation(
        timeline: LyricTimeline,
        spec: LyricsQuerySpec
    ): LyricTimeline {
        val netease = neteaseDataSource ?: return timeline
        val hasAnyTranslation = timeline.lines.any { !it.translationText.isNullOrBlank() }
        if (hasAnyTranslation || timeline.isInstrumental || timeline.lines.isEmpty()) {
            return timeline
        }

        return try {
            val transLines = netease.fetchTranslationTimeline(spec)
            if (!transLines.isNullOrEmpty()) {
                val enriched = timeline.lines.map { line ->
                    val matched = netease.findBestMatchingTranslation(line.startMs, transLines)
                    if (matched != null) {
                        line.copy(translationText = matched)
                    } else {
                        line
                    }
                }
                Logger.lyrics.i(TAG, "成功通过网易云补齐简体中文逐句翻译: ${spec.cleanArtist} - ${spec.cleanTitle} (行数=${transLines.size})")
                timeline.copy(lines = enriched)
            } else {
                timeline
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.lyrics.d(TAG, "尝试补齐翻译异常，保留原歌词: ${e.message}")
            timeline
        }
    }

    companion object {
        private const val TAG = "LyricsWaterfall"
    }
}
