package com.aiplayer.companion

import android.graphics.Bitmap

/**
 * Fixed screen layout of a Clash Royale battle, measured from a real 1080 x 2408 screenshot and stored as fractions
 * of the screen, so it scales to other screens with the same shape.
 *
 * With it the controller can (1) read the exact elixir from the purple bar in a few milliseconds, with no cloud call,
 * and (2) turn "card slot 2, left lane, at the bridge" into an exact touch. The cloud AI only has to choose; it no
 * longer has to guess pixel coordinates, which was the main reason cards were dropped in wrong places.
 */
object ClashLayout {

    // Hand: 4 cards in the bottom tray, counted from the left.
    private val SLOT_X = floatArrayOf(0.312f, 0.500f, 0.687f, 0.873f)
    private const val SLOT_Y = 0.914f

    // The elixir bar.
    private const val BAR_Y = 0.985f
    private const val BAR_START = 0.215f   // x where the bar starts (under the drop icon)
    private const val BAR_END = 0.960f     // x where a full bar ends
    private const val BAR_SCAN_FROM = 0.30f

    // Own half of the arena (the river is at about y = 0.435).
    const val OWN_TOP = 0.47f
    const val OWN_BOTTOM = 0.72f
    const val ARENA_TOP = 0.16f
    const val TRAY_TOP = 0.86f

    fun slotCenter(slot: Int): FloatArray {
        val i = (slot - 1).coerceIn(0, 3)
        return floatArrayOf(SLOT_X[i], SLOT_Y)
    }

    /**
     * Deploy spots on the player's half that are free of towers.
     * lane: left | right | center. where: bridge (own side of the bridge) | mid (in front of the princess tower) | back (beside the tower, near the king).
     */
    fun spot(lane: String, where: String): FloatArray? {
        val l = lane.lowercase().trim()
        val w = where.lowercase().trim().ifEmpty { "mid" }
        return when (l) {
            "left" -> when (w) {
                "bridge" -> floatArrayOf(0.23f, 0.49f)
                "back" -> floatArrayOf(0.38f, 0.60f)
                else -> floatArrayOf(0.26f, 0.54f)
            }
            "right" -> when (w) {
                "bridge" -> floatArrayOf(0.78f, 0.49f)
                "back" -> floatArrayOf(0.62f, 0.60f)
                else -> floatArrayOf(0.75f, 0.54f)
            }
            "center", "middle" -> when (w) {
                "bridge" -> floatArrayOf(0.50f, 0.49f)
                "back" -> floatArrayOf(0.50f, 0.58f)
                else -> floatArrayOf(0.50f, 0.54f)
            }
            else -> null
        }
    }

    private fun isFill(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r > 140 && b > 140 && g < 130 && r - g > 80
    }

    private fun isEmpty(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r < 100 && b > 110 && b - r > 50 && g < 120
    }

    /**
     * Elixir from 0.0 to 10.0 (the fraction counts too), or -1 when no battle elixir bar is on the screen
     * (menus, result screens, other games).
     */
    fun elixirOf(frame: Bitmap): Float {
        return try {
            val w = frame.width
            val h = frame.height
            val y = (BAR_Y * h).toInt().coerceIn(0, h - 1)
            val x0 = (BAR_SCAN_FROM * w).toInt()
            val start = frame.getPixel(x0, y)
            if (!isFill(start)) {
                return if (isEmpty(start)) 0.5f else -1f
            }
            var last = x0
            var miss = 0
            var x = x0
            val limit = (0.975f * w).toInt()
            while (x < limit) {
                if (isFill(frame.getPixel(x, y))) {
                    last = x
                    miss = 0
                } else {
                    miss++
                    if (miss > w / 40) break   // the empty part of the bar
                }
                x += 2
            }
            val span = (BAR_END - BAR_START) * w
            ((last - BAR_START * w) / span * 10f).coerceIn(0f, 10f)
        } catch (e: Exception) {
            -1f
        }
    }
}
