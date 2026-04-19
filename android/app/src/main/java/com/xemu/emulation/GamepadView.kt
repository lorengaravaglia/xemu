package com.xemu.emulation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xemu.NativeInterface
import kotlin.math.*

/**
 * Draws a semi-transparent Xbox controller overlay and translates touch
 * events into NativeInterface.sendButtonDown/Up and sendAxis calls.
 *
 * Layout (landscape):
 *   Left side  : D-pad + Left stick
 *   Center     : Back / Start / Guide
 *   Right side : Face buttons (A/B/X/Y) + Right stick
 *   Shoulder   : LT/LB (top-left), RT/RB (top-right)
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
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 80, 80, 80)
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
        ButtonDef(NativeInterface.BUTTON_START, "⏵"),
        ButtonDef(NativeInterface.BUTTON_BACK,  "⏴"),
        // Shoulder / bumpers
        ButtonDef(NativeInterface.BUTTON_WHITE, "LB"),
        ButtonDef(NativeInterface.BUTTON_BLACK, "RB"),
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
        val btnR   = h * 0.07f   // face / dpad button radius
        val stickR = h * 0.12f   // stick outer radius

        // ── Left stick (lower-left quadrant) ─────────────────────────────────
        leftStick.cx = w * 0.15f
        leftStick.cy = h * 0.65f
        leftStick.outerR = stickR
        leftStick.knobX = leftStick.cx
        leftStick.knobY = leftStick.cy

        // ── D-pad (upper-left) ────────────────────────────────────────────────
        val dpadCx = w * 0.12f
        val dpadCy = h * 0.38f
        btn(NativeInterface.BUTTON_DPAD_UP).apply    { cx = dpadCx;          cy = dpadCy - btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_DPAD_DOWN).apply  { cx = dpadCx;          cy = dpadCy + btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_DPAD_LEFT).apply  { cx = dpadCx - btnR * 1.8f; cy = dpadCy;          radius = btnR }
        btn(NativeInterface.BUTTON_DPAD_RIGHT).apply { cx = dpadCx + btnR * 1.8f; cy = dpadCy;          radius = btnR }

        // ── Face buttons (right side) ─────────────────────────────────────────
        val faceCx = w * 0.82f
        val faceCy = h * 0.42f
        btn(NativeInterface.BUTTON_Y).apply { cx = faceCx;          cy = faceCy - btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_A).apply { cx = faceCx;          cy = faceCy + btnR * 1.8f; radius = btnR }
        btn(NativeInterface.BUTTON_X).apply { cx = faceCx - btnR * 1.8f; cy = faceCy;          radius = btnR }
        btn(NativeInterface.BUTTON_B).apply { cx = faceCx + btnR * 1.8f; cy = faceCy;          radius = btnR }

        // ── Right stick (lower-right) ─────────────────────────────────────────
        rightStick.cx = w * 0.72f
        rightStick.cy = h * 0.65f
        rightStick.outerR = stickR
        rightStick.knobX = rightStick.cx
        rightStick.knobY = rightStick.cy

        // ── Center buttons ─────────────────────────────────────────────────────
        val centerY = h * 0.25f
        btn(NativeInterface.BUTTON_BACK).apply  { cx = w * 0.42f; cy = centerY; radius = btnR * 0.75f }
        btn(NativeInterface.BUTTON_START).apply { cx = w * 0.58f; cy = centerY; radius = btnR * 0.75f }

        // ── Shoulder / bumpers ─────────────────────────────────────────────────
        val bumperW = w * 0.10f
        val bumperH = h * 0.07f
        btn(NativeInterface.BUTTON_WHITE).apply { cx = w * 0.10f; cy = h * 0.08f; radius = bumperW * 0.5f }
        btn(NativeInterface.BUTTON_BLACK).apply { cx = w * 0.90f; cy = h * 0.08f; radius = bumperW * 0.5f }

        // ── Triggers ──────────────────────────────────────────────────────────
        lt.rect = RectF(w * 0.02f, h * 0.01f, w * 0.14f, h * 0.06f + bumperH)
        rt.rect = RectF(w * 0.86f, h * 0.01f, w * 0.98f, h * 0.06f + bumperH)
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
            NativeInterface.sendAxis(lt.axis, NativeInterface.AXIS_MAX)
            return
        }
        if (rt.rect.contains(x, y) && rt.pointerId == -1) {
            rt.pressed = true; rt.pointerId = pid
            NativeInterface.sendAxis(rt.axis, NativeInterface.AXIS_MAX)
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
                NativeInterface.sendButtonDown(b.mask)
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
            NativeInterface.sendAxis(lt.axis, 0)
        }
        if (rt.pointerId == pid) {
            rt.pressed = false; rt.pointerId = -1
            NativeInterface.sendAxis(rt.axis, 0)
        }
        // Sticks
        if (leftStick.pointerId == pid) {
            leftStick.pointerId = -1
            leftStick.knobX = leftStick.cx; leftStick.knobY = leftStick.cy
            NativeInterface.sendAxis(leftStick.axisX, 0)
            NativeInterface.sendAxis(leftStick.axisY, 0)
        }
        if (rightStick.pointerId == pid) {
            rightStick.pointerId = -1
            rightStick.knobX = rightStick.cx; rightStick.knobY = rightStick.cy
            NativeInterface.sendAxis(rightStick.axisX, 0)
            NativeInterface.sendAxis(rightStick.axisY, 0)
        }
        // Buttons
        for (b in buttons) {
            if (b.pointerId == pid) {
                b.pressed = false; b.pointerId = -1
                NativeInterface.sendButtonUp(b.mask)
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
        NativeInterface.sendAxis(stick.axisX, (nx * norm * NativeInterface.AXIS_MAX).toInt())
        NativeInterface.sendAxis(stick.axisY, (-ny * norm * NativeInterface.AXIS_MAX).toInt())
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
