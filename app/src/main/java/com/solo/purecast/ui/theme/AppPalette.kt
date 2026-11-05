package com.solo.purecast.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 💡 PureCast Apple TV HIG 自适应色彩系统 (AppPalette)
 * 严格遵从 HIG 规范，支持深色 (Dark) / 浅色 (Light) 模式切换与持久化存储
 */
object AppPalette {
    var isDark by mutableStateOf(true) // 电视环境默认深色底
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("purecast_appearance", Context.MODE_PRIVATE)
        isDark = prefs.getBoolean("is_dark_mode", true)
    }

    fun setDark(context: Context, enabled: Boolean) {
        isDark = enabled
        context.getSharedPreferences("purecast_appearance", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_dark_mode", enabled)
            .apply()
    }

    fun adaptive(light: Color, dark: Color): Color = if (isDark) dark else light

    // 页面背景底色：浅色为柔和的灰白，深色为深邃的 #101014
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
