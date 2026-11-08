package com.solo.purecast.core.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.MainActivity
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
                // 启动或通知前台服务挂载设备
                PlaybackService.start(context)
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                Logger.usb.i(TAG, "检测到 USB 设备拔出: ${device?.deviceName}")
                PlaybackService.instance?.handleUsbDeviceDetached(device)
            }

            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON -> {
                Logger.usb.i(TAG, "电视盒子冷开机或快速开机广播触发 ($action)，拉起后台音频服务并冷启动枚举 USB 设备...")
                PlaybackService.start(context)

                // 💡 开机自启动全屏拉起大屏播放主界面，实现真正的开机即用
                try {
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                        val audioDevice = usbManager?.deviceList?.values?.firstOrNull { device ->
                            device.vendorId == UsbDeviceManager.SONY_VENDOR_ID ||
                            device.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
                            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
                        }
                        if (audioDevice != null) {
                            setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                            putExtra(UsbManager.EXTRA_DEVICE, audioDevice)
                        }
                    }
                    context.startActivity(activityIntent)
                    Logger.usb.i(TAG, "开机广播触发：成功拉起 MainActivity 大屏主界面")
                } catch (e: Exception) {
                    Logger.usb.e(TAG, "开机拉起 MainActivity 异常", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbDeviceReceiver"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
