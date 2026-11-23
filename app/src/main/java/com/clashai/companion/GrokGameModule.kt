package com.aiplayer.companion

import android.graphics.Bitmap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * General module for any game. It sends the screen to Grok, gets one action,
 * and performs it. It is used when no game-specific module matches.
 *
 * Requests are rate limited: one request at a time, and only when the screen has changed.
 */
class GrokGameModule : GameModule {

    override val id: String = "grok_general"

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var pending = false

    // Guarded by `this`.
    private var pendingAction: GameAction? = null

    private var lastRequestAt = 0L
    private var lastActionAt = 0L
    private var lastSignature: IntArray? = null

    override fun matches(packageName: String?, frame: Bitmap): Boolean =
        AiSettings.apiKey.isNotBlank() && packageName != null

    override fun decide(frame: Bitmap): GameAction {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < ACTION_GAP_MS) return GameAction.Wait

        synchronized(this) {
            val ready = pendingAction
            if (ready != null) {
                pendingAction = null
                lastActionAt = now
                return ready
            }
        }

        val sig = signature(frame)
        val previous = lastSignature
        val changed = previous == null || diff(previous, sig) > CHANGE_THRESHOLD

        if (!pending && changed && now - lastRequestAt > REQUEST_GAP_MS) {
            lastRequestAt = now
            lastSignature = sig
            pending = true

            // The frame is recycled by AgentLoop after this call, so send a copy.
            val small = scaledCopy(frame)
            val key = AiSettings.apiKey
            val model = AiSettings.model

            worker.execute {
                try {
                    val advice = GrokClient.askForAction(key, model, small)
                    AdviceLog.update(advice)
                    val action = toAction(advice)
                    if (action != null) {
                        synchronized(this) { pendingAction = action }
                    }
                } catch (e: Exception) {
                    AdviceLog.error(e.message ?: "خطای نامشخص")
                } finally {
                    small.recycle()
                    pending = false
                }
            }
        }
        return GameAction.Wait
    }

    private fun toAction(a: Advice): GameAction? {
        val x1 = a.x.coerceIn(0f, 1f)
        val y1 = a.y.coerceIn(0f, 1f)
        return when (a.type) {
            "tap" -> GameAction.Tap(x1, y1)
            "hold" -> GameAction.Hold(x1, y1)
            "swipe" -> GameAction.Swipe(x1, y1, a.x2.coerceIn(0f, 1f), a.y2.coerceIn(0f, 1f))
            else -> null
        }
    }

    /** A coarse brightness grid. Used to detect whether the screen has changed. */
    private fun signature(frame: Bitmap): IntArray {
        val out = IntArray(GRID * GRID)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val x = ((gx + 0.5f) / GRID * frame.width).toInt().coerceIn(0, frame.width - 1)
                val y = ((gy + 0.5f) / GRID * frame.height).toInt().coerceIn(0, frame.height - 1)
                val p = frame.getPixel(x, y)
                out[gy * GRID + gx] = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            }
        }
        return out
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / (a.size * 3)
    }

    private fun scaledCopy(frame: Bitmap): Bitmap {
        val w = 540
        val h = (frame.height.toFloat() * w / frame.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        // createScaledBitmap may return the same object. Make a real copy in that case.
        return if (scaled === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    companion object {
        private const val ACTION_GAP_MS = 1200L
        private const val REQUEST_GAP_MS = 2500L
        private const val CHANGE_THRESHOLD = 6
        private const val GRID = 16
    }
}
