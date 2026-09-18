package com.boxxy.emulation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The in-game menu.
 *
 * Presented in a PopupWindow anchored to the MENU pill rather than as a bottom
 * sheet. The activity is locked to landscape, where a BottomSheetDialog opens
 * at its collapsed peek height against a short viewport and leaves most of the
 * list below the fold — which is why the old menu appeared mostly off-screen.
 * Anchoring to the button keeps it on screen by construction, and [maxHeight]
 * plus a scroll keeps it that way however many items it grows.
 *
 * UI RULE: every control that can be pressed must show it, and a highlight is
 * the first choice -- a ripple alone is too easy to miss against a dark surface
 * over moving video, and it is gone before the eye reaches it if the press also
 * dismisses what was pressed.
 *
 * That last case is most of this menu: six of its eight items dismiss it as
 * their first action, so feedback that lasts only while the finger is down is
 * torn away at the instant it matters and a quick tap shows almost nothing. So
 * a dismissing row holds a brighter confirm highlight for [CONFIRM_HOLD_MS]
 * *after* release and only then runs its action, making the chosen row visibly
 * the thing the menu shrinks back into.
 *
 * No ripple on these rows. On a menu the useful question is "which item did I
 * choose", not "where did I touch", and the ripple is also the part most
 * truncated by dismissal — so the highlight carries it alone here. This is a
 * per-surface decision and not a verdict on ripples elsewhere in the app.
 *
 * Built in Compose with [com.boxxy.ui.theme.XemuTheme] so it picks up the same
 * Material 3 colour scheme as the rest of the app — including the dynamic
 * palette on Android 12+ — instead of the hand-mixed greys it used before.
 */
@Composable
fun InGameMenu(
    initialOverlayLabel: String,
    initialHrtfOn: Boolean,
    recordingLabel: String,
    isRecordingBusy: Boolean,
    maxHeight: Dp,
    visibleState: MutableTransitionState<Boolean>,
    onFullyHidden: () -> Unit,
    onCycleOverlay: () -> String,
    onToggleHrtf: () -> Boolean,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onMapControls: () -> Unit,
    onToggleRecording: () -> Unit,
    onPlayRecording: () -> Unit,
    onExit: () -> Unit,
) {
    /*
     * Remove the overlay only once the exit animation has actually finished,
     * so the menu is seen to shrink back into the button rather than vanish.
     *
     * This must not run during composition: onFullyHidden() detaches the
     * ComposeView, which disposes the very composition that is running, and
     * Compose throws "Composition is disposed while composing".  A
     * LaunchedEffect defers it until after the frame.
     */
    LaunchedEffect(visibleState.targetState, visibleState.currentState, visibleState.isIdle) {
        if (!visibleState.targetState && !visibleState.currentState && visibleState.isIdle) {
            onFullyHidden()
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        /*
         * Grow out of the button it is anchored to: the origin is the popup's
         * top-right, which sits directly under the MENU pill in the top-right
         * corner, so the menu reads as coming from the control that opened it.
         */
        enter = scaleIn(
            animationSpec = tween(160),
            initialScale = 0.85f,
            transformOrigin = TransformOrigin(1f, 0f),
        ) + fadeIn(tween(120)),
        exit = scaleOut(
            animationSpec = tween(120),
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(1f, 0f),
        ) + fadeOut(tween(100)),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.widthIn(min = 208.dp, max = 232.dp),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                /*
                 * The two toggles keep the menu open and update in place; the
                 * items that lead somewhere else close it. Cycling the overlay
                 * mode meant reopening the menu three times before.
                 */
                var overlayLabel by remember { mutableStateOf(initialOverlayLabel) }
                var hrtfOn by remember { mutableStateOf(initialHrtfOn) }

                MenuSectionLabel("Display & Audio")
                MenuRow(
                    label = "Overlay",
                    value = overlayLabel,
                    dismissesMenu = false,
                ) { overlayLabel = onCycleOverlay() }
                MenuRow(
                    label = "HRTF",
                    value = if (hrtfOn) "On" else "Off",
                    dismissesMenu = false,
                ) { hrtfOn = onToggleHrtf() }

                MenuSeparator()
                MenuSectionLabel("Save States")
                MenuRow(label = "Save State", onClick = onSaveState)
                MenuRow(label = "Load State", onClick = onLoadState)

                MenuSeparator()
                MenuSectionLabel("Input")
                MenuRow(label = "Map Controls", onClick = onMapControls)
                MenuRow(
                    label = recordingLabel,
                    enabled = !isRecordingBusy,
                    onClick = onToggleRecording,
                )
                MenuRow(label = "Play Recording", onClick = onPlayRecording)

                MenuSeparator()
                MenuRow(
                    label = "Exit to Library",
                    danger = true,
                    onClick = onExit,
                )
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 1.dp),
    )
}

/** How long a dismissing row stays lit after release, before it acts. */
private const val CONFIRM_HOLD_MS = 140L

@Composable
private fun MenuRow(
    label: String,
    value: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    /**
     * Whether choosing this row closes the menu. Rows that stay open already
     * report themselves by changing their value, and delaying those would just
     * make the toggle feel sluggish.
     */
    dismissesMenu: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger   -> MaterialTheme.colorScheme.error
        else     -> MaterialTheme.colorScheme.onSurface
    }
    /* Highlight while held, then confirm: see the UI rule at the top of this file. */
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The destructive row confirms in its own colour; a green flash on "Exit to
    // Library" would read as reassurance about the one item that deserves none.
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val target = when {
        !enabled           -> Color.Transparent
        confirming         -> accent.copy(alpha = 0.38f)
        pressed            -> accent.copy(alpha = 0.18f)
        else               -> Color.Transparent
    }
    /*
     * The confirm flash appears at once and fades on the way out, so it is not
     * missed; an animated rise would eat most of the hold before it was bright.
     */
    val highlight by animateColorAsState(
        targetValue = target,
        animationSpec = tween(if (confirming) 0 else 140),
        label = "menuRowHighlight",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(highlight)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                if (!dismissesMenu) {
                    onClick()
                } else if (!confirming) {
                    // Guarded so a second tap during the hold cannot fire the
                    // action twice.
                    confirming = true
                    scope.launch {
                        delay(CONFIRM_HOLD_MS)
                        onClick()
                        /*
                         * Deliberately not cleared. onClick() only starts the
                         * exit animation, so the row stays lit while the menu
                         * shrinks away — which is the whole point. The state
                         * dies with the composition when onFullyHidden()
                         * detaches the view, so the next open starts clean.
                         */
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun MenuSeparator() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}
