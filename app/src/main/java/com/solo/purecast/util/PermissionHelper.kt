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
 *    Android TV / 电视盒子固件深度定制，部分定制 ROM 缺少特定 Settings Activity 或存在静默关闭的空壳页面。
 *    API 30+ 跳过 resolveActivity() 预检，采用 try-catch 级联跳转 + 窗口失焦探测自动降级。
 */
object PermissionHelper {

    private const val TAG = "PermissionHelper"
    private val cascadeToken = java.util.concurrent.atomic.AtomicLong(0L)
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

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

    private data class SettingsCandidate(
        val label: String,
        val intent: Intent,
        val onSuccessToast: String? = null
    )

    /**
     * 💡 通用级联安全跳转引擎（专为 Android TV / 电视盒定制固件设计）：
     * 1. API 30+ 不依赖 resolveActivity() 预检（避免包可见性误判）；
     * 2. 独立捕获 ActivityNotFoundException / SecurityException 级联降级；
     * 3. 针对 TV 固件“空壳 Activity（startActivity 不抛异常但 10ms 内静默 finish 毫无界面）”的顽疾：
     *    若调用方是 Activity，跳转后延迟 350ms 检查 Activity 是否依然持有窗口焦点 (hasWindowFocus())；
     *    若焦点从未离开，说明目标页面为空壳静默关闭，自动继续降级到下一候选页面。
     */
    private fun Context.findActivity(): android.app.Activity? {
        var current: Context? = this
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) return current
            current = current.baseContext
        }
        return current as? android.app.Activity
    }

    private fun launchSettingsCascade(
        context: Context,
        candidates: List<SettingsCandidate>,
        fallbackToast: String,
        index: Int = 0,
        token: Long = cascadeToken.incrementAndGet()
    ) {
        if (cascadeToken.get() != token) return
        if (index >= candidates.size) {
            Logger.service.e(TAG, "所有系统设置候选页面均不可用或为空壳页面")
            android.widget.Toast.makeText(context, fallbackToast, android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val candidate = candidates[index]
        val activity = context.findActivity()
        val launchContext: Context = activity ?: context
        val started = try {
            val intent = candidate.intent
            if (activity == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchContext.startActivity(intent)
            Logger.service.i(TAG, "已发起系统设置跳转: ${candidate.label}")
            true
        } catch (e: Exception) {
            Logger.service.w(TAG, "打开 ${candidate.label} 失败，立即尝试下一级: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        if (!started) {
            launchSettingsCascade(context, candidates, fallbackToast, index + 1, token)
            return
        }

        // 💡 若在 Activity 上下文启动，350ms 后探测窗口焦点是否真正切走；
        // 若依然停留在 PureCast 且持有焦点，说明 TV 固件该页面为空壳静默 finish，自动接力下一级（含末项 Toast 兜底）！
        if (activity != null) {
            mainHandler.postDelayed({
                if (cascadeToken.get() != token) return@postDelayed
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                if (activity.hasWindowFocus()) {
                    Logger.service.w(TAG, "${candidate.label} 跳转后窗口未失焦（TV 固件空壳静默关闭），自动降级至下一候选页")
                    launchSettingsCascade(context, candidates, fallbackToast, index + 1, token)
                } else if (!candidate.onSuccessToast.isNullOrBlank()) {
                    android.widget.Toast.makeText(context, candidate.onSuccessToast, android.widget.Toast.LENGTH_LONG).show()
                }
            }, 350L)
        } else if (!candidate.onSuccessToast.isNullOrBlank()) {
            android.widget.Toast.makeText(context, candidate.onSuccessToast, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 💡 安全打开悬浮窗授权设置页
     */
    fun openOverlaySettings(context: Context) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            SettingsCandidate("ACTION_MANAGE_OVERLAY_PERMISSION", Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)),
            SettingsCandidate("ACTION_MANAGE_OVERLAY_PERMISSION (无 URI)", Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)),
            SettingsCandidate(
                "ACTION_MANAGE_APPLICATIONS_SETTINGS",
                Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                onSuccessToast = "请在「特殊应用权限 → 显示在其他应用的上层」中允许 PureCast"
            ),
            SettingsCandidate("ACTION_APPLICATION_DETAILS_SETTINGS", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)),
            SettingsCandidate("ACTION_SETTINGS", Intent(Settings.ACTION_SETTINGS))
        )
        launchSettingsCascade(
            context = context,
            candidates = candidates,
            fallbackToast = "当前电视系统未开放悬浮窗设置页，请在系统设置中手动开启"
        )
    }

    /**
     * 💡 安全打开电池优化白名单申请页
     * - 非 TV 设备优先尝试直达弹窗与电池优化列表；
     * - Android TV (`TvSettings`) 实测入口位于 `MANAGE_APPLICATIONS_SETTINGS` →「特殊应用权限 → 电源优化」，
     *   在 Leanback TV 上直接跳转 `ACTION_MANAGE_APPLICATIONS_SETTINGS` 并附带引导 Toast，零空壳等待。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val candidates = buildList {
            if (!isTv && !isBatteryOptimizationIgnored(context)) {
                add(
                    SettingsCandidate(
                        "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                    )
                )
            }
            if (!isTv) {
                add(
                    SettingsCandidate(
                        "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                )
            }
            add(
                SettingsCandidate(
                    "ACTION_MANAGE_APPLICATIONS_SETTINGS",
                    Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                    onSuccessToast = "请点击「特殊应用权限 → 电源优化」将 PureCast 设为不优化"
                )
            )
            add(
                SettingsCandidate(
                    "ACTION_APPLICATION_DETAILS_SETTINGS",
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                )
            )
            add(SettingsCandidate("ACTION_SETTINGS", Intent(Settings.ACTION_SETTINGS)))
        }
        launchSettingsCascade(
            context = context,
            candidates = candidates,
            fallbackToast = "当前电视系统未开放电池白名单页面，请在系统设置中将 PureCast 设为不限制后台"
        )
    }

    /**
     * 💡 安全打开防权限自动回收设置页 (API 30+)
     */
    fun openAutoRevokeSettings(context: Context) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    SettingsCandidate(
                        "ACTION_AUTO_REVOKE_PERMISSIONS",
                        Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS).setData(pkgUri)
                    )
                )
            }
            add(SettingsCandidate("ACTION_APPLICATION_DETAILS_SETTINGS", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)))
            add(SettingsCandidate("ACTION_MANAGE_APPLICATIONS_SETTINGS", Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)))
            add(SettingsCandidate("ACTION_SETTINGS", Intent(Settings.ACTION_SETTINGS)))
        }
        launchSettingsCascade(
            context = context,
            candidates = candidates,
            fallbackToast = "当前电视系统未开放防权限回收页面，请在应用详情中检查权限"
        )
    }

    /**
     * 💡 打开应用详情系统设置页 (多级保底跳转：应用详情 -> 应用列表 -> 系统设置首页 -> Toast 提示)
     */
    fun openAppDetailsSettings(context: Context, fallbackToast: String? = null) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            SettingsCandidate("ACTION_APPLICATION_DETAILS_SETTINGS", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)),
            SettingsCandidate("ACTION_MANAGE_APPLICATIONS_SETTINGS", Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)),
            SettingsCandidate("ACTION_SETTINGS", Intent(Settings.ACTION_SETTINGS))
        )
        launchSettingsCascade(
            context = context,
            candidates = candidates,
            fallbackToast = fallbackToast ?: "当前电视系统不支持跳转此设置页，请手动打开系统设置"
        )
    }
}
