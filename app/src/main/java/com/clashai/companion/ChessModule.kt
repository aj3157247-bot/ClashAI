package com.aiplayer.companion

import android.graphics.Bitmap
import com.aiplayer.companion.engine.Chess
import com.aiplayer.companion.engine.ChessPos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline chess player. It works with any chess app that shows a normal 8 x 8 board, as long as the game
 * starts from the standard starting position and you play the side at the bottom of the screen.
 *
 * How it works (no internet):
 *  1. The board is found by its evenly spaced grid lines, so it does not matter where the app draws it.
 *  2. From the starting position it keeps its own copy of the game. Each frame it only reads which squares
 *     hold a bright piece, a dark piece or nothing, and finds the one legal move that explains the change.
 *     That is how it follows the opponent's moves (including captures, castling, en passant).
 *  3. On its turn it searches with the built-in engine and taps the "from" square, then the "to" square,
 *     at the exact center of each square.
 */
class ChessModule : GameModule {

    override val id: String = "chess_offline"

    private class Obs(val occ: BooleanArray, val feat: IntArray)   // indexed by screen row * 8 + col

    private var geo: BoardDetector.Geo? = null
    private var lastDetectAt = 0L

    private var cachedFrame: Bitmap? = null
    private var cachedObs: Obs? = null
    private var cachedW = 0
    private var cachedH = 0

    // Game tracking.
    private var pos: ChessPos? = null
    private var weWhite = true
    private var flipped = false
    private var threshold = 0
    private var mismatch = 0

    // Our pending touches and retry state.
    private val taps = ArrayDeque<FloatArray>()
    private var nextAt = 0L
    private var lastPlanned = -1
    private var stuck = 0

    override fun matches(packageName: String?, frame: Bitmap): Boolean {
        val obs = analyse(frame) ?: return false
        if (pos != null) return true
        return startCalibration(obs) != null
    }

    // ---------------------------------------------------------------- observation

    private fun analyse(frame: Bitmap): Obs? {
        if (cachedFrame === frame) return cachedObs
        cachedFrame = frame
        cachedObs = null
        val w = frame.width
        val h = frame.height
        cachedW = w
        cachedH = h

        val now = System.currentTimeMillis()
        var g = geo
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, 0, 0, w, h)

        if (g != null && !BoardDetector.isCheckerboard(px, w, h, g)) {
            g = null
            geo = null
        }
        if (g == null) {
            if (now - lastDetectAt < DETECT_GAP_MS) return null
            lastDetectAt = now
            val found = BoardDetector.detect(px, w, h) ?: return null
            if (!BoardDetector.isCheckerboard(px, w, h, found)) return null
            geo = found
            g = found
        }

        val board = g ?: return null
        val occ = BooleanArray(64)
        val feat = IntArray(64)
        val base = IntArray(3)
        val hist = IntArray(256)
        for (row in 0..7) {
            for (col in 0..7) {
                BoardDetector.baseline(px, w, h, board, row, col, base)
                val xa = (board.x0 + (col + 0.30f) * board.cell).toInt().coerceIn(0, w - 1)
                val xb = (board.x0 + (col + 0.70f) * board.cell).toInt().coerceIn(xa, w - 1)
                val ya = (board.y0 + (row + 0.35f) * board.cell).toInt().coerceIn(0, h - 1)
                val yb = (board.y0 + (row + 0.75f) * board.cell).toInt().coerceIn(ya, h - 1)
                java.util.Arrays.fill(hist, 0)
                var all = 0
                var diff = 0
                for (y in ya..yb) {
                    for (x in xa..xb) {
                        val p = px[y * w + x]
                        val r = (p shr 16) and 0xFF
                        val gg = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        all++
                        if (abs(r - base[0]) + abs(gg - base[1]) + abs(b - base[2]) > 70) {
                            diff++
                            hist[(r + gg + b) / 3]++
                        }
                    }
                }
                val i = row * 8 + col
                if (all > 0 && diff.toFloat() / all >= 0.3f) {
                    occ[i] = true
                    var acc = 0
                    var med = 0
                    for (v in 0..255) {
                        acc += hist[v]
                        if (acc * 2 >= diff) { med = v; break }
                    }
                    feat[i] = med
                }
            }
        }
        val obs = Obs(occ, feat)
        cachedObs = obs
        return obs
    }

    /**
     * Checks that the screen shows the standard starting position: both top rows and both bottom rows full,
     * the four middle rows empty, and the two armies clearly different in brightness.
     * Returns [threshold, weWhite(1/0)] or null.
     */
    private fun startCalibration(obs: Obs): IntArray? {
        for (i in 0..63) {
            val row = i / 8
            val full = row <= 1 || row >= 6
            if (obs.occ[i] != full) return null
        }
        var topMax = 0
        var topMin = 255
        var botMax = 0
        var botMin = 255
        var topSum = 0
        var botSum = 0
        for (i in 0..63) {
            val row = i / 8
            if (row <= 1) {
                topSum += obs.feat[i]; topMax = max(topMax, obs.feat[i]); topMin = min(topMin, obs.feat[i])
            } else if (row >= 6) {
                botSum += obs.feat[i]; botMax = max(botMax, obs.feat[i]); botMin = min(botMin, obs.feat[i])
            }
        }
        val topMean = topSum / 16
        val botMean = botSum / 16
        if (abs(topMean - botMean) < 20) return null
        val bottomBright = botMean > topMean
        val lowMax = if (bottomBright) topMax else botMax
        val highMin = if (bottomBright) botMin else topMin
        val t = if (lowMax < highMin) (lowMax + highMin) / 2 else (topMean + botMean) / 2
        return intArrayOf(t, if (bottomBright) 1 else 0)
    }

    // ---------------------------------------------------------------- squares

    /** screen (row, col) -> engine square (rank * 8 + file). */
    private fun engineSq(row: Int, col: Int): Int {
        val f = if (flipped) 7 - col else col
        val r = if (flipped) row else 7 - row
        return r * 8 + f
    }

    private fun screenXY(sq: Int, g: BoardDetector.Geo): FloatArray {
        val r = sq shr 3
        val f = sq and 7
        val col = if (flipped) 7 - f else f
        val row = if (flipped) r else 7 - r
        return floatArrayOf(g.centerX(col), g.centerY(row))
    }

    /** Observed board in engine squares: 0 empty, +1 bright piece, -1 dark piece. */
    private fun observedBoard(obs: Obs): IntArray {
        val out = IntArray(64)
        for (i in 0..63) {
            if (!obs.occ[i]) continue
            val sq = engineSq(i / 8, i % 8)
            out[sq] = if (obs.feat[i] > threshold) 1 else -1
        }
        return out
    }

    private fun expectedBoard(p: ChessPos): IntArray {
        val out = IntArray(64)
        for (s in 0..63) {
            val v = p.sq[s].toInt()
            out[s] = if (v > 0) 1 else if (v < 0) -1 else 0
        }
        return out
    }

    /** Returns -1 when occupancy differs anywhere, otherwise the number of squares whose color differs. */
    private fun cost(observed: IntArray, expected: IntArray): Int {
        var colorDiff = 0
        for (s in 0..63) {
            val o = observed[s]
            val e = expected[s]
            if ((o == 0) != (e == 0)) return -1
            if (o != e) colorDiff++
        }
        return colorDiff
    }

    // ---------------------------------------------------------------- decision

    override fun decide(frame: Bitmap): GameAction {
        val now = System.currentTimeMillis()
        if (now < nextAt) return GameAction.Wait

        // Our two touches are sent one by one, with a pause so the game can react.
        if (taps.isNotEmpty()) {
            val t = taps.removeFirst()
            nextAt = now + (if (taps.isEmpty()) SETTLE_MS else TAP_GAP_MS)
            return GameAction.Tap(t[0] / cachedW.coerceAtLeast(1), t[1] / cachedH.coerceAtLeast(1))
        }

        val obs = analyse(frame)
        val g = geo
        if (obs == null || g == null) {
            AdviceLog.info("شطرنج: صفحه‌ی شطرنج پیدا نشد")
            return GameAction.Wait
        }

        // (Re)start tracking whenever the standard starting position is on the screen.
        val start = startCalibration(obs)
        val current = pos
        if (current == null || (start != null && !isStartPos(current))) {
            if (start == null) return GameAction.Wait
            threshold = start[0]
            weWhite = start[1] == 1
            flipped = !weWhite
            pos = Chess.startPos()
            mismatch = 0
            stuck = 0
            lastPlanned = -1
            AdviceLog.info(if (weWhite) "شطرنج: بازی تازه، من سفید هستم" else "شطرنج: بازی تازه، من سیاه هستم")
        }
        var p = pos ?: return GameAction.Wait

        // Follow the game: find the legal move that explains what the screen shows.
        val observed = observedBoard(obs)
        val here = cost(observed, expectedBoard(p))
        if (here < 0 || here > 1) {
            var bestMove = -1
            var bestCost = Int.MAX_VALUE
            for (m in Chess.legalMoves(p)) {
                val c = cost(observed, expectedBoard(Chess.apply(p, m)))
                if (c in 0..1 && c < bestCost) { bestCost = c; bestMove = m }
            }
            if (bestMove == -1) {
                mismatch++
                if (mismatch >= LOST_AFTER) {
                    AdviceLog.info("شطرنج: وضعیت صفحه با بازی من نمی‌خواند؛ ردیابی از نو شروع می‌شود")
                    pos = null
                    mismatch = 0
                } else {
                    AdviceLog.info("شطرنج: صبر برای پایان انیمیشن...")
                }
                nextAt = now + 500L
                return GameAction.Wait
            }
            val mover = if (p.whiteToMove == weWhite) "من" else "حریف"
            AdviceLog.info("شطرنج: $mover ${Chess.name(bestMove)} بازی کرد")
            p = Chess.apply(p, bestMove)
            pos = p
            stuck = 0
            lastPlanned = -1
        }
        mismatch = 0

        if (p.whiteToMove != weWhite) {
            nextAt = now + 600L
            return GameAction.Wait
        }

        val move = Chess.bestMove(p, THINK_MS)
        if (move == -1) {
            AdviceLog.info("شطرنج: حرکت قانونی ندارم (بازی تمام شده)")
            nextAt = now + 3000L
            return GameAction.Wait
        }

        // If the same move is planned again, the last touches did not work: try destination first to clear a stuck selection.
        if (move == lastPlanned) stuck++ else stuck = 0
        lastPlanned = move
        val a = screenXY(Chess.from(move), g)
        val b = screenXY(Chess.to(move), g)
        taps.clear()
        if (stuck >= 1) taps.addLast(b)
        taps.addLast(a)
        taps.addLast(b)
        AdviceLog.info("شطرنج: ${Chess.name(move)} را بازی می‌کنم")
        val first = taps.removeFirst()
        nextAt = now + TAP_GAP_MS
        return GameAction.Tap(first[0] / cachedW.coerceAtLeast(1), first[1] / cachedH.coerceAtLeast(1))
    }

    private fun isStartPos(p: ChessPos): Boolean {
        val s = Chess.startPos()
        if (p.whiteToMove != s.whiteToMove || p.castle != 15) return false
        for (i in 0..63) if (p.sq[i] != s.sq[i]) return false
        return true
    }

    companion object {
        private const val DETECT_GAP_MS = 2500L
        private const val TAP_GAP_MS = 750L
        private const val SETTLE_MS = 2200L
        private const val THINK_MS = 1300L
        private const val LOST_AFTER = 14
    }
}
