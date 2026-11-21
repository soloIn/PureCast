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
class SettingsRepository internal constructor(context: Context) {

    private val prefs: SharedPreferences = resolveDeviceProtectedPrefs(context.applicationContext)

    private val _friendlyNameFlow = MutableStateFlow(getFriendlyName())
    val friendlyNameFlow: StateFlow<String> = _friendlyNameFlow.asStateFlow()

    private val _airPlayEnabledFlow = MutableStateFlow(isAirPlayEnabled())
    val airPlayEnabledFlow: StateFlow<Boolean> = _airPlayEnabledFlow.asStateFlow()

    private val _upnpEnabledFlow = MutableStateFlow(isUpnpEnabled())
    val upnpEnabledFlow: StateFlow<Boolean> = _upnpEnabledFlow.asStateFlow()

    private val _fluidQualityFlow = MutableStateFlow(getFluidQuality())
    val fluidQualityFlow: StateFlow<FluidQuality> = _fluidQualityFlow.asStateFlow()

    // 💡 补充暴露内存 StateFlow，彻底消除设置页 300ms 转场首帧磁盘同步读取
    private val _idleTimeoutFlow = MutableStateFlow(getIdleTimeoutSeconds())
    val idleTimeoutFlow: StateFlow<Int> = _idleTimeoutFlow.asStateFlow()

    private val _onboardingCompletedFlow = MutableStateFlow(isOnboardingCompleted())
    val onboardingCompletedFlow: StateFlow<Boolean> = _onboardingCompletedFlow.asStateFlow()

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
        _idleTimeoutFlow.value = seconds
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
        _onboardingCompletedFlow.value = completed
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

    /**
     * 💡 获取或首次生成持久化的本地管理 MAC 地址（12 位大写无分隔符，首字节 02 开头，符合 IEEE 802 LAA 规范），
     * 用于 Android 11+ 隐私策略屏蔽真实网卡 MAC 时的局域网唯一 mDNS 身份兜底，避免多台设备冲突。
     */
    fun getOrCreateFallbackMacAddress(): String {
        val existing = prefs.getString(KEY_FALLBACK_MAC_ADDRESS, null)
        if (!existing.isNullOrBlank() && existing.length == 12) {
            return existing
        }
        val random = java.security.SecureRandom()
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        // 💡 置位本地管理位 (bit1=1) 且清除组播位 (bit0=0) -> 合法单播 LAA MAC
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        val generated = bytes.joinToString("") { "%02X".format(it) }
        prefs.edit().putString(KEY_FALLBACK_MAC_ADDRESS, generated).apply()
        Logger.service.i(TAG, "首次生成并持久化本地管理兜底 MAC: $generated")
        return generated
    }

    companion object {
        private const val TAG = "SettingsRepository"
        const val PREFS_NAME = "purecast_settings"

        /**
         * 💡 Direct Boot 兼容：所有声明了 directBootAware="true" 的组件在用户未解锁前均可安全读写配置。
         * 若系统已解锁且旧 CE 存储存在历史配置，自动无感迁移至 Device Protected (DE) 存储。
         */
        internal fun resolveDeviceProtectedPrefs(appContext: Context): SharedPreferences {
            val dpContext = appContext.createDeviceProtectedStorageContext()
            val dpPrefs = dpContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // 💡 仅当 DE 存储尚为空（首次升级）时才尝试从旧 CE 存储迁移，杜绝已有 DE 配置被空 CE 文件误覆盖
            if (dpPrefs.all.isEmpty()) {
                try {
                    dpContext.moveSharedPreferencesFrom(appContext, PREFS_NAME)
                } catch (e: Exception) {
                    Logger.service.d(TAG, "跳过 CE->DE 配置迁移（可能处于 Direct Boot 未解锁阶段）: ${e.message}")
                }
                return dpContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return dpPrefs
        }

        @Volatile
        private var instance: SettingsRepository? = null

        /**
         * 💡 获取进程级单例仓库，确保主屏、设置页与前台服务共享同一组内存 StateFlow，
         * 避免每次打开设置页重复同步读盘与配置状态不同步。
         */
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun resetInstanceForTest() {
            synchronized(this) {
                instance = null
            }
        }

        private const val KEY_FRIENDLY_NAME = "upnp_friendly_name"
        private const val KEY_IDLE_TIMEOUT_SECONDS = "usb_idle_timeout_seconds"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_AIRPLAY_ENABLED = "airplay_enabled"
        private const val KEY_UPNP_ENABLED = "upnp_enabled"
        private const val KEY_FLUID_QUALITY = "fluid_quality"
        private const val KEY_FALLBACK_MAC_ADDRESS = "airplay_fallback_mac_address"

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
