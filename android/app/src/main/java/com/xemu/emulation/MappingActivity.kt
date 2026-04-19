package com.xemu.emulation

import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Controller remapping screen.
 *
 * Shows each Xbox button with its currently-assigned Android keycode.
 * Tap a row → the activity listens for the next physical gamepad button
 * press and assigns it.
 *
 * Launched from the in-game menu; can also be reached from MainActivity.
 */
class MappingActivity : AppCompatActivity() {

    private lateinit var mapping: ControllerMapping
    private var capturingFor: ControllerMapping.XboxButton? = null

    private lateinit var statusText: TextView
    private lateinit var rowContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapping = ControllerMapping(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(32, 32, 32, 64)
        }
        scroll.addView(root)
        setContentView(scroll)

        // ── Title ─────────────────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "Controller Mapping"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        })

        // ── Status / capture prompt ────────────────────────────────────────────
        statusText = TextView(this).apply {
            setIdlePrompt()
            textSize = 15f
            setPadding(0, 8, 0, 16)
        }
        root.addView(statusText)

        // ── Reset button ──────────────────────────────────────────────────────
        val resetBtn = Button(this).apply {
            text = "Reset to Defaults"
            setOnClickListener {
                if (capturingFor != null) cancelCapture()
                AlertDialog.Builder(this@MappingActivity)
                    .setTitle("Reset Mappings")
                    .setMessage("Reset all button mappings to defaults?")
                    .setPositiveButton("Reset") { _, _ ->
                        mapping.resetToDefaults()
                        mapping.save()
                        refreshRows()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        root.addView(resetBtn)

        // ── Divider / section header ───────────────────────────────────────────
        root.addView(sectionHeader("BUTTONS"))

        // ── Mapping rows ──────────────────────────────────────────────────────
        rowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowContainer)
        refreshRows()

        // ── Axes info (read-only) ─────────────────────────────────────────────
        root.addView(sectionHeader("AXES (auto-detected)"))
        root.addView(TextView(this).apply {
            text = "Standard axis layout is used automatically:\n" +
                   "Left stick → AXIS_X / AXIS_Y\n" +
                   "Right stick → AXIS_Z / AXIS_RZ\n" +
                   "Triggers → AXIS_LTRIGGER / AXIS_RTRIGGER"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(8, 8, 8, 8)
        })

        // ── Cancel capture on outside tap via back button ──────────────────────
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    // ── Build / refresh the button rows ───────────────────────────────────────

    private fun refreshRows() {
        rowContainer.removeAllViews()
        // Build reverse map: XboxButton → list of bound keycodes
        val reverse = mutableMapOf<ControllerMapping.XboxButton, MutableList<Int>>()
        mapping.allButtonMappings().forEach { (kc, btn) ->
            reverse.getOrPut(btn) { mutableListOf() }.add(kc)
        }
        for (btn in ControllerMapping.XboxButton.values()) {
            val bound = reverse[btn]?.joinToString(", ") {
                ControllerMapping.keycodeLabel(it)
            } ?: "(none)"
            rowContainer.addView(buildRow(btn, bound))
        }
    }

    private fun buildRow(btn: ControllerMapping.XboxButton, currentLabel: String): View {
        val bg = if (capturingFor == btn)
            Color.parseColor("#3A3A00") else Color.parseColor("#2A2A2A")

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            setPadding(16, 20, 16, 20)
        }

        row.addView(TextView(this).apply {
            text = btn.label
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(TextView(this).apply {
            text = currentLabel
            setTextColor(if (capturingFor == btn) Color.YELLOW else Color.LTGRAY)
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.setOnClickListener { startCapturing(btn) }

        // Divider
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(row)
        wrapper.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        return wrapper
    }

    // ── Capture logic ─────────────────────────────────────────────────────────

    private fun startCapturing(btn: ControllerMapping.XboxButton) {
        capturingFor = btn
        statusText.text = "Press button on controller for: ${btn.label}  (tap here to cancel)"
        statusText.setTextColor(Color.YELLOW)
        statusText.setOnClickListener { cancelCapture() }
        refreshRows()
    }

    private fun cancelCapture() {
        capturingFor = null
        statusText.setOnClickListener(null)
        statusText.setIdlePrompt()
        refreshRows()
    }

    private fun TextView.setIdlePrompt() {
        text = "Tap a button row, then press on your physical controller to assign it."
        setTextColor(Color.GRAY)
    }

    // ── Intercept physical gamepad button presses ─────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val capturing = capturingFor
        if (capturing != null && event.action == KeyEvent.ACTION_DOWN) {
            val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD != 0) ||
                            (event.source and InputDevice.SOURCE_DPAD != 0)
            if (isGamepad) {
                mapping.assignButton(event.keyCode, capturing)
                mapping.save()
                cancelCapture()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sectionHeader(text: String): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(8, 24, 8, 8)
        })
        wrapper.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#444444"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        return wrapper
    }
}
