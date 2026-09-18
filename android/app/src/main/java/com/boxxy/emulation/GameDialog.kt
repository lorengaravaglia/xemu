package com.boxxy.emulation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Shared shell for the in-game dialogs.
 *
 * These were AppCompat AlertDialogs.  Theming them dark stopped them clashing,
 * but they still read as platform dialogs dropped on top of the app — a grey
 * rectangle with white text, next to a menu and slot picker built from
 * [com.boxxy.ui.theme.XemuTheme] with green section headers and tonal surfaces.
 * Same shell, same animation, same type scale as the slot picker, so
 * everything shown over a running game belongs to the same UI.
 */
@Composable
fun GameDialogFrame(
    title: String,
    visibleState: MutableTransitionState<Boolean>,
    onFullyHidden: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    LaunchedEffect(visibleState.targetState, visibleState.currentState, visibleState.isIdle) {
        if (!visibleState.targetState && !visibleState.currentState && visibleState.isIdle) {
            onFullyHidden()
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = scaleIn(tween(160), initialScale = 0.9f) + fadeIn(tween(120)),
        exit = scaleOut(tween(120), targetScale = 0.94f) + fadeOut(tween(100)),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp,
            shadowElevation = 16.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * A dialog action.
 *
 * TextButton's ripple alone is the weakest feedback in this UI -- a thin wash
 * over a dark surface on top of moving video -- so per the rule at the top of
 * InGameMenu.kt it also takes a highlight while held.
 */
@Composable
fun DialogAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.background(
            color = if (pressed && enabled) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                Color.Transparent
            },
            shape = MaterialTheme.shapes.small,
        ),
    ) { Text(label) }
}

/** Right-aligned action row, matching the slot picker's Cancel placement. */
@Composable
private fun DialogActions(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DialogAction(dismissLabel, onDismiss)
        if (confirmLabel != null && onConfirm != null) {
            DialogAction(confirmLabel, onConfirm, confirmEnabled)
        }
    }
}

/** Title, message, confirm/cancel. */
@Composable
fun GameConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    visibleState: MutableTransitionState<Boolean>,
    maxHeight: androidx.compose.ui.unit.Dp,
    onFullyHidden: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GameDialogFrame(title, visibleState, onFullyHidden, maxHeight) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        DialogActions(dismissLabel, onDismiss, confirmLabel, true, onConfirm)
    }
}

/** Title and a list of choices. */
@Composable
fun GameListDialog(
    title: String,
    items: List<String>,
    dismissLabel: String,
    visibleState: MutableTransitionState<Boolean>,
    maxHeight: androidx.compose.ui.unit.Dp,
    onFullyHidden: () -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    /*
     * Picking a row used to call straight through, which dismissed the dialog
     * on the same frame and cut the ripple off before it drew -- the tap looked
     * like it had done nothing.  Hold the chosen row highlighted for a moment
     * first, the same way the slot picker holds the tile that was pressed.
     */
    var picked by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(picked) {
        picked?.let {
            delay(160)
            onPick(it)
        }
    }

    GameDialogFrame(title, visibleState, onFullyHidden, maxHeight) {
        items.forEachIndexed { index, label ->
            val isPicked = picked == index
            Surface(
                onClick = { if (picked == null) picked = index },
                enabled = picked == null,
                shape = MaterialTheme.shapes.small,
                color = if (isPicked) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPicked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                )
            }
        }
        DialogActions(dismissLabel, onDismiss)
    }
}

/** Title and a single text field. */
@Composable
fun GameTextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    dismissLabel: String,
    visibleState: MutableTransitionState<Boolean>,
    maxHeight: androidx.compose.ui.unit.Dp,
    onFullyHidden: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }

    GameDialogFrame(title, visibleState, onFullyHidden, maxHeight) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        DialogActions(
            dismissLabel = dismissLabel,
            onDismiss = onDismiss,
            confirmLabel = confirmLabel,
            /* Nothing sensible to name a recording after. */
            confirmEnabled = value.isNotBlank(),
            onConfirm = { onConfirm(value.trim()) },
        )
    }
}
