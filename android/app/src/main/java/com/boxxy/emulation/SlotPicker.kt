package com.boxxy.emulation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One save slot: its number, whether it already holds a state, and -- for
 * states saved since A5 -- a thumbnail of the scene and when it was saved.
 */
data class SlotInfo(
    val number: Int,
    val occupied: Boolean,
    val thumb: java.io.File? = null,
    val savedAt: Long? = null,
)

/** "5 min. ago" style, for a slot's save time. */
fun slotAge(savedAt: Long): String =
    if (System.currentTimeMillis() - savedAt < 60_000L) "just now"
    else android.text.format.DateUtils.getRelativeTimeSpanString(
        savedAt, System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

/** The slot's thumbnail, decoded once per file version. */
@Composable
fun rememberSlotThumb(slot: SlotInfo): ImageBitmap? =
    remember(slot.thumb?.path, slot.savedAt) {
        SlotThumbs.load(slot.thumb)?.asImageBitmap()
    }

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
    /** Set only when something went wrong; success needs no words. */
    errorText: String?,
    /** True while the operation is in flight, so the tiles stop responding. */
    busy: Boolean,
    /** The slot being saved or loaded, which shows it is working. */
    activeSlot: Int?,
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
                 * Reserved but blank unless something failed.  Progress is
                 * shown by the tile that was pressed rather than by text
                 * appearing here -- but the line keeps its space so an error
                 * does not shove the grid down when it does appear.
                 */
                Text(
                    errorText ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
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
                                active = slot.number == activeSlot,
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
                Box(modifier = Modifier.align(Alignment.End)) {
                    DialogAction("Cancel", onCancel)
                }
            }
        }
    }
}

@Composable
private fun SlotTile(
    slot: SlotInfo,
    selectable: Boolean,
    interactive: Boolean,
    /** This is the slot being worked on: hold it visibly pressed. */
    active: Boolean,
    isSave: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val border = when {
        active        -> scheme.primary
        !selectable   -> scheme.outline.copy(alpha = 0.2f)
        slot.occupied -> scheme.primary.copy(alpha = 0.7f)
        else          -> scheme.outline.copy(alpha = 0.5f)
    }
    val labelColor = when {
        active        -> scheme.primary
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
        /*
         * The worked-on tile stays filled for the duration, so the press has a
         * visible effect that outlasts the ripple without any text appearing.
         */
        color = when {
            active                       -> scheme.primary.copy(alpha = 0.30f)
            slot.occupied && selectable  -> scheme.primary.copy(alpha = 0.10f)
            else                         -> scheme.surfaceVariant.copy(alpha = 0.25f)
        },
        border = BorderStroke(1.dp, border),
        modifier = Modifier.width(132.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 7.dp),
        ) {
            /* The scene saved there, 16:9 like the game; a blank of the same
             * size otherwise, so every tile lines up. */
            val thumb = rememberSlotThumb(slot)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(scheme.surfaceVariant.copy(alpha = 0.35f)),
            ) {
                if (thumb != null) {
                    Image(
                        thumb, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = if (selectable) 1f else 0.4f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                "Slot ${slot.number}",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp),
            )
            Text(
                when {
                    !slot.occupied               -> "Empty"
                    slot.savedAt != null && isSave -> "Overwrite \u00b7 " + slotAge(slot.savedAt)
                    slot.savedAt != null         -> slotAge(slot.savedAt)
                    isSave                       -> "Overwrite"
                    else                         -> "Saved"
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
