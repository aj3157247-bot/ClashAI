package com.aiplayer.companion

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The small control panel that floats on top of the game: a Start / Stop button, the mode, and what the bot
 * is doing right now. It is drawn as an accessibility overlay, so it needs no "display over other apps"
 * permission. Drag the title to move it.
 */
class BotHud(private val service: AutoPlayAccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var dot: View? = null
    private var title: TextView? = null
    private var status: TextView? = null
    private var toggle: Button? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (root != null) handler.postDelayed(this, REFRESH_MS)
        }
    }

    fun isShown(): Boolean = root != null

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    private fun round(fillColor: Int, strokeColor: Int, radiusDp: Int, strokeDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fillColor)
        g.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    fun show() {
        if (root != null) return
        val panel = LinearLayout(service)
        panel.orientation = LinearLayout.VERTICAL
        panel.layoutDirection = View.LAYOUT_DIRECTION_RTL
        panel.setPadding(dp(10), dp(8), dp(10), dp(8))
        panel.background = round(Color.argb(228, 12, 18, 30), Color.rgb(0, 229, 255), 14, 2)

        // Header: status dot + title (drag handle).
        val header = LinearLayout(service)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val d = View(service)
        d.background = round(Color.RED, Color.WHITE, 8, 0)
        val dotLp = LinearLayout.LayoutParams(dp(12), dp(12))
        dotLp.marginEnd = dp(8)
        header.addView(d, dotLp)

        val t = TextView(service)
        t.setTextColor(Color.WHITE)
        t.textSize = 13f
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        header.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(dp(236), LinearLayout.LayoutParams.WRAP_CONTENT))

        val s = TextView(service)
        s.setTextColor(Color.rgb(200, 230, 255))
        s.textSize = 12f
        s.maxLines = 4
        val sLp = LinearLayout.LayoutParams(dp(236), LinearLayout.LayoutParams.WRAP_CONTENT)
        sLp.topMargin = dp(6)
        panel.addView(s, sLp)

        val row = LinearLayout(service)
        row.orientation = LinearLayout.HORIZONTAL
        val b = Button(service)
        b.textSize = 13f
        b.setTextColor(Color.WHITE)
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(8), dp(6), dp(8), dp(6))
        b.setOnClickListener {
            if (AgentLoop.isRunning()) AgentLoop.stop() else AgentLoop.start()
            refresh()
        }
        row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val hide = Button(service)
        hide.text = "پنهان"
        hide.textSize = 12f
        hide.setTextColor(Color.WHITE)
        hide.minHeight = 0
        hide.minimumHeight = 0
        hide.setPadding(dp(8), dp(6), dp(8), dp(6))
        hide.background = round(Color.argb(200, 70, 80, 100), Color.TRANSPARENT, 10, 0)
        hide.setOnClickListener { service.hideHud() }
        val hideLp = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
        hideLp.marginStart = dp(8)
        row.addView(hide, hideLp)

        val rowLp = LinearLayout.LayoutParams(dp(236), LinearLayout.LayoutParams.WRAP_CONTENT)
        rowLp.topMargin = dp(8)
        panel.addView(row, rowLp)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val screenW = service.resources.displayMetrics.widthPixels
        lp.x = if (savedX >= 0) savedX else (screenW - dp(236) - dp(28)).coerceAtLeast(0)
        lp.y = if (savedY >= 0) savedY else dp(56)

        // Dragging by the header.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        header.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (ev.rawX - downX)).toInt()
                    lp.y = (startY + (ev.rawY - downY)).toInt()
                    savedX = lp.x
                    savedY = lp.y
                    try {
                        wm.updateViewLayout(panel, lp)
                    } catch (e: Exception) {
                        // The view was removed while dragging.
                    }
                    true
                }
                else -> true
            }
        }

        try {
            wm.addView(panel, lp)
        } catch (e: Exception) {
            AdviceLog.error("نمایش پنل ربات ممکن نشد: ${e.message}")
            return
        }
        root = panel
        params = lp
        dot = d
        title = t
        status = s
        toggle = b
        refresh()
        handler.postDelayed(ticker, REFRESH_MS)
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        val r = root
        if (r != null) {
            try {
                wm.removeView(r)
            } catch (e: Exception) {
                // already removed
            }
        }
        root = null
        params = null
        dot = null
        title = null
        status = null
        toggle = null
    }

    private fun refresh() {
        val running = AgentLoop.isRunning()
        dot?.background = round(if (running) Color.rgb(0, 220, 90) else Color.rgb(240, 60, 60), Color.WHITE, 8, 0)
        title?.text = "AI Player · " + AiSettings.modeLabel()
        status?.text = AdviceLog.lastText.take(170)
        val b = toggle
        if (b != null) {
            b.text = if (running) "توقف ربات" else "شروع بازی"
            b.background = round(
                if (running) Color.rgb(200, 45, 45) else Color.rgb(20, 150, 80),
                Color.TRANSPARENT, 10, 0
            )
        }
    }

    companion object {
        private const val REFRESH_MS = 400L

        @Volatile private var savedX = -1
        @Volatile private var savedY = -1
    }
}
