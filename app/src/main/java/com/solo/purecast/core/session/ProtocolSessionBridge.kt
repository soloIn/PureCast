package com.solo.purecast.core.session

import com.solo.purecast.model.StreamProtocol

/**
 * 💡 硬件热拔出后重开声卡成功时，向活跃协议确认底层管线是否已恢复推流
 */
enum class ProtocolResumeReopenResult {
    /** 底层生产者与缓冲管线存活，已刷新 epoch 并可立即恢复 PLAYING */
    STREAMING_READY,

    /** 正在从记录 URI 异步重拉流，保持 TRANSITIONING + EXCLUSIVE_MUTED 等待首帧 */
    RESTARTING,

    /** 底层推流连接已失活或无有效源，保持 PAUSED + EXCLUSIVE_MUTED 避免假播放 */
    PRODUCER_DEAD
}

/**
 * 💡 协议服务端与会话协调器之间的统一桥梁契约：
 * 收拢事件投递、全局会话/序号分配与跨协议抢占建流准入，避免向各协议构造器散落大量 lambda 参数。
 */
interface ProtocolSessionBridge {
    fun dispatch(envelope: SessionEventEnvelope<SessionEventPayload>)
    fun nextSessionId(): Long
    fun nextSequence(): Long
    fun acquireHardwareForStream(
        protocol: StreamProtocol,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        codec: String
    ): Long
}
