package com.boxxy.library

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.boxxy.ui.theme.XemuTheme

/**
 * Dual-screen library (OPEN-WORK D5): the selected game's showcase fills the
 * top screen (the activity) and the card list goes on the lower touchscreen,
 * in this window -- the same shape as the in-game panel
 * (emulation/DualScreen.kt).  The library activity stays where it is, so how
 * Android groups the library and a running game into tasks is unchanged; the
 * window is dismissed whenever the library stops (a game starting, the app
 * going to the background) and shown again when it returns.
 *
 * Controller input arrives at whichever display was touched last; the
 * activity forwards it here ([forwardKey]), so the D-pad always drives the
 * list.  Either way it lands in [dispatchKeyEvent], which never lets a
 * navigation key fall through to the top screen: with no card focused yet
 * (a fresh launch shows the selected card highlighted but unfocused), the
 * first press focuses that card and then acts, so A launches the game the
 * border is on instead of reaching the top screen's view-mode button.
 */
class LibraryPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val content: MutableState<(@Composable () -> Unit)?>,
) : Presentation(activity, display) {

    /** Whether a card in the list has controller focus. */
    var hasCardFocus: () -> Boolean = { true }
    /** Gives controller focus to the selected (highlighted) card. */
    var focusSelected: () -> Unit = {}
    private var composeView: ComposeView? = null

    /** Controller focus to the selected card.  This window may still be in
     *  touch mode, where a focus request is refused: leave it first. */
    fun focusSelectedFromTouch() {
        composeView?.requestFocusFromTouch()
        focusSelected()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in NAV_KEYS) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && !hasCardFocus()) focusSelectedFromTouch()
        /* Compose clicks on DPAD_CENTER, not BUTTON_A.  Unhandled, the
         * system would re-send A as DPAD_CENTER to the focused window --
         * possibly the top screen -- so translate it here. */
        val e = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A)
            KeyEvent(event.downTime, event.eventTime, event.action,
                     KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState,
                     event.deviceId, event.scanCode, event.flags, event.source)
        else event
        super.dispatchKeyEvent(e)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(activity)
            decor.setViewTreeViewModelStoreOwner(activity)
            decor.setViewTreeSavedStateRegistryOwner(activity)
        }
        setContentView(ComposeView(context).also { composeView = it }.apply {
            setContent {
                XemuTheme {
                    Surface(color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.fillMaxSize()) {
                        content.value?.invoke()
                    }
                }
            }
        })
    }

    companion object {
        /** The one showing, for key forwarding from the activity. */
        var showing: LibraryPresentation? = null

        /** The lower screen, when dual-screen mode is on and one exists. */
        fun companionDisplay(context: Context): Display? {
            val on = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
                .getBoolean("dual_screen", false)
            if (!on) return null
            val dm = context.getSystemService(DisplayManager::class.java)
            return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        }

        private val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y)

        /** Controller navigation keys go to the list on the lower screen.
         *  Back stays with the activity (it navigates the library's screens). */
        fun forwardKey(event: KeyEvent): Boolean {
            val p = showing ?: return false
            if (!p.isShowing || event.keyCode !in NAV_KEYS) return false
            return p.dispatchKeyEvent(event)
        }
    }
}
