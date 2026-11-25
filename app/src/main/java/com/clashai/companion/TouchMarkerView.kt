package com.aiplayer.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Draws a pink circle with the pixel coordinates at each point the bot touches.
 * pts = x1, y1 [, x2, y2]. Two points are joined by a line (a swipe).
 */
class TouchMarkerView(context: Context, private val pts: FloatArray) : View(context) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 0, 200)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 0, 200)
        style = Paint.Style.FILL
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        if (pts.size >= 4) canvas.drawLine(pts[0], pts[1], pts[2], pts[3], ring)
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            canvas.drawCircle(x, y, 48f, fill)
            canvas.drawCircle(x, y, 48f, ring)
            canvas.drawCircle(x, y, 7f, dot)
            canvas.drawText("${x.toInt()},${y.toInt()}", x + 58f, y - 58f, label)
            i += 2
        }
    }
}
