package com.xemu.emulation

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp as composeDp
import com.xemu.ui.theme.XemuTheme
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.widget.EditText
import android.util.Log
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
                InputRecorder.ACTION_BENCHMARK -> {
                    if (emulationStarted) {
                        /* Optional "slot" extra loads that save state first and
                         * lets it settle, so one command gives a measurement
                         * from a known, identical starting state. */
                        val slot = intent.getIntExtra(InputRecorder.EXTRA_SLOT, 0)
                        val frames =
                            intent.getIntExtra(InputRecorder.EXTRA_FRAMES, 600)
                        if (slot in 1..8) {
                            Thread {
                                if (loadStateSlot(slot)) {
                                    Thread.sleep(1500)   /* let the guest settle */
                                    NativeInterface.startBenchmark(frames)
                                }
                            }.start()
                        } else {
                            NativeInterface.startBenchmark(frames)
                        }
                    }
                }
                InputRecorder.ACTION_LOAD_STATE -> {
                    if (emulationStarted) {
                        val slot = intent.getIntExtra(InputRecorder.EXTRA_SLOT, 1)
                        Thread { loadStateSlot(slot) }.start()
                    }
                }
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
    /**
     * Rolling frame-time plot.
     *
     * Samples are intervals between NEW GUEST FRAMES, not between buffer
     * swaps.  The render loop presents even when the guest has not finished a
     * frame, so swap intervals are pinned to the display cadence and never
     * rose above ~31 ms even at 20 fps; guest-frame intervals show the real
     * cost and track the fps readout beside them.
     */
    private inner class FrameTimeBarView(context: Context) : View(context) {
        var samples: IntArray = IntArray(0)

        private val maxMs = 100f
        private val path = Path()
        private val fillPath = Path()

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(235, 120, 220, 255)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(48, 120, 220, 255)
        }
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 220, 0)
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }
        private val overPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(235, 255, 110, 110)
        }

        private fun yFor(ms: Int): Float =
            height - (ms.coerceIn(0, maxMs.toInt()) / maxMs * height)

        override fun onDraw(canvas: Canvas) {
            val n = samples.size
            if (n < 2) return
            // Each sample occupies a slot and is held flat across it: a frame
            // has a duration, so a step is the honest shape.  Interpolating
            // between samples would draw times that never occurred.
            val dx = width.toFloat() / n

            // Filled area under the staircase.
            fillPath.reset()
            fillPath.moveTo(0f, height.toFloat())
            for (i in 0 until n) {
                val y = yFor(samples[i])
                fillPath.lineTo(i * dx, y)
                fillPath.lineTo((i + 1) * dx, y)
            }
            fillPath.lineTo(width.toFloat(), height.toFloat())
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)

            // The 33.3 ms budget.
            val targetY = yFor(33)
            canvas.drawLine(0f, targetY, width.toFloat(), targetY, targetPaint)

            /*
             * Draw per segment rather than per run.  Two things were wrong
             * with run-splitting: the riser into a run was drawn in the
             * PREVIOUS run's colour, so the rising edge into a late frame
             * looked on-budget; and with a plain `ms > 33` test a steady
             * 33.3 ms frame rounds to 33 or 34 alternately and the trace
             * flickered red/blue while nothing was actually wrong.
             *
             * Hysteresis fixes the second: a frame only turns red above
             * 36 ms (~8% late) and only returns to blue at or below 33 ms,
             * so the boundary cannot oscillate.  The riser always takes the
             * colour of the sample it is arriving at.
             */
            var late = false
            for (k in 0 until n) {
                val ms = samples[k]
                late = if (late) ms > 33 else ms > 36
                val paint = if (late) overPaint else linePaint
                val y = yFor(ms)

                if (k > 0) {
                    // Riser into this sample, in THIS sample's colour.
                    canvas.drawLine(k * dx, yFor(samples[k - 1]), k * dx, y, paint)
                }
                canvas.drawLine(k * dx, y, (k + 1) * dx, y, paint)
            }
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

        root = FrameLayout(this)
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
        menuBtn = TextView(this).apply {
            text = "MENU"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = pillDrawable(Color.argb(160, 20, 20, 20))
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener { showMenuPopup(this) }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = 16.dp; rightMargin = 16.dp })

        onBackPressedDispatcher.addCallback(this, overlayBackCallback)

        // ── Transient message surface (replaces Toast) ────────────────────────
        /*
         * Its own small ComposeView under the MENU pill rather than an entry in
         * showOverlay(): those cover the screen to catch outside taps, which a
         * message must not do — the game has to stay playable while one is up.
         * Kept GONE when idle so it cannot intercept a touch even by accident.
         */
        messageView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                XemuTheme {
                    GameMessage(
                        text = messageText.value,
                        visible = messageVisible.value,
                        isError = messageIsError.value,
                    )
                }
            }
        }
        root.addView(messageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { topMargin = 64.dp; rightMargin = 16.dp })

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
                addAction(InputRecorder.ACTION_BENCHMARK)
                addAction(InputRecorder.ACTION_LOAD_STATE)
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

    /**
     * Show a transient message, replacing whatever is on screen.
     *
     * Safe to call from any thread.  Errors linger, since "it did not work"
     * takes longer to read and act on than a confirmation.
     */
    private fun showMessage(text: String, isError: Boolean = false) {
        runOnUiThread {
            messageHide?.let { messageView.removeCallbacks(it) }
            messageText.value = text
            messageIsError.value = isError
            messageVisible.value = true
            messageView.visibility = View.VISIBLE

            val hide = Runnable {
                messageVisible.value = false
                /* Let the fade finish before the view stops being drawable. */
                messageView.postDelayed({
                    if (!messageVisible.value) {
                        messageView.visibility = View.GONE
                    }
                }, 220)
            }
            messageHide = hide
            messageView.postDelayed(hide, if (isError) 3500L else 1800L)
        }
    }

    // ── In-game menu ──────────────────────────────────────────────────────────

    private var isExiting = false
    private var stateOpInFlight = false
    private lateinit var messageView: ComposeView
    private val messageText = mutableStateOf("")
    private val messageIsError = mutableStateOf(false)
    private val messageVisible = mutableStateOf(false)
    private var messageHide: Runnable? = null
    private lateinit var menuBtn: TextView
    private lateinit var root: FrameLayout
    private var overlayView: View? = null
    private var overlayTransition: MutableTransitionState<Boolean>? = null

    /**
     * Host a Compose overlay in the activity's own view tree.
     *
     * Not a PopupWindow: a popup gets its own window whose decor view has no
     * ViewTreeLifecycleOwner, and Compose walks up to the window root to build
     * its recomposer, so a ComposeView inside one dies with
     * "ViewTreeLifecycleOwner not found".  Living in the activity's tree also
     * puts the overlay in the same coordinate space as the views it is
     * positioned against, so it cannot land off-screen.
     *
     * [content] receives the transition driving its enter/exit animation and a
     * callback to run once it has finished animating out.  Removal is bound to
     * this particular view rather than to "whatever is open", because the menu
     * starts its exit animation and immediately opens the slot picker — a
     * shared reference would have the menu's removal take the picker with it.
     */
    private fun showOverlay(
        gravity: Int,
        topMarginPx: Int = 0,
        rightMarginPx: Int = 0,
        dimBackground: Boolean = false,
        content: @Composable (MutableTransitionState<Boolean>, () -> Unit) -> Unit,
    ) {
        val transition = MutableTransitionState(false).apply { targetState = true }
        val overlay = FrameLayout(this).apply {
            isClickable = true
            if (dimBackground) setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { dismissOverlay() }
        }

        val removeThis = {
            root.removeView(overlay)
            if (overlayView === overlay) {
                overlayView = null
                overlayTransition = null
                overlayBackCallback.isEnabled = false
            }
        }

        val composeView = ComposeView(this).apply {
            setContent { XemuTheme { content(transition, removeThis) } }
        }
        overlay.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity,
        ).apply { topMargin = topMarginPx; rightMargin = rightMarginPx })

        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        overlayView = overlay
        overlayTransition = transition
        overlayBackCallback.isEnabled = true
    }

    /**
     * Let Back close whatever overlay is open instead of leaving the game.
     *
     * The menu, slot picker and dialogs used to be AlertDialogs and BottomSheets,
     * which consume Back themselves.  As Compose overlays inside the activity's
     * own view tree they do not, so Back fell through to the activity and ended
     * emulation -- from a menu, with no confirmation and with unsaved progress
     * lost.
     */
    private val overlayBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            dismissOverlay()
        }
    }

    /** Starts the close animation; the view is removed once it finishes. */
    private fun dismissOverlay() {
        overlayTransition?.targetState = false
    }

    /**
     * The in-game menu, anchored under the MENU pill.
     *
     * This replaced a BottomSheetDialog.  The activity is locked to landscape,
     * where a sheet opens at its collapsed peek height against a short
     * viewport, so most of the list sat below the fold every time.
     */
    private fun showMenuPopup(anchor: View) {
        if (overlayView != null) {
            dismissOverlay()
            return
        }

        val topPx = anchor.bottom + 8.dp
        val maxHeightDp =
            ((resources.displayMetrics.heightPixels - topPx - 16.dp) /
             resources.displayMetrics.density).composeDp

        showOverlay(Gravity.TOP or Gravity.END, topPx, 16.dp) { transition, onHidden ->
            InGameMenu(
                initialOverlayLabel = overlayModeLabel(),
                initialHrtfOn = mainPrefs.getBoolean("audio_hrtf", false),
                recordingLabel = when {
                    InputRecorder.isRecording -> "Stop Recording"
                    InputRecorder.isPlaying   -> "Playback active"
                    else                      -> "Record Input"
                },
                isRecordingBusy = InputRecorder.isPlaying,
                maxHeight = maxHeightDp,
                visibleState = transition,
                onFullyHidden = onHidden,
                onCycleOverlay = { cycleOverlayMode() },
                onToggleHrtf = { toggleHrtf() },
                onSaveState = { dismissOverlay(); showSlotPicker(isSave = true) },
                onLoadState = { dismissOverlay(); showSlotPicker(isSave = false) },
                onMapControls = {
                    dismissOverlay()
                    startActivity(Intent(this@EmulationActivity,
                                         MappingActivity::class.java))
                },
                onToggleRecording = { dismissOverlay(); toggleRecording() },
                onPlayRecording = { dismissOverlay(); showPlayRecordingDialog() },
                onExit = { dismissOverlay(); confirmExit() },
            )
        }
    }

    /** Height a centred dialog may use before it starts scrolling. */
    private fun dialogMaxHeight() =
        ((resources.displayMetrics.heightPixels * 0.62f) /
         resources.displayMetrics.density).composeDp

    /**
     * Save/load slot picker.  Eight tiles in two rows of four rather than the
     * eight-item AlertDialog it replaced, which ran off the bottom of a
     * landscape screen and left the last slot unreachable.
     */
    private fun showSlotPicker(isSave: Boolean) {
        val existing = NativeInterface.listStates().toSet()
        val slots = (1..8).map { n ->
            SlotInfo(n, "${gameId}_slot_$n" in existing)
        }

        /*
         * Held outside the composable so the worker thread can drive them.
         * saveState/loadState block on the QEMU main loop -- calling them from
         * the click handler froze the UI for the whole operation, which is why
         * pressing a slot appeared to do nothing until it was already over.
         */
        val errorText = mutableStateOf<String?>(null)
        val busy = mutableStateOf(false)
        val activeSlot = mutableStateOf<Int?>(null)

        showOverlay(Gravity.CENTER, dimBackground = true) { transition, onHidden ->
            SlotPicker(
                title = if (isSave) "Save State" else "Load State",
                slots = slots,
                isSave = isSave,
                errorText = errorText.value,
                busy = busy.value,
                activeSlot = activeSlot.value,
                visibleState = transition,
                onFullyHidden = onHidden,
                onCancel = { dismissOverlay() },
                onPick = { n ->
                    if (!busy.value) {
                        busy.value = true
                        errorText.value = null
                        /* The pressed tile holds a filled state for the
                         * duration; no text announces what is happening. */
                        activeSlot.value = n

                        val name = "${gameId}_slot_$n"
                        Thread {
                            val err = if (isSave) {
                                NativeInterface.saveState(name)
                            } else {
                                NativeInterface.loadState(name)
                            }
                            runOnUiThread {
                                busy.value = false
                                errorText.value = err
                                if (err != null) {
                                    activeSlot.value = null
                                }
                                /* Linger on a failure so the reason can be
                                 * read; get out of the way on success. */
                                root.postDelayed(
                                    { dismissOverlay() },
                                    if (err == null) 400L else 3000L,
                                )
                            }
                        }.start()
                    }
                },
            )
        }
    }

    // ── Save / load state dialogs ─────────────────────────────────────────────

    // ── Input recording / playback dialogs ────────────────────────────────────

    private fun toggleRecording() {
        if (InputRecorder.isPlaying) {
            showMessage("Cannot record during playback", isError = true)
            return
        }
        if (InputRecorder.isRecording) {
            showStopRecordingDialog()
        } else if (InputRecorder.startRecording(gameId)) {
            showMessage("Recording started")
        }
    }

    private fun showStopRecordingDialog() {
        showOverlay(Gravity.CENTER, dimBackground = true) { transition, onHidden ->
            GameTextInputDialog(
                title = "Save Recording",
                label = "Recording name, e.g. perf_test_1",
                confirmLabel = "Save",
                dismissLabel = "Discard",
                visibleState = transition,
                maxHeight = dialogMaxHeight(),
                onFullyHidden = onHidden,
                onConfirm = { name ->
                    dismissOverlay()
                    InputRecorder.stopRecording(filesDir, name)
                    showMessage("Recording saved as $name")
                },
                onDismiss = {
                    dismissOverlay()
                    InputRecorder.cancelRecording()
                },
            )
        }
    }

    private fun showPlayRecordingDialog() {
        val names = InputRecorder.listRecordings(filesDir, gameId)
        if (names.isEmpty()) {
            showMessage("No recordings for this game", isError = true)
            return
        }
        showOverlay(Gravity.CENTER, dimBackground = true) { transition, onHidden ->
            GameListDialog(
                title = "Play Recording",
                items = names,
                dismissLabel = "Cancel",
                visibleState = transition,
                maxHeight = dialogMaxHeight(),
                onFullyHidden = onHidden,
                onPick = { i ->
                    dismissOverlay()
                    InputRecorder.startPlayback(filesDir, gameId, names[i])
                },
                onDismiss = { dismissOverlay() },
            )
        }
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

    /** Cycles the overlay mode and returns the new label, so the menu can
     *  update without being dismissed and reopened. */
    private fun cycleOverlayMode(): String {
        overlayMode = when (overlayMode) {
            OverlayMode.AUTO        -> OverlayMode.ALWAYS_SHOW
            OverlayMode.ALWAYS_SHOW -> OverlayMode.ALWAYS_HIDE
            OverlayMode.ALWAYS_HIDE -> OverlayMode.AUTO
        }
        mainPrefs.edit().putString("overlay_mode", overlayMode.name).apply()
        updateOverlayVisibility()
        return overlayModeLabel()
    }

    private fun overlayModeLabel(): String = when (overlayMode) {
        OverlayMode.AUTO        -> "Auto"
        OverlayMode.ALWAYS_SHOW -> "Always"
        OverlayMode.ALWAYS_HIDE -> "Hidden"
    }

    /**
     * Toggle HRTF 3D positional audio.  The APU re-reads this every audio
     * frame, so it applies immediately and can be A/B'd while a game is
     * running.  Shares the "audio_hrtf" pref with the Audio settings screen.
     */
    private fun toggleHrtf(): Boolean {
        val enabled = !mainPrefs.getBoolean("audio_hrtf", false)
        mainPrefs.edit().putBoolean("audio_hrtf", enabled).apply()
        NativeInterface.setHrtf(enabled)
        return enabled
    }

    private fun confirmExit() {
        showOverlay(Gravity.CENTER, dimBackground = true) { transition, onHidden ->
            GameConfirmDialog(
                title = "Exit to Library",
                message = "Return to the game library?\n\n" +
                          "Unsaved game progress will be lost.",
                confirmLabel = "Exit",
                dismissLabel = "Cancel",
                visibleState = transition,
                maxHeight = dialogMaxHeight(),
                onFullyHidden = onHidden,
                onConfirm = { dismissOverlay(); exitToLibrary() },
                onDismiss = { dismissOverlay() },
            )
        }
    }

    /**
     * Leave emulation and return to the library.
     *
     * The old path called requestExit(), which flushes and then _exit(0)s the
     * emulation process from under the activity — the window dies mid-frame and
     * it reads as the app falling over rather than navigating back.
     *
     * Flush first, off the UI thread because the call blocks on the QEMU main
     * loop, then finish() so the normal activity transition plays and
     * MainActivity resumes.  Only after that does the process go: QEMU cannot
     * be re-initialised in a process that has already run it, so the next
     * launch needs a fresh one.
     */
    private fun exitToLibrary() {
        if (isExiting) return
        isExiting = true
        showMessage("Saving\u2026")
        Thread {
            NativeInterface.flushBlockDevices()
            runOnUiThread {
                finish()
                /* Let the transition render before the process disappears. */
                window.decorView.postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 350)
            }
        }.start()
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
            ControllerMapping.HotkeyFunction.OPEN_MENU     -> showMenuPopup(menuBtn)
            ControllerMapping.HotkeyFunction.CYCLE_OVERLAY -> cycleOverlayMode()
            ControllerMapping.HotkeyFunction.TOGGLE_FPS    -> toggleFpsOverlay()
        }
    }

    /* Load a save slot by number.  Logs the outcome under xemu-android so a
     * scripted measurement can confirm the state was actually restored. */
    /**
     * Load a slot from the benchmark/automation path.
     *
     * Stays synchronous because its callers are scripted and want the result
     * before continuing; the quick-save hotkeys below do not, and must not
     * block the UI thread.
     */
    private fun loadStateSlot(slot: Int): Boolean {
        val name = "${gameId}_slot_$slot"
        if (name !in NativeInterface.listStates()) {
            android.util.Log.w("xemu-android", "loadstate: slot $slot is empty")
            return false
        }
        val err = NativeInterface.loadState(name)
        if (err != null) {
            android.util.Log.e("xemu-android", "loadstate: slot $slot failed: $err")
            return false
        }
        android.util.Log.i("xemu-android", "loadstate: loaded slot $slot")
        return true
    }

    /**
     * Run a blocking save/load off the UI thread and report what happened.
     *
     * saveState/loadState wait on the QEMU main loop, so calling them directly
     * from a hotkey froze the UI until the snapshot was done — and then said
     * "Saved" whether or not it had worked.
     */
    private fun runStateOp(busyMsg: String, okMsg: String, op: () -> String?) {
        if (stateOpInFlight) {
            return
        }
        stateOpInFlight = true
        showMessage(busyMsg)
        Thread {
            val err = op()
            runOnUiThread {
                stateOpInFlight = false
                showMessage(err ?: okMsg, isError = err != null)
            }
        }.start()
    }

    private fun executeQuickSave() {
        val name = "${gameId}_slot_$quickSaveSlot"
        runStateOp("Saving to slot $quickSaveSlot…", "Saved to slot $quickSaveSlot") {
            NativeInterface.saveState(name)
        }
    }

    private fun executeQuickLoad() {
        val name = "${gameId}_slot_$quickSaveSlot"
        if (name !in NativeInterface.listStates()) {
            showMessage("Slot $quickSaveSlot is empty", isError = true)
            return
        }
        runStateOp("Loading slot $quickSaveSlot…", "Loaded slot $quickSaveSlot") {
            NativeInterface.loadState(name)
        }
    }

    private fun changeQuickSaveSlot(delta: Int) {
        quickSaveSlot = ((quickSaveSlot - 1 + delta + 8) % 8) + 1
        showMessage("Quick-save slot: $quickSaveSlot")
    }

    private fun toggleUserPause() {
        if (userPaused) {
            NativeInterface.resumeEmulation()
            userPaused = false
            showMessage("Resumed")
        } else {
            NativeInterface.pauseEmulation()
            userPaused = true
            showMessage("Paused — press hotkey again to resume")
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
            showMessage("Screenshot requires Android 8+", isError = true)
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
            else showMessage("Screenshot failed", isError = true)
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
                    showMessage("Screenshot saved to Pictures/xemu")
                }
            } else {
                val dir = java.io.File(filesDir, "screenshots").also { it.mkdirs() }
                java.io.File(dir, filename).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                showMessage("Screenshot saved to app storage")
            }
        } catch (e: Exception) {
            showMessage("Screenshot failed: ${e.message}", isError = true)
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
        /* Off by default: the TSO store barriers are elided on this
         * uniprocessor guest.  Only set if the user turned the safety valve on
         * in Advanced settings. */
        NativeInterface.setAccurateMemoryOrdering(
            mainPrefs.getBoolean("accurate_mem_ordering", false))
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
