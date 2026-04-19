package com.xemu.emulation

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.xemu.NativeInterface

/**
 * Maps Android gamepad keycodes / motion-event axes to Xbox controller
 * buttons and axes.  User overrides are persisted in SharedPreferences.
 */
class ControllerMapping(context: Context) {

    private val prefs = context.getSharedPreferences("controller_mapping", Context.MODE_PRIVATE)

    enum class XboxButton(val label: String, val mask: Int) {
        A("A",                    NativeInterface.BUTTON_A),
        B("B",                    NativeInterface.BUTTON_B),
        X("X",                    NativeInterface.BUTTON_X),
        Y("Y",                    NativeInterface.BUTTON_Y),
        DPAD_UP("D-pad Up",       NativeInterface.BUTTON_DPAD_UP),
        DPAD_DOWN("D-pad Down",   NativeInterface.BUTTON_DPAD_DOWN),
        DPAD_LEFT("D-pad Left",   NativeInterface.BUTTON_DPAD_LEFT),
        DPAD_RIGHT("D-pad Right", NativeInterface.BUTTON_DPAD_RIGHT),
        BACK("Back",              NativeInterface.BUTTON_BACK),
        START("Start",            NativeInterface.BUTTON_START),
        WHITE("White (LB)",       NativeInterface.BUTTON_WHITE),
        BLACK("Black (RB)",       NativeInterface.BUTTON_BLACK),
        LSTICK("Left Stick ↓",   NativeInterface.BUTTON_LSTICK),
        RSTICK("Right Stick ↓",  NativeInterface.BUTTON_RSTICK),
    }

    enum class XboxAxis(val label: String, val index: Int) {
        LTRIG("Left Trigger",    NativeInterface.AXIS_LTRIG),
        RTRIG("Right Trigger",   NativeInterface.AXIS_RTRIG),
        LSTICK_X("Left Stick X", NativeInterface.AXIS_LSTICK_X),
        LSTICK_Y("Left Stick Y", NativeInterface.AXIS_LSTICK_Y),
        RSTICK_X("Right Stick X",NativeInterface.AXIS_RSTICK_X),
        RSTICK_Y("Right Stick Y",NativeInterface.AXIS_RSTICK_Y),
    }

    companion object {
        val DEFAULT_BUTTONS: Map<Int, XboxButton> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A      to XboxButton.A,
            KeyEvent.KEYCODE_BUTTON_B      to XboxButton.B,
            KeyEvent.KEYCODE_BUTTON_X      to XboxButton.X,
            KeyEvent.KEYCODE_BUTTON_Y      to XboxButton.Y,
            KeyEvent.KEYCODE_BUTTON_L1     to XboxButton.WHITE,
            KeyEvent.KEYCODE_BUTTON_R1     to XboxButton.BLACK,
            KeyEvent.KEYCODE_BUTTON_THUMBL to XboxButton.LSTICK,
            KeyEvent.KEYCODE_BUTTON_THUMBR to XboxButton.RSTICK,
            KeyEvent.KEYCODE_BUTTON_START  to XboxButton.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to XboxButton.BACK,
            KeyEvent.KEYCODE_BUTTON_MODE   to XboxButton.BACK,
            KeyEvent.KEYCODE_DPAD_UP       to XboxButton.DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN     to XboxButton.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT     to XboxButton.DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT    to XboxButton.DPAD_RIGHT,
        )

        // Standard mapping covers most controllers (Xbox, PS, Switch Pro via BT)
        val DEFAULT_AXES: Map<Int, XboxAxis> = mapOf(
            MotionEvent.AXIS_X        to XboxAxis.LSTICK_X,
            MotionEvent.AXIS_Y        to XboxAxis.LSTICK_Y,
            MotionEvent.AXIS_Z        to XboxAxis.RSTICK_X,
            MotionEvent.AXIS_RZ       to XboxAxis.RSTICK_Y,
            MotionEvent.AXIS_LTRIGGER to XboxAxis.LTRIG,
            MotionEvent.AXIS_RTRIGGER to XboxAxis.RTRIG,
            MotionEvent.AXIS_BRAKE    to XboxAxis.LTRIG,
            MotionEvent.AXIS_GAS      to XboxAxis.RTRIG,
        )

        fun keycodeLabel(keycode: Int): String =
            KeyEvent.keyCodeToString(keycode)
                .removePrefix("KEYCODE_")
                .replace("_", " ")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
    }

    private val buttonMap = DEFAULT_BUTTONS.toMutableMap()
    private val axisMap   = DEFAULT_AXES.toMutableMap()

    init { load() }

    private fun load() {
        prefs.all.forEach { (key, value) ->
            val ordinal = value as? Int ?: return@forEach
            when {
                key.startsWith("btn_") -> {
                    val keycode = key.removePrefix("btn_").toIntOrNull() ?: return@forEach
                    XboxButton.values().getOrNull(ordinal)?.let { buttonMap[keycode] = it }
                }
                key.startsWith("axis_") -> {
                    val axis = key.removePrefix("axis_").toIntOrNull() ?: return@forEach
                    XboxAxis.values().getOrNull(ordinal)?.let { axisMap[axis] = it }
                }
            }
        }
    }

    fun save() {
        prefs.edit().apply {
            clear()
            buttonMap.forEach { (keycode, btn) ->
                if (DEFAULT_BUTTONS[keycode] != btn) putInt("btn_$keycode", btn.ordinal)
            }
            axisMap.forEach { (axis, xboxAxis) ->
                if (DEFAULT_AXES[axis] != xboxAxis) putInt("axis_$axis", xboxAxis.ordinal)
            }
            apply()
        }
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        buttonMap.clear(); buttonMap.putAll(DEFAULT_BUTTONS)
        axisMap.clear();   axisMap.putAll(DEFAULT_AXES)
    }

    fun getXboxButton(keycode: Int): XboxButton? = buttonMap[keycode]
    fun getXboxAxis(axis: Int): XboxAxis?         = axisMap[axis]
    fun allButtonMappings(): Map<Int, XboxButton>  = buttonMap.toMap()

    /** Assign an Android keycode to an Xbox button (removing any prior keycode for that button). */
    fun assignButton(keycode: Int, btn: XboxButton) {
        val iter = buttonMap.entries.iterator()
        while (iter.hasNext()) { if (iter.next().value == btn) iter.remove() }
        buttonMap[keycode] = btn
    }
}
