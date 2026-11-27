package com.aiplayer.companion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds an 8 x 8 checkerboard anywhere on the screen without knowing its colors or position.
 * The grid lines of a board are strong, evenly spaced edges, so we look for 9 equally spaced
 * edges in both directions (cell size between 9.5% and 13.5% of the screen width).
 * Used by the offline chess module and to snap the cloud AI's touches onto cell centers.
 */
object BoardDetector {

    class Geo(val x0: Float, val y0: Float, val cell: Float) {
        fun centerX(col: Int): Float = x0 + (col + 0.5f) * cell
        fun centerY(row: Int): Float = y0 + (row + 0.5f) * cell
        fun contains(x: Float, y: Float): Boolean =
            x >= x0 && y >= y0 && x < x0 + 8 * cell && y < y0 + 8 * cell
    }

    private fun gray(p: Int): Int =
        ((((p shr 16) and 0xFF) * 3) + (((p shr 8) and 0xFF) * 6) + (p and 0xFF)) / 10

    private fun lineScore(profile: FloatArray, start: Float, cell: Float): Float {
        var s = 0f
        for (k in 0..8) {
            val i = (start + k * cell).roundToInt()
            var m = 0f
            for (j in (i - 1)..(i + 1)) {
                if (j >= 0 && j < profile.size && profile[j] > m) m = profile[j]
            }
            s += m
        }
        return s
    }

    /** Returns the board position in full-resolution pixels, or null. Does not check that it is a real chessboard. */
    fun detect(px: IntArray, w: Int, h: Int): Geo? {
        val w2 = w / 2
        val h2 = h / 2
        if (w2 < 100 || h2 < 100) return null
        val g = IntArray(w2 * h2)
        for (y in 0 until h2) {
            val src = y * 2 * w
            val dst = y * w2
            for (x in 0 until w2) g[dst + x] = gray(px[src + x * 2])
        }
        val ex = FloatArray(w2)
        val ey = FloatArray(h2)
        for (y in 0 until h2) {
            val row = y * w2
            for (x in 0 until w2 - 1) {
                val d = abs(g[row + x + 1] - g[row + x])
                if (d > 40) ex[x] += d.toFloat()
            }
        }
        for (y in 0 until h2 - 1) {
            val row = y * w2
            val next = row + w2
            for (x in 0 until w2) {
                val d = abs(g[next + x] - g[row + x])
                if (d > 40) ey[y] += d.toFloat()
            }
        }

        var bestScore = -1f
        var bx = 0
        var by = 0
        var bc = 0f
        var cell = w2 * 0.095f
        val hi = w2 * 0.135f
        while (cell <= hi) {
            var sx = -1f
            var ax = 0
            val maxX = (w2 - 8 * cell).toInt()
            for (x0 in 0..maxX) {
                val s = lineScore(ex, x0.toFloat(), cell)
                if (s > sx) { sx = s; ax = x0 }
            }
            var sy = -1f
            var ay = 0
            val maxY = (h2 - 8 * cell).toInt()
            for (y0 in 0..maxY) {
                val s = lineScore(ey, y0.toFloat(), cell)
                if (s > sy) { sy = s; ay = y0 }
            }
            if (sx + sy > bestScore) { bestScore = sx + sy; bx = ax; by = ay; bc = cell }
            cell += 0.1f
        }
        if (bestScore <= 0f) return null
        return Geo(bx * 2f, by * 2f, bc * 2f)
    }

    // Sample points inside a square, away from the middle where a piece stands: four corners and the middle of the
    // left and right edge. Some apps print a rank number in the top-left corner and a file letter in the bottom-right
    // corner (a1 has both). Two text corners used to spoil the old 4-point median, so an EMPTY a1 looked like a black
    // piece and the bot lost track of the game as soon as the a1 rook moved. Six points tolerate two bad ones.
    private val SAMPLE_X = floatArrayOf(0.10f, 0.90f, 0.10f, 0.90f, 0.08f, 0.93f)
    private val SAMPLE_Y = floatArrayOf(0.12f, 0.12f, 0.90f, 0.90f, 0.55f, 0.55f)

    /** Median color of the inset sample points of a square: the empty square color, even when a piece stands on it. */
    fun baseline(px: IntArray, w: Int, h: Int, g: Geo, row: Int, col: Int, out: IntArray) {
        val rs = IntArray(6)
        val gs = IntArray(6)
        val bs = IntArray(6)
        for (k in 0..5) {
            val cx = (g.x0 + (col + SAMPLE_X[k]) * g.cell).toInt()
            val cy = (g.y0 + (row + SAMPLE_Y[k]) * g.cell).toInt()
            var r = 0
            var gg = 0
            var b = 0
            var n = 0
            for (y in max(0, cy - 2)..min(h - 1, cy + 2)) {
                for (x in max(0, cx - 2)..min(w - 1, cx + 2)) {
                    val p = px[y * w + x]
                    r += (p shr 16) and 0xFF
                    gg += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
            }
            if (n == 0) n = 1
            rs[k] = r / n
            gs[k] = gg / n
            bs[k] = b / n
        }
        out[0] = median6(rs)
        out[1] = median6(gs)
        out[2] = median6(bs)
    }

    private fun median6(a: IntArray): Int {
        val s = a.sortedArray()
        return (s[2] + s[3]) / 2
    }

    /** True when the 64 squares alternate between two clearly different colors. */
    fun isCheckerboard(px: IntArray, w: Int, h: Int, g: Geo): Boolean {
        if (g.x0 < 0 || g.y0 < 0 || g.x0 + 8 * g.cell > w || g.y0 + 8 * g.cell > h) return false
        val gr = IntArray(64)
        val c = IntArray(3)
        for (r in 0..7) {
            for (col in 0..7) {
                baseline(px, w, h, g, r, col, c)
                gr[r * 8 + col] = (c[0] * 3 + c[1] * 6 + c[2]) / 10
            }
        }
        var sumA = 0
        var sumB = 0
        for (i in 0..63) {
            val parity = ((i / 8) + (i % 8)) % 2
            if (parity == 0) sumA += gr[i] else sumB += gr[i]
        }
        val meanA = sumA / 32
        val meanB = sumB / 32
        if (abs(meanA - meanB) < 35) return false
        var ok = 0
        for (i in 0..63) {
            val parity = ((i / 8) + (i % 8)) % 2
            val m = if (parity == 0) meanA else meanB
            if (abs(gr[i] - m) < 30) ok++
        }
        return ok >= 54
    }
}
