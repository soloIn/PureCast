package com.solo.purecast.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val PureCastBackground = Color(0xFF0F1015)
val PureCastSurface = Color(0xFF1B1D24)
val PureCastPrimary = Color(0xFF0A84FF)
val PureCastSecondary = Color(0xFF30D158) // 索尼 Hi-Res / Bit-Perfect 荧光绿
val PureCastAccent = Color(0xFF5E5CE6)
val PureCastTextPrimary = Color(0xFFF5F5F7)
val PureCastTextSecondary = Color(0xFFA1A1A6)

@OptIn(ExperimentalTvMaterial3Api::class)
private val DarkColorPalette = darkColorScheme(
    primary = PureCastPrimary,
    secondary = PureCastSecondary,
    background = PureCastBackground,
    surface = PureCastSurface,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = PureCastTextPrimary,
    onSurface = PureCastTextPrimary
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PureCastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorPalette,
        content = content
    )
}
