package com.solo.purecast.core.usb

import android.app.Activity
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
import android.os.SystemClock
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.core.session.EventOrigin
import com.solo.purecast.core.session.HardwareAttachedEvent
import com.solo.purecast.core.session.HardwareDetachedEvent
import com.solo.purecast.core.session.HardwareOpenSuccess
import com.solo.purecast.core.session.HardwareReconfigureFailedEvent
import com.solo.purecast.core.session.HardwareReconfiguredEvent
import com.solo.purecast.core.session.HardwareStateSyncedEvent
import com.solo.purecast.core.session.SessionEventEnvelope
import com.solo.purecast.core.session.SessionEventPayload
import com.solo.purecast.core.session.SessionHardwareBridge
import com.solo.purecast.core.session.UsbPermissionGrantedEvent
import com.solo.purecast.core.session.UsbPermissionRequestedEvent
import com.solo.purecast.model.ErrorCode
import com.solo.purecast.model.FlushReason
import com.solo.purecast.model.HardwarePipelineState
import com.solo.purecast.util.Logger
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 💡 脱敏可测试的 USB Audio 设备描述符，消除纯 JVM 单测对 Android Binder Parcelable 的强依赖
 */
data class UsbDeviceDescriptor(
    val deviceId: Int,
    val deviceName: String,
    val productName: String,
    val vendorId: Int,
    val productId: Int,
    val rawDevice: UsbDevice? = null
)

/**
 * 💡 USB 连接文件描述符封装契约
 */
interface UsbOpenFdHandle {
    val fileDescriptor: Int
    fun close()
}

/**
 * 💡 系统 USB / Audio 服务桥接契约（便于 JVM 单测注入桩实现）
 */
interface UsbSystemBridge {
    fun scanAudioDevice(): UsbDeviceDescriptor?
    fun hasPermission(device: UsbDeviceDescriptor): Boolean
    fun openConnection(device: UsbDeviceDescriptor): UsbOpenFdHandle?
    fun requestPermission(device: UsbDeviceDescriptor, activity: Activity?)
    fun triggerAudioSelfHealing()
}

/**
 * 💡 USB Native 驱动底层桥接契约
 */
interface UsbNativeAudioBridge {
    fun openDevice(fd: Int, sampleRate: Int, bitDepth: Int, channels: Int): Int
    fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long
    fun releaseDevice()
    fun emergencyMuteAndFlush(reason: FlushReason): Long
    fun getCurrentBufferEpoch(): Long
    fun resumeStream(): Int
}

internal class AndroidUsbOpenFdHandle(
    private val connection: UsbDeviceConnection
) : UsbOpenFdHandle {
    override val fileDescriptor: Int
        get() = connection.fileDescriptor

    override fun close() {
        connection.close()
    }
}

internal class AndroidUsbSystemBridge(
    private val context: Context
) : UsbSystemBridge {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun scanAudioDevice(): UsbDeviceDescriptor? {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            return null
        }

        var candidate: UsbDevice? = null
        for (device in deviceList.values) {
            Logger.usb.d(
                TAG,
                "扫描到设备: ${device.deviceName}, VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)}"
            )
            if (isUsbAudioDevice(device)) {
                candidate = device
                if (device.vendorId == UsbDeviceManager.SONY_VENDOR_ID) {
                    Logger.usb.i(TAG, "精准匹配到 Sony USB DAC 设备: ${device.productName} (VID:0x054c)")
                    break
                }
            }
        }
        return candidate?.toDescriptor()
    }

    override fun hasPermission(device: UsbDeviceDescriptor): Boolean {
        val raw = device.rawDevice ?: findRawDevice(device) ?: return false
        return usbManager.hasPermission(raw)
    }

    override fun openConnection(device: UsbDeviceDescriptor): UsbOpenFdHandle? {
        val raw = device.rawDevice ?: findRawDevice(device) ?: return null
        val conn = usbManager.openDevice(raw) ?: return null
        return AndroidUsbOpenFdHandle(conn)
    }

    override fun requestPermission(device: UsbDeviceDescriptor, activity: Activity?) {
        val raw = device.rawDevice ?: findRawDevice(device) ?: return
        val targetCtx: Context = activity ?: context
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            targetCtx,
            0,
            Intent(UsbDeviceManager.ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            },
            flags
        )
        usbManager.requestPermission(raw, permissionIntent)
    }

    override fun triggerAudioSelfHealing() {
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

    private fun findRawDevice(descriptor: UsbDeviceDescriptor): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull {
            it.deviceId == descriptor.deviceId ||
                (it.vendorId == descriptor.vendorId && it.productId == descriptor.productId)
        }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    companion object {
        private const val TAG = "UsbDeviceManager"
    }
}

internal object DefaultUsbNativeAudioBridge : UsbNativeAudioBridge {
    override fun openDevice(fd: Int, sampleRate: Int, bitDepth: Int, channels: Int): Int =
        NativeAudioEngine.openDevice(fd, sampleRate, bitDepth, channels)

    override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long =
        NativeAudioEngine.reconfigureAudioFormat(sampleRate, bitDepth, channels)

    override fun releaseDevice() {
        NativeAudioEngine.releaseDevice()
    }

    override fun emergencyMuteAndFlush(reason: FlushReason): Long =
        NativeAudioEngine.emergencyMuteAndFlush(reason.nativeCode)

    override fun getCurrentBufferEpoch(): Long =
        NativeAudioEngine.getCurrentBufferEpoch()

    override fun resumeStream(): Int =
        NativeAudioEngine.resumeStream()
}

internal fun UsbDevice.toDescriptor(): UsbDeviceDescriptor {
    val label = (productName ?: deviceName).ifBlank { "USB Audio DAC" }
    return UsbDeviceDescriptor(
        deviceId = deviceId,
        deviceName = deviceName,
        productName = label,
        vendorId = vendorId,
        productId = productId,
        rawDevice = this
    )
}

/**
 * 💡 PureCast USB 设备生命周期与单线程硬件执行器 (HardwareExecutor) 管理器
 *
 * 核心架构契约（U4）：
 * 1. 所有 USB 热插拔枚举、授权回调、`openDevice`、`reconfigureAudioFormat`、`releaseDevice`
 *    均由单一专用线程 `HardwareExecutor` 串行执行，彻底消灭 BroadcastReceiver 超时与跨线程竞争；
 * 2. 插入识别与授权通过统一停留于 [HardwarePipelineState.RELEASED]，绝不提前空转 URB 或抢播变更业务态；
 * 3. 播放态下物理拔出立即置 Native 静音失效门控并销毁句柄，按独立递增 `deviceGeneration` 上报
 *    [HardwareDetachedEvent]，非播放态下热插拔严守业务态隔离；
 * 4. `claimUsbExclusive` / `openDeviceWithCurrentFd` 遇到三元组变化优先调用 `reconfigureAudioFormat` 原位切频，
 *    失败时执行释放重开安全回滚。
 */
class UsbDeviceManager internal constructor(
    private val systemBridge: UsbSystemBridge,
    private val nativeBridge: UsbNativeAudioBridge,
    private val hardwareExecutor: ExecutorService = createSingleThreadHardwareExecutor(),
    private val ownsExecutor: Boolean = false,
    private val context: Context? = null,
    private val onDeviceStateChanged: (connected: Boolean, deviceName: String?) -> Unit = { _, _ -> },
    private val dispatchEvent: (SessionEventEnvelope<SessionEventPayload>) -> Unit = {},
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) : SessionHardwareBridge {

    constructor(
        context: Context,
        onDeviceStateChanged: (connected: Boolean, deviceName: String?) -> Unit,
        dispatchEvent: (SessionEventEnvelope<SessionEventPayload>) -> Unit = {}
    ) : this(
        systemBridge = AndroidUsbSystemBridge(context),
        nativeBridge = DefaultUsbNativeAudioBridge,
        hardwareExecutor = createSingleThreadHardwareExecutor(),
        ownsExecutor = true,
        context = context,
        onDeviceStateChanged = onDeviceStateChanged,
        dispatchEvent = dispatchEvent
    )

    private val idleHandler: Handler? = if (context != null) Handler(Looper.getMainLooper()) else null

    // 💡 重入检测标识：若已经在 HardwareExecutor 线程中直接调用同步方法，避免向单线程池 submit 自锁
    private val inHardwareExecutor = ThreadLocal.withInitial { false }

    private var activeDevice: UsbDeviceDescriptor? = null
    private var activeConnection: UsbOpenFdHandle? = null
    private val isExclusiveTaken = AtomicBoolean(false)
    private val isPermissionRequesting = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)

    private val deviceGeneration = AtomicLong(0L)
    private val hardwareSequence = AtomicLong(0L)

    @Volatile
    private var permissionActivityRef: WeakReference<Activity>? = null

    private var currentSampleRate: Int = 0
    private var currentBitDepth: Int = 0
    private var currentChannels: Int = 0

    // 💡 10 秒空闲自动归还调度任务（兼容旧直接调度入口；主逻辑于 U5 由协调器接管）
    private var idleTimeoutMillis = 10_000L
    private val releaseRunnable = Runnable {
        Logger.usb.i(TAG, "推流暂停/空闲已满 ${idleTimeoutMillis / 1000} 秒，触发内核声卡让渡时序...")
        executeOnHardwareAsync {
            releaseUsbExclusiveInternal()
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val descriptor = device?.toDescriptor()
            // 💡 严禁在 BroadcastReceiver 主线程中加锁或调用阻塞 JNI，仅非阻塞投递给 HardwareExecutor
            executeOnHardwareAsync {
                handlePermissionResultInternal(descriptor, granted)
            }
        }
    }

    init {
        if (context != null) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(permissionReceiver, filter)
            }
        }
    }

    fun bindPermissionActivity(activity: Activity?) {
        permissionActivityRef = activity?.let { WeakReference(it) }
    }

    /**
     * 冷启动或手动触发扫描连接 USB Audio 设备（在 HardwareExecutor 上串行执行）
     */
    fun scanAndAttachDevice(): Boolean = runOnHardwareSync {
        scanAndAttachDeviceInternal()
    }

    private fun scanAndAttachDeviceInternal(): Boolean {
        Logger.usb.i(TAG, "HardwareExecutor 扫描 USB Audio 设备列表...")
        val candidate = systemBridge.scanAudioDevice()
        if (candidate == null) {
            Logger.usb.w(TAG, "未发现符合规范的 USB Audio Class 设备")
            onDeviceStateChanged(false, null)
            return false
        }
        return handleAttachedDescriptorInternal(candidate)
    }

    /**
     * 💡 非阻塞投递 Intent / BroadcastReceiver 收到的 USB 插入事件，杜绝 Broadcast 超时
     */
    fun onDeviceAttachedAsync(device: UsbDevice) {
        val descriptor = device.toDescriptor()
        executeOnHardwareAsync {
            handleAttachedDescriptorInternal(descriptor)
        }
    }

    /**
     * 兼容调用方保留名（内部已改为非阻塞派发给单线程执行器）
     */
    fun onDeviceAttachedFromIntent(device: UsbDevice) {
        onDeviceAttachedAsync(device)
    }

    private fun handleAttachedDescriptorInternal(descriptor: UsbDeviceDescriptor): Boolean {
        val current = activeDevice
        val isSameActive = current != null && current.deviceId == descriptor.deviceId &&
            current.vendorId == descriptor.vendorId && current.productId == descriptor.productId

        activeDevice = descriptor
        val hasPerm = systemBridge.hasPermission(descriptor)

        if (!isSameActive) {
            // 💡 物理设备新插拔事件递增独立 deviceGeneration，隔离上一代过期硬件事件
            deviceGeneration.incrementAndGet()
            isPermissionRequesting.set(false)
            Logger.usb.i(
                TAG,
                "检测到 USB DAC 插入: ${descriptor.productName}, gen=${deviceGeneration.get()}, hasPermission=$hasPerm"
            )
            emitHardwareEventLocked(
                HardwareAttachedEvent(
                    deviceName = descriptor.productName,
                    hasPermission = hasPerm
                )
            )
        } else if (hasPerm && isPermissionRequesting.getAndSet(false)) {
            emitHardwareEventLocked(
                UsbPermissionGrantedEvent(
                    deviceName = descriptor.productName,
                    granted = true
                )
            )
        }

        return if (hasPerm) {
            onDeviceStateChanged(true, descriptor.productName)
            true
        } else {
            Logger.usb.i(TAG, "USB DAC (${descriptor.productName}) 尚未获授权，维持 ATTACHED/PERMISSION_PENDING 等待授权")
            false
        }
    }

    /**
     * 💡 非阻塞异步处理拔出广播（供 UsbDeviceReceiver / PlaybackService 调用）
     */
    fun handleDeviceDetachedAsync(device: UsbDevice?) {
        val descriptor = device?.toDescriptor()
        executeOnHardwareAsync {
            handleDeviceDetachedInternal(descriptor)
        }
    }

    /**
     * 💡 串行执行 USB 设备物理拔出与停机让渡
     */
    fun handleDeviceDetached(device: UsbDevice?): Boolean = runOnHardwareSync {
        handleDeviceDetachedInternal(device?.toDescriptor())
    }

    private fun handleDeviceDetachedInternal(descriptor: UsbDeviceDescriptor?): Boolean {
        val current = activeDevice ?: return false
        val isCurrentDetached = descriptor == null ||
            descriptor.deviceId == current.deviceId ||
            (descriptor.vendorId == current.vendorId && descriptor.productId == current.productId)

        if (!isCurrentDetached) {
            return false
        }

        Logger.usb.i(TAG, "HardwareExecutor 处理当前 USB DAC 物理拔出 (${descriptor?.productName ?: current.productName})")
        // 💡 R9/U4：先锁死 Native 可写原子标记并紧急静音，再撤回 URB、关闭句柄
        nativeBridge.emergencyMuteAndFlush(FlushReason.STOP_OR_TEARDOWN)
        releaseUsbExclusiveInternal()
        activeDevice = null
        isPermissionRequesting.set(false)

        deviceGeneration.incrementAndGet()
        emitHardwareEventLocked(
            HardwareDetachedEvent(reason = "usb_detached")
        )
        onDeviceStateChanged(false, null)
        return true
    }

    /**
     * 💡 处理权限回调（同步版本供单测调用，生产广播通过 executeOnHardwareAsync 派发）
     */
    fun onPermissionResult(device: UsbDeviceDescriptor?, granted: Boolean) {
        runOnHardwareSync {
            handlePermissionResultInternal(device, granted)
        }
    }

    private fun handlePermissionResultInternal(descriptor: UsbDeviceDescriptor?, granted: Boolean) {
        isPermissionRequesting.set(false)
        val resolved = descriptor ?: activeDevice ?: systemBridge.scanAudioDevice()
        if (resolved == null) {
            Logger.usb.w(TAG, "收到 USB 权限回调但当前无关联设备: granted=$granted")
            return
        }
        activeDevice = resolved
        Logger.usb.i(TAG, "HardwareExecutor 收到 USB 权限审批结果: device=${resolved.productName}, granted=$granted")
        emitHardwareEventLocked(
            UsbPermissionGrantedEvent(
                deviceName = resolved.productName,
                granted = granted
            )
        )
        if (granted) {
            // 💡 U4 统一落态规则：授权通过仅进入 RELEASED 待命态，绝不提前 openDevice 空转 URB
            onDeviceStateChanged(true, resolved.productName)
        }
    }

    /**
     * 💡 由前台 Activity 或 Coordinator TransitionEffect 触发原生 USB 授权请求
     */
    fun requestPermissionWithActivity(activity: Activity) {
        bindPermissionActivity(activity)
        executeOnHardwareAsync {
            requestPermissionInternal(activity, null)
        }
    }

    override fun requestUsbPermission(deviceName: String) {
        executeOnHardwareAsync {
            requestPermissionInternal(permissionActivityRef?.get(), deviceName)
        }
    }

    private fun requestPermissionInternal(activity: Activity?, targetDeviceName: String?) {
        val device = activeDevice ?: systemBridge.scanAudioDevice()?.also { activeDevice = it }
        if (device == null) {
            Logger.usb.w(TAG, "requestUsbPermission 失败: 无可用 USB 设备 ($targetDeviceName)")
            return
        }

        if (systemBridge.hasPermission(device)) {
            Logger.usb.i(TAG, "设备已拥有访问授权 (${device.productName})，同步回执入 RELEASED")
            isPermissionRequesting.set(false)
            emitHardwareEventLocked(
                UsbPermissionGrantedEvent(deviceName = device.productName, granted = true)
            )
            onDeviceStateChanged(true, device.productName)
            return
        }

        if (!isPermissionRequesting.compareAndSet(false, true)) {
            Logger.usb.d(TAG, "当前已存在进行中的 USB 权限请求，跳过重复弹窗")
            return
        }

        Logger.usb.i(TAG, "发起 USB 设备访问授权请求: ${device.productName}")
        emitHardwareEventLocked(UsbPermissionRequestedEvent(deviceName = device.productName))
        systemBridge.requestPermission(device, activity)
    }

    /**
     * 兼容旧布尔返回签名：同步串行在 HardwareExecutor 中开启或切换 USB 独占
     */
    fun claimUsbExclusive(sampleRate: Int, bitDepth: Int, channels: Int): Boolean {
        return openDeviceWithCurrentFd(sampleRate, bitDepth, channels).isSuccess
    }

    /**
     * 💡 实现 [SessionHardwareBridge.openDeviceWithCurrentFd]：
     * - 同规格幂等命中直接返回现有 epoch；
     * - 已独占且规格变化时优先执行 [reconfigureAudioFormat] 原位切频，失败则释放重开安全回滚；
     * - 未独占时使用新获取的 Linux usbfs fd 调用 `openDevice` 进入独占。
     */
    override fun openDeviceWithCurrentFd(sampleRate: Int, bitDepth: Int, channels: Int): Result<HardwareOpenSuccess> {
        return runOnHardwareSync {
            openOrReconfigureInternal(sampleRate, bitDepth, channels)
        }
    }

    override fun reconfigureAudioFormat(sampleRate: Int, bitDepth: Int, channels: Int): Long {
        return runOnHardwareSync {
            openOrReconfigureInternal(sampleRate, bitDepth, channels)
                .map { it.epoch }
                .getOrElse { -1L }
        }
    }

    private fun openOrReconfigureInternal(sampleRate: Int, bitDepth: Int, channels: Int): Result<HardwareOpenSuccess> {
        idleHandler?.removeCallbacks(releaseRunnable)

        val device = activeDevice ?: systemBridge.scanAudioDevice()?.also { activeDevice = it }
        if (device == null) {
            Logger.usb.e(TAG, "openDeviceWithCurrentFd 失败: 无可用 USB 设备")
            return Result.failure(IllegalStateException("无可用 USB DAC 设备"))
        }

        if (!systemBridge.hasPermission(device)) {
            Logger.usb.w(TAG, "openDeviceWithCurrentFd 失败: 缺少 USB 权限 (${device.productName})")
            requestPermissionInternal(permissionActivityRef?.get(), device.productName)
            return Result.failure(SecurityException("缺少 USB 设备权限"))
        }

        val currentlyExclusive = isExclusiveTaken.get() && activeConnection != null
        if (currentlyExclusive) {
            if (currentSampleRate == sampleRate && currentBitDepth == bitDepth && currentChannels == channels) {
                val epoch = nativeBridge.getCurrentBufferEpoch().coerceAtLeast(1L)
                Logger.usb.i(TAG, "USB 设备已按相同规格独占 ($sampleRate Hz, $bitDepth bit, $channels ch), 复用 epoch=$epoch")
                return Result.success(HardwareOpenSuccess(epoch = epoch, dacName = device.productName))
            }

            // 💡 U4 三元组原位切频时序：通知进入 AUDIO_RECONFIGURING 并仅传递 (sampleRate, bitDepth, channels)
            Logger.usb.i(
                TAG,
                "HardwareExecutor 执行三元组原位切频: ${currentSampleRate}Hz/${currentBitDepth}bit -> ${sampleRate}Hz/${bitDepth}bit/${channels}ch"
            )
            emitHardwareEventLocked(
                HardwareStateSyncedEvent(
                    newHardwareState = HardwarePipelineState.AUDIO_RECONFIGURING,
                    dacName = device.productName
                )
            )
            val reconfigEpoch = nativeBridge.reconfigureAudioFormat(sampleRate, bitDepth, channels)
            if (reconfigEpoch > 0L) {
                currentSampleRate = sampleRate
                currentBitDepth = bitDepth
                currentChannels = channels
                emitHardwareEventLocked(
                    HardwareReconfiguredEvent(
                        sampleRate = sampleRate,
                        bitDepth = bitDepth,
                        channels = channels,
                        epoch = reconfigEpoch
                    )
                )
                return Result.success(HardwareOpenSuccess(epoch = reconfigEpoch, dacName = device.productName))
            }

            // 💡 U4 契约：原位切频返回负值 -> 安全回滚（释放旧句柄 + 重开新 fd 并打开新规格）
            Logger.usb.w(TAG, "原位切频失败 ($reconfigEpoch)，执行安全回滚释放旧连接并重开...")
            releaseUsbExclusiveInternal()
            val fallbackResult = freshOpenDeviceInternal(device, sampleRate, bitDepth, channels, isReconfigureRollback = true)
            if (fallbackResult.isFailure) {
                emitHardwareEventLocked(
                    HardwareReconfigureFailedEvent(
                        errorCode = ErrorCode.USB_RECONFIGURE_FAILED,
                        message = "UAC2 三元组切频及回滚重开均失败 (${sampleRate}Hz/${bitDepth}bit)",
                        fallbackState = HardwarePipelineState.RELEASED
                    )
                )
            }
            return fallbackResult
        }

        return freshOpenDeviceInternal(device, sampleRate, bitDepth, channels, isReconfigureRollback = false)
    }

    private fun freshOpenDeviceInternal(
        device: UsbDeviceDescriptor,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        isReconfigureRollback: Boolean
    ): Result<HardwareOpenSuccess> {
        Logger.usb.i(TAG, "打开 UsbDeviceConnection 并穿透 Linux usbfs fd 到 Native 驱动...")
        val connection = systemBridge.openConnection(device)
        if (connection == null) {
            Logger.usb.e(TAG, "openConnection 返回 null，无法获取设备句柄")
            if (!isReconfigureRollback) {
                emitHardwareEventLocked(
                    HardwareReconfigureFailedEvent(
                        errorCode = ErrorCode.USB_OPEN_FAILED,
                        message = "无法打开 USB 设备连接句柄 (${device.productName})",
                        fallbackState = HardwarePipelineState.RELEASED
                    )
                )
            }
            return Result.failure(IllegalStateException("openConnection returned null"))
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            Logger.usb.e(TAG, "获取底层 Linux usbfs fd 失败 (fd=$fd)")
            connection.close()
            if (!isReconfigureRollback) {
                emitHardwareEventLocked(
                    HardwareReconfigureFailedEvent(
                        errorCode = ErrorCode.USB_OPEN_FAILED,
                        message = "无效的 USB 文件描述符 (fd=$fd)",
                        fallbackState = HardwarePipelineState.RELEASED
                    )
                )
            }
            return Result.failure(IllegalStateException("Invalid usbfs fd=$fd"))
        }

        val ret = nativeBridge.openDevice(fd, sampleRate, bitDepth, channels)
        if (ret != 0) {
            Logger.usb.e(TAG, "Native openDevice 失败: ret=$ret, fd=$fd")
            connection.close()
            if (!isReconfigureRollback) {
                emitHardwareEventLocked(
                    HardwareReconfigureFailedEvent(
                        errorCode = ErrorCode.USB_OPEN_FAILED,
                        message = "Native openDevice 失败 ($ret)",
                        fallbackState = HardwarePipelineState.RELEASED
                    )
                )
            }
            return Result.failure(IllegalStateException("Native openDevice returned $ret"))
        }

        activeConnection = connection
        currentSampleRate = sampleRate
        currentBitDepth = bitDepth
        currentChannels = channels
        isExclusiveTaken.set(true)

        val epoch = nativeBridge.getCurrentBufferEpoch().coerceAtLeast(1L)
        if (isReconfigureRollback) {
            emitHardwareEventLocked(
                HardwareReconfiguredEvent(
                    sampleRate = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    epoch = epoch
                )
            )
        }
        Logger.usb.i(TAG, "🎉 成功独占 USB 声卡 (${sampleRate}Hz / ${bitDepth}bit / ${channels}ch), fd=$fd, epoch=$epoch")
        return Result.success(HardwareOpenSuccess(epoch = epoch, dacName = device.productName))
    }

    /**
     * 💡 快路径同步紧急静音（直达 C++ 原子静音标记，严禁排队等待 HardwareExecutor）
     */
    override fun emergencyMuteAndFlush(reason: FlushReason): Long =
        nativeBridge.emergencyMuteAndFlush(reason)

    override fun resumeNativeStream(): Int =
        nativeBridge.resumeStream()

    override fun releaseDevice() {
        releaseUsbExclusive()
    }

    fun releaseUsbExclusive() {
        runOnHardwareSync {
            releaseUsbExclusiveInternal()
        }
    }

    /**
     * 💡 外部音频焦点彻底丢失（AUDIOFOCUS_LOSS）时立即归还 USB 独占句柄，
     * 并在串行硬件线程同步最新硬件状态回协调器（保证 hardwareSequence 单调递增）。
     */
    fun releaseForExternalFocusLoss() {
        executeOnHardwareAsync {
            val hadExclusive = isExclusiveTaken.get() || activeConnection != null
            releaseUsbExclusiveInternal()
            val dev = activeDevice
            if (hadExclusive && dev != null) {
                val targetState = if (systemBridge.hasPermission(dev)) {
                    HardwarePipelineState.RELEASED
                } else {
                    HardwarePipelineState.PERMISSION_PENDING
                }
                emitHardwareEventLocked(HardwareStateSyncedEvent(targetState))
            }
        }
    }

    private fun releaseUsbExclusiveInternal() {
        idleHandler?.removeCallbacks(releaseRunnable)
        val wasExclusive = isExclusiveTaken.getAndSet(false)
        val conn = activeConnection
        activeConnection = null
        currentSampleRate = 0
        currentBitDepth = 0
        currentChannels = 0

        if (!wasExclusive && conn == null) {
            return
        }

        Logger.usb.i(TAG, "HardwareExecutor 执行声卡解绑归还与自愈时序...")
        nativeBridge.releaseDevice()
        conn?.let {
            try {
                it.close()
                Logger.usb.i(TAG, "UsbDeviceConnection 句柄已正常关闭")
            } catch (e: Exception) {
                Logger.usb.w(TAG, "关闭 UsbDeviceConnection 时捕获异常", e)
            }
        }
        systemBridge.triggerAudioSelfHealing()
    }

    fun scheduleIdleRelease(customTimeoutMillis: Long = idleTimeoutMillis) {
        val handler = idleHandler ?: return
        handler.removeCallbacks(releaseRunnable)
        if (idleTimeoutMillis <= 0L || customTimeoutMillis <= 0L) {
            Logger.usb.i(TAG, "已配置常驻独占策略 (0秒)，保持声卡独占不自动让渡")
            return
        }
        Logger.usb.i(TAG, "启动 ${customTimeoutMillis / 1000} 秒空闲声卡让渡倒计时...")
        handler.postDelayed(releaseRunnable, customTimeoutMillis)
    }

    fun cancelIdleRelease() {
        idleHandler?.removeCallbacks(releaseRunnable)
    }

    fun setIdleTimeoutSeconds(seconds: Int) {
        idleTimeoutMillis = if (seconds <= 0) 0L else seconds.coerceAtLeast(3) * 1000L
        Logger.usb.i(TAG, "更新空闲声卡让渡时间为: $seconds 秒 (0秒为常驻独占)")
    }

    fun isExclusive(): Boolean = isExclusiveTaken.get()

    fun currentDeviceGeneration(): Long = deviceGeneration.get()

    fun destroy() {
        if (!isDestroyed.compareAndSet(false, true)) return
        idleHandler?.removeCallbacks(releaseRunnable)
        try {
            runOnHardwareSync {
                releaseUsbExclusiveInternal()
            }
        } catch (e: Exception) {
            Logger.usb.w(TAG, "destroy 执行释放收尾时捕获异常: ${e.message}")
        }
        permissionActivityRef = null
        if (context != null) {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (e: Exception) {
                Logger.usb.w(TAG, "注销 USB 权限广播接收器异常: ${e.message}")
            }
        }
        if (ownsExecutor) {
            hardwareExecutor.shutdown()
            try {
                if (!hardwareExecutor.awaitTermination(1500L, TimeUnit.MILLISECONDS)) {
                    hardwareExecutor.shutdownNow()
                }
            } catch (_: InterruptedException) {
                hardwareExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun emitHardwareEventLocked(payload: SessionEventPayload) {
        dispatchEvent(
            SessionEventEnvelope(
                origin = EventOrigin.USB_HARDWARE,
                sessionId = 0L,
                deviceGeneration = deviceGeneration.get(),
                sequence = hardwareSequence.incrementAndGet(),
                timestampElapsedMs = clockMs(),
                payload = payload
            )
        )
    }

    private fun executeOnHardwareAsync(block: () -> Unit) {
        if (hardwareExecutor.isShutdown) return
        hardwareExecutor.execute {
            val prev = inHardwareExecutor.get() ?: false
            inHardwareExecutor.set(true)
            try {
                block()
            } catch (t: Throwable) {
                Logger.usb.e(TAG, "HardwareExecutor 异步任务执行异常", t)
            } finally {
                inHardwareExecutor.set(prev)
            }
        }
    }

    private fun <T> runOnHardwareSync(block: () -> T): T {
        if (inHardwareExecutor.get() == true) {
            return block()
        }
        if (hardwareExecutor.isShutdown) {
            return block()
        }
        return hardwareExecutor.submit(Callable {
            val prev = inHardwareExecutor.get() ?: false
            inHardwareExecutor.set(true)
            try {
                block()
            } finally {
                inHardwareExecutor.set(prev)
            }
        }).get()
    }

    companion object {
        private const val TAG = "UsbDeviceManager"
        const val ACTION_USB_PERMISSION = "com.solo.purecast.USB_PERMISSION"
        const val SONY_VENDOR_ID = 0x054c // 索尼 Vendor ID (十进制 1356)

        private fun createSingleThreadHardwareExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PureCast-HardwareExecutor").apply {
                    isDaemon = false
                }
            }
    }
}
