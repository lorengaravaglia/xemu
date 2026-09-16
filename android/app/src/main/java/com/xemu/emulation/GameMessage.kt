package com.xemu.emulation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Transient in-game message.
 *
 * Replaces Toast, which on Android 12+ is drawn by the platform as a pill
 * carrying the app icon, positioned and styled by the system. Over a running
 * game it read as a notification interrupting from outside rather than as part
 * of the app, and it ignored [com.xemu.ui.theme.XemuTheme] entirely.
 *
 * Anchored under the MENU pill so every transient surface — this, the menu and
 * the slot picker — comes from the same corner.
 *
 * [text] and [visible] are separate so the message survives its own exit
 * animation: hiding it clears [visible] while the text stays put, where a
 * single nullable string would blank the surface before it had finished
 * fading.
 */
@Composable
fun GameMessage(text: String, visible: Boolean, isError: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(140)) { -it / 2 } + fadeIn(tween(140)),
        exit = fadeOut(tween(180)),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
