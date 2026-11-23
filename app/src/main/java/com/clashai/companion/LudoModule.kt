package com.aiplayer.companion

import android.graphics.Bitmap

/**
 * First version of the Ludo module (offline, against the computer).
 *
 * Coordinates are fractions of the screen (0..1), calibrated from a 1080x2400 screenshot.
 * If the layout is different on your phone, change only the values in LudoLayout.
 *
 * Limitations of this version:
 * - It does not read the dice value, so it does not know which move is legal.
 * - It taps the dice, then tries the home positions one by one. The game ignores illegal taps.
 * - It does not track pieces on the track yet.
 */
class LudoModule : GameModule {

    override val id: String = "ludo_offline"

    private var phase = 0
    private var homeIndex = 0
    private var lastActionAt = 0L

    override fun matches(packageName: String?, frame: Bitmap): Boolean {
        if (packageName?.lowercase()?.contains("ludo") == true) return true
        return looksLikeLudoBoard(frame)
    }

    override fun decide(frame: Bitmap): GameAction {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < LudoLayout.ACTION_COOLDOWN_MS) return GameAction.Wait
        lastActionAt = now

        return when (phase) {
            0 -> {
                // Roll the dice.
                phase = 1
                homeIndex = 0
                GameAction.Tap(LudoLayout.DICE_X, LudoLayout.DICE_Y)
            }
            else -> {
                // Try our home pieces one by one. After the four tries, roll again.
                val spot = LudoLayout.HOME_SPOTS[homeIndex]
                homeIndex++
                if (homeIndex >= LudoLayout.HOME_SPOTS.size) {
                    phase = 0
                }
                GameAction.Tap(spot.first, spot.second)
            }
        }
    }

    /** Checks the four colored quarters of the board at their calibrated points. */
    private fun looksLikeLudoBoard(frame: Bitmap): Boolean {
        var hits = 0
        for ((x, y, target) in LudoLayout.QUARTER_SAMPLES) {
            val px = frame.getPixel(
                (x * frame.width).toInt().coerceIn(0, frame.width - 1),
                (y * frame.height).toInt().coerceIn(0, frame.height - 1)
            )
            if (close(px, target)) hits++
        }
        return hits >= 3
    }

    private fun close(pixel: Int, rgb: Triple<Int, Int, Int>): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return Math.abs(r - rgb.first) < 60 &&
            Math.abs(g - rgb.second) < 60 &&
            Math.abs(b - rgb.third) < 60
    }
}

object LudoLayout {
    const val ACTION_COOLDOWN_MS = 1500L

    // Dice button at the bottom center.
    const val DICE_X = 0.5f
    const val DICE_Y = 0.946f

    // Centers of the four home circles in the blue (bottom-left) quarter.
    val HOME_SPOTS: List<Pair<Float, Float>> = listOf(
        0.170f to 0.567f,
        0.287f to 0.567f,
        0.170f to 0.619f,
        0.287f to 0.619f
    )

    // Colored quarters of the board used to recognize a Ludo screen.
    // Each entry: x, y, expected RGB.
    val QUARTER_SAMPLES: List<Triple<Float, Float, Triple<Int, Int, Int>>> = listOf(
        Triple(0.22f, 0.335f, Triple(230, 30, 40)),    // red
        Triple(0.77f, 0.335f, Triple(0, 160, 70)),     // green
        Triple(0.77f, 0.59f, Triple(255, 220, 30)),    // yellow
        Triple(0.22f, 0.59f, Triple(20, 110, 170))     // blue
    )
}
