package com.solo.purecast.airplay

import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.MetadataPatchEvent
import com.solo.purecast.core.session.ProgressUpdateEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.StreamResumeEvent
import com.solo.purecast.core.session.StreamSetupEvent
import com.solo.purecast.core.session.StreamTeardownEvent
import com.solo.purecast.core.session.TrackBoundaryEvent
import com.solo.purecast.core.session.TrackSwitchEvent
import com.solo.purecast.model.DurationState
import com.solo.purecast.model.SeekCapability
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata
import com.solo.purecast.util.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 💡 无状态 AirPlay C++ 回调事件翻译与分派器
 *
 * 将 RAOP C++ 层带 [nativeSessionId] 与递增 [sequence] 的回调统一封包为
 * [SessionEventEnvelope]，映射为全局唯一协调器 `sessionId` 并过滤过期会话回调。
 */
internal class AirPlayNativeEventDispatcher(
    private val dispatchEvent: (SessionEventEnvelope<SessionEventPayload>) -> Unit,
    private val allocateSessionId: () -> Long,
    private val allocateSequence: () -> Long,
    private val onAcquireHardwareForStream: (sampleRate: Int, bitDepth: Int, channels: Int, codec: String) -> Long,
    private val onDecryptAndInjectCryptoKey: (aesKeyBase64: String, aesIvBase64: String) -> Unit,
    private val onUpdateDacpTarget: (clientIp: String, dacpId: String, activeRemoteToken: String, dacpPort: Int) -> Unit,
    private val onSolveChallenge: (challengeBase64: String) -> String?,
    private val onRtpActiveChanged: (Boolean) -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : NativeAirPlayCallback {

    private val nativeToGlobalSessionMap = ConcurrentHashMap<Long, Long>()
    private val lastStateSequenceByNative = ConcurrentHashMap<Long, Long>()
    private val latestNativeSessionId = AtomicLong(0L)
    private val trackGenerationGenerator = AtomicLong(0L)
    private val lastSeekTimestampMs = AtomicLong(0L)
    private val eventDispatchLock = Any()

    @Volatile
    private var currentTrackGeneration: Long = 0L

    @Volatile
    private var currentTrackTitle: String = ""

    @Volatile
    private var currentTrackArtist: String = ""

    @Volatile
    private var currentTrackDurationFixed: Boolean = false

    @Volatile
    private var currentTrackMarkedLive: Boolean = false

    @Volatile
    private var postFlushProgressDurationMs: Long = 0L

    @Volatile
    private var postFlushProgressPositionMs: Long = -1L

    @Volatile
    private var postFlushProgressTimestampMs: Long = 0L

    @Volatile
    private var lastTrackSwitchFlushedTimestampMs: Long = 0L

    // 💡 记录最近一次无 FLUSH 场景（任意位置首次投屏 / 自然自动切歌）到达的协议进度，防止紧随其后的 DMAP 边界事件误归零
    @Volatile
    private var recentProgressPositionMs: Long = -1L

    @Volatile
    private var recentProgressDurationMs: Long = 0L

    @Volatile
    private var recentProgressTimestampMs: Long = 0L

    @Volatile
    private var pendingOrphanCoverBytes: ByteArray? = null

    @Volatile
    private var pendingOrphanCoverTimestampMs: Long = 0L

    @Volatile
    private var lastActiveMetadata: TrackMetadata? = null

    @Volatile
    private var lastClientIp: String? = null

    fun resetSessions() {
        nativeToGlobalSessionMap.clear()
        lastStateSequenceByNative.clear()
        lastSeekTimestampMs.set(0L)
        postFlushProgressDurationMs = 0L
        postFlushProgressPositionMs = -1L
        postFlushProgressTimestampMs = 0L
        lastTrackSwitchFlushedTimestampMs = 0L
        recentProgressPositionMs = -1L
        recentProgressDurationMs = 0L
        recentProgressTimestampMs = 0L
        pendingOrphanCoverBytes = null
        pendingOrphanCoverTimestampMs = 0L
        lastActiveMetadata = null
        lastClientIp = null
        onRtpActiveChanged(false)
    }

    /**
     * 💡 标记 TV 本机触发了寻道请求，在 4000ms 窗口期内将紧随其后的 FLUSH 标记为 isSeekFlush = true，
     * 确保协调器保留用户拖动进度条设定的目标播放位置，彻底解决 AirPlay 1 寻道后进度条瞬间归零的问题。
     */
    fun markPendingSeek() {
        lastSeekTimestampMs.set(clockMs())
    }

    /**
     * 💡 校验给定全局会话是否仍属于当前最新活跃 AirPlay 原生会话，防止延迟到达的抢占清理误杀后续新会话
     */
    fun hasSession(globalSessionId: Long): Boolean {
        if (globalSessionId <= 0L) return false
        synchronized(eventDispatchLock) {
            val latestNative = latestNativeSessionId.get()
            return nativeToGlobalSessionMap[latestNative] == globalSessionId
        }
    }

    override fun onStreamRequested(
        nativeSessionId: Long,
        sequence: Long,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        codec: String,
        clientIp: String,
        aesKeyBase64: String,
        aesIvBase64: String,
        dacpId: String,
        activeRemoteToken: String,
        dacpPort: Int
    ): Long {
        onDecryptAndInjectCryptoKey(aesKeyBase64, aesIvBase64)
        onUpdateDacpTarget(clientIp, dacpId, activeRemoteToken, dacpPort)

        val resolvedCodec = codec.ifBlank { "ALAC" }
        val acquiredEpoch = onAcquireHardwareForStream(sampleRate, bitDepth, channels, resolvedCodec)
        if (acquiredEpoch <= 0L) {
            Logger.airplay.w(TAG, "AirPlay 推流请求被拒绝或硬件未就绪: nativeSid=$nativeSessionId")
            return -1L
        }

        val globalSessionId = allocateSessionId()
        val initialTxGen: Long
        synchronized(eventDispatchLock) {
            val isSameClient = (lastClientIp != null && lastClientIp == clientIp) ||
                (lastClientIp == null && clientIp.isNotBlank())
            lastClientIp = clientIp
            val isSameNativeSession = (latestNativeSessionId.get() == nativeSessionId) || isSameClient
            latestNativeSessionId.set(nativeSessionId)
            nativeToGlobalSessionMap[nativeSessionId] = globalSessionId
            lastStateSequenceByNative[nativeSessionId] = sequence

            // 💡 修复 Finding 5: 续播识别支持同客户端跨 SETUP 重连保持已有元数据，杜绝暂停续播时歌名封面被刷空
            val cachedMeta = lastActiveMetadata
            val isResuming = isSameNativeSession &&
                cachedMeta != null &&
                cachedMeta.title.isNotBlank() &&
                cachedMeta.title != "AirPlay 音频直通"

            // 💡 同曲目 TEARDOWN -> SETUP 续播复用当前 trackGeneration，避免触发歌词大屏重载与时钟重置；
            // 若暂停期间手机已切换为新歌，紧随其后的 DMAP 元数据包会检测到标题变更并正常递增 trackGeneration。
            initialTxGen = if (isResuming && currentTrackGeneration > 0L) {
                currentTrackGeneration
            } else {
                trackGenerationGenerator.incrementAndGet()
            }
            currentTrackGeneration = initialTxGen

            if (!isResuming) {
                lastActiveMetadata = null
            }

            val initialMetadata = if (isResuming) {
                cachedMeta!!
            } else {
                TrackMetadata(
                    title = "AirPlay 音频直通",
                    artist = "AirPlay 设备",
                    album = "",
                    durationMs = 0L,
                    durationState = DurationState.UNKNOWN,
                    seekCapability = SeekCapability.UNKNOWN
                )
            }
            currentTrackTitle = initialMetadata.title
            currentTrackArtist = initialMetadata.artist
            currentTrackDurationFixed = initialMetadata.durationMs > 0L
            currentTrackMarkedLive = false
            if (!isResuming) {
                recentProgressPositionMs = -1L
                recentProgressDurationMs = 0L
                recentProgressTimestampMs = 0L
            }
            emitLocked(
                sessionId = globalSessionId,
                payload = StreamSetupEvent(
                    protocol = StreamProtocol.AIRPLAY_1,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    codec = resolvedCodec,
                    initialEpoch = acquiredEpoch,
                    trackGeneration = initialTxGen,
                    initialMetadata = initialMetadata
                )
            )
        }
        onRtpActiveChanged(true)
        return acquiredEpoch
    }

    override fun onStreamStopped(nativeSessionId: Long, sequence: Long, epoch: Long) {
        onRtpActiveChanged(false)
        synchronized(eventDispatchLock) {
            val globalSid = acceptActiveStateEventLocked(nativeSessionId, sequence) ?: return
            nativeToGlobalSessionMap.remove(nativeSessionId)
            lastStateSequenceByNative.remove(nativeSessionId)
            // 💡 契约修复：RTSP TEARDOWN 属于传输通道挂起，由状态机转入 10 分钟 PAUSED 挂起 (hardStop = false)，
            // 严禁作为 hardStop 硬停止导致大屏闪烁跳回等待投屏与 10s 误释放声卡。
            emitLocked(
                sessionId = globalSid,
                payload = StreamTeardownEvent(hardStop = false)
            )
        }
    }

    override fun onStreamPaused(nativeSessionId: Long, sequence: Long, epoch: Long) {
        synchronized(eventDispatchLock) {
            val globalSid = acceptActiveStateEventLocked(nativeSessionId, sequence) ?: return
            emitLocked(sessionId = globalSid, payload = ExplicitPauseEvent(epoch = epoch))
        }
    }

    override fun onStreamResumed(nativeSessionId: Long, sequence: Long, epoch: Long) {
        onRtpActiveChanged(true)
        synchronized(eventDispatchLock) {
            val globalSid = acceptActiveStateEventLocked(nativeSessionId, sequence) ?: return
            emitLocked(
                sessionId = globalSid,
                payload = StreamResumeEvent(
                    resumedEpoch = epoch,
                    protocol = StreamProtocol.AIRPLAY_1
                )
            )
        }
    }

    override fun onTrackSwitchFlushed(nativeSessionId: Long, sequence: Long, newEpoch: Long) {
        synchronized(eventDispatchLock) {
            val globalSid = acceptActiveStateEventLocked(nativeSessionId, sequence) ?: return
            val seekTs = lastSeekTimestampMs.get()
            val isSeek = seekTs > 0L && (clockMs() - seekTs) < 4000L
            if (isSeek) {
                lastSeekTimestampMs.set(0L) // 💡 消费单次寻道标记
                Logger.airplay.i(TAG, "AirPlay 寻道触发 FLUSH 响应: sid=$globalSid, newEpoch=$newEpoch, isSeekFlush=true")
            } else {
                lastTrackSwitchFlushedTimestampMs = clockMs()
                postFlushProgressDurationMs = 0L // 💡 切歌换代重置已知时长缓存，防止旧曲目时长泄漏
                postFlushProgressPositionMs = -1L // 💡 切歌换代重置已知初始进度缓存
                postFlushProgressTimestampMs = 0L
                recentProgressPositionMs = -1L
                recentProgressDurationMs = 0L
                recentProgressTimestampMs = 0L
            }
            emitLocked(
                sessionId = globalSid,
                payload = TrackSwitchEvent(newEpoch = newEpoch, isSeekFlush = isSeek)
            )
        }
    }

    override fun onFirstDataPacket(
        nativeSessionId: Long,
        sequence: Long,
        epoch: Long,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        codec: String
    ) {
        onRtpActiveChanged(true)
        synchronized(eventDispatchLock) {
            if (nativeSessionId != latestNativeSessionId.get()) return
            val globalSid = nativeToGlobalSessionMap[nativeSessionId] ?: return
            emitLocked(
                sessionId = globalSid,
                payload = FirstDataPacketEvent(
                    epoch = epoch,
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    codec = codec.ifBlank { "ALAC" },
                    protocol = StreamProtocol.AIRPLAY_1
                )
            )
        }
    }

    override fun onChallengeRequested(challengeBase64: String): String? =
        onSolveChallenge(challengeBase64)

    override fun onMetadataReceived(
        nativeSessionId: Long,
        sequence: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverArt: ByteArray?
    ) {
        synchronized(eventDispatchLock) {
            if (nativeSessionId != latestNativeSessionId.get()) return
            val globalSid = nativeToGlobalSessionMap[nativeSessionId] ?: return

            val trimmedTitle = title.trim()
            val trimmedArtist = artist.trim()
            val trimmedAlbum = album.trim()

            // 💡 过滤 iOS 切歌前瞬发的占位加载标题（如 "正在载入…" / "Loading…"），无论是否附带占位图均不触发假切歌边界
            if (trimmedTitle.isNotEmpty() &&
                trimmedTitle.lowercase() in TRANSIENT_LOADING_TITLES
            ) {
                Logger.airplay.d(TAG, "忽略 iOS 瞬态加载占位标题: title=$trimmedTitle")
                return
            }

            // 💡 孤立封面先于文本到达场景 (image/jpeg 报文没有 title/artist)
            if (trimmedTitle.isEmpty() && trimmedArtist.isEmpty() && coverArt != null && coverArt.isNotEmpty()) {
                pendingOrphanCoverBytes = coverArt.copyOf()
                pendingOrphanCoverTimestampMs = clockMs()
            }

            val now = clockMs()
            val orphanCoverValid = (now - pendingOrphanCoverTimestampMs) <= 2500L
            val effectiveCover = if (coverArt != null && coverArt.isNotEmpty()) {
                coverArt
            } else if (orphanCoverValid) {
                pendingOrphanCoverBytes
            } else {
                null
            }

            val isPlaceholderTitle = currentTrackTitle == "AirPlay 音频直通"
            val isBoundarySwitch = trimmedTitle.isNotEmpty() &&
                currentTrackTitle.isNotEmpty() &&
                (trimmedTitle != currentTrackTitle ||
                    (trimmedArtist.isNotEmpty() && currentTrackArtist.isNotEmpty() && trimmedArtist != currentTrackArtist))

            val postFlushElapsedMs = if (postFlushProgressTimestampMs > 0L) (now - postFlushProgressTimestampMs) else -1L
            val postFlushProgressFresh = postFlushElapsedMs in 0L..4000L
            val postFlushDurationMatchesDmap = durationMs <= 0L || postFlushProgressDurationMs <= 0L ||
                kotlin.math.abs(postFlushProgressDurationMs - durationMs) <= 2000L
            val canInheritPostFlushProgress = postFlushProgressFresh && postFlushDurationMatchesDmap
            if (!postFlushProgressFresh && postFlushProgressTimestampMs > 0L) {
                postFlushProgressDurationMs = 0L
                postFlushProgressPositionMs = -1L
                postFlushProgressTimestampMs = 0L
            }

            val recentElapsedMs = if (recentProgressTimestampMs > 0L) (now - recentProgressTimestampMs) else -1L
            val recentProgressFresh = recentElapsedMs in 0L..4000L
            // 💡 严禁在无 FLUSH 切歌时把上一首歌的旧进度（如 11908ms）泄漏给新歌：
            // 仅在冷启动占位标题（首次中途投屏）或先到的 progress 时长与新曲 DMAP 时长精确匹配（±2s）时才允许继承！
            val durationMatchesIncomingDmap = durationMs > 0L && recentProgressDurationMs > 0L &&
                kotlin.math.abs(recentProgressDurationMs - durationMs) <= 2000L
            val canInheritRecentProgressOnNoFlush = recentProgressFresh && recentProgressPositionMs >= 0L && (
                isPlaceholderTitle ||
                durationMatchesIncomingDmap
            )

            // 💡 支持 FLUSH 切歌（4s 有效期内且时长匹配）与无 FLUSH 场景继承先到的真实时长与开播进度，
            // 并在继承无 FLUSH 进度时补偿到达至今的流逝时间，避免 DMAP 晚到 1~2 秒后将进度倒拉回旧快照
            val effectiveDurationMs = when {
                durationMs > 0L -> durationMs
                canInheritPostFlushProgress && postFlushProgressDurationMs > 0L -> postFlushProgressDurationMs
                canInheritRecentProgressOnNoFlush && recentProgressDurationMs > 0L -> recentProgressDurationMs
                else -> 0L
            }
            val effectivePositionMs = when {
                canInheritPostFlushProgress && postFlushProgressPositionMs >= 0L -> postFlushProgressPositionMs
                canInheritRecentProgressOnNoFlush -> {
                    val advanced = recentProgressPositionMs + recentElapsedMs.coerceAtLeast(0L)
                    if (effectiveDurationMs > 0L) advanced.coerceAtMost(effectiveDurationMs) else advanced
                }
                else -> 0L
            }

            if (isBoundarySwitch) {
                val nextTxGen = trackGenerationGenerator.incrementAndGet()
                currentTrackGeneration = nextTxGen
                currentTrackDurationFixed = effectiveDurationMs > 0L
                currentTrackMarkedLive = false
                Logger.airplay.i(
                    TAG,
                    "确立新曲目边界: gen=$nextTxGen, title=$trimmedTitle, artist=$trimmedArtist, initialPos=${effectivePositionMs}ms, initialDur=${effectiveDurationMs}ms"
                )
                emitLocked(
                    sessionId = globalSid,
                    payload = TrackBoundaryEvent(
                        trackGeneration = nextTxGen,
                        trackKey = "$trimmedTitle|$trimmedArtist",
                        initialDurationState = if (effectiveDurationMs > 0L) DurationState.FIXED else DurationState.UNKNOWN,
                        seekCapability = if (effectiveDurationMs > 0L) SeekCapability.SEEKABLE else SeekCapability.UNKNOWN,
                        initialPositionMs = effectivePositionMs,
                        initialDurationMs = effectiveDurationMs
                    )
                )
                postFlushProgressDurationMs = 0L // 💡 消费后清空
                postFlushProgressPositionMs = -1L // 💡 消费后清空
                postFlushProgressTimestampMs = 0L
                recentProgressPositionMs = -1L
                recentProgressDurationMs = 0L
                recentProgressTimestampMs = 0L
                lastTrackSwitchFlushedTimestampMs = 0L
                // 💡 修复 Finding 4: 切歌边界彻底清空上一曲残留的孤立封面缓存，杜绝无封面歌曲误继承旧封面
                pendingOrphanCoverBytes = null
            }

            if (trimmedTitle.isNotEmpty()) {
                currentTrackTitle = trimmedTitle
            }
            if (trimmedArtist.isNotEmpty()) {
                currentTrackArtist = trimmedArtist
            }
            if (effectiveDurationMs > 0L) {
                currentTrackDurationFixed = true
                currentTrackMarkedLive = false
                try {
                    // 💡 修复 Finding 3: 传入 -1L 保持 Native 已有播放进度，严禁硬编码 0L 冲刷进度归零
                    com.solo.purecast.core.nativebridge.NativeAudioEngine.updateAirPlayTrackProgress(-1L, effectiveDurationMs)
                } catch (t: Throwable) {
                    Logger.airplay.w(TAG, "同步 Native 曲目时长异常: ${t.message}")
                }
            }

            val patchedDurationState = when {
                effectiveDurationMs > 0L -> DurationState.FIXED
                !currentTrackDurationFixed && !currentTrackMarkedLive -> DurationState.UNKNOWN
                else -> null
            }
            val patchedSeek = when {
                effectiveDurationMs > 0L -> SeekCapability.SEEKABLE
                !currentTrackDurationFixed && !currentTrackMarkedLive -> SeekCapability.UNKNOWN
                else -> null
            }

            val patch = MetadataPatchEvent(
                trackGeneration = currentTrackGeneration,
                title = trimmedTitle.ifEmpty { null },
                artist = trimmedArtist.ifEmpty { null },
                album = trimmedAlbum.ifEmpty { null },
                coverBytes = effectiveCover?.takeIf { it.isNotEmpty() },
                durationMs = effectiveDurationMs.takeIf { it > 0L },
                durationState = patchedDurationState,
                seekCapability = patchedSeek
            )

            // 💡 维护活跃会话元数据快照，以便在 RTSP TEARDOWN 挂起恢复时无缝继承
            val prevMeta = lastActiveMetadata ?: TrackMetadata()
            lastActiveMetadata = prevMeta.copy(
                title = patch.title ?: prevMeta.title,
                artist = patch.artist ?: prevMeta.artist,
                album = patch.album ?: prevMeta.album,
                coverBytes = patch.coverBytes ?: prevMeta.coverBytes,
                durationMs = patch.durationMs ?: prevMeta.durationMs,
                durationState = patch.durationState ?: prevMeta.durationState,
                seekCapability = patch.seekCapability ?: prevMeta.seekCapability
            )
            // 💡 仅当当前报文包含歌名或艺术家信息且已成功消费孤立封面时，才清空 pendingOrphanCoverBytes，
            // 严禁在纯封面报文 (image/jpeg) 自身到达时误清空，确保紧随其后的曲目元数据包能够完整继承封面！
            if ((trimmedTitle.isNotEmpty() || trimmedArtist.isNotEmpty()) && effectiveCover != null && effectiveCover.isNotEmpty()) {
                pendingOrphanCoverBytes = null
            }

            emitLocked(
                sessionId = globalSid,
                payload = patch
            )
        }
    }

    override fun onProgressUpdated(nativeSessionId: Long, sequence: Long, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L && durationMs <= 0L) {
            return
        }
        synchronized(eventDispatchLock) {
            if (nativeSessionId != latestNativeSessionId.get()) return
            val globalSid = nativeToGlobalSessionMap[nativeSessionId] ?: return

            val timeSinceFlush = if (lastTrackSwitchFlushedTimestampMs > 0L) {
                clockMs() - lastTrackSwitchFlushedTimestampMs
            } else {
                -1L
            }
            if (timeSinceFlush in 0L..4000L) {
                if (durationMs > 0L) {
                    postFlushProgressDurationMs = durationMs
                }
                if (positionMs >= 0L) {
                    postFlushProgressPositionMs = positionMs
                }
                postFlushProgressTimestampMs = clockMs()
            }
            if (positionMs >= 0L || durationMs > 0L) {
                if (positionMs >= 0L) {
                    recentProgressPositionMs = positionMs
                }
                if (durationMs > 0L) {
                    recentProgressDurationMs = durationMs
                }
                recentProgressTimestampMs = clockMs()
            }

            Logger.airplay.d(
                TAG,
                "AirPlay 进度上报: pos=${positionMs}ms, dur=${durationMs}ms, trackGen=$currentTrackGeneration, timeSinceFlush=$timeSinceFlush"
            )

            if (durationMs > 0L) {
                // 💡 同步更新活跃会话快照中的时长与寻道能力
                val prev = lastActiveMetadata ?: TrackMetadata()
                if (prev.durationMs != durationMs) {
                    lastActiveMetadata = prev.copy(
                        durationMs = durationMs,
                        durationState = DurationState.FIXED,
                        seekCapability = SeekCapability.SEEKABLE
                    )
                }

                if (!currentTrackDurationFixed) {
                    currentTrackDurationFixed = true
                    currentTrackMarkedLive = false
                    emitLocked(
                        sessionId = globalSid,
                        payload = MetadataPatchEvent(
                            trackGeneration = currentTrackGeneration,
                            durationMs = durationMs,
                            durationState = DurationState.FIXED,
                            seekCapability = SeekCapability.SEEKABLE
                        )
                    )
                }
                try {
                    com.solo.purecast.core.nativebridge.NativeAudioEngine.updateAirPlayTrackProgress(positionMs, durationMs)
                } catch (t: Throwable) {
                    Logger.airplay.w(TAG, "同步 Native 曲目进度异常: ${t.message}")
                }
            }

            // 💡 修复 Finding 5: 当 positionMs < 0L 时说明首帧尚在本地缓冲区排队，已通过上方 MetadataPatchEvent 校准了总时长，
            // 绝不发射 ProgressUpdateEvent，杜绝将已走字进度归零
            if (positionMs >= 0L) {
                emitLocked(
                    sessionId = globalSid,
                    payload = ProgressUpdateEvent(
                        positionMs = positionMs,
                        durationMs = durationMs.coerceAtLeast(0L),
                        trackGeneration = currentTrackGeneration
                    )
                )
            }
        }
    }

    private fun acceptActiveStateEventLocked(nativeSessionId: Long, nativeSeq: Long): Long? {
        if (nativeSessionId != latestNativeSessionId.get()) {
            Logger.airplay.d(TAG, "丢弃非最新 AirPlay native 会话回调: sid=$nativeSessionId")
            return null
        }
        val prevSeq = lastStateSequenceByNative[nativeSessionId] ?: 0L
        if (nativeSeq <= prevSeq) {
            Logger.airplay.d(TAG, "丢弃乱序 AirPlay 状态回调: sid=$nativeSessionId, seq=$nativeSeq <= $prevSeq")
            return null
        }
        lastStateSequenceByNative[nativeSessionId] = nativeSeq
        return nativeToGlobalSessionMap[nativeSessionId]
    }

    private fun emitLocked(sessionId: Long, payload: SessionEventPayload) {
        dispatchEvent(
            SessionEventEnvelope(
                origin = EventOrigin.AIRPLAY,
                sessionId = sessionId,
                deviceGeneration = 0L,
                sequence = allocateSequence(),
                timestampElapsedMs = clockMs(),
                payload = payload
            )
        )
    }

    companion object {
        private const val TAG = "AirPlayEventDispatcher"

        private val TRANSIENT_LOADING_TITLES = setOf(
            "正在载入…",
            "正在载入...",
            "loading…",
            "loading..."
        )
    }
}
