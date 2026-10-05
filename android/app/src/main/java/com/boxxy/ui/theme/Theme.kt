package com.boxxy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun XemuTheme(content: @Composable () -> Unit) {
    /* Material You palette: always available at minSdk 33. */
    val colorScheme = dynamicDarkColorScheme(LocalContext.current)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
