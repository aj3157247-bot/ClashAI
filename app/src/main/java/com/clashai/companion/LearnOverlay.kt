package com.aiplayer.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot

/**
 * A transparent layer over the whole screen that is used only while the bot is LEARNING.
 *
 * It sees every touch of the user, tells the LearningCoach what the user did, and then plays the same touch into the
 * game through the accessibility service, so that the game still reacts. While the copy is played, the layer lets touches
 * pass through (otherwise it would record its own copy).
 *
 * Because the real finger is absorbed by the layer, the game receives the touch a moment after the finger is lifted
 * (about 40 ms for a tap, the length of the stroke for a swipe). This is fine for turn based and tap games. It is not
 * meant for games that need continuous steering in real time. Only one finger is recorded.
 */
class LearnOverlay(private val service: AutoPlayAccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private var downUptime = 0L
    private var downWall = 0L
    private var multi = false
    private var active = false

    fun isShown(): Boolean = view != null

    fun show() {
        if (view != null) return
        val v = object : View(service) {
            override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            AdviceLog.error("لایه‌ی یادگیری ساخته نشد: ${e.message}")
            return
        }
        view = v
        lp = p
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val v = view
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                // already removed
            }
        }
        view = null
        lp = null
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                xs.clear()
                ys.clear()
                xs.add(ev.rawX)
                ys.add(ev.rawY)
                downUptime = SystemClock.uptimeMillis()
                downWall = System.currentTimeMillis()
                multi = false
                active = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_MOVE -> {
                if (active && !multi && xs.size < 600) {
                    xs.add(ev.rawX)
                    ys.add(ev.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (active) {
                    active = false
                    xs.add(ev.rawX)
                    ys.add(ev.rawY)
                    finish()
                }
            }
            MotionEvent.ACTION_CANCEL -> active = false
        }
        return true
    }

    private fun finish() {
        val size = service.displaySizePx()
        val w = size[0]
        val h = size[1]
        if (w <= 0f || h <= 0f || xs.isEmpty()) return
        val dur = (SystemClock.uptimeMillis() - downUptime).coerceIn(40L, 6000L)

        var length = 0f
        for (i in 1 until xs.size) length += hypot((xs[i] - xs[i - 1]) / w, (ys[i] - ys[i - 1]) / h)
        val x1 = xs.first() / w
        val y1 = ys.first() / h
        val x2 = xs.last() / w
        val y2 = ys.last() / h
        val type = when {
            length >= 0.02f -> LearningCoach.T_SWIPE
            dur >= 450L -> LearningCoach.T_HOLD
            else -> LearningCoach.T_TAP
        }
        if (!multi) {
            LearningCoach.onUserGesture(service.currentPackage(), type, x1, y1, x2, y2, dur, downWall)
        }

        // Copy the path (at most 30 points) before it is cleared by the next touch.
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        val step = if (xs.size <= 30) 1 else xs.size / 30
        var i = 0
        while (i < xs.size) {
            px.add(xs[i])
            py.add(ys[i])
            i += step
        }
        px.add(xs.last())
        py.add(ys.last())
        replay(px, py, if (type == LearningCoach.T_TAP) dur.coerceIn(40L, 200L) else dur)
    }

    private fun setTouchable(on: Boolean) {
        val v = view ?: return
        val p = lp ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
            // The layer was removed meanwhile.
        }
    }

    /** Plays the user's touch into the game. */
    private fun replay(px: List<Float>, py: List<Float>, dur: Long) {
        setTouchable(false)
        handler.postDelayed({
            try {
                val path = Path()
                path.moveTo(px[0], py[0])
                for (i in 1 until px.size) path.lineTo(px[i], py[i])
                val stroke = GestureDescription.StrokeDescription(path, 0L, dur.coerceAtLeast(40L))
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val ok = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            setTouchable(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            setTouchable(true)
                        }
                    },
                    handler
                )
                if (!ok) setTouchable(true)
            } catch (e: Exception) {
                setTouchable(true)
            }
        }, 35L)
        // Safety: never leave the screen untouchable.
        handler.postDelayed({ setTouchable(true) }, dur + 1000L)
    }
}
