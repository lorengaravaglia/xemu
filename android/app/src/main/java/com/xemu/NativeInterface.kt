package com.xemu

import android.view.Surface

object NativeInterface {
    // Button masks matching CONTROLLER_BUTTON_* in xemu-input.h
    const val BUTTON_A           = 1 shl 0
    const val BUTTON_B           = 1 shl 1
    const val BUTTON_X           = 1 shl 2
    const val BUTTON_Y           = 1 shl 3
    const val BUTTON_DPAD_LEFT   = 1 shl 4
    const val BUTTON_DPAD_UP     = 1 shl 5
    const val BUTTON_DPAD_RIGHT  = 1 shl 6
    const val BUTTON_DPAD_DOWN   = 1 shl 7
    const val BUTTON_BACK        = 1 shl 8
    const val BUTTON_START       = 1 shl 9
    const val BUTTON_WHITE       = 1 shl 10
    const val BUTTON_BLACK       = 1 shl 11
    const val BUTTON_LSTICK      = 1 shl 12
    const val BUTTON_RSTICK      = 1 shl 13

    // Axis indices matching CONTROLLER_AXIS_* in xemu-input.h
    const val AXIS_LTRIG    = 0
    const val AXIS_RTRIG    = 1
    const val AXIS_LSTICK_X = 2
    const val AXIS_LSTICK_Y = 3
    const val AXIS_RSTICK_X = 4
    const val AXIS_RSTICK_Y = 5

    // Axis range: -32767 to +32767 for sticks, 0 to 32767 for triggers
    const val AXIS_MAX = 32767

    external fun startEmulation(
        surface: Surface,
        configPath: String,
        mcpxPath: String,
        biosPath: String,
        hddPath: String,
        isoPath: String          // empty string = no disc (boot to dashboard)
    )

    external fun stopEmulation()
    external fun pauseEmulation()
    external fun resumeEmulation()

    /** Press a button (mask = one or more BUTTON_* constants OR'd together). */
    external fun sendButtonDown(mask: Int)
    /** Release a button. */
    external fun sendButtonUp(mask: Int)
    /** Set an axis value. axis = AXIS_* constant, value in [-32767, 32767]. */
    external fun sendAxis(axis: Int, value: Int)
    /** Terminate the emulation process and return to MainActivity. */
    external fun requestExit()
}
