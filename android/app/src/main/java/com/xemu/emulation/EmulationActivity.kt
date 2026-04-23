package com.xemu.emulation

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xemu.MainActivity
import com.xemu.NativeInterface

class EmulationActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var gamepadView: GamepadView
    private lateinit var mapping: ControllerMapping
    private lateinit var inputManager: InputManager

    // Kept open for the lifetime of the activity so QEMU can read via /proc/self/fd/<n>
    private var isoPfd: ParcelFileDescriptor? = null

    // True after the first startEmulation() call — subsequent surface events use setSurface()
    private var emulationStarted = false

    // Overlay visibility mode
    private enum class OverlayMode { AUTO, ALWAYS_SHOW, ALWAYS_HIDE }
    private var overlayMode = OverlayMode.AUTO

    // Display aspect ratio: true = 16:9 stretch (default), false = 4:3 pillarbox
    private var aspect16x9 = true

    // D-pad hat axis state (for AXIS_HAT_X / AXIS_HAT_Y)
    private var lastHatX = 0f
    private var lastHatY = 0f

    private val prefs get() = getSharedPreferences("emulation_prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mapping = ControllerMapping(this)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        overlayMode = OverlayMode.valueOf(
            prefs.getString("overlay_mode", OverlayMode.AUTO.name) ?: OverlayMode.AUTO.name
        )
        aspect16x9 = prefs.getBoolean("aspect_16x9", true)

        val root = FrameLayout(this)
        setContentView(root)

        // ── Rendering surface (full screen, behind everything) ────────────────
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ── Virtual gamepad overlay ───────────────────────────────────────────
        gamepadView = GamepadView(this)
        root.addView(gamepadView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ── Menu button (top-right corner) ────────────────────────────────────
        val menuBtn = TextView(this).apply {
            text = "⏏ MENU"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 30, 30, 30))
            setPadding(20, 10, 20, 10)
            setOnClickListener { showMenuDialog() }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = 16; rightMargin = 16 })

        // ── SurfaceView callback — starts emulation once surface is ready ─────
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!emulationStarted) {
                    startEmulation(holder)
                    NativeInterface.setAspectRatio(aspect16x9)
                    emulationStarted = true
                } else {
                    // Surface recreated after going to background or rotating — hand the
                    // new ANativeWindow to the native layer without restarting emulation.
                    NativeInterface.setSurface(holder.surface)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Block until the render thread has released the EGL context so Android
                // can safely destroy the underlying ANativeWindow.
                NativeInterface.setSurface(null)
            }
        })

        updateOverlayVisibility()
    }

    override fun onResume() {
        super.onResume()
        NativeInterface.resumeEmulation()
        inputManager.registerInputDeviceListener(this, null)
        updateOverlayVisibility()
    }

    override fun onPause() {
        super.onPause()
        NativeInterface.pauseEmulation()
        inputManager.unregisterInputDeviceListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        isoPfd?.close()
    }

    // ── InputManager.InputDeviceListener ─────────────────────────────────────

    override fun onInputDeviceAdded(deviceId: Int) = updateOverlayVisibility()
    override fun onInputDeviceRemoved(deviceId: Int) = updateOverlayVisibility()
    override fun onInputDeviceChanged(deviceId: Int) = updateOverlayVisibility()

    private fun hasPhysicalGamepad(): Boolean {
        return InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            val src = dev.sources
            (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
             src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) &&
            !dev.isVirtual
        }
    }

    private fun updateOverlayVisibility() {
        gamepadView.visibility = when (overlayMode) {
            OverlayMode.ALWAYS_SHOW -> View.VISIBLE
            OverlayMode.ALWAYS_HIDE -> View.GONE
            OverlayMode.AUTO        -> if (hasPhysicalGamepad()) View.GONE else View.VISIBLE
        }
    }

    // ── Physical gamepad button input ─────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device ?: return super.dispatchKeyEvent(event)
        val src = dev.sources
        val isGamepad = (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                        (src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD)
        if (!isGamepad) return super.dispatchKeyEvent(event)

        val xboxBtn = mapping.getXboxButton(event.keyCode) ?: return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeInterface.sendButtonDown(xboxBtn.mask)
            KeyEvent.ACTION_UP   -> NativeInterface.sendButtonUp(xboxBtn.mask)
        }
        return true
    }

    // ── Physical gamepad analog axes ──────────────────────────────────────────

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val dev = event.device ?: return super.dispatchGenericMotionEvent(event)
        val src = dev.sources
        if (src and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            src and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) {
            return super.dispatchGenericMotionEvent(event)
        }
        if (event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)

        // Standard analog axes.
        // xemu's internal convention: positive Y = up (matches keyboard mapping).
        // Android reports AXIS_Y / AXIS_RZ as negative when pushed up, so negate Y axes.
        for ((androidAxis, xboxAxis) in ControllerMapping.DEFAULT_AXES) {
            var raw = event.getAxisValue(androidAxis)
            if (xboxAxis == ControllerMapping.XboxAxis.LSTICK_Y ||
                xboxAxis == ControllerMapping.XboxAxis.RSTICK_Y) {
                raw = -raw
            }
            val scaled = (raw * 32767f).toInt().coerceIn(-32767, 32767)
            NativeInterface.sendAxis(xboxAxis.index, scaled)
        }

        // D-pad hat axes → button presses
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        updateHatAxis(lastHatX, hatX,
            NativeInterface.BUTTON_DPAD_LEFT, NativeInterface.BUTTON_DPAD_RIGHT)
        updateHatAxis(lastHatY, hatY,
            NativeInterface.BUTTON_DPAD_UP, NativeInterface.BUTTON_DPAD_DOWN)
        lastHatX = hatX
        lastHatY = hatY

        return true
    }

    /** Convert a hat axis value change into D-pad button press/release events. */
    private fun updateHatAxis(prev: Float, curr: Float, negMask: Int, posMask: Int) {
        // Release negative direction if no longer held
        if (prev < -0.5f && curr >= -0.5f) NativeInterface.sendButtonUp(negMask)
        // Release positive direction if no longer held
        if (prev > 0.5f  && curr <= 0.5f)  NativeInterface.sendButtonUp(posMask)
        // Press negative direction
        if (curr < -0.5f && prev >= -0.5f) NativeInterface.sendButtonDown(negMask)
        // Press positive direction
        if (curr > 0.5f  && prev <= 0.5f)  NativeInterface.sendButtonDown(posMask)
    }

    // ── Menu dialog ───────────────────────────────────────────────────────────

    private fun showMenuDialog() {
        val overlayLabel = when (overlayMode) {
            OverlayMode.AUTO        -> "Overlay: Auto (hide with controller)"
            OverlayMode.ALWAYS_SHOW -> "Overlay: Always Show"
            OverlayMode.ALWAYS_HIDE -> "Overlay: Always Hide"
        }
        val aspectLabel = if (aspect16x9) "Aspect: 16:9 (stretch)" else "Aspect: 4:3 (pillarbox)"
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(arrayOf(overlayLabel, aspectLabel, "Map Controls", "Exit")) { _, which ->
                when (which) {
                    0 -> cycleOverlayMode()
                    1 -> toggleAspectRatio()
                    2 -> startActivity(Intent(this, MappingActivity::class.java))
                    3 -> confirmExit()
                }
            }
            .show()
    }

    private fun toggleAspectRatio() {
        aspect16x9 = !aspect16x9
        prefs.edit().putBoolean("aspect_16x9", aspect16x9).apply()
        NativeInterface.setAspectRatio(aspect16x9)
    }

    private fun cycleOverlayMode() {
        overlayMode = when (overlayMode) {
            OverlayMode.AUTO        -> OverlayMode.ALWAYS_SHOW
            OverlayMode.ALWAYS_SHOW -> OverlayMode.ALWAYS_HIDE
            OverlayMode.ALWAYS_HIDE -> OverlayMode.AUTO
        }
        prefs.edit().putString("overlay_mode", overlayMode.name).apply()
        updateOverlayVisibility()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Exit Emulation")
            .setMessage("Return to the file selection screen?\n\nUnsaved game progress will be lost.")
            .setPositiveButton("Exit") { _, _ -> NativeInterface.requestExit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Emulation start ───────────────────────────────────────────────────────

    private fun startEmulation(holder: SurfaceHolder) {
        val configPath = filesDir.absolutePath + "/xemu.toml"
        java.io.File(configPath).delete()

        val mcpxUri = intent.getStringExtra("mcpx") ?: ""
        val biosUri = intent.getStringExtra("bios") ?: ""
        val hddUri  = intent.getStringExtra("hdd")  ?: ""
        val isoUriStr = intent.getStringExtra("iso") ?: ""

        val mcpxPath = MainActivity.getRealFilePath(this, mcpxUri, "mcpx.bin")
        val biosPath = MainActivity.getRealFilePath(this, biosUri, "bios.bin")
        val hddPath = getCachedOrCopy(hddUri, "xbox_hdd.qcow2")
        val isoPath = if (isoUriStr.isNotEmpty()) {
            try {
                val (pfd, path) = MainActivity.openFileDescriptorPath(this, isoUriStr)
                isoPfd = pfd
                path
            } catch (e: Exception) { "" }
        } else ""

        NativeInterface.startEmulation(
            holder.surface,
            configPath,
            mcpxPath,
            biosPath,
            hddPath,
            isoPath
        )
    }

    private fun getCachedOrCopy(uriStr: String, fileName: String): String {
        val prefKey = "cached_uri_$fileName"
        val cached = java.io.File(filesDir, fileName)
        if (cached.exists() && prefs.getString(prefKey, null) == uriStr) {
            return cached.absolutePath
        }
        // URI changed or cache missing — delete stale copy and re-copy from source
        cached.delete()
        val path = MainActivity.getRealFilePath(this, uriStr, fileName)
        prefs.edit().putString(prefKey, uriStr).apply()
        return path
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
}
