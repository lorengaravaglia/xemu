package com.xemu.emulation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One save slot: its number, and whether it already holds a state. */
data class SlotInfo(val number: Int, val occupied: Boolean)

/**
 * Save/load slot picker.
 *
 * Replaces an AlertDialog with eight list items, which in landscape ran past
 * the bottom of the screen so the last slot could not be reached — the same
 * problem the old bottom-sheet menu had. Eight tiles in two rows of four suit
 * a landscape window and fit without scrolling.
 *
 * Occupancy is shown rather than hidden: loading an empty slot is disabled
 * instead of silently doing nothing, and saving over a used one says so before
 * it is tapped.
 */
@Composable
fun SlotPicker(
    title: String,
    slots: List<SlotInfo>,
    isSave: Boolean,
    /** Non-null while a save/load is running or its result is being shown. */
    status: String?,
    /** True while the operation is in flight, so the tiles stop responding. */
    busy: Boolean,
    /** True when [status] reports a failure, which is shown in the error colour. */
    failed: Boolean,
    visibleState: MutableTransitionState<Boolean>,
    onFullyHidden: () -> Unit,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
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
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )

                /*
                 * The status line lives above the grid and reserves its space
                 * whether or not there is anything to say, so starting a save
                 * does not shift the tiles under the finger that tapped them.
                 */
                Text(
                    status ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                for (row in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0 until 4) {
                            val slot = slots.getOrNull(row * 4 + col) ?: continue
                            SlotTile(
                                slot = slot,
                                /*
                                 * Appearance depends only on whether the slot
                                 * is a valid target -- nothing to load from an
                                 * empty one.  It deliberately does NOT depend
                                 * on `busy`: making every tile restyle the
                                 * moment an operation starts read as the whole
                                 * grid flashing, which drowned out the ripple
                                 * on the tile actually pressed.
                                 */
                                selectable = isSave || slot.occupied,
                                /* Clicks stop while an operation is running,
                                 * without the tiles changing how they look. */
                                interactive = !busy && (isSave || slot.occupied),
                                isSave = isSave,
                                onClick = { onPick(slot.number) },
                            )
                        }
                    }
                    if (row == 0) Box(Modifier.padding(top = 8.dp))
                }

                /* Aligned within the Column rather than in a fillMaxWidth Row:
                 * filling the width propagates the maximum constraint back up
                 * and stretches the whole dialog across the screen. */
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun SlotTile(
    slot: SlotInfo,
    selectable: Boolean,
    interactive: Boolean,
    isSave: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val border = when {
        !selectable   -> scheme.outline.copy(alpha = 0.2f)
        slot.occupied -> scheme.primary.copy(alpha = 0.7f)
        else          -> scheme.outline.copy(alpha = 0.5f)
    }
    val labelColor = when {
        !selectable   -> scheme.onSurface.copy(alpha = 0.3f)
        slot.occupied -> scheme.primary
        else          -> scheme.onSurface
    }
    /*
     * Surface's onClick overload rather than Modifier.clickable: it puts the
     * press indication inside the shape, so the ripple is clipped to the
     * rounded tile instead of spilling past its corners.
     */
    Surface(
        onClick = onClick,
        enabled = interactive,
        shape = MaterialTheme.shapes.medium,
        color = if (slot.occupied && selectable) {
            scheme.primary.copy(alpha = 0.10f)
        } else {
            scheme.surfaceVariant.copy(alpha = 0.25f)
        },
        border = BorderStroke(1.dp, border),
        modifier = Modifier.width(84.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
        ) {
            Text(
                "Slot ${slot.number}",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    slot.occupied && isSave -> "Overwrite"
                    slot.occupied           -> "Saved"
                    else                    -> "Empty"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selectable) {
                    scheme.onSurface.copy(alpha = 0.55f)
                } else {
                    scheme.onSurface.copy(alpha = 0.3f)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}
