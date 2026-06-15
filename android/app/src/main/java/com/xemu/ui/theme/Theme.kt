package com.xemu.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Xbox green seed color used as fallback on Android < 12
private val XboxGreen = Color(0xFF107C10)
private val XboxGreenDark = Color(0xFF0A5E0A)

private val FallbackDarkColors = darkColorScheme(
    primary = XboxGreen,
    onPrimary = Color.White,
    primaryContainer = XboxGreenDark,
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFF52B043),
    onSecondary = Color.White,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun XemuTheme(content: @Composable () -> Unit) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        FallbackDarkColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
