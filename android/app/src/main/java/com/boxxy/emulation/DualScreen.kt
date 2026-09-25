package com.boxxy.emulation

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.boxxy.ui.theme.XemuTheme

/*
 * Dual-screen mode: the second screen of a dual-screen handheld (the AYN Thor's
 * lower panel) becomes a companion panel while a game runs -- pause, save and
 * load, screenshots, stats -- leaving the game image on the top screen alone.
 *
 * Opt-in.  In single-screen mode the lower screen belongs to Android and to
 * whatever the user runs there, and this code never touches it.
 *
 * INPUT is the part that needed design:
 *
 *  - Touching the lower screen must not take the controller away from the
 *    game.  Android tracks focus per display and sends key events to the
 *    most recently touched one; with the plain launcher down there, one tap
 *    handed the controller to the launcher (confirmed by the user).  So the
 *    panel's window IS focusable -- which keeps the launcher from ever being
 *    the focused window on that display -- and forwards every key and motion
 *    event straight to EmulationActivity.  Whichever screen Android considers
 *    focused, input arrives at one routing point.
 *  - The controller can also drive the panel, on request.  The activity routes
 *    each event either to the guest or to panel navigation, depending on
 *    [BottomPanelState.controllerHere].  Handing it over pauses the game (the
 *    player is not driving it anyway); handing it back restores the pause
 *    state it had.
 */

/** Observable state of the lower-screen panel; owned by EmulationActivity. */
class BottomPanelState {
    enum class Screen { HOME, SAVE_SLOTS, LOAD_SLOTS }

    val screen = mutableStateOf(Screen.HOME)
    /** True while controller input drives this panel instead of the game. */
    val controllerHere = mutableStateOf(false)
    /** Controller highlight, an index into the current screen's 4x2 grid. */
    val focus = mutableIntStateOf(0)
    val paused = mutableStateOf(false)
    val quickSlot = mutableIntStateOf(1)
    val stats = mutableStateOf("")
    val slots = mutableStateOf<List<SlotInfo>>(emptyList())
    val busy = mutableStateOf(false)
    val activeSlot = mutableStateOf<Int?>(null)
    val status = mutableStateOf<String?>(null)
    val statusIsError = mutableStateOf(false)

    /** Move the controller highlight within the 4-column, 2-row grid. */
    fun moveFocus(dx: Int, dy: Int) {
        val col = (focus.intValue % COLUMNS + dx).coerceIn(0, COLUMNS - 1)
        val row = (focus.intValue / COLUMNS + dy).coerceIn(0, ROWS - 1)
        focus.intValue = row * COLUMNS + col
    }

    companion object {
        const val COLUMNS = 4
        const val ROWS = 2

        const val TILE_PAUSE = 0
        const val TILE_QUICK_SAVE = 1
        const val TILE_QUICK_LOAD = 2
        const val TILE_QUICK_SLOT = 3
        const val TILE_SAVE = 4
        const val TILE_LOAD = 5
        const val TILE_SCREENSHOT = 6
        const val TILE_MORE = 7
    }
}

/** What the panel asks the activity to do.  Touch and controller share these. */
interface BottomPanelActions {
    fun onHomeTile(index: Int)
    fun onSlot(number: Int)
    fun onBackToHome()
    fun onToggleController()
}

/**
 * The window on the second display.
 *
 * A Presentation is a Dialog, whose decor view has no ViewTree owners, and
 * Compose looks for them from the window root -- the same failure the
 * activity's PopupWindow overlays hit.  Borrow the activity's.
 */
class BottomScreenPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val state: BottomPanelState,
    private val actions: BottomPanelActions,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(activity)
            decor.setViewTreeViewModelStoreOwner(activity)
            decor.setViewTreeSavedStateRegistryOwner(activity)
        }
        setContentView(ComposeView(context).apply {
            setContent { XemuTheme { BottomPanel(state, actions) } }
        })
    }

    /* Input that lands here because this display was touched last goes to the
     * same routing as input on the game's display.  See the file comment. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        activity.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        activity.dispatchGenericMotionEvent(event)
}

@Composable
private fun BottomPanel(state: BottomPanelState, actions: BottomPanelActions) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            ControllerBanner(state, actions)
            Spacer(Modifier.height(12.dp))

            when (state.screen.value) {
                BottomPanelState.Screen.HOME -> HomeGrid(state, actions)
                else -> SlotGrid(state, actions)
            }

            Spacer(Modifier.weight(1f))
            Text(
                state.status.value ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.statusIsError.value) scheme.error else scheme.onBackground,
            )
            Text(
                state.stats.value,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/** Where the controller is going, and the switch that moves it. */
@Composable
private fun ControllerBanner(state: BottomPanelState, actions: BottomPanelActions) {
    val here = state.controllerHere.value
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (here) scheme.primary.copy(alpha = 0.18f)
                else scheme.surfaceVariant.copy(alpha = 0.35f),
        border = if (here) BorderStroke(1.dp, scheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (here) "Controller: this screen" else "Controller: game",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        here               -> "D-pad to move, A to choose, B to return"
                        state.paused.value -> "Paused"
                        else               -> "Playing"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface.copy(alpha = 0.65f),
                )
            }
            TextButton(onClick = actions::onToggleController) {
                Text(if (here) "Back to game" else "Use controller here")
            }
        }
    }
}

@Composable
private fun HomeGrid(state: BottomPanelState, actions: BottomPanelActions) {
    val slot = state.quickSlot.intValue
    val labels = listOf(
        if (state.paused.value) "Resume" else "Pause",
        "Quick save\nslot $slot",
        "Quick load\nslot $slot",
        "Quick slot\n$slot  ▸",
        "Save…",
        "Load…",
        "Screenshot",
        "More…",
    )
    TileGrid(
        count = labels.size,
        focus = if (state.controllerHere.value) state.focus.intValue else -1,
    ) { index, focused ->
        PanelTile(
            label = labels[index],
            focused = focused,
            enabled = !state.busy.value,
            onClick = { actions.onHomeTile(index) },
        )
    }
}

@Composable
private fun SlotGrid(state: BottomPanelState, actions: BottomPanelActions) {
    val isSave = state.screen.value == BottomPanelState.Screen.SAVE_SLOTS
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (isSave) "Save state" else "Load state",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = actions::onBackToHome, enabled = !state.busy.value) {
            Text("Back")
        }
    }
    Spacer(Modifier.height(8.dp))
    val slots = state.slots.value
    TileGrid(
        count = slots.size,
        focus = if (state.controllerHere.value) state.focus.intValue else -1,
    ) { index, focused ->
        val s = slots[index]
        val usable = isSave || s.occupied
        PanelTile(
            label = "Slot ${s.number}\n" + when {
                s.occupied && isSave -> "Overwrite"
                s.occupied           -> "Saved"
                else                 -> "Empty"
            },
            focused = focused,
            active = state.activeSlot.value == s.number,
            enabled = usable && !state.busy.value,
            dim = !usable,
            onClick = { actions.onSlot(s.number) },
        )
    }
}

/** A 4-column grid of equal tiles that share the panel's width. */
@Composable
private fun TileGrid(
    count: Int,
    focus: Int,
    tile: @Composable (index: Int, focused: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in 0 until (count + BottomPanelState.COLUMNS - 1) / BottomPanelState.COLUMNS) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(124.dp),
            ) {
                for (col in 0 until BottomPanelState.COLUMNS) {
                    val i = row * BottomPanelState.COLUMNS + col
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (i < count) tile(i, i == focus)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTile(
    label: String,
    focused: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    active: Boolean = false,
    dim: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = when {
            active  -> scheme.primary.copy(alpha = 0.30f)
            focused -> scheme.primary.copy(alpha = 0.16f)
            else    -> scheme.surfaceVariant.copy(alpha = 0.30f)
        },
        /* The controller highlight is a heavier border, so it reads even over
         * the "active" fill of a slot that is being written. */
        border = when {
            focused -> BorderStroke(3.dp, scheme.primary)
            dim     -> BorderStroke(1.dp, scheme.outline.copy(alpha = 0.2f))
            else    -> BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f))
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = scheme.onSurface.copy(alpha = if (dim) 0.35f else 1f),
            )
        }
    }
}
