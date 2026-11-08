package com.solo.purecast.data

import android.content.Context
import android.content.SharedPreferences
import com.solo.purecast.ui.background.FluidQuality
import com.solo.purecast.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 💡 PureCast 配置持久化仓库 (SettingsRepository)
 * 采用 SharedPreferences 持久化存储大屏端各项设置：
 * 1. UPnP / DLNA 局域网友好广播名称 (friendlyName)
 * 2. USB 声卡 10 秒空闲让渡策略 (idleTimeoutSeconds)
 * 3. 首次开箱自检向导展示标记 (onboardingCompleted)
 * 4. 动态流光背景画质档位 (fluidQuality)
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _friendlyNameFlow = MutableStateFlow(getFriendlyName())
    val friendlyNameFlow: StateFlow<String> = _friendlyNameFlow.asStateFlow()

    private val _airPlayEnabledFlow = MutableStateFlow(isAirPlayEnabled())
    val airPlayEnabledFlow: StateFlow<Boolean> = _airPlayEnabledFlow.asStateFlow()

    private val _upnpEnabledFlow = MutableStateFlow(isUpnpEnabled())
    val upnpEnabledFlow: StateFlow<Boolean> = _upnpEnabledFlow.asStateFlow()

    private val _fluidQualityFlow = MutableStateFlow(getFluidQuality())
    val fluidQualityFlow: StateFlow<FluidQuality> = _fluidQualityFlow.asStateFlow()

    /**
     * 获取 UPnP 设备局域网广播名，默认 "PureCast TV"
     */
    fun getFriendlyName(): String {
        return prefs.getString(KEY_FRIENDLY_NAME, DEFAULT_FRIENDLY_NAME) ?: DEFAULT_FRIENDLY_NAME
    }

    /**
     * 设置并持久化 UPnP 广播名称，同时更新 StateFlow
     */
    fun setFriendlyName(name: String) {
        val trimmed = name.trim().ifEmpty { DEFAULT_FRIENDLY_NAME }
        Logger.service.i(TAG, "更新 UPnP 设备广播名称: $trimmed")
        prefs.edit().putString(KEY_FRIENDLY_NAME, trimmed).apply()
        _friendlyNameFlow.value = trimmed
    }

    /**
     * 获取空闲让渡超时秒数，默认 10 秒
     */
    fun getIdleTimeoutSeconds(): Int {
        return prefs.getInt(KEY_IDLE_TIMEOUT_SECONDS, DEFAULT_IDLE_TIMEOUT)
    }

    /**
     * 设置空闲让渡超时秒数
     */
    fun setIdleTimeoutSeconds(seconds: Int) {
        Logger.service.i(TAG, "更新声卡空闲让渡超时时间: ${seconds}秒")
        prefs.edit().putInt(KEY_IDLE_TIMEOUT_SECONDS, seconds).apply()
    }

    /**
     * 检查是否启用 AirPlay 1 服务，默认 true
     */
    fun isAirPlayEnabled(): Boolean {
        return prefs.getBoolean(KEY_AIRPLAY_ENABLED, true)
    }

    /**
     * 设置并持久化 AirPlay 1 服务开关状态
     */
    fun setAirPlayEnabled(enabled: Boolean) {
        Logger.service.i(TAG, "更新 AirPlay 1 服务开关状态: $enabled")
        prefs.edit().putBoolean(KEY_AIRPLAY_ENABLED, enabled).apply()
        _airPlayEnabledFlow.value = enabled
    }

    /**
     * 检查是否启用 UPnP / DLNA 服务，默认 true
     */
    fun isUpnpEnabled(): Boolean {
        return prefs.getBoolean(KEY_UPNP_ENABLED, true)
    }

    /**
     * 设置并持久化 UPnP / DLNA 服务开关状态
     */
    fun setUpnpEnabled(enabled: Boolean) {
        Logger.service.i(TAG, "更新 UPnP / DLNA 服务开关状态: $enabled")
        prefs.edit().putBoolean(KEY_UPNP_ENABLED, enabled).apply()
        _upnpEnabledFlow.value = enabled
    }

    /**
     * 检查首次启动自检向导是否已由用户完成或确认
     */
    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /**
     * 记录首次启动自检向导完成状态
     */
    fun setOnboardingCompleted(completed: Boolean) {
        Logger.service.i(TAG, "更新开箱自检完成标记: $completed")
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    /**
     * 获取动态流光背景画质档位，默认 STANDARD (960, 60fps)
     */
    fun getFluidQuality(): FluidQuality {
        val name = prefs.getString(KEY_FLUID_QUALITY, FluidQuality.STANDARD.name)
        return FluidQuality.fromName(name)
    }

    /**
     * 设置并持久化动态流光背景画质档位
     */
    fun setFluidQuality(quality: FluidQuality) {
        Logger.service.i(TAG, "更新动态流光背景画质档位: ${quality.name}")
        prefs.edit().putString(KEY_FLUID_QUALITY, quality.name).apply()
        _fluidQualityFlow.value = quality
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "purecast_settings"

        private const val KEY_FRIENDLY_NAME = "upnp_friendly_name"
        private const val KEY_IDLE_TIMEOUT_SECONDS = "usb_idle_timeout_seconds"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_AIRPLAY_ENABLED = "airplay_enabled"
        private const val KEY_UPNP_ENABLED = "upnp_enabled"
        private const val KEY_FLUID_QUALITY = "fluid_quality"

        const val DEFAULT_FRIENDLY_NAME = "PureCast TV"
        const val DEFAULT_IDLE_TIMEOUT = 10

        /**
         * 常用预设友好名称列表，便于电视遥控器快速选定
         */
        val PRESET_FRIENDLY_NAMES = listOf(
            "PureCast TV",
            "PureCast (客厅)",
            "PureCast (Hi-Fi DAC)",
            "PureCast (ZX300A)",
            "PureCast (书房)"
        )
    }
}
