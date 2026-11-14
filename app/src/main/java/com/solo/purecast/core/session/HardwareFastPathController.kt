package com.solo.purecast.core.session

import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.model.FlushReason
import com.solo.purecast.util.Logger

/**
 * 💡 Native 硬件快路径桥接契约
 * 默认对接 [NativeAudioEngine] JNI，单元测试可注入轻量内存桩以实现纯 JVM 验证。
 */
interface FastPathNativeBridge {
    fun emergencyMuteAndFlush(reason: FlushReason): Long
    fun getCurrentBufferEpoch(): Long
    fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long
    fun resumeStream(): Int
    fun isStreaming(): Boolean
}

/**
 * 💡 生产环境 Native JNI 直连桥接实现
 */
object NativeAudioFastPathBridge : FastPathNativeBridge {
    override fun emergencyMuteAndFlush(reason: FlushReason): Long =
        NativeAudioEngine.emergencyMuteAndFlush(reason.nativeCode)

    override fun getCurrentBufferEpoch(): Long =
        NativeAudioEngine.getCurrentBufferEpoch()

    override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long =
        NativeAudioEngine.reconfigureAudioFormat(sampleRate, bitDepth, channels)

    override fun resumeStream(): Int =
        NativeAudioEngine.resumeStream()

    override fun isStreaming(): Boolean =
        NativeAudioEngine.isStreaming()
}

/**
 * 💡 硬件快速控制通道控制器 (Hardware Fast-Path Controller)
 *
 * 架构红线（R1）：
 * 1. 所有调用同步直达 Native 原子操作，严禁经过协程 Channel 排队或等待 `PlaybackSessionCoordinator`；
 * 2. 区分 [FlushReason.EXPLICIT_PAUSE]（仅全 0 保活、保留缓冲与当前 epoch）与
 *    [FlushReason.FLUSH_SWITCH] / [FlushReason.STOP_OR_TEARDOWN] / [FlushReason.PREEMPT]（递增 epoch 并由 URB 消费端安全排空）；
 * 3. 返回最新有效 `buffer_epoch_`（`>= 1L` 有效，`< 0L` 为错误码），供流写入侧写入首帧前对齐校验。
 */
class HardwareFastPathController(
    private val bridge: FastPathNativeBridge = NativeAudioFastPathBridge
) {
    /**
     * 触发硬件紧急静音与按策略世代刷新（无锁快路径，目标 T3 <= 20ms）
     */
    fun emergencyMuteAndFlush(reason: FlushReason): Long {
        val epoch = bridge.emergencyMuteAndFlush(reason)
        Logger.audio.i(TAG, "快路径执行 emergencyMuteAndFlush(reason=$reason) -> epoch=$epoch")
        return epoch
    }

    /**
     * 读取当前最新的 Native 缓冲世代号
     */
    fun getCurrentBufferEpoch(): Long = bridge.getCurrentBufferEpoch()

    /**
     * 同步执行三元组原位切频（仅传递音频规格，Native 内部自动匹配 altSetting）
     */
    fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long {
        val result = bridge.reconfigureAudioFormat(sampleRate, bitDepth, channels)
        if (result < 0L) {
            Logger.audio.w(TAG, "三元组原位切频返回错码: rate=$sampleRate, bit=$bitDepth, ch=$channels, err=$result")
        } else {
            Logger.audio.i(TAG, "三元组原位切频完成: ${sampleRate}Hz/${bitDepth}bit/${channels}ch -> epoch=$result")
        }
        return result
    }

    /**
     * 恢复等时有效 PCM 输出（清除显式暂停标记，沿用原 epoch）
     */
    fun resumeStream(): Int = bridge.resumeStream()

    /**
     * 当前等时硬件是否处于未静音推流态
     */
    fun isStreaming(): Boolean = bridge.isStreaming()

    companion object {
        private const val TAG = "FastPath"

        val default: HardwareFastPathController by lazy { HardwareFastPathController() }
    }
}
