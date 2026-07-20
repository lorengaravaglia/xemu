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
        isoPath: String,         // empty string = no disc (boot to dashboard)
        renderer: String,        // "VULKAN", "OPENGL", or "" for default
        hookLibDir: String,      // applicationInfo.nativeLibraryDir (adrenotools hook .so location)
        driverDir: String,       // dir containing custom Vulkan driver .so; "" = system driver
        driverName: String       // soname of custom Vulkan driver; "" = system driver
    )

    external fun stopEmulation()
    external fun pauseEmulation()
    external fun resumeEmulation()

    /**
     * Notify native code of a surface change.
     * Pass null when the surface is destroyed (app background/rotation).
     * Pass the new Surface when it is recreated.
     * Must NOT be called on the first surface creation — use startEmulation() instead.
     */
    external fun setSurface(surface: Surface?)

    /** Press a button (mask = one or more BUTTON_* constants OR'd together). */
    external fun sendButtonDown(mask: Int)
    /** Release a button. */
    external fun sendButtonUp(mask: Int)
    /** Set an axis value. axis = AXIS_* constant, value in [-32767, 32767]. */
    external fun sendAxis(axis: Int, value: Int)
    /** Terminate the emulation process and return to MainActivity. */
    external fun requestExit()
    /** Stop the VM and flush all block devices to disk (clears qcow2 dirty bit). */
    external fun flushBlockDevices()
    /** Returns total rendered frame count; sample twice over a known interval to compute FPS. */
    external fun getRenderedFrameCount(): Int

    /**
     * Returns the worst frame time (ms) seen since the last call, then resets
     * the accumulator. Measures real eglSwapBuffers intervals — call once per
     * second from the overlay runnable.
     */
    external fun getWorstFrameTimeMs(): Int

    /** Returns the running total of GL shader programs compiled this session. */
    external fun getCompiledShaderCount(): Int

    /**
     * Set the display aspect ratio.
     * 0 = Native (integer scale of 640×480, centered)
     * 1 = Auto   (stretch to fill)
     * 2 = 4:3    (pillarbox/letterbox)
     * 3 = 16:9   (stretch to fill, default)
     */
    external fun setAspectRatio(ratio: Int)

    /**
     * Set the blit texture filter.
     * nearest=true → GL_NEAREST (sharp/pixel-art).
     * nearest=false → GL_LINEAR (smooth, default).
     */
    external fun setFilterNearest(nearest: Boolean)

    /**
     * Set the internal NV2A surface render scale (1=native, 2=2×, 3=3×).
     * Applied once on the first frame after QEMU/NV2A is initialized.
     * Takes effect on the current session (pass before or shortly after startEmulation).
     */
    external fun setSurfaceScale(scale: Int)

    /**
     * Save current VM state to a named snapshot inside the qcow2 HDD image.
     * Slot name convention: "slot_1" … "slot_8".
     * Blocks briefly while the snapshot is written; call from a background thread
     * if the UI must remain responsive during save.
     */
    external fun saveState(name: String)

    /**
     * Load a previously saved VM snapshot by name.
     * Has no effect if the named snapshot does not exist.
     */
    external fun loadState(name: String)

    /**
     * Return the names of all snapshots currently stored in the HDD image.
     * Returns an empty array when no snapshots exist or QEMU is not initialized.
     */
    external fun listStates(): Array<String>

    /**
     * Returns the current Xbox rumble motor intensities as [left, right],
     * each in range 0–65535. Poll at ~100 ms intervals during emulation.
     */
    external fun getRumble(): IntArray

    /**
     * Returns up to 60 frame time samples (ms) in chronological order (oldest first).
     * Each value is the eglSwapBuffers interval for one rendered frame.
     * Use for a rolling frame time bar graph.
     */
    external fun getFrameTimeHistory(): IntArray

    /**
     * Returns the time (ms) the render thread spent waiting for the PGRAPH thread
     * to finish compositing the last frame (nv2a_get_framebuffer_surface sync wait).
     * High values indicate GPU rendering is the bottleneck.
     */
    external fun getPgraphSyncWaitMs(): Int
}
