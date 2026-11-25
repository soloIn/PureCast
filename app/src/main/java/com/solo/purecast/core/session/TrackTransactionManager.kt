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
     * 💡 记录当前持有的封面元数据所属的曲目代数与专辑名。
     * 若切歌后新曲目未上报新封面，仅当新曲目显式上报了相同的非空专辑名时视为同专辑续期认领；
     * 否则保持旧代数，直到 1200ms 宽限期超时被自动清理。
     */
    var coverTrackGeneration: Long = 0L
        private set

    private var coverOriginGeneration: Long = 0L
    private var coverRenewedInCurrentGen: Boolean = false
    private var coverArtistBeforeRenewal: String = ""
    private var hasAlbumInCurrentGen: Boolean = false
    private var hasArtistInCurrentGen: Boolean = false

    private var coverAlbum: String = ""
    private var coverArtist: String = ""

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
        hasAlbumInCurrentGen = false
        hasArtistInCurrentGen = false
        coverRenewedInCurrentGen = false
        coverArtistBeforeRenewal = ""
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
        val nextArtist = patch.artist ?: currentMetadata.artist
        val nextAlbum = patch.album ?: currentMetadata.album
        val explicitPatchArtist = if (patch.artist != null) normalizeCoverArtist(patch.artist) else ""
        val explicitPatchAlbum = if (patch.album != null) patch.album.trim() else ""
        if (explicitPatchAlbum.isNotEmpty()) {
            hasAlbumInCurrentGen = true
        }
        if (explicitPatchArtist.isNotEmpty()) {
            hasArtistInCurrentGen = true
        }

        val nextCoverBytes: ByteArray?
        val nextCoverUri: String?

        if (hasNewBytes || hasNewUri) {
            nextCoverBytes = if (hasNewBytes) patchBytes else null
            nextCoverUri = if (hasNewUri) patchUri else null
            coverTrackGeneration = currentTrackGeneration
            coverOriginGeneration = currentTrackGeneration
            coverRenewedInCurrentGen = false
            coverArtistBeforeRenewal = ""
            // 💡 仅当当前代已显式收到专辑/歌手时才记录封面归属，避免封面先于 DMAP 到达时误把上一曲专辑/歌手写入封面归属
            coverAlbum = if (hasAlbumInCurrentGen) nextAlbum.trim() else ""
            coverArtist = if (hasArtistInCurrentGen) normalizeCoverArtist(nextArtist) else ""
        } else if (currentMetadata.coverBytes != null || currentMetadata.coverUri != null) {
            nextCoverBytes = currentMetadata.coverBytes
            nextCoverUri = currentMetadata.coverUri
            if (coverTrackGeneration == currentTrackGeneration) {
                if (!coverRenewedInCurrentGen) {
                    // 💡 同代封面先于 DMAP 文本到达时，补充记录封面所属专辑与歌手
                    if (coverAlbum.isEmpty() && explicitPatchAlbum.isNotEmpty()) {
                        coverAlbum = explicitPatchAlbum
                    }
                    if (coverArtist.isEmpty() && explicitPatchArtist.isNotEmpty()) {
                        coverArtist = explicitPatchArtist
                    }
                } else {
                    // 💡 若本代此前因同名专辑提前续期，后续到达的显式专辑/歌手不匹配时撤销提前续期并回滚本代补记的歌手
                    val albumMismatched = explicitPatchAlbum.isNotEmpty() && !coverAlbum.equals(explicitPatchAlbum, ignoreCase = true)
                    val artistMismatched = explicitPatchArtist.isNotEmpty() && coverArtist.isNotEmpty() &&
                        !coverArtist.equals(explicitPatchArtist, ignoreCase = true)
                    if (albumMismatched || artistMismatched) {
                        coverTrackGeneration = coverOriginGeneration
                        coverRenewedInCurrentGen = false
                        coverArtist = coverArtistBeforeRenewal
                        coverArtistBeforeRenewal = ""
                    } else if (coverArtist.isEmpty() && explicitPatchArtist.isNotEmpty()) {
                        coverArtist = explicitPatchArtist
                    }
                }
            } else {
                val effectiveCurrentArtist = when {
                    explicitPatchArtist.isNotEmpty() -> explicitPatchArtist
                    hasArtistInCurrentGen -> normalizeCoverArtist(nextArtist)
                    else -> ""
                }
                if (explicitPatchAlbum.isNotEmpty() &&
                    coverAlbum.equals(explicitPatchAlbum, ignoreCase = true) &&
                    (effectiveCurrentArtist.isEmpty() || coverArtist.isEmpty() || coverArtist.equals(effectiveCurrentArtist, ignoreCase = true))
                ) {
                    // 💡 同专辑切歌保底：显式上报相同非空专辑且未切换不同歌手时续期封面代数，避免 1200ms 宽限期误清旧封面
                    coverArtistBeforeRenewal = coverArtist
                    coverTrackGeneration = currentTrackGeneration
                    coverRenewedInCurrentGen = true
                    if (coverArtist.isEmpty() && effectiveCurrentArtist.isNotEmpty()) {
                        coverArtist = effectiveCurrentArtist
                    }
                }
            }
        } else {
            nextCoverBytes = null
            nextCoverUri = null
        }

        currentMetadata = currentMetadata.copy(
            title = patch.title ?: currentMetadata.title,
            artist = nextArtist,
            album = nextAlbum,
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
            coverOriginGeneration = 0L
            coverRenewedInCurrentGen = false
            coverArtistBeforeRenewal = ""
            coverAlbum = ""
            coverArtist = ""
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
        val initAlbum = if (initialMetadata != null) initialMetadata.album.trim() else ""
        val initArtist = if (initialMetadata != null) normalizeCoverArtist(initialMetadata.artist) else ""
        hasAlbumInCurrentGen = initAlbum.isNotEmpty()
        hasArtistInCurrentGen = initArtist.isNotEmpty()
        coverTrackGeneration = if (hasInitialCover) generation else 0L
        coverOriginGeneration = if (hasInitialCover) generation else 0L
        coverRenewedInCurrentGen = false
        coverArtistBeforeRenewal = ""
        coverAlbum = if (hasInitialCover) initAlbum else ""
        coverArtist = if (hasInitialCover) initArtist else ""
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
        coverOriginGeneration = 0L
        coverRenewedInCurrentGen = false
        coverArtistBeforeRenewal = ""
        hasAlbumInCurrentGen = false
        hasArtistInCurrentGen = false
        coverAlbum = ""
        coverArtist = ""
        currentTrackKey = null
        currentMetadata = TrackMetadata()
    }

    private fun normalizeCoverArtist(artist: String): String {
        val trimmed = artist.trim()
        return if (trimmed in PLACEHOLDER_ARTISTS) "" else trimmed
    }

    companion object {
        private const val TAG = "TrackTx"
        private val PLACEHOLDER_ARTISTS = setOf(
            TrackMetadata.DEFAULT_ARTIST,
            "PureCast",
            "PureCast UPnP / DLNA 接收端",
            "AirPlay 设备"
        )
    }
}
