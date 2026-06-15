package com.xemu.emulation

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    // Per-game snapshot prefix derived from the ISO filename (e.g. "halo" → slots "halo_slot_1"…)
    private var gameId: String = "game"

    // Overlay visibility mode
    private enum class OverlayMode { AUTO, ALWAYS_SHOW, ALWAYS_HIDE }
    private var overlayMode = OverlayMode.AUTO

    // Display aspect ratio: true = 16:9 stretch (default), false = 4:3 pillarbox
    private var aspect16x9 = true

    // D-pad hat axis state (for AXIS_HAT_X / AXIS_HAT_Y)
    private var lastHatX = 0f
    private var lastHatY = 0f

    // Performance overlay
    private lateinit var overlayContainer: LinearLayout
    private lateinit var fpsLine: TextView
    private lateinit var frametimeLine: TextView
    private lateinit var memoryLine: TextView
    private lateinit var shadersLine: TextView
    private val fpsHandler = Handler(Looper.getMainLooper())
    private var lastFrameCount = 0
    private var lastFpsTime = 0L
    private val fpsRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val count = NativeInterface.getRenderedFrameCount()
            val elapsed = now - lastFpsTime
            if (lastFpsTime != 0L && elapsed > 0) {
                val fps = (count - lastFrameCount) * 1000f / elapsed
                if (fpsLine.visibility == View.VISIBLE)
                    fpsLine.text = "FPS: %.1f".format(fps)
            }
            if (frametimeLine.visibility == View.VISIBLE) {
                val worstMs = NativeInterface.getWorstFrameTimeMs()
                frametimeLine.text = "Frame: ${worstMs} ms"
            }
            if (memoryLine.visibility == View.VISIBLE) {
                val kb = java.io.File("/proc/self/status").readLines()
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.filter { it.isDigit() }
                    ?.toLongOrNull() ?: 0L
                memoryLine.text = "RAM: ${kb / 1024} MB"
            }
            if (shadersLine.visibility == View.VISIBLE) {
                val n = NativeInterface.getCompiledShaderCount()
                shadersLine.text = "Shaders: $n"
            }
            lastFrameCount = count
            lastFpsTime = now
            fpsHandler.postDelayed(this, 1000)
        }
    }

    private val prefs get() = getSharedPreferences("emulation_prefs", Context.MODE_PRIVATE)
    private val mainPrefs get() = getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    // dp → px helper
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: let content draw behind system bars (immersive mode hides them anyway)
        WindowCompat.setDecorFitsSystemWindows(window, false)
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

        // ── Performance overlay (configurable corner + metrics) ───────────────
        fun overlayLine() = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        fpsLine       = overlayLine()
        frametimeLine = overlayLine()
        memoryLine    = overlayLine()
        shadersLine   = overlayLine()
        overlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rectDrawable(Color.argb(160, 20, 20, 20))
            setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            addView(fpsLine)
            addView(frametimeLine)
            addView(memoryLine)
            addView(shadersLine)
        }
        root.addView(overlayContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ── Menu button pill (top-right corner) ───────────────────────────────
        val menuBtn = TextView(this).apply {
            text = "MENU"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = pillDrawable(Color.argb(160, 20, 20, 20))
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener { showMenuSheet() }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = 16.dp; rightMargin = 16.dp })

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

        applyOverlaySettings()
        updateOverlayVisibility()
    }

    override fun onResume() {
        super.onResume()
        NativeInterface.resumeEmulation()
        inputManager.registerInputDeviceListener(this, null)
        lastFpsTime = 0L
        fpsHandler.post(fpsRunnable)
        applyOverlaySettings()
        updateOverlayVisibility()
    }

    override fun onPause() {
        super.onPause()
        fpsHandler.removeCallbacks(fpsRunnable)
        NativeInterface.pauseEmulation()
        inputManager.unregisterInputDeviceListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeInterface.flushBlockDevices()
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

    // ── Bottom sheet menu ─────────────────────────────────────────────────────

    private fun showMenuSheet() {
        val sheet = BottomSheetDialog(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(0, 8.dp, 0, 32.dp)
        }

        // Drag-handle visual
        container.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 4.dp.toFloat()
                setColor(Color.parseColor("#555555"))
            }
            layoutParams = LinearLayout.LayoutParams(40.dp, 4.dp).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 8.dp
                bottomMargin = 16.dp
            }
        })

        val overlayLabel = when (overlayMode) {
            OverlayMode.AUTO        -> "Overlay: Auto"
            OverlayMode.ALWAYS_SHOW -> "Overlay: Always Show"
            OverlayMode.ALWAYS_HIDE -> "Overlay: Always Hide"
        }
        val aspectLabel = if (aspect16x9) "Aspect: 16:9" else "Aspect: 4:3"

        fun item(label: String, danger: Boolean = false, action: () -> Unit) {
            container.addView(sheetItem(label, danger) { sheet.dismiss(); action() })
        }

        item(overlayLabel)    { cycleOverlayMode() }
        item(aspectLabel)     { toggleAspectRatio() }

        container.addView(sheetDivider())

        item("Save State")    { showSaveStateDialog() }
        item("Load State")    { showLoadStateDialog() }
        item("Map Controls")  { startActivity(Intent(this, MappingActivity::class.java)) }

        container.addView(sheetDivider())

        item("Exit", danger = true) { confirmExit() }

        sheet.setContentView(container)
        sheet.show()
    }

    private fun sheetItem(label: String, danger: Boolean, onClick: () -> Unit): TextView {
        val normalBg = ColorDrawable(Color.TRANSPARENT)
        val pressedBg = ColorDrawable(Color.argb(40, 255, 255, 255))
        return TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(if (danger) Color.parseColor("#FF5252") else Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 14.dp, 24.dp, 14.dp)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                addState(intArrayOf(), normalBg)
            }
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun sheetDivider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#333333"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 4.dp, 0, 4.dp) }
    }

    // ── Save / load state dialogs ─────────────────────────────────────────────

    private fun showSaveStateDialog() {
        val slotLabels = Array(8) { i -> "Slot ${i + 1}" }
        AlertDialog.Builder(this)
            .setTitle("Save State")
            .setItems(slotLabels) { _, which ->
                NativeInterface.saveState("${gameId}_slot_${which + 1}")
                Toast.makeText(this, "Saved to Slot ${which + 1}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLoadStateDialog() {
        val existingSlots = NativeInterface.listStates().toSet()
        val slotLabels = Array(8) { i ->
            val name = "${gameId}_slot_${i + 1}"
            if (name in existingSlots) "Slot ${i + 1}" else "Slot ${i + 1} (empty)"
        }
        AlertDialog.Builder(this)
            .setTitle("Load State")
            .setItems(slotLabels) { _, which ->
                val name = "${gameId}_slot_${which + 1}"
                if (name in existingSlots) {
                    NativeInterface.loadState(name)
                } else {
                    Toast.makeText(this, "Slot ${which + 1} is empty", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Derive a short, filesystem-safe game ID from the ISO URI for snapshot namespacing. */
    private fun isoUriToGameId(uriStr: String): String {
        if (uriStr.isEmpty()) return "dashboard"
        val basename = android.net.Uri.parse(uriStr).lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: return "game"
        return basename
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifEmpty { "game" }
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
            .setMessage("Return to the game library?\n\nUnsaved game progress will be lost.")
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
        gameId = isoUriToGameId(isoUriStr)
        val renderer  = intent.getStringExtra("renderer") ?: ""
        val driverDir  = intent.getStringExtra("driverDir")  ?: ""
        val driverName = intent.getStringExtra("driverName") ?: ""
        val hookLibDir = applicationInfo.nativeLibraryDir

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
            isoPath,
            renderer,
            hookLibDir,
            driverDir,
            driverName
        )
    }

    private fun getCachedOrCopy(uriStr: String, fileName: String): String {
        val prefKey = "cached_uri_$fileName"
        val cached = java.io.File(filesDir, fileName)
        if (cached.exists() && prefs.getString(prefKey, null) == uriStr) {
            return cached.absolutePath
        }
        cached.delete()
        val path = MainActivity.getRealFilePath(this, uriStr, fileName)
        prefs.edit().putString(prefKey, uriStr).apply()
        return path
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a rounded-pill GradientDrawable with the given fill color. */
    private fun pillDrawable(colorArgb: Int) = GradientDrawable().apply {
        cornerRadius = 100.dp.toFloat()
        setColor(colorArgb)
    }

    /** Returns a rectangle GradientDrawable with the given fill color. */
    private fun rectDrawable(colorArgb: Int) = GradientDrawable().apply {
        cornerRadius = 4.dp.toFloat()
        setColor(colorArgb)
    }

    /** Read overlay settings from main_prefs and apply position + visibility. */
    private fun applyOverlaySettings() {
        val showFps       = mainPrefs.getBoolean("overlay_show_fps", true)
        val showFrametime = mainPrefs.getBoolean("overlay_show_frametime", false)
        val showMemory    = mainPrefs.getBoolean("overlay_show_memory", false)
        val showShaders   = mainPrefs.getBoolean("overlay_show_shaders", false)

        fpsLine.visibility       = if (showFps)       View.VISIBLE else View.GONE
        frametimeLine.visibility = if (showFrametime) View.VISIBLE else View.GONE
        memoryLine.visibility    = if (showMemory)    View.VISIBLE else View.GONE
        shadersLine.visibility   = if (showShaders)   View.VISIBLE else View.GONE

        val anyVisible = showFps || showFrametime || showMemory || showShaders
        overlayContainer.visibility = if (anyVisible) View.VISIBLE else View.GONE

        val position = mainPrefs.getString("overlay_position", "TOP_LEFT") ?: "TOP_LEFT"
        val gravity = when (position) {
            "TOP_RIGHT"    -> Gravity.TOP    or Gravity.END
            "BOTTOM_LEFT"  -> Gravity.BOTTOM or Gravity.START
            "BOTTOM_RIGHT" -> Gravity.BOTTOM or Gravity.END
            else           -> Gravity.TOP    or Gravity.START
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity,
        ).apply {
            val margin = 16.dp
            topMargin    = if (position.startsWith("TOP"))    margin else 0
            bottomMargin = if (position.startsWith("BOTTOM")) margin else 0
            leftMargin   = if (position.endsWith("LEFT"))     margin else 0
            rightMargin  = if (position.endsWith("RIGHT"))    margin else 0
        }
        overlayContainer.layoutParams = lp
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
