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
            durationMs = 0L,
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
        val nextCoverBytes = if (patchBytes != null && patchBytes.isNotEmpty()) {
            patchBytes
        } else {
            currentMetadata.coverBytes
        }

        currentMetadata = currentMetadata.copy(
            title = patch.title ?: currentMetadata.title,
            artist = patch.artist ?: currentMetadata.artist,
            album = patch.album ?: currentMetadata.album,
            coverUri = patch.coverUri ?: currentMetadata.coverUri,
            coverBytes = nextCoverBytes,
            durationMs = patch.durationMs ?: currentMetadata.durationMs,
            durationState = patch.durationState ?: currentMetadata.durationState,
            seekCapability = patch.seekCapability ?: currentMetadata.seekCapability
        )
        return currentMetadata
    }

    /**
     * 用新流初始元数据原子重建第 1 代曲目事务（例如建流时已解析到完整标签）
     */
    fun resetForNewStream(generation: Long, initialMetadata: TrackMetadata?): TrackMetadata {
        currentTrackGeneration = generation
        currentTrackKey = initialMetadata?.trackUri ?: "stream-$generation"
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
        currentTrackKey = null
        currentMetadata = TrackMetadata()
    }

    companion object {
        private const val TAG = "TrackTx"
    }
}
