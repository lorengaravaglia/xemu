package com.boxxy.emulation

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.boxxy.ui.theme.XemuTheme
import kotlinx.coroutines.launch

/*
 * Dual-screen mode: the second screen of a dual-screen handheld (the AYN Thor's
 * lower panel) becomes a companion panel while a game runs -- quick actions,
 * the in-game menu and performance numbers -- leaving the game image on the
 * top screen alone.  Three tabs, switched by swiping (or tapping a tab).
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
 *    state it had.  Tabs change by touch only.
 */

/** One tick of the numbers the top-screen performance overlay shows. */
data class PerfSnapshot(
    val fps: Float? = null,
    val worstMs: Int? = null,
    val history: IntArray = IntArray(0),
    val gpuWaitMs: Int? = null,
    val ramMb: Long? = null,
    val shaders: Int? = null,
    val vcpuPct: Int? = null,
    val nv2aPct: Int? = null,
    val gpuBusyPct: Int? = null,
    val gpuMhz: Int? = null,
)

/** Observable state of the lower-screen panel; owned by EmulationActivity. */
class BottomPanelState {
    /** The three tabs.  Each has a root screen; Quick and Menu have sub-screens. */
    enum class Tab(val title: String) { QUICK("Quick"), MENU("Menu"), PERF("Performance") }

    enum class Screen(val tab: Tab) {
        HOME(Tab.QUICK), SAVE_SLOTS(Tab.QUICK), LOAD_SLOTS(Tab.QUICK),
        MENU(Tab.MENU), CONFIRM_EXIT(Tab.MENU),
        PERF(Tab.PERF),
    }

    val screen = mutableStateOf(Screen.HOME)
    /** True while controller input drives this panel instead of the game. */
    val controllerHere = mutableStateOf(false)
    /** Controller highlight, an index into the current screen's tiles. */
    val focus = mutableIntStateOf(0)
    val paused = mutableStateOf(false)
    val quickSlot = mutableIntStateOf(1)
    /** One-line summary shown under the Quick and Menu tabs. */
    val stats = mutableStateOf("")
    val perf = mutableStateOf(PerfSnapshot())
    val slots = mutableStateOf<List<SlotInfo>>(emptyList())
    val busy = mutableStateOf(false)
    val activeSlot = mutableStateOf<Int?>(null)
    val status = mutableStateOf<String?>(null)
    val statusIsError = mutableStateOf(false)

    /* Menu tab labels -- the in-game menu's, set by the activity. */
    val overlayLabel = mutableStateOf("")
    val recordingLabel = mutableStateOf("Record input")
    val recordingBusy = mutableStateOf(false)

    /** Tiles on the current screen; the highlight never lands past them. */
    fun tileCount(): Int = when (screen.value) {
        Screen.HOME -> HOME_TILES
        Screen.SAVE_SLOTS, Screen.LOAD_SLOTS -> slots.value.size
        Screen.MENU -> MENU_TILES
        Screen.CONFIRM_EXIT -> 2
        Screen.PERF -> 0
    }

    /** Move the controller highlight within the 4-column grid. */
    fun moveFocus(dx: Int, dy: Int) {
        val rows = (tileCount() + COLUMNS - 1) / COLUMNS
        if (rows == 0) return
        val col = (focus.intValue % COLUMNS + dx).coerceIn(0, COLUMNS - 1)
        val row = (focus.intValue / COLUMNS + dy).coerceIn(0, rows - 1)
        focus.intValue = (row * COLUMNS + col).coerceAtMost(tileCount() - 1)
    }

    companion object {
        const val COLUMNS = 4

        const val TILE_PAUSE = 0
        const val TILE_QUICK_SAVE = 1
        const val TILE_QUICK_LOAD = 2
        const val TILE_QUICK_SLOT = 3
        const val TILE_SAVE = 4
        const val TILE_LOAD = 5
        const val TILE_SCREENSHOT = 6
        const val HOME_TILES = 7

        const val MENU_OVERLAY = 0
        const val MENU_MAP_CONTROLS = 1
        const val MENU_RECORD = 2
        const val MENU_PLAY_RECORDING = 3
        const val MENU_EXIT = 4
        const val MENU_TILES = 5
    }
}

/** What the panel asks the activity to do.  Touch and controller share these. */
interface BottomPanelActions {
    fun onSelectTab(tab: BottomPanelState.Tab)
    fun onHomeTile(index: Int)
    fun onSlot(number: Int)
    fun onBackToHome()
    fun onToggleController()
    fun onMenuTile(index: Int)
    /** From the exit confirmation: leave, or go back to the menu. */
    fun onConfirmExit(exit: Boolean)
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

/* HorizontalPager is still experimental in this Compose version. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomPanel(state: BottomPanelState, actions: BottomPanelActions) {
    val scheme = MaterialTheme.colorScheme
    val tabs = BottomPanelState.Tab.values()
    val current = state.screen.value.tab
    val pager = rememberPagerState(initialPage = current.ordinal) { tabs.size }
    val scope = rememberCoroutineScope()

    /* Keep the pager and the panel state in step both ways: a swipe that
     * settles on another page selects that tab, and a tab opened by code
     * (the MENU pill, Back) scrolls the pager there. */
    LaunchedEffect(pager.settledPage) {
        val settled = tabs[pager.settledPage]
        if (settled != state.screen.value.tab) actions.onSelectTab(settled)
    }
    LaunchedEffect(current) {
        if (pager.currentPage != current.ordinal) pager.animateScrollToPage(current.ordinal)
    }

    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            ControllerBanner(state, actions)
            TabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = scheme.background,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                tabs.forEach { t ->
                    Tab(
                        selected = pager.currentPage == t.ordinal,
                        onClick = { scope.launch { pager.animateScrollToPage(t.ordinal) } },
                        text = { Text(t.title) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            HorizontalPager(
                state = pager,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                val tab = tabs[page]
                /* The page for the current tab shows its current screen; a
                 * neighbour seen mid-swipe shows its root. */
                val screen = if (tab == state.screen.value.tab) state.screen.value
                             else when (tab) {
                                 BottomPanelState.Tab.QUICK -> BottomPanelState.Screen.HOME
                                 BottomPanelState.Tab.MENU -> BottomPanelState.Screen.MENU
                                 BottomPanelState.Tab.PERF -> BottomPanelState.Screen.PERF
                             }
                Column(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        BottomPanelState.Screen.HOME -> HomeGrid(state, actions)
                        BottomPanelState.Screen.MENU -> MenuGrid(state, actions)
                        BottomPanelState.Screen.CONFIRM_EXIT -> ConfirmExit(state, actions)
                        BottomPanelState.Screen.PERF -> PerfView(state.perf.value)
                        else -> SlotGrid(state, actions)
                    }
                }
            }

            Text(
                state.status.value ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.statusIsError.value) scheme.error else scheme.onBackground,
            )
            /* The Performance tab shows all of it; elsewhere, the summary. */
            if (pager.currentPage != BottomPanelState.Tab.PERF.ordinal) {
                Text(
                    state.stats.value,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onBackground.copy(alpha = 0.6f),
                )
            }
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
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (here) "Controller: this screen" else "Controller: game",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        here               -> "D-pad to move, A to choose, B to go back"
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
    )
    TileGrid(
        count = labels.size,
        focus = focusFor(state, BottomPanelState.Screen.HOME),
    ) { index, focused ->
        PanelTile(
            label = labels[index],
            focused = focused,
            enabled = !state.busy.value,
            onClick = { actions.onHomeTile(index) },
        )
    }
}

/** The highlight is shown only on the screen the controller is actually on. */
private fun focusFor(state: BottomPanelState, screen: BottomPanelState.Screen): Int =
    if (state.controllerHere.value && state.screen.value == screen) state.focus.intValue
    else -1

@Composable
private fun SlotGrid(state: BottomPanelState, actions: BottomPanelActions) {
    val isSave = state.screen.value == BottomPanelState.Screen.SAVE_SLOTS
    SubScreenHeader(if (isSave) "Save state" else "Load state",
                    enabled = !state.busy.value, onBack = actions::onBackToHome)
    val slots = state.slots.value
    TileGrid(
        count = slots.size,
        focus = focusFor(state, state.screen.value),
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

/** Header row for a sub-screen: its title and the way back. */
@Composable
private fun SubScreenHeader(title: String, enabled: Boolean = true, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack, enabled = enabled) { Text("Back") }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * The in-game menu, on the lower screen so opening it no longer covers the
 * game.  Save and load live on the Quick tab; this holds the rest of what the
 * top-screen menu offers.
 */
@Composable
private fun MenuGrid(state: BottomPanelState, actions: BottomPanelActions) {
    val labels = listOf(
        "Overlay\n${state.overlayLabel.value}",
        "Map controls",
        state.recordingLabel.value,
        "Play recording",
        "Exit to library",
    )
    TileGrid(
        count = labels.size,
        focus = focusFor(state, BottomPanelState.Screen.MENU),
    ) { index, focused ->
        PanelTile(
            label = labels[index],
            focused = focused,
            enabled = !(index == BottomPanelState.MENU_RECORD &&
                        state.recordingBusy.value),
            onClick = { actions.onMenuTile(index) },
        )
    }
}

@Composable
private fun ConfirmExit(state: BottomPanelState, actions: BottomPanelActions) {
    SubScreenHeader("Exit to library?", onBack = { actions.onConfirmExit(false) })
    Text(
        "Your place is kept for Quick resume. Save to a slot to keep it longer.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    val labels = listOf("Exit", "Cancel")
    TileGrid(
        count = labels.size,
        focus = focusFor(state, BottomPanelState.Screen.CONFIRM_EXIT),
    ) { index, focused ->
        PanelTile(
            label = labels[index],
            focused = focused,
            enabled = true,
            onClick = { actions.onConfirmExit(index == 0) },
        )
    }
}

/** The performance overlay's numbers, large enough to read at a glance. */
@Composable
private fun PerfView(p: PerfSnapshot) {
    val cells = listOf(
        "FPS" to (p.fps?.let { "%.1f".format(it) } ?: "--"),
        "Worst frame" to (p.worstMs?.let { "$it ms" } ?: "--"),
        "CPU x86" to (p.vcpuPct?.let { "$it%" } ?: "--"),
        "CPU NV2A" to (p.nv2aPct?.let { "$it%" } ?: "--"),
        "GPU" to (p.gpuBusyPct?.let { b -> "$b%" + (p.gpuMhz?.let { " @ $it" } ?: "") }
                  ?: "n/a"),
        "GPU wait" to (p.gpuWaitMs?.let { "$it ms" } ?: "--"),
        "RAM" to (p.ramMb?.let { "$it MB" } ?: "--"),
        "Shaders" to (p.shaders?.toString() ?: "--"),
    )
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in cells.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in row) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = scheme.surfaceVariant.copy(alpha = 0.30f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                 color = scheme.onSurface.copy(alpha = 0.65f))
                            Text(value, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        FrameTimeGraph(p.history, modifier = Modifier.fillMaxWidth().height(96.dp))
    }
}

/*
 * Frame times, as the top overlay draws them: deliberately not themed.  Blue
 * is a frame inside the 33.3 ms budget, red one that missed it, and the
 * yellow dashed line is the budget itself -- colour carrying meaning, which the
 * app's accent colour must not overwrite.  Scale 0-100 ms.
 */
@Composable
private fun FrameTimeGraph(samples: IntArray, modifier: Modifier) {
    val ok = Color(0xEB78DCFF)
    val miss = Color(0xEBFF6E6E)
    val target = Color(0x96FFDC00)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        modifier = modifier,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            fun y(ms: Int) = size.height - (ms.coerceIn(0, 100) / 100f * size.height)
            drawLine(target, Offset(0f, y(33)), Offset(size.width, y(33)),
                     strokeWidth = 2f,
                     pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            if (samples.size < 2) return@Canvas
            val step = size.width / (samples.size - 1)
            for (i in 1 until samples.size) {
                val a = Offset((i - 1) * step, y(samples[i - 1]))
                val b = Offset(i * step, y(samples[i]))
                drawLine(if (samples[i] > 34) miss else ok, a, b, strokeWidth = 3f)
            }
        }
    }
}

/**
 * Up to four equal tiles per row.  A short row is centred rather than
 * left-aligned, so two tiles under a row of four sit in the middle.
 */
@Composable
private fun TileGrid(
    count: Int,
    focus: Int,
    tile: @Composable (index: Int, focused: Boolean) -> Unit,
) {
    val gap = 10.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tileWidth = (maxWidth - gap * (BottomPanelState.COLUMNS - 1)) /
                        BottomPanelState.COLUMNS
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until (count + BottomPanelState.COLUMNS - 1) / BottomPanelState.COLUMNS) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                ) {
                    val first = row * BottomPanelState.COLUMNS
                    for (i in first until minOf(first + BottomPanelState.COLUMNS, count)) {
                        Box(modifier = Modifier.width(tileWidth).fillMaxHeight()) {
                            tile(i, i == focus)
                        }
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
