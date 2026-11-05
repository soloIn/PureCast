package com.solo.purecast.airplay

/**
 * 💡 NativeAirPlayCallback: Native RAOP 协议栈与 Android 系统服务的交互契约
 */
interface NativeAirPlayCallback {
    /**
     * 收到 AirPlay 投播请求 (SETUP 阶段)
     * @param sampleRate 采样率 (标称 44100)
     * @param bitDepth 位深 (标称 16)
     * @param channels 声道数 (标称 2)
     * @param clientIp iPhone 对端 IP
     * @param aesKeyBase64 RSA 加密的 AES Key (Base64)
     * @param aesIvBase64 AES IV (Base64)
     * @param dacpId DACP-ID 会话标识
     * @param activeRemoteToken Active-Remote 遥控令牌
     * @param dacpPort DACP HTTP 控制端口
     * @return true 允许推流接入 (已就绪音频焦点与声卡独占), false 拒绝投播
     */
    fun onStreamRequested(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        clientIp: String,
        aesKeyBase64: String,
        aesIvBase64: String,
        dacpId: String,
        activeRemoteToken: String,
        dacpPort: Int
    ): Boolean

    /**
     * AirPlay 会话停止或网络连接断开 (TEARDOWN 阶段)
     */
    fun onStreamStopped()

    /**
     * 💡 接收到推流端暂停请求或检测到音频流中断 (PAUSE / Stream Inactivity 触发)
     */
    fun onStreamPaused()

    /**
     * 💡 接收到推流端恢复请求或检测到新音频包到达 (RECORD / Stream Resumed 触发)
     */
    fun onStreamResumed()

    /**
     * 收到 Apple-Challenge 挑战握手请求 (OPTIONS / ANNOUNCE 阶段)
     * @param challengeBase64 Apple 设备发来的 Base64 编码随机挑战
     * @return 经 RSA 私钥签名的 Apple-Response Base64 字符串，失败返回 null
     */
    fun onChallengeRequested(challengeBase64: String): String?

    /**
     * 接收到推流端 DMAP 音乐元数据更新
     * @param title 歌曲名
     * @param artist 艺术家
     * @param album 专辑
     * @param durationMs 音轨时长 (毫秒)
     * @param coverArt 封面图片字节数据 (JPEG/PNG)
     */
    fun onMetadataReceived(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverArt: ByteArray?
    )

    /**
     * 💡 接收到推流端播放进度更新 (SET_PARAMETER progress: start/curr/end)
     * @param positionMs 当前播放位置 (毫秒)
     * @param durationMs 歌曲总时长 (毫秒)
     */
    fun onProgressUpdated(positionMs: Long, durationMs: Long)
}
