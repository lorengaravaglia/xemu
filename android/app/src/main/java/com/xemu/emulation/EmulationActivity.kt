package com.xemu.emulation

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.CombinedVibration
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.*
import androidx.annotation.RequiresApi
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.widget.EditText
import android.util.Log
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.xemu.MainActivity
import com.xemu.NativeInterface

/**
 * How long to wait for a second key before treating a combo-participating
 * button as a plain press.  Long enough to press both keys of a combo without
 * leaking the single-key action into the game, short enough not to feel laggy.
 */
private const val COMBO_WINDOW_MS = 120L

/**
 * How long a synthesized tap is held down.  The guest polls the XID gamepad
 * every 4 ms (bInterval = 4) but game logic samples it once per frame -- ~38 ms
 * at 26 fps and ~77 ms during a dip -- so a shorter press can fall between two
 * samples and be missed entirely.
 */
private const val TAP_HOLD_MS = 100L

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

    // D-pad hat axis state (for AXIS_HAT_X / AXIS_HAT_Y)
    private var lastHatX = 0f
    private var lastHatY = 0f

    // Hotkey state
    // Buttons that appear in any combo are held "pending" (not sent to emulator) until we know
    // whether a combo completed. If the combo fires they're suppressed; if released alone they
    // tap through as a normal button press.
    private val heldGamepadKeycodes = mutableSetOf<Int>()      // all physically held gamepad keys
    private val pendingGamepadKeycodes = mutableSetOf<Int>()   // held back from emulator (in a combo)
    private val suppressedGamepadKeycodes = mutableSetOf<Int>()// consumed by a fired combo this press
    private val comboHandler = Handler(Looper.getMainLooper())
    private val comboPendingDowns = mutableMapOf<Int, Runnable>() // deferred presses, by keycode
    private val lateSentKeycodes = mutableSetOf<Int>()         // pressed after the combo window
    private var quickSaveSlot = 1                              // 1–8, cycled via hotkey
    private var userPaused = false                             // user explicitly paused in-game

    // Triggers InputRecorder playback from `adb shell am broadcast` for automated
    // performance testing — see InputRecorder.kt.
    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                InputRecorder.ACTION_PLAY -> {
                    val name = intent.getStringExtra(InputRecorder.EXTRA_NAME)
                    if (name.isNullOrBlank()) {
                        Log.w("xemu-inputrec", "PLAYBACK_REJECTED reason=missing_name")
                        return
                    }
                    if (!emulationStarted) {
                        Log.w("xemu-inputrec", "PLAYBACK_REJECTED reason=not_ready")
                        return
                    }
                    InputRecorder.startPlayback(filesDir, gameId, name)
                }
                InputRecorder.ACTION_STOP_PLAYBACK -> InputRecorder.cancelPlayback()
            }
        }
    }

    // Performance overlay
    private lateinit var overlayContainer: LinearLayout
    private lateinit var fpsLine: TextView
    private lateinit var frametimeLine: TextView
    private lateinit var frameTimeGraph: FrameTimeBarView
    private lateinit var pgraphLine: TextView
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
                frametimeLine.text = "Frame: ${worstMs} ms worst"
            }
            if (frameTimeGraph.visibility == View.VISIBLE) {
                frameTimeGraph.samples = NativeInterface.getFrameTimeHistory()
                frameTimeGraph.invalidate()
            }
            if (pgraphLine.visibility == View.VISIBLE) {
                val syncMs = NativeInterface.getPgraphSyncWaitMs()
                pgraphLine.text = "GPU wait: ${syncMs} ms"
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

    /**
     * Canvas-based rolling frame time bar graph.
     * Bars are color-coded: green ≤ 16 ms, yellow ≤ 33 ms, red > 33 ms.
     * A dashed line marks the 33 ms / 30 fps target.
     * Y-axis scale: 0–100 ms (bars are clamped to 100 ms).
     */
    private inner class FrameTimeBarView(context: Context) : View(context) {
        var samples: IntArray = IntArray(0)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 220, 0)
            strokeWidth = 1.5f
        }

        override fun onDraw(canvas: Canvas) {
            if (samples.isEmpty()) return
            val n = samples.size
            val barW = width.toFloat() / n
            val maxMs = 100f
            for (i in samples.indices) {
                val ms = samples[i].coerceAtLeast(0)
                barPaint.color = when {
                    ms <= 16 -> Color.argb(200, 80, 200, 80)
                    ms <= 33 -> Color.argb(200, 220, 200, 60)
                    else     -> Color.argb(200, 220, 80, 80)
                }
                val barH = (ms.coerceAtMost(100) / maxMs * height)
                canvas.drawRect(
                    i * barW + 0.5f, height - barH,
                    (i + 1) * barW - 0.5f, height.toFloat(),
                    barPaint
                )
            }
            // 33 ms target line (30 fps)
            val targetY = height - (33f / maxMs * height)
            canvas.drawLine(0f, targetY, width.toFloat(), targetY, linePaint)
        }
    }

    // Rumble
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    private val rumbleHandler = Handler(Looper.getMainLooper())
    private val rumbleRunnable = object : Runnable {
        override fun run() {
            val vals = NativeInterface.getRumble()
            val l = vals[0]  // left/heavy motor, 0–65535
            val r = vals[1]  // right/light motor, 0–65535

            if (l == 0 && r == 0) {
                vibrator.cancel()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cancelControllerRumble()
            } else {
                // ── Device vibrator ───────────────────────────────────────────
                // Blend both motors: left (heavy) weighted 60%, right (light) 40%.
                // When right motor strongly dominates, approximate its high-frequency
                // character with a rapid on/off waveform instead of a solid pulse.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (r > l * 3 / 2) {
                        val amp = (r * 255 / 65535).coerceIn(1, 255)
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 20, 10, 20, 10, 20, 10, 20, 10, 20, 10),
                            intArrayOf(0, amp, 0, amp, 0, amp, 0, amp, 0, amp, 0),
                            -1
                        )
                    } else {
                        val blended = ((l * 0.6f + r * 0.4f) * 255f / 65535f).toInt().coerceIn(1, 255)
                        VibrationEffect.createOneShot(150, blended)
                    }
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(150)
                }
                // ── Physical controller (API 31+) ─────────────────────────────
                // Drive each motor independently on the controller itself.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) vibrateController(l, r)
            }
            rumbleHandler.postDelayed(this, 100)
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
            mainPrefs.getString("overlay_mode", OverlayMode.AUTO.name) ?: OverlayMode.AUTO.name
        )

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
        pgraphLine    = overlayLine()
        memoryLine    = overlayLine()
        shadersLine   = overlayLine()
        frameTimeGraph = FrameTimeBarView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(200.dp, 40.dp)
        }
        overlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rectDrawable(Color.argb(160, 20, 20, 20))
            setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            addView(fpsLine)
            addView(frametimeLine)
            addView(frameTimeGraph)
            addView(pgraphLine)
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
                    applyGraphicsSettings()
                    applyAudioSettings()
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
        if (!userPaused) NativeInterface.resumeEmulation()
        inputManager.registerInputDeviceListener(this, null)
        lastFpsTime = 0L
        fpsHandler.post(fpsRunnable)
        rumbleHandler.post(rumbleRunnable)
        // Exported (not RECEIVER_NOT_EXPORTED) so `adb shell am broadcast` (uid=shell)
        // can reach it — shell is NOT exempt from the exported-receiver check on this
        // Android version, confirmed via BroadcastQueue "Exported Denial" in logcat.
        // Debug/automation-only feature; acceptable on a personal test device.
        ContextCompat.registerReceiver(
            this, playbackReceiver,
            IntentFilter().apply {
                addAction(InputRecorder.ACTION_PLAY)
                addAction(InputRecorder.ACTION_STOP_PLAYBACK)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        // Re-read overlay mode from main_prefs in case it changed in Settings
        overlayMode = OverlayMode.valueOf(
            mainPrefs.getString("overlay_mode", OverlayMode.AUTO.name) ?: OverlayMode.AUTO.name
        )
        mapping.reloadHotkeys()
        applyOverlaySettings()
        applyGraphicsSettings()
        applyAudioSettings()
        updateOverlayVisibility()
    }

    override fun onPause() {
        super.onPause()
        fpsHandler.removeCallbacks(fpsRunnable)
        rumbleHandler.removeCallbacks(rumbleRunnable)
        unregisterReceiver(playbackReceiver)
        vibrator.cancel()
        NativeInterface.pauseEmulation()  // idempotent: safe even if already user-paused
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
        val keycode = event.keyCode

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                heldGamepadKeycodes.add(keycode)

                if (keycode in suppressedGamepadKeycodes) {
                    // Already consumed as part of an active combo — eat the repeat
                    return true
                }

                if (mapping.isInAnyHotkey(keycode)) {
                    // This key participates in a hotkey combo — hold it pending
                    pendingGamepadKeycodes.add(keycode)

                    // Check if the full held set now completes a combo
                    val fn = mapping.matchHotkey(heldGamepadKeycodes)
                    if (fn != null) {
                        // Fire the hotkey action
                        executeHotkeyFunction(fn)
                        // Suppress all combo keycodes (including pending ones)
                        val combo = mapping.hotkeyMappings[fn] ?: emptySet()
                        combo.forEach { cancelDeferredPress(it) }
                        suppressedGamepadKeycodes.addAll(combo)
                        pendingGamepadKeycodes.removeAll(combo)
                    } else if (keycode !in comboPendingDowns &&
                               keycode !in lateSentKeycodes) {
                        // No combo yet.  Send the press once the combo window
                        // closes, so holding the button still holds it in-game
                        // instead of being swallowed until release.
                        scheduleDeferredPress(keycode, xboxBtn.mask)
                    }
                } else {
                    // Not in any hotkey — send immediately
                    InputRecorder.sendButtonDown(xboxBtn.mask)
                }
            }

            KeyEvent.ACTION_UP -> {
                heldGamepadKeycodes.remove(keycode)
                comboPendingDowns.remove(keycode)?.let { comboHandler.removeCallbacks(it) }

                when {
                    keycode in suppressedGamepadKeycodes -> {
                        suppressedGamepadKeycodes.remove(keycode)
                        // Consumed by a combo.  If the press had already been
                        // sent before the combo completed, release it.
                        if (lateSentKeycodes.remove(keycode)) {
                            InputRecorder.sendButtonUp(xboxBtn.mask)
                        }
                    }
                    lateSentKeycodes.remove(keycode) -> InputRecorder.sendButtonUp(xboxBtn.mask)
                    keycode in pendingGamepadKeycodes -> {
                        pendingGamepadKeycodes.remove(keycode)
                        // Released before the combo window closed.  The guest
                        // only ever sees the *current* button state when it
                        // polls the USB controller, so a down/up pair sent
                        // back-to-back is almost never sampled -- hold the
                        // press for TAP_HOLD_MS so at least one poll sees it.
                        InputRecorder.sendButtonDown(xboxBtn.mask)
                        comboHandler.postDelayed(
                            { InputRecorder.sendButtonUp(xboxBtn.mask) }, TAP_HOLD_MS)
                    }
                    else -> InputRecorder.sendButtonUp(xboxBtn.mask)
                }
            }
        }
        return true
    }

    /**
     * Send [mask] as a press once the combo window closes, unless the key was
     * released or consumed by a hotkey first.  Keys that take part in a combo
     * cannot be sent on ACTION_DOWN (that would fire the game action every
     * time you start a combo), but they must not wait for ACTION_UP either --
     * that yields a zero-length press the guest never samples.
     */
    private fun scheduleDeferredPress(keycode: Int, mask: Int) {
        val press = object : Runnable {
            override fun run() {
                comboPendingDowns.remove(keycode)
                if (keycode in heldGamepadKeycodes &&
                    keycode !in suppressedGamepadKeycodes) {
                    pendingGamepadKeycodes.remove(keycode)
                    lateSentKeycodes.add(keycode)
                    InputRecorder.sendButtonDown(mask)
                }
            }
        }
        comboPendingDowns[keycode] = press
        comboHandler.postDelayed(press, COMBO_WINDOW_MS)
    }

    /** Cancel a deferred press, releasing it first if it already went out. */
    private fun cancelDeferredPress(keycode: Int) {
        comboPendingDowns.remove(keycode)?.let { comboHandler.removeCallbacks(it) }
        if (lateSentKeycodes.remove(keycode)) {
            mapping.getXboxButton(keycode)?.let { InputRecorder.sendButtonUp(it.mask) }
        }
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
            InputRecorder.sendAxis(xboxAxis.index, scaled)
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
        if (prev < -0.5f && curr >= -0.5f) InputRecorder.sendButtonUp(negMask)
        // Release positive direction if no longer held
        if (prev > 0.5f  && curr <= 0.5f)  InputRecorder.sendButtonUp(posMask)
        // Press negative direction
        if (curr < -0.5f && prev >= -0.5f) InputRecorder.sendButtonDown(negMask)
        // Press positive direction
        if (curr > 0.5f  && prev <= 0.5f)  InputRecorder.sendButtonDown(posMask)
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

        fun item(label: String, danger: Boolean = false, action: () -> Unit) {
            container.addView(sheetItem(label, danger) { sheet.dismiss(); action() })
        }

        item(overlayLabel)    { cycleOverlayMode() }

        val hrtfOn = mainPrefs.getBoolean("audio_hrtf", false)
        item(if (hrtfOn) "HRTF: On" else "HRTF: Off") { toggleHrtf() }

        container.addView(sheetDivider())

        item("Save State")    { showSaveStateDialog() }
        item("Load State")    { showLoadStateDialog() }
        item("Map Controls")  { startActivity(Intent(this, MappingActivity::class.java)) }

        container.addView(sheetDivider())

        val recordLabel = when {
            InputRecorder.isRecording -> "Stop Recording"
            InputRecorder.isPlaying   -> "Recording (playback active)"
            else                       -> "Start Recording Input"
        }
        item(recordLabel)       { toggleRecording() }
        item("Play Recording")  { showPlayRecordingDialog() }

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

    // ── Input recording / playback dialogs ────────────────────────────────────

    private fun toggleRecording() {
        if (InputRecorder.isPlaying) {
            Toast.makeText(this, "Cannot record during playback", Toast.LENGTH_SHORT).show()
            return
        }
        if (InputRecorder.isRecording) {
            showStopRecordingDialog()
        } else if (InputRecorder.startRecording(gameId)) {
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStopRecordingDialog() {
        val input = EditText(this).apply { hint = "Recording name, e.g. perf_test_1" }
        AlertDialog.Builder(this)
            .setTitle("Save Recording")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { "recording_${System.currentTimeMillis()}" }
                val file = InputRecorder.stopRecording(filesDir, name)
                Toast.makeText(
                    this,
                    if (file != null) "Saved: ${file.name}" else "Save failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Discard") { _, _ -> InputRecorder.cancelRecording() }
            .show()
    }

    private fun showPlayRecordingDialog() {
        val names = InputRecorder.listRecordings(filesDir, gameId)
        if (names.isEmpty()) {
            Toast.makeText(this, "No recordings for this game", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Play Recording")
            .setItems(names.toTypedArray()) { _, which ->
                InputRecorder.startPlayback(filesDir, gameId, names[which])
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

    private fun cycleOverlayMode() {
        overlayMode = when (overlayMode) {
            OverlayMode.AUTO        -> OverlayMode.ALWAYS_SHOW
            OverlayMode.ALWAYS_SHOW -> OverlayMode.ALWAYS_HIDE
            OverlayMode.ALWAYS_HIDE -> OverlayMode.AUTO
        }
        mainPrefs.edit().putString("overlay_mode", overlayMode.name).apply()
        updateOverlayVisibility()
    }

    /**
     * Toggle HRTF 3D positional audio.  The APU re-reads this every audio
     * frame, so it applies immediately and can be A/B'd while a game is
     * running.  Shares the "audio_hrtf" pref with the Audio settings screen.
     */
    private fun toggleHrtf() {
        val enabled = !mainPrefs.getBoolean("audio_hrtf", false)
        mainPrefs.edit().putBoolean("audio_hrtf", enabled).apply()
        NativeInterface.setHrtf(enabled)
        Toast.makeText(this, if (enabled) "HRTF on" else "HRTF off",
                       Toast.LENGTH_SHORT).show()
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

        /* Both are read while the machine is built; must precede startEmulation(). */
        NativeInterface.setSkipBootAnim(mainPrefs.getBoolean("skip_boot_anim", false))
        NativeInterface.setVoiceWorkers(mainPrefs.getInt("audio_voice_workers", 2))

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

    // ── Hotkey actions ────────────────────────────────────────────────────────

    private fun executeHotkeyFunction(fn: ControllerMapping.HotkeyFunction) {
        when (fn) {
            ControllerMapping.HotkeyFunction.QUICK_SAVE    -> executeQuickSave()
            ControllerMapping.HotkeyFunction.QUICK_LOAD    -> executeQuickLoad()
            ControllerMapping.HotkeyFunction.SLOT_NEXT     -> changeQuickSaveSlot(+1)
            ControllerMapping.HotkeyFunction.SLOT_PREV     -> changeQuickSaveSlot(-1)
            ControllerMapping.HotkeyFunction.SCREENSHOT    -> takeScreenshot()
            ControllerMapping.HotkeyFunction.TOGGLE_PAUSE  -> toggleUserPause()
            ControllerMapping.HotkeyFunction.OPEN_MENU     -> showMenuSheet()
            ControllerMapping.HotkeyFunction.CYCLE_OVERLAY -> cycleOverlayMode()
            ControllerMapping.HotkeyFunction.TOGGLE_FPS    -> toggleFpsOverlay()
        }
    }

    private fun executeQuickSave() {
        val name = "${gameId}_slot_$quickSaveSlot"
        NativeInterface.saveState(name)
        Toast.makeText(this, "Saved to Slot $quickSaveSlot", Toast.LENGTH_SHORT).show()
    }

    private fun executeQuickLoad() {
        val name = "${gameId}_slot_$quickSaveSlot"
        if (name in NativeInterface.listStates()) {
            NativeInterface.loadState(name)
            Toast.makeText(this, "Loaded Slot $quickSaveSlot", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Slot $quickSaveSlot is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeQuickSaveSlot(delta: Int) {
        quickSaveSlot = ((quickSaveSlot - 1 + delta + 8) % 8) + 1
        Toast.makeText(this, "Quick-save slot: $quickSaveSlot", Toast.LENGTH_SHORT).show()
    }

    private fun toggleUserPause() {
        if (userPaused) {
            NativeInterface.resumeEmulation()
            userPaused = false
            Toast.makeText(this, "Resumed", Toast.LENGTH_SHORT).show()
        } else {
            NativeInterface.pauseEmulation()
            userPaused = true
            Toast.makeText(this, "Paused — press hotkey again to resume", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFpsOverlay() {
        val showFps = !mainPrefs.getBoolean("overlay_show_fps", true)
        mainPrefs.edit().putBoolean("overlay_show_fps", showFps).apply()
        applyOverlaySettings()
    }

    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            takeScreenshotApi26()
        } else {
            Toast.makeText(this, "Screenshot requires Android 8+", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun takeScreenshotApi26() {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w == 0 || h == 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bmp, { result ->
            if (result == PixelCopy.SUCCESS) saveScreenshot(bmp)
            else Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveScreenshot(bmp: Bitmap) {
        val filename = "xemu_${System.currentTimeMillis()}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/xemu")
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { u ->
                    contentResolver.openOutputStream(u)?.use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    Toast.makeText(this, "Screenshot saved to Pictures/xemu",
                        Toast.LENGTH_SHORT).show()
                }
            } else {
                val dir = java.io.File(filesDir, "screenshots").also { it.mkdirs() }
                java.io.File(dir, filename).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                Toast.makeText(this, "Screenshot saved to app storage",
                    Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Screenshot failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Returns the first connected physical gamepad/joystick device ID, or null. */
    private fun physicalGamepadDeviceId(): Int? =
        InputDevice.getDeviceIds().toList()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .firstOrNull { dev ->
                !dev.isVirtual &&
                (dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                 dev.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            }?.id

    /** Drive the physical controller's own left/right rumble motors independently (API 31+). */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun vibrateController(l: Int, r: Int) {
        val dev = physicalGamepadDeviceId()?.let { InputDevice.getDevice(it) } ?: return
        val vm = dev.vibratorManager
        val ids = vm.vibratorIds
        if (ids.isEmpty()) return
        val parallel = CombinedVibration.startParallel()
        var hasAny = false
        if (l > 0) {
            val amp = (l * 255 / 65535).coerceIn(1, 255)
            parallel.addVibrator(ids[0], VibrationEffect.createOneShot(150, amp))
            hasAny = true
        }
        if (ids.size >= 2 && r > 0) {
            val amp = (r * 255 / 65535).coerceIn(1, 255)
            parallel.addVibrator(ids[1], VibrationEffect.createOneShot(150, amp))
            hasAny = true
        }
        if (hasAny) vm.vibrate(parallel.combine()) else vm.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun cancelControllerRumble() {
        physicalGamepadDeviceId()?.let { InputDevice.getDevice(it)?.vibratorManager?.cancel() }
    }

    /** Read graphics settings from main_prefs and push to native layer. */
    private fun applyGraphicsSettings() {
        NativeInterface.setAspectRatio(mainPrefs.getInt("aspect_ratio", 3))
        NativeInterface.setFilterNearest(mainPrefs.getBoolean("filter_nearest", false))
        NativeInterface.setSurfaceScale(mainPrefs.getInt("surface_scale", 1))
    }

    /**
     * Read audio settings from main_prefs and push to native.  Only HRTF is
     * applied here; the voice worker count is read once when the APU starts and
     * so must be set before startEmulation().
     */
    private fun applyAudioSettings() {
        NativeInterface.setHrtf(mainPrefs.getBoolean("audio_hrtf", false))
    }

    /** Read overlay settings from main_prefs and apply position + visibility. */
    private fun applyOverlaySettings() {
        val showFps       = mainPrefs.getBoolean("overlay_show_fps", true)
        val showFrametime = mainPrefs.getBoolean("overlay_show_frametime", false)
        val showMemory    = mainPrefs.getBoolean("overlay_show_memory", false)
        val showShaders   = mainPrefs.getBoolean("overlay_show_shaders", false)

        fpsLine.visibility       = if (showFps)       View.VISIBLE else View.GONE
        frametimeLine.visibility = if (showFrametime) View.VISIBLE else View.GONE
        frameTimeGraph.visibility = if (showFrametime) View.VISIBLE else View.GONE
        pgraphLine.visibility    = if (showFrametime) View.VISIBLE else View.GONE
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
