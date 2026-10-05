package com.boxxy.emulation

import android.view.Display
import android.view.Window

/**
 * Run the panels at 60 Hz while a game plays (OPEN-WORK A8).
 *
 * The guest presents at 30 fps (60 at most), so a 120 Hz panel only adds
 * refreshes and compositions that show nothing new, and costs power and heat.
 * Each game window asks for its display's 60 Hz mode at the current
 * resolution through preferredDisplayModeId -- honoured while the window is
 * in front, so the library and the rest of the system keep the user's
 * setting.  debug.xemu.panel60=0 disables, for A/B.
 */
object DisplayRate {
    /** The 60 Hz mode of [display] at its current resolution, or 0 (none). */
    fun sixtyHzModeId(display: Display): Int = modeIdNear(display, 60f)

    private fun modeIdNear(display: Display, hz: Float): Int {
        val cur = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth &&
                      it.physicalHeight == cur.physicalHeight &&
                      it.refreshRate in (hz - 1f)..(hz + 1f) }
            .minByOrNull { kotlin.math.abs(it.refreshRate - hz) }
            ?.modeId ?: 0
    }

    fun apply(window: Window?, display: Display?) {
        if (window == null || display == null) return
        /* "0" = no request; "120" = request 120 Hz, for measuring what
         * 120 Hz costs (the Thor's own default vote otherwise holds 60). */
        val prop = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "debug.xemu.panel60", "1") as String
        } catch (_: Exception) { "1" }
        val enabled = prop != "0"
        val id = when (prop) {
            "0" -> 0
            "120" -> modeIdNear(display, 120f)
            else -> sixtyHzModeId(display)
        }
        window.attributes = window.attributes.apply { preferredDisplayModeId = id }
        android.util.Log.i("xemu-android", "display ${display.displayId}: preferred mode $id " +
            "(panel60=$prop${if (enabled) "" else ", no request"})")
    }
}
