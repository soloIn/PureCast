package com.solo.purecast.core.nativebridge

import com.solo.purecast.util.Logger

/**
 * 💡 NativeAudioEngine: C++20 底层驱动与 JNI 通信桥梁
 * 封装对 libusb-1.0、Linux usbfs、SPSC 无锁环形缓冲与 UAC2 时钟控制的全部底层调用。
 */
object NativeAudioEngine {

    private const val TAG = "NativeAudioEngine"

    init {
        // 确保 Native 库已就绪
        try {
            System.loadLibrary("purecast_usb")
        } catch (e: UnsatisfiedLinkError) {
            Logger.audio.e(TAG, "加载 purecast_usb 失败", e)
        }
    }

    /**
     * 获取静态链接的 libusb 版本号
     */
    external fun getLibusbVersion(): String

    /**
     * 初始化底层 Native 运行环境与 libusb 上下文
     * @return 0 表示成功，负数表示失败错误码
     */
    external fun initNativeContext(): Int

    /**
     * 接管指定 Android usbfs 文件描述符并初始化音频端点
     * @param fileDescriptor 来自 UsbDeviceConnection.getFileDescriptor() 的底层 Linux fd
     * @param sampleRate 目标采样率 (如 44100, 96000)
     * @param bitDepth 目标有效位深 (如 16, 24)
     * @param channels 声道数 (默认为 2)
     * @return 0 成功，负数失败
     */
    external fun openDevice(
        fileDescriptor: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int
    ): Int

    /**
     * 💡 UAC 原位三元组切频与安全回滚
     * 严禁在上层暴露 altSetting，由 Native 内部通过 parse_audio_descriptors 匹配
     * @return 成功返回新激活的 buffer_epoch (>= 1L)，失败返回负错误码
     */
    external fun reconfigureAudioFormat(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int
    ): Long

    /**
     * 💡 无锁硬件快路径：按 FlushReason 立即静音保活并可选递增 epoch 排空旧流
     * @param reason 0=EXPLICIT_PAUSE, 1=FLUSH_SWITCH, 2=STOP_OR_TEARDOWN, 3=PREEMPT
     * @return 最新有效的 buffer_epoch (>= 1L)
     */
    external fun emergencyMuteAndFlush(reason: Int): Long

    /**
     * 获取当前活跃的无锁环形缓冲区世代 (Epoch)
     */
    external fun getCurrentBufferEpoch(): Long

    /**
     * 💡 带世代防串扰与消费追平门闩的 PCM 直通写入
     * @param expectedEpoch 切频/开流时获取的世代令牌
     * @return >0 实际写入字节，0 整包满载丢弃，负数表示停止/过期/超时错误
     */
    external fun writePcm(data: ByteArray, offset: Int, length: Int, expectedEpoch: Long): Int

    /**
     * 恢复等时传输流
     */
    external fun resumeStream(): Int

    /**
     * 彻底释放 USB 设备独占权，触发内核驱动重绑时序
     * 顺序：停止 URB -> release interface -> attach kernel driver -> close
     */
    external fun releaseDevice(): Int

    /**
     * 获取无锁环形缓冲区当前可用字节数
     */
    external fun getBufferAvailable(): Int

    /**
     * 获取无锁环形缓冲区总字节容量
     */
    external fun getBufferCapacity(): Int

    /**
     * 获取无锁环形缓冲区当前未消费（可读）的有效 PCM 字节数
     */
    external fun getAvailableRead(): Int

    /**
     * 💡 获取当前环形缓冲区中尚未被 USB 等时线程消费的字节数 (语义化别名)
     */
    external fun getUnconsumedBytes(): Int

    /**
     * 💡 获取当前环形缓冲区中尚未消费数据对应的硬件物理输出延迟 (毫秒)
     * 基于底层采样率、位深与声道数实时换算，供 UI 层进度与歌词时钟进行毫秒级精准扣除。
     */
    external fun getRingBufferDelayMs(): Long

    /**
     * 💡 计算指定字节量在指定音频格式下的毫秒时延
     */
    fun calculateLatencyMs(unconsumedBytes: Int, sampleRate: Int, bitDepth: Int, channels: Int): Long {
        if (unconsumedBytes <= 0 || sampleRate <= 0 || bitDepth <= 0 || channels <= 0) return 0L
        val bytesPerSample = (bitDepth / 8).coerceAtLeast(1)
        val bytesPerSec = sampleRate.toLong() * channels.toLong() * bytesPerSample.toLong()
        if (bytesPerSec <= 0L) return 0L
        return (unconsumedBytes.toLong() * 1000L) / bytesPerSec
    }

    /**
     * 检查声卡当前是否处于独占活跃状态
     */
    external fun isStreaming(): Boolean

    /**
     * 💡 检查声卡当前是否处于显式暂停态（仅反映 is_explicitly_paused_，不含切歌/Seek Flush 预充静音）
     */
    external fun isExplicitlyPaused(): Boolean

    /**
     * FLAC 解码器采样格式解析与首帧写入事件回调
     */
    interface FlacFormatCallback {
        /**
         * 同步完成硬件三元组重配并锁定流世代，返回 >0 的 expectedEpoch；<= 0 则中止解码
         */
        fun onFormatReady(sampleRate: Int, bitDepth: Int, channels: Int, totalPcmFrames: Long): Long

        /**
         * 首包有效 decoded PCM 写入环形缓冲时触发，用于解锁 PLAYING 状态
         */
        fun onFirstPcmWritten() {}
    }

    /**
     * 获取 Native FLAC 流解码已成功写入环形缓冲区的 PCM 采样帧数
     */
    external fun getFlacDecodedFrames(): Long

    /**
     * 重置 Native FLAC 流解码写入的采样帧计数器
     */
    external fun resetFlacDecodedFrames()


    /**
     * 💡 通过 Native dr_flac 流式无损解码 FLAC 并实时直通写入环形缓冲区
     */
    external fun decodeFlacStream(
        inputStream: java.io.InputStream,
        callback: FlacFormatCallback
    ): Boolean

    /**
     * 💡 启动底层 Native RAOP (AirPlay 1 RTSP) 服务
     * @param port RTSP 服务监听端口 (默认 5000)
     * @param callback 协议事件与流状态监听器
     */
    external fun startAirPlayServer(
        port: Int,
        callback: com.solo.purecast.airplay.NativeAirPlayCallback
    ): Boolean

    /**
     * 💡 停止底层 Native RAOP 服务并释放全部网络与音频管道
     */
    external fun stopAirPlayServer()

    /**
     * 💡 跨协议抢占时仅中止当前 AirPlay 客户端 UDP 推流与活动 RTSP client_fd，保持 server_fd 监听在线
     */
    external fun preemptAirPlayClient()

    /**
     * 💡 DAC 重新独占后刷新 RaopRtp 活跃 epoch 并解除暂停态，返回 true 表示 UDP/RTSP 管线仍在线
     */
    external fun updateAirPlayActiveEpoch(newEpoch: Long): Boolean

    /**
     * 💡 注入经由 RSA-2048 私钥解密出的原始 AES-128 密钥与 IV
     */
    external fun setAirPlayCryptoKey(aesKey: ByteArray, aesIv: ByteArray)

    /**
     * 💡 向底层 Native RAOP 同步当前播放进度与曲目总时长，用于曲目尾部静音免疫窗口判定
     */
    external fun updateAirPlayTrackProgress(positionMs: Long, durationMs: Long): Boolean

    /**
     * 💡 闭环读取底层 Native RAOP 扬声器当前真实出声曲目进度 (毫秒)，未就绪或排队期返回 -1L
     */
    external fun getAirPlayRealtimePositionMs(): Long

    /**
     * 💡 失效底层 Native RAOP 当前曲目 RTP 起点锚点（切歌边界或本机 Seek 前调用，防止旧曲起点污染新曲）
     */
    external fun invalidateAirPlayProgressAnchor()
}
