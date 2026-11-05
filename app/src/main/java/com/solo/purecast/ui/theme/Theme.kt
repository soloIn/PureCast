package com.solo.purecast.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

val PureCastPrimary = Color(0xFF0A84FF)
val PureCastSecondary = Color(0xFF30D158) // 索尼 Hi-Res / Bit-Perfect 荧光绿
val PureCastAccent = Color(0xFF5E5CE6)

@OptIn(ExperimentalTvMaterial3Api::class)
private val DarkColorPalette = darkColorScheme(
    primary = PureCastPrimary,
    secondary = PureCastSecondary,
    background = Color(0xFF101014),
    surface = Color(0xFF1C1C22),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFFF5F5F7),
    onSurface = Color(0xFFF5F5F7)
)

@OptIn(ExperimentalTvMaterial3Api::class)
private val LightColorPalette = lightColorScheme(
    primary = PureCastPrimary,
    secondary = PureCastSecondary,
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E)
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PureCastTheme(
    darkTheme: Boolean = AppPalette.isDark,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorPalette else LightColorPalette,
        content = content
    )
}
