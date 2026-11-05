package com.solo.purecast.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 💡 PureCast 外观色彩模式枚举
 */
enum class ThemeMode(val title: String) {
    AUTO("自动 (跟随系统)"),
    DARK("深色模式"),
    LIGHT("浅色模式")
}

/**
 * 💡 PureCast Apple TV HIG 自适应色彩系统 (AppPalette)
 * 严格遵从 Apple TV HIG 规范，支持自动 (跟随系统 uiMode)、强制深色、强制浅色切换与持久化
 */
object AppPalette {
    var mode by mutableStateOf(ThemeMode.AUTO)
        private set

    var systemIsDark by mutableStateOf(true)
        private set

    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.AUTO -> systemIsDark
        }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("purecast_appearance", Context.MODE_PRIVATE)
        val savedModeStr = prefs.getString("theme_mode", null)
        mode = if (savedModeStr != null) {
            try {
                ThemeMode.valueOf(savedModeStr)
            } catch (_: Exception) {
                ThemeMode.AUTO
            }
        } else if (prefs.contains("is_dark_mode")) {
            // 兼容迁移旧版本布尔值
            if (prefs.getBoolean("is_dark_mode", true)) ThemeMode.DARK else ThemeMode.LIGHT
        } else {
            ThemeMode.AUTO
        }
        updateSystemNightMode(context)
    }

    fun updateSystemNightMode(context: Context) {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        systemIsDark = uiMode != Configuration.UI_MODE_NIGHT_NO
    }

    fun setThemeMode(context: Context, newMode: ThemeMode) {
        mode = newMode
        context.getSharedPreferences("purecast_appearance", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", newMode.name)
            .putBoolean("is_dark_mode", isDark)
            .apply()
    }

    fun setDark(context: Context, enabled: Boolean) {
        setThemeMode(context, if (enabled) ThemeMode.DARK else ThemeMode.LIGHT)
    }

    fun adaptive(light: Color, dark: Color): Color = if (isDark) dark else light

    // 页面背景底色：浅色为柔和的高级浅灰白，深色为深邃的 #101014
    val background get() = adaptive(Color(0xFFF2F2F7), Color(0xFF101014))

    // 卡片/分组容器背景：浅色为纯白，深色为 #1C1C22
    val surface get() = adaptive(Color.White, Color(0xFF1C1C22))

    // 次级容器/标签背景
    val surfaceSecondary get() = adaptive(Color(0xFFE5E5EA), Color(0xFF2C2C34))

    // 文字色彩
    val primaryText get() = adaptive(Color(0xFF1C1C1E), Color(0xFFF5F5F7))
    val secondaryText get() = adaptive(Color(0xFF6E6E73), Color(0xFF8E8E93))

    // 分隔线与描边
    val divider get() = adaptive(Color(0xFFE5E5EA), Color(0xFF2C2C32))
    val border get() = adaptive(Color(0xFFD1D1D6), Color(0xFF383842))

    // 语义功能色 (Apple HIG System Colors)
    val accent = Color(0xFF0A84FF)  // Apple System Blue
    val success = Color(0xFF30D158) // Apple System Green (Bit-Perfect / 独占)
    val warning = Color(0xFFFF9F0A) // Apple System Orange (让渡 / 待机)
    val error = Color(0xFFFF453A)   // Apple System Red (异常 / 非独占)
}
