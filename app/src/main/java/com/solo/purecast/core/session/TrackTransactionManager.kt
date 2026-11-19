package com.solo.purecast.core.session

import com.solo.purecast.model.DurationState
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.util.Logger

/**
 * 💡 曲目边界事件处理回执
 */
data class TrackBoundaryResult(
    val accepted: Boolean,
    val activeGeneration: Long,
    val metadata: TrackMetadata
)

/**
 * 💡 曲目事务与元数据分包增量合并管理器
 *
 * 架构红线（R6, R8）：
 * 1. 仅在收到有效新 [TrackBoundaryEvent] 时自增/切换 [currentTrackGeneration]；
 * 2. 同曲目边界内的增量 [MetadataPatchEvent] 仅覆写非空有效字段，缺失字段自动沿用当前代已知信息；
 * 3. 任何携带过期或非活跃 [MetadataPatchEvent.trackGeneration] 的延迟分包一律丢弃；
 * 4. 切歌边界只复位时长/寻道能力为新境界初始值，**绝不预先把现有封面 `coverBytes` 置 `null`**，保证封面视觉稳态交由 UI 层平滑淡入淡出。
 */
class TrackTransactionManager {

    var currentTrackGeneration: Long = 0L
        private set

    var currentTrackKey: String? = null
        private set

    /**
     * 💡 记录当前持有的封面元数据所属的曲目代数。
     * 若切歌后新曲目未上报新封面，此值保持旧代数，直到 1200ms 宽限期超时被自动清理。
     */
    var coverTrackGeneration: Long = 0L
        private set

    var currentMetadata: TrackMetadata = TrackMetadata()
        private set

    /**
     * 仅在协议层明确划定新曲目边界时切换曲目代数
     */
    fun onTrackBoundary(event: TrackBoundaryEvent): TrackBoundaryResult {
        if (event.trackGeneration <= currentTrackGeneration) {
            Logger.service.d(
                TAG,
                "忽略过期或重复曲目边界: incomingGen=${event.trackGeneration}, activeGen=$currentTrackGeneration"
            )
            return TrackBoundaryResult(
                accepted = false,
                activeGeneration = currentTrackGeneration,
                metadata = currentMetadata
            )
        }

        currentTrackGeneration = event.trackGeneration
        currentTrackKey = event.trackKey
        // 💡 R8: 切歌边界保留现有封面 coverBytes，只刷新新境界初始时长与寻道能力
        currentMetadata = currentMetadata.copy(
            durationMs = if (event.initialDurationMs > 0L) event.initialDurationMs else 0L,
            durationState = event.initialDurationState,
            seekCapability = event.seekCapability,
            trackUri = event.trackUri
        )

        Logger.service.i(
            TAG,
            "确立新曲目事务边界: gen=$currentTrackGeneration, key=${event.trackKey}, durationState=${event.initialDurationState}"
        )
        return TrackBoundaryResult(
            accepted = true,
            activeGeneration = currentTrackGeneration,
            metadata = currentMetadata
        )
    }

    /**
     * 在当前活跃 [currentTrackGeneration] 内增量合并元数据补丁
     * @return 成功合并返回最新 [TrackMetadata]；若代数不匹配返回 `null`
     */
    fun applyMetadataPatch(patch: MetadataPatchEvent): TrackMetadata? {
        if (patch.trackGeneration != currentTrackGeneration) {
            Logger.service.d(
                TAG,
                "丢弃非活跃曲目代数补丁: patchGen=${patch.trackGeneration}, activeGen=$currentTrackGeneration"
            )
            return null
        }

        val patchBytes = patch.coverBytes
        val hasNewBytes = patchBytes != null && patchBytes.isNotEmpty()
        val patchUri = patch.coverUri
        val hasNewUri = patchUri != null && patchUri.isNotEmpty()

        val nextCoverBytes: ByteArray?
        val nextCoverUri: String?

        if (hasNewBytes && hasNewUri) {
            nextCoverBytes = patchBytes
            nextCoverUri = patchUri
            coverTrackGeneration = currentTrackGeneration
        } else if (hasNewBytes) {
            nextCoverBytes = patchBytes
            nextCoverUri = null
            coverTrackGeneration = currentTrackGeneration
        } else if (hasNewUri) {
            nextCoverBytes = null
            nextCoverUri = patchUri
            coverTrackGeneration = currentTrackGeneration
        } else {
            nextCoverBytes = currentMetadata.coverBytes
            nextCoverUri = currentMetadata.coverUri
        }

        currentMetadata = currentMetadata.copy(
            title = patch.title ?: currentMetadata.title,
            artist = patch.artist ?: currentMetadata.artist,
            album = patch.album ?: currentMetadata.album,
            coverUri = nextCoverUri,
            coverBytes = nextCoverBytes,
            durationMs = patch.durationMs ?: currentMetadata.durationMs,
            durationState = patch.durationState ?: currentMetadata.durationState,
            seekCapability = patch.seekCapability ?: currentMetadata.seekCapability
        )
        return currentMetadata
    }

    /**
     * 💡 切歌 1200ms 宽限期超时后，若新曲目仍未认领封面（如无封面曲目），清除上一曲残留的封面字段
     */
    fun clearStaleCoverIfUnclaimed(targetGeneration: Long): TrackMetadata? {
        if (targetGeneration != currentTrackGeneration) return null
        if (coverTrackGeneration >= currentTrackGeneration) return null
        val hasBytes = currentMetadata.coverBytes != null
        val hasUri = currentMetadata.coverUri != null
        if (hasBytes || hasUri) {
            Logger.service.i(
                TAG,
                "曲目代数 $targetGeneration 在 1200ms 宽限期内未认领新封面，优雅清除上一曲残留封面 (prevGen=$coverTrackGeneration)"
            )
            currentMetadata = currentMetadata.copy(coverBytes = null, coverUri = null)
            return currentMetadata
        }
        return null
    }

    /**
     * 用新流初始元数据原子重建第 1 代曲目事务（例如建流时已解析到完整标签）
     */
    fun resetForNewStream(generation: Long, initialMetadata: TrackMetadata?): TrackMetadata {
        currentTrackGeneration = generation
        currentTrackKey = initialMetadata?.trackUri ?: "stream-$generation"
        val hasInitialCover = initialMetadata != null &&
            (initialMetadata.coverBytes != null || initialMetadata.coverUri != null)
        coverTrackGeneration = if (hasInitialCover) generation else 0L
        currentMetadata = initialMetadata ?: TrackMetadata(
            durationState = DurationState.UNKNOWN,
            seekCapability = SeekCapability.UNKNOWN
        )
        return currentMetadata
    }

    /**
     * 彻底重置事务状态（会话完全终止返回 IDLE 时调用）
     */
    fun reset() {
        currentTrackGeneration = 0L
        coverTrackGeneration = 0L
        currentTrackKey = null
        currentMetadata = TrackMetadata()
    }

    companion object {
        private const val TAG = "TrackTx"
    }
}
