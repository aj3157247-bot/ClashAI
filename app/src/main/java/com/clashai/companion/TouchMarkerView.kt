package com.aiplayer.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One touch the bot makes, in screen pixels. kind: 0 = tap, 1 = hold, 2 = swipe. */
class MarkerItem(
    val kind: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val durationMs: Long
)

/**
 * Draws where and how the bot touches the screen, so a person can follow every touch.
 *
 *  - tap   : a bright pink dot with three rings that spread out (like a ripple)
 *  - hold  : a cyan dot with a ring that fills up while the finger stays down
 *  - swipe : an orange line with an arrow, and a full dot at the end
 *
 * Every touch gets a number (when there are several at once), its kind in words and its pixel position,
 * written on a dark label so it can be read on any game background.
 * The view lives for `lifeMs` and animates itself; the service removes it afterwards.
 */
class TouchMarkerView(
    context: Context,
    private val items: List<MarkerItem>,
    private val lifeMs: Long
) : View(context) {

    private val born = SystemClock.uptimeMillis()
    private val dens = context.resources.displayMetrics.density

    private val pink = Color.rgb(255, 30, 200)
    private val cyan = Color.rgb(0, 229, 255)
    private val orange = Color.rgb(255, 171, 0)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * dens
        isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 10, 14, 24)
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val box = RectF()

    private fun colorOf(kind: Int): Int = when (kind) {
        1 -> cyan
        2 -> orange
        else -> pink
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((255f * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        val elapsed = SystemClock.uptimeMillis() - born
        val life = if (lifeMs < 1L) 1L else lifeMs
        val p = (elapsed.toFloat() / life).coerceIn(0f, 1f)
        // Stays fully visible for most of its life, then fades out.
        val fade = if (p > 0.8f) (1f - p) / 0.2f else 1f

        for (i in items.indices) {
            val m = items[i]
            val c = colorOf(m.kind)
            if (m.kind == 2) drawSwipe(canvas, m, c, fade)
            drawPoint(canvas, m, c, fade, elapsed)
            drawLabel(canvas, m, i, c, fade)
        }
        if (elapsed < life) postInvalidateOnAnimation()
    }

    private fun drawPoint(canvas: Canvas, m: MarkerItem, c: Int, fade: Float, elapsed: Long) {
        val x = m.x1
        val y = m.y1
        val base = 26f * dens

        // Spreading rings (a new ring every 280 ms, each lives 900 ms).
        for (k in 0..2) {
            val age = elapsed - k * 280L
            if (age < 0L) continue
            val t = (age % 900L).toFloat() / 900f
            val r = base + base * 1.9f * t
            ring.strokeWidth = 4f * dens
            ring.color = withAlpha(c, (1f - t) * 0.9f * fade)
            canvas.drawCircle(x, y, r, ring)
        }

        // Solid disc with a black edge, so it is visible on white and on dark boards.
        fill.color = withAlpha(c, 0.38f * fade)
        canvas.drawCircle(x, y, base, fill)
        rim.strokeWidth = 7f * dens
        rim.color = withAlpha(Color.BLACK, 0.55f * fade)
        canvas.drawCircle(x, y, base, rim)
        ring.strokeWidth = 4.5f * dens
        ring.color = withAlpha(c, fade)
        canvas.drawCircle(x, y, base, ring)

        if (m.kind == 1) {
            // The ring fills up while the finger is held down.
            val hold = if (m.durationMs < 1L) 1L else m.durationMs
            val prog = (elapsed.toFloat() / hold).coerceIn(0f, 1f)
            ring.strokeWidth = 7f * dens
            ring.color = withAlpha(Color.WHITE, fade)
            box.set(x - base * 1.25f, y - base * 1.25f, x + base * 1.25f, y + base * 1.25f)
            canvas.drawArc(box, -90f, 360f * prog, false, ring)
        }

        // The center point: white with a black rim.
        fill.color = withAlpha(Color.WHITE, fade)
        canvas.drawCircle(x, y, 7f * dens, fill)
        rim.strokeWidth = 2f * dens
        rim.color = withAlpha(Color.BLACK, fade)
        canvas.drawCircle(x, y, 7f * dens, rim)
    }

    private fun drawSwipe(canvas: Canvas, m: MarkerItem, c: Int, fade: Float) {
        val dx = m.x2 - m.x1
        val dy = m.y2 - m.y1
        val len = hypot(dx, dy)
        if (len < 1f) return

        // Dark under-line, then the colored line on top.
        ring.strokeWidth = 12f * dens
        ring.color = withAlpha(Color.BLACK, 0.5f * fade)
        canvas.drawLine(m.x1, m.y1, m.x2, m.y2, ring)
        ring.strokeWidth = 8f * dens
        ring.color = withAlpha(c, fade)
        canvas.drawLine(m.x1, m.y1, m.x2, m.y2, ring)

        // Arrow head at the end.
        val ang = atan2(dy, dx)
        val head = 22f * dens
        path.reset()
        path.moveTo(m.x2, m.y2)
        path.lineTo(m.x2 - head * cos(ang - 0.45f), m.y2 - head * sin(ang - 0.45f))
        path.lineTo(m.x2 - head * cos(ang + 0.45f), m.y2 - head * sin(ang + 0.45f))
        path.close()
        fill.color = withAlpha(c, fade)
        canvas.drawPath(path, fill)

        // End dot (the finger lifts here).
        fill.color = withAlpha(Color.WHITE, fade)
        canvas.drawCircle(m.x2, m.y2, 9f * dens, fill)
        rim.strokeWidth = 3f * dens
        rim.color = withAlpha(c, fade)
        canvas.drawCircle(m.x2, m.y2, 9f * dens, rim)
    }

    private fun drawLabel(canvas: Canvas, m: MarkerItem, index: Int, c: Int, fade: Float) {
        val name = when (m.kind) {
            1 -> "نگه‌داشتن " + String.format("%.1f", m.durationMs / 1000f) + "s"
            2 -> "کشیدن"
            else -> "لمس"
        }
        val num = if (items.size > 1) "${index + 1}  " else ""
        val line = num + name + "  " + m.x1.toInt() + "," + m.y1.toInt()
        val w = text.measureText(line)
        val padX = 9f * dens
        val h = text.textSize + 12f * dens

        var left = m.x1 + 34f * dens
        if (left + w + 2 * padX > width) left = m.x1 - 34f * dens - w - 2 * padX
        if (left < 4f * dens) left = 4f * dens
        var top = m.y1 - 62f * dens
        if (top < 4f * dens) top = m.y1 + 40f * dens

        box.set(left, top, left + w + 2 * padX, top + h)
        labelBg.alpha = (220f * fade).toInt()
        canvas.drawRoundRect(box, 10f * dens, 10f * dens, labelBg)
        ring.strokeWidth = 2f * dens
        ring.color = withAlpha(c, fade)
        canvas.drawRoundRect(box, 10f * dens, 10f * dens, ring)
        text.alpha = (255f * fade).toInt()
        canvas.drawText(line, left + padX, top + h - 8f * dens, text)
    }
}
