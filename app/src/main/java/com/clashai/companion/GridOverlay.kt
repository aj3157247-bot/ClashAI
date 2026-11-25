package com.aiplayer.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * Prepares the screenshot for the AI: scales it, and optionally draws a thin labeled grid.
 * Vision models are much more accurate at pointing when the picture itself shows coordinates.
 */
object GridOverlay {

    fun render(frame: Bitmap, targetW: Int, grid: Boolean): Bitmap {
        val h = (frame.height.toFloat() * targetW / frame.width).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            frame,
            Rect(0, 0, frame.width, frame.height),
            Rect(0, 0, targetW, h),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        if (!grid) return out

        val line = Paint().apply {
            color = Color.argb(95, 255, 0, 200)
            strokeWidth = 1.5f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = targetW * 0.024f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        for (i in 1..9) {
            val f = i / 10f
            val x = f * targetW
            val y = f * h
            canvas.drawLine(x, 0f, x, h.toFloat(), line)
            canvas.drawLine(0f, y, targetW.toFloat(), y, line)
            val label = "0." + i
            canvas.drawText(label, x + 3f, text.textSize, text)
            canvas.drawText(label, x + 3f, h - 6f, text)
            canvas.drawText(label, 4f, y - 3f, text)
            canvas.drawText(label, targetW - text.measureText(label) - 4f, y - 3f, text)
        }
        return out
    }
}
