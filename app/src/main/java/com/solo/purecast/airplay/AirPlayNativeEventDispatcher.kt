package com.solo.purecast.airplay

import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.ExplicitPauseEvent
import com.solo.purecast.core.session.FirstDataPacketEvent
import com.solo.purecast.core.session.ImplicitPauseEvent
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
    private var lastTrackSwitchFlushedTimestampMs: Long = 0L

    @Volatile
    private var pendingOrphanCoverBytes: ByteArray? = null

    @Volatile
    private var pendingOrphanCoverTimestampMs: Long = 0L

    fun resetSessions() {
        nativeToGlobalSessionMap.clear()
        lastStateSequenceByNative.clear()
        lastSeekTimestampMs.set(0L)
        postFlushProgressDurationMs = 0L
        lastTrackSwitchFlushedTimestampMs = 0L
        pendingOrphanCoverBytes = null
        pendingOrphanCoverTimestampMs = 0L
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
            nativeToGlobalSessionMap[nativeSessionId] = globalSessionId
            lastStateSequenceByNative[nativeSessionId] = sequence
            latestNativeSessionId.set(nativeSessionId)
            initialTxGen = trackGenerationGenerator.incrementAndGet()
            currentTrackGeneration = initialTxGen
            currentTrackTitle = ""
            currentTrackArtist = ""
            currentTrackDurationFixed = false
            currentTrackMarkedLive = false

            val initialMetadata = TrackMetadata(
                title = "AirPlay 音频直通",
                artist = "AirPlay 设备",
                album = "",
                durationMs = 0L,
                durationState = DurationState.UNKNOWN,
                seekCapability = SeekCapability.UNKNOWN
            )
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

    override fun onStreamPaused(nativeSessionId: Long, sequence: Long, epoch: Long, isImplicit: Boolean) {
        synchronized(eventDispatchLock) {
            val globalSid = acceptActiveStateEventLocked(nativeSessionId, sequence) ?: return
            val payload: SessionEventPayload = if (isImplicit) {
                ImplicitPauseEvent(epoch = epoch)
            } else {
                ExplicitPauseEvent(epoch = epoch)
            }
            emitLocked(sessionId = globalSid, payload = payload)
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

            val isBoundarySwitch = trimmedTitle.isNotEmpty() &&
                currentTrackTitle.isNotEmpty() &&
                (trimmedTitle != currentTrackTitle ||
                    (trimmedArtist.isNotEmpty() && currentTrackArtist.isNotEmpty() && trimmedArtist != currentTrackArtist))

            // 💡 P0-3: 若切歌 FLUSH 后收到了 progress 报文，继承该时长防止新歌时长被抹零；使用后立即清空避免污染后续曲目
            val effectiveDurationMs = if (durationMs > 0L) {
                durationMs
            } else if (postFlushProgressDurationMs > 0L) {
                postFlushProgressDurationMs
            } else {
                0L
            }

            if (isBoundarySwitch) {
                val nextTxGen = trackGenerationGenerator.incrementAndGet()
                currentTrackGeneration = nextTxGen
                currentTrackDurationFixed = effectiveDurationMs > 0L
                currentTrackMarkedLive = false
                emitLocked(
                    sessionId = globalSid,
                    payload = TrackBoundaryEvent(
                        trackGeneration = nextTxGen,
                        trackKey = "$trimmedTitle|$trimmedArtist",
                        initialDurationState = if (effectiveDurationMs > 0L) DurationState.FIXED else DurationState.UNKNOWN,
                        seekCapability = if (effectiveDurationMs > 0L) SeekCapability.SEEKABLE else SeekCapability.UNKNOWN
                    )
                )
                postFlushProgressDurationMs = 0L // 💡 消费后清空
                lastTrackSwitchFlushedTimestampMs = 0L
                if (effectiveCover != null && effectiveCover === pendingOrphanCoverBytes) {
                    pendingOrphanCoverBytes = null
                }
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
                    com.solo.purecast.core.nativebridge.NativeAudioEngine.updateAirPlayTrackProgress(0L, effectiveDurationMs)
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

            emitLocked(
                sessionId = globalSid,
                payload = MetadataPatchEvent(
                    trackGeneration = currentTrackGeneration,
                    title = trimmedTitle.ifEmpty { null },
                    artist = trimmedArtist.ifEmpty { null },
                    album = trimmedAlbum.ifEmpty { null },
                    coverBytes = effectiveCover?.takeIf { it.isNotEmpty() },
                    durationMs = effectiveDurationMs.takeIf { it > 0L },
                    durationState = patchedDurationState,
                    seekCapability = patchedSeek
                )
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
            if (timeSinceFlush in 0L..4000L && durationMs > 0L) {
                postFlushProgressDurationMs = durationMs
            }

            if (durationMs > 0L && !currentTrackDurationFixed) {
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
            if (durationMs > 0L) {
                try {
                    com.solo.purecast.core.nativebridge.NativeAudioEngine.updateAirPlayTrackProgress(positionMs, durationMs)
                } catch (t: Throwable) {
                    Logger.airplay.w(TAG, "同步 Native 曲目进度异常: ${t.message}")
                }
            }
            // 💡 R7 契约：当 durationMs == 0 时保持 UNKNOWN，绝不能因 positionMs > 0 而武断标记为 LIVE，
            // 避免切歌或首播进度先于元数据到达时 UI 误报 LIVE 红点并永久锁定不可寻道。

            emitLocked(
                sessionId = globalSid,
                payload = ProgressUpdateEvent(
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    trackGeneration = currentTrackGeneration
                )
            )
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
    }
}
