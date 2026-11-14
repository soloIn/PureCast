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

    fun resetSessions() {
        nativeToGlobalSessionMap.clear()
        lastStateSequenceByNative.clear()
        onRtpActiveChanged(false)
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
                seekCapability = SeekCapability.UNSEEKABLE
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
            emitLocked(
                sessionId = globalSid,
                payload = TrackSwitchEvent(newEpoch = newEpoch)
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

            val isBoundarySwitch = trimmedTitle.isNotEmpty() &&
                currentTrackTitle.isNotEmpty() &&
                (trimmedTitle != currentTrackTitle ||
                    (trimmedArtist.isNotEmpty() && currentTrackArtist.isNotEmpty() && trimmedArtist != currentTrackArtist))

            if (isBoundarySwitch) {
                val nextTxGen = trackGenerationGenerator.incrementAndGet()
                currentTrackGeneration = nextTxGen
                currentTrackDurationFixed = false
                currentTrackMarkedLive = false
                emitLocked(
                    sessionId = globalSid,
                    payload = TrackBoundaryEvent(
                        trackGeneration = nextTxGen,
                        trackKey = "$trimmedTitle|$trimmedArtist",
                        initialDurationState = if (durationMs > 0L) DurationState.FIXED else DurationState.UNKNOWN,
                        seekCapability = if (durationMs > 0L) SeekCapability.SEEKABLE else SeekCapability.UNSEEKABLE
                    )
                )
            }

            if (trimmedTitle.isNotEmpty()) {
                currentTrackTitle = trimmedTitle
            }
            if (trimmedArtist.isNotEmpty()) {
                currentTrackArtist = trimmedArtist
            }
            if (durationMs > 0L) {
                currentTrackDurationFixed = true
                currentTrackMarkedLive = false
            }

            val patchedDurationState = when {
                durationMs > 0L -> DurationState.FIXED
                !currentTrackDurationFixed && !currentTrackMarkedLive -> DurationState.UNKNOWN
                else -> null
            }
            val patchedSeek = when {
                durationMs > 0L -> SeekCapability.SEEKABLE
                !currentTrackDurationFixed && !currentTrackMarkedLive -> SeekCapability.UNSEEKABLE
                else -> null
            }

            emitLocked(
                sessionId = globalSid,
                payload = MetadataPatchEvent(
                    trackGeneration = currentTrackGeneration,
                    title = trimmedTitle.ifEmpty { null },
                    artist = trimmedArtist.ifEmpty { null },
                    album = trimmedAlbum.ifEmpty { null },
                    coverBytes = coverArt?.takeIf { it.isNotEmpty() },
                    durationMs = durationMs.takeIf { it > 0L },
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
            } else if (durationMs == 0L && positionMs > 0L && !currentTrackDurationFixed && !currentTrackMarkedLive) {
                currentTrackMarkedLive = true
                emitLocked(
                    sessionId = globalSid,
                    payload = MetadataPatchEvent(
                        trackGeneration = currentTrackGeneration,
                        durationMs = 0L,
                        durationState = DurationState.LIVE,
                        seekCapability = SeekCapability.UNSEEKABLE
                    )
                )
            }

            emitLocked(
                sessionId = globalSid,
                payload = ProgressUpdateEvent(
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L)
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
