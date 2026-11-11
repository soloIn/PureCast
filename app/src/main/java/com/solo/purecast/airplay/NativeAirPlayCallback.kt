package com.solo.purecast.airplay

/**
 * 💡 NativeAirPlayCallback: Native RAOP 协议栈与 Kotlin 无状态驱动层的异步事件桥接契约
 * 全部异步会话回调均由 C++ 层携带单调 [nativeSessionId] 与递增 [sequence] 注入
 */
interface NativeAirPlayCallback {
    /**
     * 收到 AirPlay 投播请求 (SETUP 阶段)
     * @return >0 表示允许推流且硬件已锁定新世代 epoch；<= 0 表示拒绝投播 (RTSP 返回 503)
     */
    fun onStreamRequested(
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
    ): Long

    /**
     * AirPlay 会话停止或网络连接断开 (TEARDOWN 阶段)
     */
    fun onStreamStopped(nativeSessionId: Long, sequence: Long, epoch: Long)

    /**
     * 💡 接收到推流端暂停请求或检测到音频流静音/断流 (isImplicit=true 表示 600ms/180帧静音触发，须 0ms 瞬切 UI)
     */
    fun onStreamPaused(nativeSessionId: Long, sequence: Long, epoch: Long, isImplicit: Boolean)

    /**
     * 💡 接收到推流端同流恢复请求或静音恢复 (PAUSE -> RECORD 无 FLUSH 触发)
     */
    fun onStreamResumed(nativeSessionId: Long, sequence: Long, epoch: Long)

    /**
     * 💡 收到 RTSP FLUSH 切歌/Seek 冲刷，C++ 已无锁切换至新世代 [newEpoch]
     */
    fun onTrackSwitchFlushed(nativeSessionId: Long, sequence: Long, newEpoch: Long)

    /**
     * 💡 新流或 FLUSH 新世代的首包有效解码 PCM 写入环形缓冲，触发业务层进入 STREAMING/PLAYING
     */
    fun onFirstDataPacket(
        nativeSessionId: Long,
        sequence: Long,
        epoch: Long,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        codec: String
    )

    /**
     * 收到 Apple-Challenge 挑战握手请求 (OPTIONS / ANNOUNCE 阶段)
     */
    fun onChallengeRequested(challengeBase64: String): String?

    /**
     * 接收到推流端 DMAP 音乐元数据更新
     */
    fun onMetadataReceived(
        nativeSessionId: Long,
        sequence: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverArt: ByteArray?
    )

    /**
     * 💡 接收到推流端播放进度更新 (SET_PARAMETER progress: start/curr/end)
     */
    fun onProgressUpdated(nativeSessionId: Long, sequence: Long, positionMs: Long, durationMs: Long)
}
