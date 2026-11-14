package com.solo.purecast.core.usb

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import com.solo.purecast.service.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 💡 UsbDeviceReceiver 广播拦截与静默后台启动测试 (#15)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbDeviceReceiverTest {

    @Test
    fun `boot and quickboot broadcasts start PlaybackService silently without throwing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val receiver = UsbDeviceReceiver()
        val shadowApp = Shadows.shadowOf(app)

        val bootActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            UsbDeviceReceiver.ACTION_QUICKBOOT_POWERON,
            UsbDeviceReceiver.ACTION_HTC_QUICKBOOT_POWERON
        )
        for (action in bootActions) {
            receiver.onReceive(app, Intent(action))
            val started = shadowApp.nextStartedService
            assertNotNull("广播 [$action] 必须触发 PlaybackService 前台启动", started)
            assertEquals(PlaybackService::class.java.name, started?.component?.className)
        }
    }

    @Test
    fun `usb attach broadcast without extra device still starts PlaybackService safely`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val receiver = UsbDeviceReceiver()
        val shadowApp = Shadows.shadowOf(app)

        receiver.onReceive(app, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        val started = shadowApp.nextStartedService
        assertNotNull(started)
        assertEquals(PlaybackService::class.java.name, started?.component?.className)
    }

    @Test
    fun `usb detach, unknown action, and null intent do not crash or spawn unexpected services`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val receiver = UsbDeviceReceiver()
        val shadowApp = Shadows.shadowOf(app)

        receiver.onReceive(app, Intent(UsbManager.ACTION_USB_DEVICE_DETACHED))
        receiver.onReceive(app, Intent("com.solo.purecast.UNKNOWN_ACTION"))
        receiver.onReceive(app, null)

        assertNull("拔出、未知广播与空 Intent 不应重复拉起 PlaybackService", shadowApp.nextStartedService)
    }
}
