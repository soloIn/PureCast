package com.solo.purecast.core.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.util.Logger

/**
 * 💡 USB 设备热插拔与开机广播监听器
 * 拦截 USB_DEVICE_ATTACHED、USB_DEVICE_DETACHED 以及各类电视盒子的开机/快速唤醒广播
 */
class UsbDeviceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Logger.usb.i(TAG, "收到系统广播事件: $action")

        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                Logger.usb.i(TAG, "检测到 USB 设备插入: ${device?.deviceName}, VendorID=0x${device?.vendorId?.toString(16)}")
                // 💡 严禁在广播接收器阻塞：拉起服务并将设备异步投递给单线程 HardwareExecutor
                PlaybackService.start(context)
                if (device != null) {
                    PlaybackService.instance?.handleUsbDeviceAttachedFromIntent(device)
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                Logger.usb.i(TAG, "检测到 USB 设备拔出: ${device?.deviceName}")
                // 💡 非阻塞投递拔出事件给 HardwareExecutor，规避 Broadcast 超时 ANR
                PlaybackService.instance?.handleUsbDeviceDetached(device)
            }

            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON -> {
                Logger.usb.i(TAG, "电视盒子冷开机或快速开机广播触发 ($action)，静默拉起后台核心音频服务并枚举 USB 设备...")
                // 💡 作为纯系统后台服务静默自启动，绝不开机主动拉起 Activity 干扰电视大屏桌面
                PlaybackService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "UsbDeviceReceiver"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
