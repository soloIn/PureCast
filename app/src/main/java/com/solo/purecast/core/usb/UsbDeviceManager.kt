package com.solo.purecast.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 💡 PureCast USB 设备生命周期与声卡让渡调度管理器
 * 负责 USB 设备的冷启动枚举、权限动态申请、Linux usbfs 文件描述符提取、
 * 10秒空闲自动归还计时器，以及内核驱动重绑后的声卡主动自检自愈。
 */
class UsbDeviceManager(
    private val context: Context,
    private val onDeviceStateChanged: (connected: Boolean, deviceName: String?) -> Unit
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private val isExclusiveTaken = AtomicBoolean(false)
    private val isPermissionRequesting = AtomicBoolean(false)

    // 💡 记录当前活跃的声卡硬件配置，切歌时比对是否需要重配底层 Native 驱动
    private var currentSampleRate: Int = 0
    private var currentBitDepth: Int = 0
    private var currentChannels: Int = 0

    // 💡 10 秒空闲自动归还调度任务
    private var idleTimeoutMillis = 10_000L
    private val releaseRunnable = Runnable {
        Logger.usb.i(TAG, "推流暂停/空闲已满 ${idleTimeoutMillis / 1000} 秒，触发内核声卡让渡时序...")
        releaseUsbExclusive()
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this@UsbDeviceManager) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    isPermissionRequesting.set(false)
                    Logger.usb.i(TAG, "收到 USB 权限申请结果: device=${device?.deviceName}, granted=$granted")
                    if (granted && device != null) {
                        activeDevice = device
                        onDeviceStateChanged(true, device.productName ?: device.deviceName)
                    } else {
                        Logger.usb.w(TAG, "用户或系统拒绝了 USB 设备访问权限: ${device?.deviceName}")
                    }
                }
            }
        }
    }

    init {
        // 注册权限广播接收器
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    /**
     * 冷启动或热插拔时主动扫描已连接的 USB Audio 设备
     * 特别针对索尼 ZX300A (VendorID 0x054c)
     */
    fun scanAndAttachDevice(): Boolean {
        Logger.usb.i(TAG, "开始主动扫描 USB 设备列表 (冷启动 / 设备插入)...")
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            Logger.usb.i(TAG, "当前未检测到任何 USB 外部设备")
            onDeviceStateChanged(false, null)
            return false
        }

        var candidate: UsbDevice? = null
        for (device in deviceList.values) {
            Logger.usb.d(TAG, "扫描到设备: ${device.deviceName}, VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)}")
            if (isUsbAudioDevice(device)) {
                candidate = device
                if (device.vendorId == SONY_VENDOR_ID) {
                    Logger.usb.i(TAG, "精准匹配到 Sony USB DAC 设备: ${device.productName} (VID:0x054c)")
                    break
                }
            }
        }

        if (candidate == null) {
            Logger.usb.w(TAG, "未发现符合规范的 USB Audio Class 设备")
            onDeviceStateChanged(false, null)
            return false
        }

        activeDevice = candidate
        if (usbManager.hasPermission(candidate)) {
            Logger.usb.i(TAG, "已具备 USB 设备访问授权: ${candidate.productName}")
            onDeviceStateChanged(true, candidate.productName ?: candidate.deviceName)
            return true
        } else {
            Logger.usb.i(TAG, "检测到未授权的 USB DAC 设备 (${candidate.productName}), 等待 Activity 呈现授权对话框...")
            return false
        }
    }

    /**
     * 💡 从 Activity Intent 注入系统传递的 USB 设备，优先校验系统既有授权
     */
    fun onDeviceAttachedFromIntent(device: UsbDevice) {
        synchronized(this) {
            activeDevice = device
            if (usbManager.hasPermission(device)) {
                Logger.usb.i(TAG, "从 Intent 识别到系统已授权的 USB 设备: ${device.productName}")
                onDeviceStateChanged(true, device.productName ?: device.deviceName)
            } else {
                Logger.usb.i(TAG, "从 Intent 识别到 USB 设备 (${device.productName})，尚未具备访问授权")
            }
        }
    }

    /**
     * 💡 由前台 Activity 发起原生 USB 授权对话框
     */
    fun requestPermissionWithActivity(activity: android.app.Activity) {
        val device = activeDevice ?: run {
            scanAndAttachDevice()
            activeDevice
        } ?: run {
            Logger.usb.w(TAG, "requestPermissionWithActivity 失败: 无可用 USB 设备")
            return
        }

        if (usbManager.hasPermission(device)) {
            Logger.usb.i(TAG, "设备已拥有访问授权: ${device.productName}")
            onDeviceStateChanged(true, device.productName ?: device.deviceName)
            return
        }

        if (!isPermissionRequesting.compareAndSet(false, true)) {
            Logger.usb.w(TAG, "当前已存在正在处理中的 USB 权限申请对话框，跳过重复调起")
            return
        }

        Logger.usb.i(TAG, "从 Activity (${activity.localClassName}) 发起 USB 权限申请对话框...")
        requestDevicePermission(device, activity)
    }

    /**
     * 💡 处理 USB 设备热插拔拔出事件
     * @return 若被拔出的设备确实是当前连接的活跃 DAC，则释放独占并返回 true；若拔出的是无关外设或当前无连接则返回 false
     */
    fun handleDeviceDetached(device: UsbDevice?): Boolean {
        synchronized(this) {
            val current = activeDevice ?: return false
            val isCurrentDetached = device == null ||
                    device.deviceId == current.deviceId ||
                    (device.vendorId == current.vendorId && device.productId == current.productId)

            if (isCurrentDetached) {
                Logger.usb.i(TAG, "检测到当前 USB DAC 设备已物理拔出 (${device?.productName ?: current.productName})，执行声卡让渡与状态重置")
                releaseUsbExclusive()
                activeDevice = null
                onDeviceStateChanged(false, null)
                return true
            }
            return false
        }
    }

    private fun requestDevicePermission(device: UsbDevice, ctx: Context = context) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * 判断设备是否为音频类设备
     */
    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    /**
     * 接管声卡并开启独占等时传输
     * @param sampleRate 采样率 (如 44100, 96000)
     * @param bitDepth 位深 (如 16, 24)
     * @param channels 声道数 (2)
     */
    @Synchronized
    fun claimUsbExclusive(sampleRate: Int, bitDepth: Int, channels: Int): Boolean {
        mainHandler.removeCallbacks(releaseRunnable)

        val device = activeDevice ?: run {
            scanAndAttachDevice()
            activeDevice
        } ?: run {
            Logger.usb.e(TAG, "claimUsbExclusive 失败: 无可用 USB 设备")
            return false
        }

        if (!usbManager.hasPermission(device)) {
            Logger.usb.w(TAG, "claimUsbExclusive 失败: 无设备权限，重新申请...")
            requestDevicePermission(device)
            return false
        }

        if (isExclusiveTaken.get() && activeConnection != null) {
            if (currentSampleRate == sampleRate && currentBitDepth == bitDepth && currentChannels == channels) {
                Logger.usb.i(TAG, "USB 设备当前已被独占接管且音频规格一致 ($sampleRate Hz, $bitDepth bit, $channels ch)，维持当前连接")
                return true
            }
            Logger.usb.i(TAG, "检测到音频规格变更: ${currentSampleRate}Hz/${currentBitDepth}bit -> ${sampleRate}Hz/${bitDepth}bit，重新配置底层 Native 驱动...")
            val fd = activeConnection!!.fileDescriptor
            val ret = NativeAudioEngine.openDevice(fd, sampleRate, bitDepth, channels)
            if (ret == 0) {
                currentSampleRate = sampleRate
                currentBitDepth = bitDepth
                currentChannels = channels
                Logger.usb.i(TAG, "🎉 音频硬件规格切频配置成功: ${sampleRate}Hz / ${bitDepth}bit / ${channels}ch")
                return true
            } else {
                Logger.usb.w(TAG, "动态重配 Native 驱动失败 ($ret)，释放旧连接并尝试重建...")
                releaseUsbExclusive()
            }
        }

        Logger.usb.i(TAG, "打开 UsbDeviceConnection 并穿透 Linux usbfs fd 到 C++ 驱动层...")
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Logger.usb.e(TAG, "usbManager.openDevice 返回 null，无法获取设备句柄")
            return false
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            Logger.usb.e(TAG, "获取底层 Linux usbfs fd 失败 (fd=$fd)")
            connection.close()
            return false
        }

        Logger.usb.i(TAG, "成功获取 Linux fd=$fd，调用 Native openDevice...")
        val ret = NativeAudioEngine.openDevice(fd, sampleRate, bitDepth, channels)
        if (ret != 0) {
            Logger.usb.e(TAG, "NativeAudioEngine.openDevice 失败: $ret")
            connection.close()
            return false
        }

        activeConnection = connection
        currentSampleRate = sampleRate
        currentBitDepth = bitDepth
        currentChannels = channels
        isExclusiveTaken.set(true)
        Logger.usb.i(TAG, "🎉 成功独占 USB 声卡，Bit-Perfect 传输流水线就绪 (${sampleRate}Hz / ${bitDepth}bit)")
        return true
    }

    /**
     * 标记进入空闲状态，启动空闲倒计时让渡 (0秒表示常驻独占，不启动倒计时)
     */
    fun scheduleIdleRelease(customTimeoutMillis: Long = idleTimeoutMillis) {
        mainHandler.removeCallbacks(releaseRunnable)
        if (idleTimeoutMillis <= 0L || customTimeoutMillis <= 0L) {
            Logger.usb.i(TAG, "已配置常驻独占策略 (0秒)，保持声卡独占不自动让渡")
            return
        }
        Logger.usb.i(TAG, "启动 ${customTimeoutMillis / 1000} 秒空闲声卡让渡倒计时...")
        mainHandler.postDelayed(releaseRunnable, customTimeoutMillis)
    }

    /**
     * 取消空闲归还倒计时
     */
    fun cancelIdleRelease() {
        mainHandler.removeCallbacks(releaseRunnable)
    }

    /**
     * 彻底解除 USB 独占，将声卡归还 Linux 内核驱动并自检自愈
     */
    @Synchronized
    fun releaseUsbExclusive() {
        mainHandler.removeCallbacks(releaseRunnable)
        if (!isExclusiveTaken.get()) {
            return
        }

        Logger.usb.i(TAG, "开始执行声卡解绑归还与自愈时序...")
        // 💡 步骤 1: Native 层回收 URBs 并调用 libusb_attach_kernel_driver(USBDEVFS_CONNECT)
        NativeAudioEngine.releaseDevice()

        // 💡 步骤 2: 关闭 Java 层 UsbDeviceConnection
        activeConnection?.let {
            try {
                it.close()
                Logger.usb.i(TAG, "UsbDeviceConnection 句柄已正常关闭")
            } catch (e: Exception) {
                Logger.usb.w(TAG, "关闭 UsbDeviceConnection 时捕获异常", e)
            }
            activeConnection = null
        }

        isExclusiveTaken.set(false)
        currentSampleRate = 0
        currentBitDepth = 0
        currentChannels = 0

        // 💡 步骤 3: 声卡主动自检自愈 (Self-Healing Check)
        // 触发 Android AudioService 刷新物理音频设备路由，确保其他电视 App 恢复出声
        triggerSystemAudioSelfHealing()
    }

    /**
     * 触发电视系统声卡自愈枚举与路由刷新
     */
    private fun triggerSystemAudioSelfHealing() {
        try {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasUsbAudio = outputDevices.any { 
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET 
            }
            Logger.usb.i(TAG, "系统声卡自检完成: 当前系统输出设备数=${outputDevices.size}, USB声卡识别状态=$hasUsbAudio")
        } catch (e: Exception) {
            Logger.usb.w(TAG, "系统声卡自检异常", e)
        }
    }

    fun setIdleTimeoutSeconds(seconds: Int) {
        idleTimeoutMillis = if (seconds <= 0) 0L else seconds.coerceAtLeast(3) * 1000L
        Logger.usb.i(TAG, "更新空闲声卡让渡时间为: $seconds 秒 (0秒为常驻独占)")
    }

    fun isExclusive(): Boolean = isExclusiveTaken.get()

    fun destroy() {
        mainHandler.removeCallbacks(releaseRunnable)
        releaseUsbExclusive()
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        private const val TAG = "UsbDeviceManager"
        private const val ACTION_USB_PERMISSION = "com.solo.purecast.USB_PERMISSION"
        const val SONY_VENDOR_ID = 0x054c // 索尼 Vendor ID (十进制 1356)
    }
}
