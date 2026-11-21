package com.solo.purecast.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 💡 PureCast 权限与系统自检引导核心辅助类 (PermissionHelper)
 *
 * 核心设计原则：
 * 1. 严格遵守《项目背景与核心目标.md》第 4.1 节规范与零录音硬件调用承诺：
 *    全工程绝不包含任何 AudioRecord 录音硬件调用，申请 RECORD_AUDIO 仅为了满足 Android 系统
 *    在无触控 TV 端对 USB 声卡设备静默接管与防插拔弹窗的合规要求。
 * 2. 10-Foot 电视盒子高健壮性容错：
 *    Android TV / 电视盒子固件深度定制，部分定制 ROM 缺少特定 Settings Activity（例如悬浮窗设置界面）。
 *    所有 Intent 跳转均施加 resolveActivity 探测与 try-catch 保护，并在缺失时优雅降级回退至应用详情页。
 */
object PermissionHelper {

    private const val TAG = "PermissionHelper"

    /**
     * 权限状态快照实体
     */
    data class PermissionStatus(
        val isRecordAudioGranted: Boolean,
        val isOverlayGranted: Boolean,
        val isBatteryOptimizationIgnored: Boolean,
        val isAutoRevokeWhitelisted: Boolean,
        val isNotificationGranted: Boolean = true
    ) {
        /**
         * 是否所有必需与建议项均已就绪
         */
        val isAllGranted: Boolean
            get() = isRecordAudioGranted &&
                    isOverlayGranted &&
                    isBatteryOptimizationIgnored &&
                    isAutoRevokeWhitelisted &&
                    isNotificationGranted
    }

    /**
     * 💡 检查录音权限 (RECORD_AUDIO)
     * 说明：仅用于维持 USB DAC 授权的底层持久性，严禁初始化 AudioRecord 硬件实例。
     */
    fun isRecordAudioGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 💡 检查悬浮窗权限 (SYSTEM_ALERT_WINDOW)
     * 用于突破 Android 后台启动限制 (BAL, Background Activity Launch)，
     * 在后台收到局域网推流时自动拉起大屏播放界面。
     */
    fun isOverlayGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 💡 检查是否加入电池优化白名单 (IGNORE_BATTERY_OPTIMIZATIONS)
     * 防止电视盒子在熄屏或待机时杀死后台 UPnP 发现与高码率推流服务。
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 💡 检查是否在防权限自动重置白名单中 (API 30+ isAutoRevokeWhitelisted)
     * 避免长期闲置后，系统自动撤销 USB 与通知等关键权限导致推流失败。
     */
    fun isAutoRevokeWhitelisted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.packageManager.isAutoRevokeWhitelisted
            } catch (e: Exception) {
                Logger.service.w(TAG, "检测 isAutoRevokeWhitelisted 异常，默认视为已授权", e)
                true
            }
        } else {
            true
        }
    }

    fun hasMissingCriticalPermission(context: Context): Boolean {
        return !checkAllPermissions(context).isAllGranted
    }

    /**
     * 💡 检查通知权限 (Android 13+ POST_NOTIFICATIONS)，保障前台服务与锁屏媒体通知正常显示
     */
    fun isNotificationGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 一键全量自检核心权限状态
     */
    fun checkAllPermissions(context: Context): PermissionStatus {
        val audio = isRecordAudioGranted(context)
        val overlay = isOverlayGranted(context)
        val battery = isBatteryOptimizationIgnored(context)
        val autoRevoke = isAutoRevokeWhitelisted(context)
        val notification = isNotificationGranted(context)

        Logger.service.d(
            TAG,
            "自检权限状态: RECORD_AUDIO=$audio, OVERLAY=$overlay, BATTERY=$battery, AUTO_REVOKE=$autoRevoke, POST_NOTIFICATIONS=$notification"
        )

        return PermissionStatus(
            isRecordAudioGranted = audio,
            isOverlayGranted = overlay,
            isBatteryOptimizationIgnored = battery,
            isAutoRevokeWhitelisted = autoRevoke,
            isNotificationGranted = notification
        )
    }

    /**
     * 💡 安全打开悬浮窗授权设置页
     * 增加 resolveActivity 探测与 try-catch 容错机制，针对缺少该页面的电视盒子回退到应用详情页。
     */
    fun openOverlaySettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Logger.service.i(TAG, "成功打开悬浮窗授权页面")
            } else {
                Logger.service.w(TAG, "设备不支持 ACTION_MANAGE_OVERLAY_PERMISSION，回退至应用详情")
                openAppDetailsSettings(context)
            }
        } catch (e: Exception) {
            Logger.service.w(TAG, "打开悬浮窗设置异常，尝试应用详情页", e)
            openAppDetailsSettings(context)
        }
    }

    /**
     * 💡 安全打开电池优化白名单申请页
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Logger.service.i(TAG, "成功打开 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                return
            }

            // 备用通用列表设置页
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(fallbackIntent)
                Logger.service.i(TAG, "成功打开备用 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
            } else {
                Logger.service.w(TAG, "设备不支持电池优化界面，回退至应用详情")
                openAppDetailsSettings(context)
            }
        } catch (e: Exception) {
            Logger.service.w(TAG, "打开电池优化设置异常，回退至应用详情", e)
            openAppDetailsSettings(context)
        }
    }

    /**
     * 💡 安全打开防权限自动回收设置页 (API 30+)
     */
    fun openAutoRevokeSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Logger.service.i(TAG, "成功打开 ACTION_AUTO_REVOKE_PERMISSIONS")
                    return
                }
            } catch (e: Exception) {
                Logger.service.w(TAG, "无法直接跳转防权限回收界面，回退到应用详情", e)
            }
        }
        openAppDetailsSettings(context)
    }

    /**
     * 💡 打开应用详情系统设置页 (终极保底跳转)
     */
    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Logger.service.i(TAG, "成功打开系统应用详情设置页")
        } catch (e: Exception) {
            Logger.service.e(TAG, "无法打开系统应用详情设置页", e)
        }
    }
}
