package com.aiplayer.companion

import android.graphics.Bitmap
import android.media.Image
import java.util.concurrent.atomic.AtomicReference

/**
 * Passes the most recent screen frame from ScreenCaptureService to the agent.
 * Only the latest frame is kept; older unread frames are dropped.
 */
object FrameBus {
    private val latest = AtomicReference<Bitmap?>(null)
    private const val MIN_INTERVAL_MS = 200L
    @Volatile private var lastPublishedAt = 0L

    fun publish(bitmap: Bitmap) {
        latest.getAndSet(bitmap)?.let { if (!it.isRecycled) it.recycle() }
    }

    /** Converts a capture Image to a Bitmap, rate-limited to about 5 frames per second. */
    fun publishImage(image: Image) {
        val now = System.currentTimeMillis()
        if (now - lastPublishedAt < MIN_INTERVAL_MS) return
        lastPublishedAt = now

        val plane = image.planes.firstOrNull() ?: return
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val wide = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        wide.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
        wide.recycle()
        publish(cropped)
    }

    /** Returns the latest frame and clears the slot. The caller must recycle it. */
    fun take(): Bitmap? = latest.getAndSet(null)
}
