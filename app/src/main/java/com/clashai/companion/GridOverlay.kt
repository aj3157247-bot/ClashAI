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

    fun render(frame: Bitmap, targetW: Int, grid: Boolean, board: BoardDetector.Geo? = null): Bitmap {
        val h = (frame.height.toFloat() * targetW / frame.width).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            frame,
            Rect(0, 0, frame.width, frame.height),
            Rect(0, 0, targetW, h),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        if (board != null) drawBoard(canvas, board, targetW.toFloat() / frame.width)
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

    /** Draws the 8 x 8 cells of a detected board with labels A1 (top-left) to H8 (bottom-right). */
    private fun drawBoard(canvas: Canvas, b: BoardDetector.Geo, scale: Float) {
        val line = Paint().apply {
            color = Color.argb(160, 0, 230, 255)
            strokeWidth = 2f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 0, 255, 255)
            textSize = b.cell * scale * 0.22f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val x0 = b.x0 * scale
        val y0 = b.y0 * scale
        val c = b.cell * scale
        for (i in 0..8) {
            canvas.drawLine(x0 + i * c, y0, x0 + i * c, y0 + 8 * c, line)
            canvas.drawLine(x0, y0 + i * c, x0 + 8 * c, y0 + i * c, line)
        }
        for (r in 0..7) {
            for (col in 0..7) {
                val label = ('A' + col).toString() + (r + 1).toString()
                canvas.drawText(label, x0 + col * c + 4f, y0 + r * c + text.textSize, text)
            }
        }
    }
}
