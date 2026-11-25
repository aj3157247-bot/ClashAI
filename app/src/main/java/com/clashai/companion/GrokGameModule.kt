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
    @Volatile private var waitStreak = 0
    @Volatile private var backoffUntil = 0L
    private var lastActionAt = 0L
    private var lastSignature: IntArray? = null

    override fun matches(packageName: String?, frame: Bitmap): Boolean =
        AiSettings.mode == AiSettings.MODE_ONLINE && AiSettings.apiKey.isNotBlank()

    override fun decide(frame: Bitmap): GameAction {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < ACTION_GAP_MS) return GameAction.Wait
        if (now < backoffUntil) return GameAction.Wait

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

        // Ask again when the screen changed, or after a quiet period. The quiet period stops the bot
        // from freezing when a tap did nothing and the screen stayed the same.
        val heartbeat = now - lastRequestAt > HEARTBEAT_MS
        if (!pending && (changed || heartbeat) && now - lastRequestAt > REQUEST_GAP_MS) {
            lastRequestAt = now
            lastSignature = sig
            pending = true

            // The frame is recycled by AgentLoop after this call, so send a copy.
            val small = scaledCopy(frame)
            val sentW = small.width.toFloat()
            val sentH = small.height.toFloat()
            val key = AiSettings.apiKey
            val model = AiSettings.model
            val endpoint = AiSettings.endpoint

            AdviceLog.info("در حال ارسال تصویر بازی به هوش مصنوعی...")
            worker.execute {
                try {
                    val hint = if (waitStreak >= 2)
                        "The screen has not changed for several checks. If a roll, pass, end-turn, or next button is visible anywhere on the screen, tap it now. Do not answer wait."
                    else ""
                    val advice = GrokClient.askForAction(key, endpoint, model, small, hint)
                    AdviceLog.update(advice)
                    val action = toAction(advice, sentW, sentH)
                    AdviceLog.info(AdviceLog.lastText + " | " + describe(action))
                    waitStreak = if (action == null) waitStreak + 1 else 0
                    if (action != null) {
                        synchronized(this) { pendingAction = action }
                    }
                } catch (e: RateLimitException) {
                    backoffUntil = System.currentTimeMillis() + e.retryAfterSeconds * 1000L
                    AdviceLog.error("سقف سرعت Groq پر شد؛ ${e.retryAfterSeconds} ثانیه صبر می‌کند")
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

    /**
     * Converts the model's answer into a tap. The model is told to use 0..1, but sometimes it
     * answers in pixels of the image it saw. Values above 1 are treated as pixels and divided by the image size.
     */
    private fun toAction(a: Advice, imageW: Float, imageH: Float): GameAction? {
        fun norm(v: Float, size: Float): Float = (if (v > 1f) v / size else v).coerceIn(0f, 1f)
        val x1 = norm(a.x, imageW)
        val y1 = norm(a.y, imageH)
        return when (a.type) {
            "tap" -> GameAction.Tap(x1, y1)
            "hold" -> GameAction.Hold(x1, y1)
            "swipe" -> GameAction.Swipe(x1, y1, norm(a.x2, imageW), norm(a.y2, imageH))
            else -> null
        }
    }

    private fun describe(action: GameAction?): String = when (action) {
        is GameAction.Tap -> "لمس روی (%.2f, %.2f)".format(action.x, action.y)
        is GameAction.Hold -> "نگه‌داشتن روی (%.2f, %.2f)".format(action.x, action.y)
        is GameAction.Swipe -> "کشیدن از (%.2f, %.2f) تا (%.2f, %.2f)".format(action.x1, action.y1, action.x2, action.y2)
        else -> "بدون لمس (wait)"
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
        val w = 1080
        val h = (frame.height.toFloat() * w / frame.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        // createScaledBitmap may return the same object. Make a real copy in that case.
        return if (scaled === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    companion object {
        private const val ACTION_GAP_MS = 1200L
        private const val REQUEST_GAP_MS = 12000L
        private const val HEARTBEAT_MS = 25000L
        private const val CHANGE_THRESHOLD = 6
        private const val GRID = 16
    }
}
