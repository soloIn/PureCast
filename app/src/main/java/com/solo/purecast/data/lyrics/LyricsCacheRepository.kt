package com.solo.purecast.data.lyrics

import com.solo.purecast.model.LyricFormat
import com.solo.purecast.model.LyricLine
import com.solo.purecast.model.LyricTimeline
import com.solo.purecast.model.LyricWord
import com.solo.purecast.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 💡 歌词两级缓存仓库（L1 内存 LRU + L2 本地磁盘文件缓存）
 * 严格遵循线程安全与原子写入，自动清理异常脏文件并支持 3 秒时长分桶。
 */
class LyricsCacheRepository(
    private val diskCacheDir: File,
    private val maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
    private val maxDiskEntries: Int = DEFAULT_MAX_DISK_ENTRIES
) {
    private val memoryCache = object : LinkedHashMap<String, LyricTimeline>(maxMemoryEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricTimeline>?): Boolean {
            val shouldRemove = size > maxMemoryEntries
            if (shouldRemove && eldest != null) {
                Logger.lyrics.d(TAG, "L1 内存缓存驱逐条目: key=${eldest.key}")
            }
            return shouldRemove
        }
    }

    private val writeCount = AtomicInteger(0)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    init {
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs()
        }
    }

    /**
     * 💡 计算标准 SHA-256 缓存键
     * 统一使用 MetadataNormalizer 进行清洗归一化，杜绝键值裂变 (Finding 5)
     * [durationMs] 实施 3 秒分桶（durationMs / 3000L），有效吸收流媒体传输中的毫秒级边界抖动
     */
    data class CacheStats(
        val fileCount: Int,
        val totalBytes: Long
    )

    fun computeCacheKey(spec: LyricsQuerySpec): String {
        val artistKey = spec.cleanArtist
        val titleKey = spec.exactTitle.lowercase().ifEmpty { spec.cleanTitle }
        val durationBucket = if (spec.durationMs > 0) spec.durationMs / 3000L else 0L
        val raw = "v5_${artistKey}_${titleKey}_$durationBucket"

        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun computeCacheKey(artist: String, title: String, durationMs: Long): String {
        return computeCacheKey(MetadataNormalizer.buildQuerySpec(artist = artist, title = title, durationMs = durationMs))
    }

    /**
     * 💡 统计磁盘歌词缓存条目数与占用字节数
     */
    fun getCacheStats(): CacheStats {
        val files = diskCacheDir.listFiles { f -> f.isFile && (f.name.endsWith(".json") || f.name.contains(".tmp_")) }
            ?: return CacheStats(0, 0L)
        var totalBytes = 0L
        var count = 0
        for (f in files) {
            totalBytes += f.length()
            if (f.name.endsWith(".json")) count++
        }
        return CacheStats(fileCount = count, totalBytes = totalBytes)
    }

    /**
     * 💡 清空 L1 内存缓存与 L2 磁盘歌词缓存文件
     */
    fun clearAll(): CacheStats {
        synchronized(memoryCache) {
            memoryCache.clear()
        }
        val files = diskCacheDir.listFiles { f -> f.isFile } ?: return CacheStats(0, 0L)
        var clearedBytes = 0L
        var clearedCount = 0
        for (f in files) {
            val len = f.length()
            if (f.delete()) {
                clearedBytes += len
                if (f.name.endsWith(".json")) clearedCount++
            }
        }
        Logger.lyrics.i(TAG, "已清空歌词两级缓存: count=$clearedCount, bytes=$clearedBytes")
        return CacheStats(fileCount = clearedCount, totalBytes = clearedBytes)
    }

    /**
     * 💡 获取歌词：L1 命中直接返回 (0ms)；L1 未命中查 L2 磁盘 (10-20ms) 并回填 L1
     */
    fun get(key: String): LyricTimeline? {
        synchronized(memoryCache) {
            val memHit = memoryCache[key]
            if (memHit != null) {
                return memHit
            }
        }

        val diskFile = File(diskCacheDir, "$key.json")
        if (!diskFile.exists() || !diskFile.isFile) {
            return null
        }

        try {
            val jsonStr = diskFile.readText(Charsets.UTF_8)
            val timeline = deserializeFromJson(jsonStr)
            if (timeline != null) {
                // 💡 触摸文件修改时间，供 LRU 淘汰判断
                diskFile.setLastModified(System.currentTimeMillis())
                synchronized(memoryCache) {
                    memoryCache[key] = timeline
                }
                return timeline
            } else {
                // 💡 解析失败，删除损坏文件
                Logger.lyrics.w(TAG, "L2 磁盘歌词解析为空，清理损坏文件: ${diskFile.name}")
                diskFile.delete()
            }
        } catch (e: Exception) {
            Logger.lyrics.e(TAG, "L2 磁盘歌词读取异常，删除脏文件: ${diskFile.name}", e)
            diskFile.delete()
        }

        return null
    }

    /**
     * 💡 仅写入 L1 内存缓存（供远端推断的纯音乐结果使用，避免社区数据修正前被磁盘缓存永久锁死）
     */
    fun putMemoryOnly(key: String, timeline: LyricTimeline) {
        synchronized(memoryCache) {
            memoryCache[key] = timeline
        }
    }

    /**
     * 💡 写入歌词：同时更新 L1 内存与 L2 磁盘（原子写入防残损）
     */
    fun put(key: String, timeline: LyricTimeline) {
        synchronized(memoryCache) {
            memoryCache[key] = timeline
        }

        try {
            val jsonStr = serializeToJson(timeline)
            val targetFile = File(diskCacheDir, "$key.json")
            val tempFile = File(diskCacheDir, "$key.tmp_${System.nanoTime()}")

            tempFile.writeText(jsonStr, Charsets.UTF_8)
            if (!tempFile.renameTo(targetFile)) {
                // 💡 Windows / 某些 Linux 环境如果目标文件已存在 renameTo 可能失败，实施覆盖
                targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            // 💡 修复 Finding 5: 节流并在后台 IO 线程异步执行磁盘淘汰检查，杜绝主调用线程 I/O 阻塞
            if (writeCount.incrementAndGet() % TRIM_CHECK_INTERVAL == 0) {
                ioScope.launch {
                    trimDiskCacheIfNeeded()
                }
            }
        } catch (e: Exception) {
            Logger.lyrics.e(TAG, "写入 L2 磁盘歌词失败: key=$key", e)
        }
    }

    /**
     * 💡 淘汰超出限额的旧磁盘缓存
     */
    private fun trimDiskCacheIfNeeded() {
        val files = diskCacheDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= maxDiskEntries) return

        // 按最后修改时间升序排列（最旧排在最前）
        val sorted = files.sortedBy { it.lastModified() }
        val toRemoveCount = files.size - (maxDiskEntries * 0.8).toInt()
        for (i in 0 until toRemoveCount) {
            if (i < sorted.size) {
                sorted[i].delete()
            }
        }
    }

    /**
     * 💡 序列化 LyricTimeline 为 JSON 字符串
     */
    fun serializeToJson(timeline: LyricTimeline): String {
        val root = JSONObject().apply {
            put("source", timeline.source)
            put("format", timeline.format.name)
            put("isInstrumental", timeline.isInstrumental)
            put("durationMs", timeline.durationMs)

            val linesArray = JSONArray()
            for (line in timeline.lines) {
                val lineObj = JSONObject().apply {
                    put("lineIndex", line.lineIndex)
                    put("startMs", line.startMs)
                    put("endMs", line.endMs)
                    put("mainText", line.mainText)
                    line.translationText?.let { put("translationText", it) }

                    if (!line.words.isNullOrEmpty()) {
                        val wordsArray = JSONArray()
                        for (w in line.words) {
                            val wordObj = JSONObject().apply {
                                put("text", w.text)
                                put("startMs", w.startMs)
                                put("endMs", w.endMs)
                            }
                            wordsArray.put(wordObj)
                        }
                        put("words", wordsArray)
                    }
                }
                linesArray.put(lineObj)
            }
            put("lines", linesArray)
        }
        return root.toString()
    }

    /**
     * 💡 反序列化 JSON 为 LyricTimeline
     */
    fun deserializeFromJson(jsonStr: String): LyricTimeline? {
        return try {
            val root = JSONObject(jsonStr)
            val source = root.optString("source", "unknown")
            val formatStr = root.optString("format", LyricFormat.PLAIN_TEXT.name)
            val format = try {
                LyricFormat.valueOf(formatStr)
            } catch (e: Exception) {
                LyricFormat.PLAIN_TEXT
            }
            val isInstrumental = root.optBoolean("isInstrumental", false)
            val durationMs = root.optLong("durationMs", 0L)

            val linesArray = root.optJSONArray("lines") ?: JSONArray()
            val lines = ArrayList<LyricLine>(linesArray.length())

            for (i in 0 until linesArray.length()) {
                val lineObj = linesArray.getJSONObject(i)
                val lineIndex = lineObj.optInt("lineIndex", i)
                val startMs = lineObj.optLong("startMs", 0L)
                val endMs = lineObj.optLong("endMs", 0L)
                val mainText = lineObj.optString("mainText", "")
                val translationText = if (lineObj.has("translationText") && !lineObj.isNull("translationText")) {
                    val t = lineObj.optString("translationText")
                    if (t.equals("null", ignoreCase = true) || t.isBlank()) null else t
                } else null

                var words: List<LyricWord>? = null
                val wordsArray = lineObj.optJSONArray("words")
                if (wordsArray != null && wordsArray.length() > 0) {
                    val wordList = ArrayList<LyricWord>(wordsArray.length())
                    for (j in 0 until wordsArray.length()) {
                        val wObj = wordsArray.getJSONObject(j)
                        wordList.add(
                            LyricWord(
                                text = wObj.optString("text", ""),
                                startMs = wObj.optLong("startMs", 0L),
                                endMs = wObj.optLong("endMs", 0L)
                            )
                        )
                    }
                    words = wordList
                }

                lines.add(
                    LyricLine(
                        lineIndex = lineIndex,
                        startMs = startMs,
                        endMs = endMs,
                        mainText = mainText,
                        translationText = translationText,
                        words = words
                    )
                )
            }

            // 💡 损坏/异常脏数据防御：非纯音乐但有效歌词行为空，或所有行文本均为 "null" / 空白
            if (!isInstrumental) {
                if (lines.isEmpty() || lines.all { it.mainText.isBlank() || it.mainText.equals("null", ignoreCase = true) }) {
                    Logger.lyrics.w(TAG, "反序列化歌词检测到脏数据（全部为 null 或空行），拒绝采纳并触发磁盘缓存自愈删除")
                    return null
                }
            }

            LyricTimeline(
                source = source,
                format = format,
                isInstrumental = isInstrumental,
                lines = lines,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Logger.lyrics.e(TAG, "解析 JSON 歌词失败", e)
            null
        }
    }

    companion object {
        private const val TAG = "LyricsCacheRepo"
        const val DEFAULT_MAX_MEMORY_ENTRIES = 50
        const val DEFAULT_MAX_DISK_ENTRIES = 1000
        const val TRIM_CHECK_INTERVAL = 20
    }
}
