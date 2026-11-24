package com.aiplayer.companion

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Local Ludo player for the offline "You (blue) vs Computer (green)" game.
 * It does not use the internet. It reads the dice and the pieces from pixels,
 * chooses a legal move by simple rules, and taps it.
 *
 * All numbers below are measured on a 1080 x 2408 screenshot and scaled to the real frame size.
 * The detection was checked on real screenshots of this game.
 */
class LudoModule : GameModule {

    override val id: String = "ludo_offline"

    private enum class Phase { ROLL, READ }

    private var phase = Phase.ROLL
    private var nextAt = 0L
    private var readFails = 0

    override fun matches(packageName: String?, frame: Bitmap): Boolean = looksLikeLudoBoard(frame)

    override fun decide(frame: Bitmap): GameAction {
        val now = System.currentTimeMillis()
        if (now < nextAt) return GameAction.Wait

        val sx = frame.width / REF_W
        val sy = frame.height / REF_H

        if (phase == Phase.ROLL) {
            phase = Phase.READ
            readFails = 0
            nextAt = now + DICE_SETTLE_MS
            AdviceLog.info("لودو: پرتاب تاس")
            return GameAction.Tap(DIE_X / REF_W, DIE_Y / REF_H)
        }

        // phase == READ: the dice has settled, read it and play.
        if (!dieIsBlue(frame, sx, sy)) {
            // Not our turn. Try rolling again after a while.
            phase = Phase.ROLL
            nextAt = now + NOT_MY_TURN_MS
            AdviceLog.info("لودو: نوبت حریف است، صبر می‌کنم")
            return GameAction.Wait
        }

        val dice = readDice(frame, sx, sy)
        if (dice == 0) {
            readFails++
            if (readFails >= 4) {
                phase = Phase.ROLL
                nextAt = now + 1000L
            } else {
                nextAt = now + 700L
            }
            AdviceLog.info("لودو: عدد تاس خوانده نشد")
            return GameAction.Wait
        }

        val pieces = detectPieces(frame, sx, sy)
        val move = chooseMove(pieces.blue, pieces.green, dice)

        phase = Phase.ROLL
        if (move == null) {
            nextAt = now + 1500L
            AdviceLog.info("لودو: تاس $dice، حرکت قانونی ندارم (مهره‌ها: ${pieces.blue.size})")
            return GameAction.Wait
        }

        nextAt = now + MOVE_ANIM_MS
        AdviceLog.info("لودو: تاس $dice، مهره‌ی خانه ${move.fromIndex} را حرکت می‌دهم")
        return GameAction.Tap(move.x / REF_W, move.y / REF_H)
    }

    // ---------------------------------------------------------------- recognition

    private fun looksLikeLudoBoard(frame: Bitmap): Boolean {
        val sx = frame.width / REF_W
        val sy = frame.height / REF_H
        var hits = 0
        // red, green, yellow, blue frames of the four home areas
        if (isRed(avg(frame, 90, 700, sx, sy))) hits++
        if (isGreen(avg(frame, 990, 700, sx, sy))) hits++
        if (isYellow(avg(frame, 990, 1560, sx, sy))) hits++
        if (isBlueFrame(avg(frame, 90, 1560, sx, sy))) hits++
        return hits >= 3
    }

    private fun isRed(c: IntArray) = c[0] > 190 && c[1] < 90 && c[2] < 90
    private fun isGreen(c: IntArray) = c[1] > 120 && c[0] < 70 && c[2] < 120
    private fun isYellow(c: IntArray) = c[0] > 220 && c[1] > 190 && c[2] < 110
    private fun isBlueFrame(c: IntArray) = c[2] > 150 && c[0] < 90 && c[1] in 90..200

    private fun dieIsBlue(frame: Bitmap, sx: Float, sy: Float): Boolean {
        val c = avgBox(frame, 536, 2252, 543, 2257, sx, sy)
        return c[2] > 170 && c[0] < 130
    }

    /** Counts the white dots at the nine possible dot positions of the die face. */
    private fun readDice(frame: Bitmap, sx: Float, sy: Float): Int {
        var n = 0
        for (p in DOTS) {
            val c = avgBox(frame, p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, sx, sy)
            if (c[0] > 215 && c[1] > 215 && c[2] > 215) n++
        }
        return if (n in 1..6) n else 0
    }

    private data class Piece(val x: Float, val y: Float)           // ring center in reference pixels
    private data class Pieces(val blue: List<Piece>, val green: List<Piece>)

    /** Finds the dark red ring under every pin, then classifies the pin by the color of its head. */
    private fun detectPieces(frame: Bitmap, sx: Float, sy: Float): Pieces {
        val y0 = (SCAN_Y0 * sy).toInt().coerceIn(0, frame.height - 1)
        val y1 = (SCAN_Y1 * sy).toInt().coerceIn(y0 + 1, frame.height)
        val w = frame.width
        val h = y1 - y0
        val pix = IntArray(w * h)
        frame.getPixels(pix, 0, w, 0, y0, w, h)

        val mask = BooleanArray(w * h)
        for (i in pix.indices) {
            val p = pix[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            mask[i] = r in 96..184 && g in 21..79 && b in 21..84 && r - g > 45 && r - b > 45
        }

        val dil = dilate(mask, w, h)

        // connected components on the dilated mask, statistics on the original mask pixels
        val label = IntArray(w * h)
        val sumX = ArrayList<Double>()
        val sumY = ArrayList<Double>()
        val cnt = ArrayList<Int>()
        val stack = IntArray(w * h)
        var comps = 0
        for (start in dil.indices) {
            if (!dil[start] || label[start] != 0) continue
            comps++
            var sp = 0
            stack[sp++] = start
            label[start] = comps
            var sx0 = 0.0
            var sy0 = 0.0
            var c = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % w
                val cy = cur / w
                if (mask[cur]) {
                    sx0 += cx
                    sy0 += cy
                    c++
                }
                // 4-neighbours
                if (cx > 0) { val n = cur - 1; if (dil[n] && label[n] == 0) { label[n] = comps; stack[sp++] = n } }
                if (cx < w - 1) { val n = cur + 1; if (dil[n] && label[n] == 0) { label[n] = comps; stack[sp++] = n } }
                if (cy > 0) { val n = cur - w; if (dil[n] && label[n] == 0) { label[n] = comps; stack[sp++] = n } }
                if (cy < h - 1) { val n = cur + w; if (dil[n] && label[n] == 0) { label[n] = comps; stack[sp++] = n } }
            }
            sumX.add(sx0)
            sumY.add(sy0)
            cnt.add(c)
        }

        val blue = ArrayList<Piece>()
        val green = ArrayList<Piece>()
        val minSize = (RING_MIN * sx * sy).toInt()
        val maxSize = (RING_MAX * sx * sy).toInt()
        for (k in 0 until comps) {
            val c = cnt[k]
            if (c < minSize || c > maxSize) continue
            val cx = (sumX[k] / c).toInt()
            val cy = (sumY[k] / c).toInt() + y0
            val head = avgFrameBox(frame, cx - (8 * sx).toInt(), cy - (60 * sy).toInt(), cx + (8 * sx).toInt(), cy - (44 * sy).toInt())
            val p = Piece(cx / sx, cy / sy)
            if (head[2] > 200 && head[0] < 90) blue.add(p)
            else if (head[1] > 90 && head[0] < 40 && head[2] < 100) green.add(p)
        }
        return Pieces(blue, green)
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = false
                val a = max(0, x - 2)
                val b = min(w - 1, x + 2)
                var xx = a
                while (xx <= b) { if (src[row + xx]) { v = true; break }; xx++ }
                tmp[row + x] = v
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val a = max(0, y - 2)
            val b = min(h - 1, y + 2)
            for (x in 0 until w) {
                var v = false
                var yy = a
                while (yy <= b) { if (tmp[yy * w + x]) { v = true; break }; yy++ }
                out[y * w + x] = v
            }
        }
        return out
    }

    // ---------------------------------------------------------------- rules and strategy

    private data class Move(val fromIndex: Int, val x: Float, val y: Float)

    private fun cellOf(p: Piece): Pair<Int, Int> =
        Pair(floor((p.x - BX0) / CELL_X).toInt(), floor((p.y - BY0) / CELL_Y).toInt())

    /** -1 = in the blue home box, -2 = unknown place, otherwise index 0..56 on the blue path. */
    private fun pathIndex(p: Piece): Int {
        val c = cellOf(p)
        if (c.first <= 5 && c.second >= 9) return -1
        for (i in BLUE_PATH.indices) {
            if (BLUE_PATH[i][0] == c.first && BLUE_PATH[i][1] == c.second) return i
        }
        return -2
    }

    private fun chooseMove(blue: List<Piece>, green: List<Piece>, dice: Int): Move? {
        val greenCells = HashSet<Int>()
        for (g in green) {
            val c = cellOf(g)
            val inGreenHome = c.first >= 9 && c.second <= 5
            if (!inGreenHome) greenCells.add(c.first * 100 + c.second)
        }

        var best: Move? = null
        var bestScore = -1.0
        for (b in blue) {
            val i = pathIndex(b)
            if (i == -2) continue
            val j: Int
            var score: Double
            if (i == -1) {
                if (dice != 6) continue
                j = 0
                score = 60.0
            } else {
                j = i + dice
                if (j > FINISH_INDEX) continue
                score = i * 0.1
            }
            val cell = BLUE_PATH[j]
            val key = cell[0] * 100 + cell[1]
            val isSafe = SAFE.contains(key)
            if (greenCells.contains(key) && !isSafe) score += 100.0
            if (j == FINISH_INDEX) score += 90.0
            if (isSafe) score += 25.0
            if (i in 0..50 && j >= 51) score += 20.0
            if (score > bestScore) {
                bestScore = score
                best = Move(i, b.x, b.y - TAP_LIFT)
            }
        }
        return best
    }

    // ---------------------------------------------------------------- pixel helpers

    private fun avg(frame: Bitmap, x: Int, y: Int, sx: Float, sy: Float): IntArray =
        avgBox(frame, x - 3, y - 3, x + 3, y + 3, sx, sy)

    /** Average color of a box given in reference pixels. */
    private fun avgBox(frame: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, sx: Float, sy: Float): IntArray =
        avgFrameBox(frame, (x0 * sx).toInt(), (y0 * sy).toInt(), (x1 * sx).toInt(), (y1 * sy).toInt())

    private fun avgFrameBox(frame: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val ax = x0.coerceIn(0, frame.width - 1)
        val bx = x1.coerceIn(ax, frame.width - 1)
        val ay = y0.coerceIn(0, frame.height - 1)
        val by = y1.coerceIn(ay, frame.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in ay..by) {
            for (x in ax..bx) {
                val p = frame.getPixel(x, y)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0) return intArrayOf(0, 0, 0)
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    companion object {
        private const val REF_W = 1080f
        private const val REF_H = 2408f

        // tap on the die to roll
        private const val DIE_X = 540f
        private const val DIE_Y = 2300f

        private const val DICE_SETTLE_MS = 1800L
        private const val MOVE_ANIM_MS = 2600L
        private const val NOT_MY_TURN_MS = 2500L

        // board grid (15 x 15)
        private const val BX0 = 57f
        private const val BY0 = 647.2f
        private const val CELL_X = 64.33f
        private const val CELL_Y = 64.54f

        private const val SCAN_Y0 = 620f
        private const val SCAN_Y1 = 1630f
        private const val RING_MIN = 250f
        private const val RING_MAX = 700f
        private const val TAP_LIFT = 28f

        private const val FINISH_INDEX = 56

        // nine possible dot positions of the die face
        private val DOTS = arrayOf(
            intArrayOf(515, 2272), intArrayOf(539, 2272), intArrayOf(563, 2272),
            intArrayOf(515, 2300), intArrayOf(539, 2300), intArrayOf(563, 2300),
            intArrayOf(515, 2328), intArrayOf(539, 2328), intArrayOf(563, 2328)
        )

        // Blue path: indexes 0..50 on the track, 51..55 in the blue lane, 56 = center.
        private val BLUE_PATH: Array<IntArray> = arrayOf(
            intArrayOf(6, 13), intArrayOf(6, 12), intArrayOf(6, 11), intArrayOf(6, 10), intArrayOf(6, 9),
            intArrayOf(5, 8), intArrayOf(4, 8), intArrayOf(3, 8), intArrayOf(2, 8), intArrayOf(1, 8), intArrayOf(0, 8),
            intArrayOf(0, 7),
            intArrayOf(0, 6), intArrayOf(1, 6), intArrayOf(2, 6), intArrayOf(3, 6), intArrayOf(4, 6), intArrayOf(5, 6),
            intArrayOf(6, 5), intArrayOf(6, 4), intArrayOf(6, 3), intArrayOf(6, 2), intArrayOf(6, 1), intArrayOf(6, 0),
            intArrayOf(7, 0),
            intArrayOf(8, 0), intArrayOf(8, 1), intArrayOf(8, 2), intArrayOf(8, 3), intArrayOf(8, 4), intArrayOf(8, 5),
            intArrayOf(9, 6), intArrayOf(10, 6), intArrayOf(11, 6), intArrayOf(12, 6), intArrayOf(13, 6), intArrayOf(14, 6),
            intArrayOf(14, 7),
            intArrayOf(14, 8), intArrayOf(13, 8), intArrayOf(12, 8), intArrayOf(11, 8), intArrayOf(10, 8), intArrayOf(9, 8),
            intArrayOf(8, 9), intArrayOf(8, 10), intArrayOf(8, 11), intArrayOf(8, 12), intArrayOf(8, 13), intArrayOf(8, 14),
            intArrayOf(7, 14),
            intArrayOf(7, 13), intArrayOf(7, 12), intArrayOf(7, 11), intArrayOf(7, 10), intArrayOf(7, 9),
            intArrayOf(7, 8)
        )

        // Star cells and the four start cells: pieces there cannot be captured.
        private val SAFE: Set<Int> = setOf(
            6 * 100 + 2, 12 * 100 + 6, 2 * 100 + 8, 8 * 100 + 12,
            6 * 100 + 13, 1 * 100 + 6, 8 * 100 + 1, 13 * 100 + 8
        )
    }
}
