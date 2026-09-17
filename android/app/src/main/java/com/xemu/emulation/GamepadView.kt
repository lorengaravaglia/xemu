package com.xemu.emulation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.xemu.R
import com.xemu.NativeInterface
import kotlin.math.*

/**
 * Draws a semi-transparent Xbox controller overlay and translates touch
 * events into NativeInterface.sendButtonDown/Up and sendAxis calls.
 *
 * Layout (landscape):
 *   Left side  : D-pad + Left stick
 *   Center     : Back / Start
 *   Right side : Face buttons (A/B/X/Y) + Right stick
 *   Top        : LT and the White button (left), RT and Black (right)
 *
 * Nothing may overlap anything else, including the MENU pill the activity
 * draws in the top-right corner -- the triggers start below it.
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Paint ────────────────────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 30, 30, 30)
        style = Paint.Style.FILL
    }
    /*
     * Pressed takes the app's accent; everything at rest stays neutral.
     *
     * Tinting the whole pad green would fight the game behind it -- a control
     * overlay has to stay legible over arbitrary footage, which neutral
     * translucency does and a saturated hue does not.  The press is the one
     * moment where it should look like this app, and it doubles as the
     * clearest possible press feedback.
     */
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val accent = ContextCompat.getColor(context, R.color.xbox_green_light)
        color = Color.argb(210, Color.red(accent), Color.green(accent),
                           Color.blue(accent))
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 160, 160, 160)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 22f
    }
    private val stickBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val stickKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 80, 80, 80)
        style = Paint.Style.FILL
    }

    // ── Button definitions ───────────────────────────────────────────────────

    private data class ButtonDef(
        val mask: Int,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var radius: Float = 0f,
        var pressed: Boolean = false,
        var pointerId: Int = -1
    )

    private val buttons = listOf(
        // Face buttons
        ButtonDef(NativeInterface.BUTTON_A,     "A"),
        ButtonDef(NativeInterface.BUTTON_B,     "B"),
        ButtonDef(NativeInterface.BUTTON_X,     "X"),
        ButtonDef(NativeInterface.BUTTON_Y,     "Y"),
        // D-pad
        ButtonDef(NativeInterface.BUTTON_DPAD_UP,    "▲"),
        ButtonDef(NativeInterface.BUTTON_DPAD_DOWN,  "▼"),
        ButtonDef(NativeInterface.BUTTON_DPAD_LEFT,  "◀"),
        ButtonDef(NativeInterface.BUTTON_DPAD_RIGHT, "▶"),
        // Center
        /* Spelled out: the media-control glyphs that were here are not in the
         * default font and rendered as empty boxes. */
        ButtonDef(NativeInterface.BUTTON_START, "START"),
        ButtonDef(NativeInterface.BUTTON_BACK,  "BACK"),
        /*
         * Black and White, not bumpers.  The original Xbox controller had no
         * LB/RB -- it had two extra face buttons, coloured black and white,
         * which is what BUTTON_BLACK/BUTTON_WHITE are.  Labelling them LB/RB
         * told the player to press something the console never had.
         */
        ButtonDef(NativeInterface.BUTTON_WHITE, "WHT"),
        ButtonDef(NativeInterface.BUTTON_BLACK, "BLK"),
    )

    private fun btn(mask: Int) = buttons.first { it.mask == mask }

    // ── Trigger state ─────────────────────────────────────────────────────────
    // Triggers are rendered as buttons but send axis values.
    private data class TriggerDef(
        val axis: Int,
        val label: String,
        var rect: RectF = RectF(),
        var pressed: Boolean = false,
        var pointerId: Int = -1
    )
    private val lt = TriggerDef(NativeInterface.AXIS_LTRIG, "LT")
    private val rt = TriggerDef(NativeInterface.AXIS_RTRIG, "RT")

    // ── Stick state ───────────────────────────────────────────────────────────

    private data class StickDef(
        val axisX: Int, val axisY: Int,
        var cx: Float = 0f, var cy: Float = 0f,
        var outerR: Float = 0f,
        var knobX: Float = 0f, var knobY: Float = 0f,
        var pointerId: Int = -1
    )
    private val leftStick  = StickDef(NativeInterface.AXIS_LSTICK_X, NativeInterface.AXIS_LSTICK_Y)
    private val rightStick = StickDef(NativeInterface.AXIS_RSTICK_X, NativeInterface.AXIS_RSTICK_Y)

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        layout(w.toFloat(), h.toFloat())
    }

    private fun layout(w: Float, h: Float) {
        /*
         * Laid out in horizontal bands so nothing can overlap: triggers, then
         * Black/White, then d-pad and faces, then the sticks.  The previous
         * layout collided in three places -- the left stick sat on the d-pad's
         * down button, Black and White were drawn on top of the triggers, and
         * RT ran under the MENU pill.
         */
        val btnR      = h * 0.065f   // face / d-pad button radius
        val stickR    = h * 0.105f   // stick outer radius
        val shoulderR = h * 0.042f

        // ── Triggers: below the MENU pill, which ends around 0.09h ───────────
        val trigTop = h * 0.11f
        val trigBot = h * 0.195f
        lt.rect = RectF(w * 0.02f, trigTop, w * 0.13f, trigBot)
        rt.rect = RectF(w * 0.87f, trigTop, w * 0.98f, trigBot)

        // ── Black / White: their own band under the triggers ─────────────────
        val shoulderCy = h * 0.26f
        btn(NativeInterface.BUTTON_WHITE).apply {
            cx = w * 0.075f; cy = shoulderCy; radius = shoulderR
        }
        btn(NativeInterface.BUTTON_BLACK).apply {
            cx = w * 0.925f; cy = shoulderCy; radius = shoulderR
        }

        // ── Back / Start, centred between them ───────────────────────────────
        val centerY = h * 0.16f
        btn(NativeInterface.BUTTON_BACK).apply  {
            cx = w * 0.42f; cy = centerY; radius = btnR * 0.8f
        }
        btn(NativeInterface.BUTTON_START).apply {
            cx = w * 0.58f; cy = centerY; radius = btnR * 0.8f
        }

        // ── D-pad and face buttons share a band: 0.318h to 0.682h ────────────
        val clusterCy = h * 0.50f
        val dpadCx = w * 0.12f
        btn(NativeInterface.BUTTON_DPAD_UP).apply    { cx = dpadCx;               cy = clusterCy - btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_DPAD_DOWN).apply  { cx = dpadCx;               cy = clusterCy + btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_DPAD_LEFT).apply  { cx = dpadCx - btnR * 1.8f; cy = clusterCy;               radius = btnR }
        btn(NativeInterface.BUTTON_DPAD_RIGHT).apply { cx = dpadCx + btnR * 1.8f; cy = clusterCy;               radius = btnR }

        val faceCx = w * 0.85f
        btn(NativeInterface.BUTTON_Y).apply { cx = faceCx;               cy = clusterCy - btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_A).apply { cx = faceCx;               cy = clusterCy + btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_X).apply { cx = faceCx - btnR * 1.8f; cy = clusterCy;               radius = btnR }
        btn(NativeInterface.BUTTON_B).apply { cx = faceCx + btnR * 1.8f; cy = clusterCy;               radius = btnR }

        // ── Sticks: below both clusters, which end at 0.682h ─────────────────
        val stickCy = h * 0.83f
        leftStick.cx = w * 0.17f
        leftStick.cy = stickCy
        leftStick.outerR = stickR
        leftStick.knobX = leftStick.cx
        leftStick.knobY = leftStick.cy

        rightStick.cx = w * 0.80f
        rightStick.cy = stickCy
        rightStick.outerR = stickR
        rightStick.knobX = rightStick.cx
        rightStick.knobY = rightStick.cy
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // Triggers
        for (trig in listOf(lt, rt)) {
            canvas.drawRoundRect(trig.rect, 12f, 12f, if (trig.pressed) pressedPaint else fillPaint)
            canvas.drawRoundRect(trig.rect, 12f, 12f, strokePaint)
            canvas.drawText(trig.label,
                trig.rect.centerX(), trig.rect.centerY() + smallTextPaint.textSize / 3f,
                smallTextPaint)
        }

        // All circle buttons
        for (b in buttons) {
            canvas.drawCircle(b.cx, b.cy, b.radius, if (b.pressed) pressedPaint else fillPaint)
            canvas.drawCircle(b.cx, b.cy, b.radius, strokePaint)
            canvas.drawText(b.label, b.cx, b.cy + textPaint.textSize / 3f,
                if (b.radius < 40f) smallTextPaint else textPaint)
        }

        // Sticks
        for (stick in listOf(leftStick, rightStick)) {
            canvas.drawCircle(stick.cx, stick.cy, stick.outerR, stickBgPaint)
            canvas.drawCircle(stick.cx, stick.cy, stick.outerR, strokePaint)
            val knobR = stick.outerR * 0.45f
            canvas.drawCircle(stick.knobX, stick.knobY, knobR, stickKnobPaint)
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pIdx   = event.actionIndex
        val pid    = event.getPointerId(pIdx)
        val x      = event.getX(pIdx)
        val y      = event.getY(pIdx)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                handleDown(pid, x, y)

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    handleMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL ->
                handleUp(pid)
        }

        invalidate()
        return true
    }

    private fun handleDown(pid: Int, x: Float, y: Float) {
        // Triggers
        if (lt.rect.contains(x, y) && lt.pointerId == -1) {
            lt.pressed = true; lt.pointerId = pid
            InputRecorder.sendAxis(lt.axis, NativeInterface.AXIS_MAX)
            return
        }
        if (rt.rect.contains(x, y) && rt.pointerId == -1) {
            rt.pressed = true; rt.pointerId = pid
            InputRecorder.sendAxis(rt.axis, NativeInterface.AXIS_MAX)
            return
        }
        // Sticks
        if (dist(x, y, leftStick.cx, leftStick.cy) <= leftStick.outerR && leftStick.pointerId == -1) {
            leftStick.pointerId = pid
            updateStick(leftStick, x, y)
            return
        }
        if (dist(x, y, rightStick.cx, rightStick.cy) <= rightStick.outerR && rightStick.pointerId == -1) {
            rightStick.pointerId = pid
            updateStick(rightStick, x, y)
            return
        }
        // Buttons
        for (b in buttons) {
            if (dist(x, y, b.cx, b.cy) <= b.radius && b.pointerId == -1) {
                b.pressed = true; b.pointerId = pid
                InputRecorder.sendButtonDown(b.mask)
                return
            }
        }
    }

    private fun handleMove(pid: Int, x: Float, y: Float) {
        if (leftStick.pointerId == pid)  { updateStick(leftStick, x, y);  return }
        if (rightStick.pointerId == pid) { updateStick(rightStick, x, y); return }
    }

    private fun handleUp(pid: Int) {
        // Triggers
        if (lt.pointerId == pid) {
            lt.pressed = false; lt.pointerId = -1
            InputRecorder.sendAxis(lt.axis, 0)
        }
        if (rt.pointerId == pid) {
            rt.pressed = false; rt.pointerId = -1
            InputRecorder.sendAxis(rt.axis, 0)
        }
        // Sticks
        if (leftStick.pointerId == pid) {
            leftStick.pointerId = -1
            leftStick.knobX = leftStick.cx; leftStick.knobY = leftStick.cy
            InputRecorder.sendAxis(leftStick.axisX, 0)
            InputRecorder.sendAxis(leftStick.axisY, 0)
        }
        if (rightStick.pointerId == pid) {
            rightStick.pointerId = -1
            rightStick.knobX = rightStick.cx; rightStick.knobY = rightStick.cy
            InputRecorder.sendAxis(rightStick.axisX, 0)
            InputRecorder.sendAxis(rightStick.axisY, 0)
        }
        // Buttons
        for (b in buttons) {
            if (b.pointerId == pid) {
                b.pressed = false; b.pointerId = -1
                InputRecorder.sendButtonUp(b.mask)
            }
        }
    }

    private fun updateStick(stick: StickDef, touchX: Float, touchY: Float) {
        val dx = touchX - stick.cx
        val dy = touchY - stick.cy
        val d  = sqrt(dx * dx + dy * dy)
        val clamped = min(d, stick.outerR)
        val nx = if (d > 0f) dx / d else 0f
        val ny = if (d > 0f) dy / d else 0f
        stick.knobX = stick.cx + nx * clamped
        stick.knobY = stick.cy + ny * clamped
        val norm = clamped / stick.outerR  // 0..1
        // xemu internal convention: positive Y = up. Android screen Y increases downward,
        // so ny > 0 means touch below center (stick pushed down). Negate to match xemu.
        InputRecorder.sendAxis(stick.axisX, (nx * norm * NativeInterface.AXIS_MAX).toInt())
        InputRecorder.sendAxis(stick.axisY, (-ny * norm * NativeInterface.AXIS_MAX).toInt())
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
