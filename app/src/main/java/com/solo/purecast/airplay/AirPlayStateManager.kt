package com.solo.purecast.airplay

import com.solo.purecast.model.PlaybackStatus
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.model.TrackMetadata

/**
 * 💡 AirPlay 1 会话状态机与进度过滤协调器
 * 统一管理 AirPlay 传输层 (RTP)、遥控层 (DACP) 与应用层 (PlaybackStatus) 的状态隔离与流转
 */
class AirPlayStateManager {

    var protocol: StreamProtocol = StreamProtocol.NONE
        private set

    var status: PlaybackStatus = PlaybackStatus.STOPPED
        private set

    var metadata: TrackMetadata = TrackMetadata()
        private set

    var isRtpActive: Boolean = false
        private set

    var isDacpActive: Boolean = false
        private set

    var positionMs: Long = 0L
        private set

    /**
     * 收到推流开始事件 (SETUP/RECORD 建流成功)
     */
    fun onStreamStarted(meta: TrackMetadata = metadata) {
        protocol = StreamProtocol.AIRPLAY_1
        status = PlaybackStatus.PLAYING
        metadata = meta
        isRtpActive = true
        isDacpActive = true
    }

    /**
     * 传输通道拆除 (TEARDOWN)：进入挂起暂停状态，保留元数据与 DACP 会话
     */
    fun onStreamTeardown(currentPos: Long = positionMs) {
        status = PlaybackStatus.PAUSED
        isRtpActive = false
        positionMs = currentPos
    }

    /**
     * 发送端主动暂停
     */
    fun onStreamPaused(currentPos: Long = positionMs) {
        status = PlaybackStatus.PAUSED
        positionMs = currentPos
    }

    /**
     * 发送端恢复播放
     */
    fun onStreamResumed() {
        status = PlaybackStatus.PLAYING
    }

    /**
     * TV 端尝试恢复播放
     * 若 RTP 传输通道已断开 (处于 TEARDOWN 状态)，严禁直接置为 PLAYING 僵尸状态，返回 false
     * 若 RTP 仍处于活跃状态，直接切换为 PLAYING 并返回 true
     */
    fun onTvResumeRequest(): Boolean {
        if (!isDacpActive) return false
        if (!isRtpActive) {
            return false
        }
        status = PlaybackStatus.PLAYING
        return true
    }

    /**
     * 客户端重连 RTP 通道 (收到新的 SETUP/RECORD)
     */
    fun onIncomingRtpReconnect() {
        isRtpActive = true
        status = PlaybackStatus.PLAYING
    }

    /**
     * 处理 SET_PARAMETER 进度更新
     * 严格过滤 0/0 全零无效时间戳，杜绝暂停或缓冲时冲刷当前进度与时长
     * @return 若时间戳有效并更新成功返回 true，被过滤返回 false
     */
    fun handleProgressUpdate(posMs: Long, durMs: Long): Boolean {
        if (posMs <= 0L && durMs <= 0L) {
            return false
        }
        if (durMs > 0L) {
            metadata = metadata.copy(durationMs = durMs)
        }
        positionMs = posMs
        return true
    }

    /**
     * 更新元数据
     */
    fun updateMetadata(meta: TrackMetadata) {
        metadata = meta
    }

    /**
     * 彻底重置 AirPlay 会话 (10 分钟挂起超时或用户主动停止)
     */
    fun onFullSessionStop() {
        protocol = StreamProtocol.NONE
        status = PlaybackStatus.STOPPED
        metadata = TrackMetadata()
        isRtpActive = false
        isDacpActive = false
        positionMs = 0L
    }
}
