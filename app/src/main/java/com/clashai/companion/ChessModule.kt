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
 *     hold a bright piece, a dark piece or nothing, and finds the legal move(s) that explain the change.
 *     That is how it follows the opponent's moves (including captures, castling, en passant).
 *  3. On its turn it searches with the built-in engine and taps the "from" square, then the "to" square,
 *     at the exact center of each square.
 *
 * Fixes in this version (found by running the detection on real screenshots of the game):
 *  - Colored overlays (green hint dots, yellow last-move and green selection highlights) are ignored, so an
 *    empty square with a hint dot is no longer seen as occupied. Before this, the tracker lost the game
 *    as soon as a piece was selected.
 *  - The tracker explains up to 3 plies at once (our move + the reply + one more), so it keeps up when the
 *    opponent answers before the next screenshot. It also follows an Undo (goes back to an earlier position).
 *  - It never throws the game away when it is confused; it keeps trying and only restarts on a fresh start position.
 */
class ChessModule : GameModule {

    override val id: String = "chess_offline"

    private class Obs(val occ: BooleanArray, val feat: IntArray)   // indexed by screen row * 8 + col
    private class Found(val pos: ChessPos, val plies: Int, val path: IntArray)

    private var geo: BoardDetector.Geo? = null
    private var lastDetectAt = 0L

    private var cachedFrame: Bitmap? = null
    private var cachedPx: IntArray? = null
    private var cachedReady = false
    private var cachedW = 0
    private var cachedH = 0
    private val obsCache = arrayOfNulls<Obs>(2)

    // Game tracking.
    private var pos: ChessPos? = null
    private val history = ArrayList<ChessPos>()
    private var weWhite = true
    private var flipped = false
    private var threshold = 0
    private var mode = 0          // 0 = ignore strongly colored overlay pixels, 1 = count every pixel
    private var mismatch = 0

    // Our pending touches and retry state.
    private val taps = ArrayDeque<FloatArray>()
    private var nextAt = 0L
    private var lastPlanned = -1
    private var stuck = 0

    // Search state for follow().
    private var bestPos: ChessPos? = null
    private var bestCost = 0
    private val path = IntArray(MAX_PLIES)
    private var bestPath = IntArray(0)

    override fun matches(packageName: String?, frame: Bitmap): Boolean {
        if (!prepare(frame)) return false
        if (pos != null) return true
        if (calibrate() != null) return true
        // A board is visible but the game is not at its start. Offline mode explains it in decide();
        // smart mode lets the cloud AI play instead.
        return !AiSettings.cloudAllowed()
    }

    // ---------------------------------------------------------------- observation

    /** Loads the pixels of this frame and makes sure a board is known. Cached per frame. */
    private fun prepare(frame: Bitmap): Boolean {
        if (cachedFrame === frame) return cachedReady
        cachedFrame = frame
        cachedReady = false
        obsCache[0] = null
        obsCache[1] = null
        val w = frame.width
        val h = frame.height
        cachedW = w
        cachedH = h
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, 0, 0, w, h)
        cachedPx = px

        val now = System.currentTimeMillis()
        var g = geo
        if (g != null && !BoardDetector.isCheckerboard(px, w, h, g)) {
            g = null
            geo = null
        }
        if (g == null) {
            if (now - lastDetectAt < DETECT_GAP_MS) return false
            lastDetectAt = now
            val found = BoardDetector.detect(px, w, h) ?: return false
            if (!BoardDetector.isCheckerboard(px, w, h, found)) return false
            geo = found
        }
        cachedReady = true
        return true
    }

    private fun obsFor(m: Int): Obs {
        val c = obsCache[m]
        if (c != null) return c
        val o = observe(cachedPx!!, cachedW, cachedH, geo!!, if (m == 0) SAT_LIMIT else 256)
        obsCache[m] = o
        return o
    }

    /**
     * For every square: is there a piece, and how bright is it. A pixel counts as "piece" when it differs from
     * the empty square color AND is not strongly colored (hint dots and highlights are strongly green/yellow,
     * the pieces are gray, white or black). `satLimit` = 256 turns the color filter off.
     */
    private fun observe(px: IntArray, w: Int, h: Int, board: BoardDetector.Geo, satLimit: Int): Obs {
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
                        if (max(r, max(gg, b)) - min(r, min(gg, b)) >= satLimit) continue
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
        return Obs(occ, feat)
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

    /** Tries both observation modes. Returns [mode, threshold, weWhite(1/0)] or null. */
    private fun calibrate(): IntArray? {
        for (m in 0..1) {
            val c = startCalibration(obsFor(m)) ?: continue
            return intArrayOf(m, c[0], c[1])
        }
        return null
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

    // ---------------------------------------------------------------- following the game

    private fun dfs(p: ChessPos, observed: IntArray, total: Int, left: Int) {
        for (m in Chess.legalMoves(p)) {
            path[total - left] = m
            val q = Chess.apply(p, m)
            if (left == 1) {
                val c = cost(observed, expectedBoard(q))
                if (c in 0..1 && c < bestCost) {
                    bestCost = c
                    bestPos = q
                    bestPath = path.copyOf(total)
                }
            } else {
                dfs(q, observed, total, left - 1)
            }
        }
    }

    /**
     * Finds the shortest sequence of legal moves (0 to maxDepth plies) after which the position looks like the
     * screen. Zero plies means nothing changed. Several plies happen when the opponent already answered.
     */
    private fun follow(p: ChessPos, observed: IntArray, maxDepth: Int): Found? {
        val base = expectedBoard(p)
        var occDiff = 0
        for (s in 0..63) if ((base[s] == 0) != (observed[s] == 0)) occDiff++
        if (occDiff == 0 && cost(observed, base) <= 1) return Found(p, 0, IntArray(0))
        for (depth in 1..maxDepth) {
            if (occDiff > 4 * depth) continue        // one ply changes at most 4 squares (castling)
            bestPos = null
            bestCost = Int.MAX_VALUE
            dfs(p, observed, depth, depth)
            val b = bestPos
            if (b != null) return Found(b, depth, bestPath)
        }
        return null
    }

    /** After an Undo the screen shows an earlier position. Returns it (and forgets everything after it). */
    private fun rollback(observed: IntArray): ChessPos? {
        for (i in history.indices.reversed()) {
            val c = cost(observed, expectedBoard(history[i]))
            if (c in 0..1) {
                val h = history[i]
                while (history.size > i) history.removeAt(history.size - 1)
                return h
            }
        }
        return null
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

        if (!prepare(frame)) {
            AdviceLog.info("شطرنج: صفحه‌ی شطرنج پیدا نشد")
            return GameAction.Wait
        }
        val g = geo ?: return GameAction.Wait

        // (Re)start tracking whenever the standard starting position is on the screen.
        val start = calibrate()
        val current = pos
        if (current == null || (start != null && !isStartPos(current))) {
            if (start == null) {
                AdviceLog.info("شطرنج: صفحه پیدا شد ولی بازی از وضعیت شروع نیست. در خود بازی Restart بزن تا ربات مهره‌ها را بشناسد")
                nextAt = now + 1500L
                return GameAction.Wait
            }
            mode = start[0]
            threshold = start[1]
            weWhite = start[2] == 1
            flipped = !weWhite
            pos = Chess.startPos()
            history.clear()
            mismatch = 0
            stuck = 0
            lastPlanned = -1
            AdviceLog.info(if (weWhite) "شطرنج: بازی تازه، من سفید هستم" else "شطرنج: بازی تازه، من سیاه هستم")
        }
        var p = pos ?: return GameAction.Wait

        // Follow the game: find the legal move(s) that explain what the screen shows.
        val observed = observedBoard(obsFor(mode))
        val found = follow(p, observed, if (mismatch >= 2) MAX_PLIES else 2)
        if (found == null) {
            val back = rollback(observed)
            if (back != null) {
                AdviceLog.info("شطرنج: حرکت برگشت داده شد (Undo)")
                p = back
                pos = p
                mismatch = 0
                stuck = 0
                lastPlanned = -1
            } else {
                mismatch++
                if (mismatch >= LOST_AFTER) {
                    AdviceLog.info("شطرنج: وضعیت صفحه با بازی من نمی‌خواند؛ منتظر می‌مانم (با Restart در بازی دوباره شروع می‌شود)")
                } else {
                    AdviceLog.info("شطرنج: صبر برای پایان انیمیشن...")
                }
                nextAt = now + (if (mismatch >= LOST_AFTER) 1500L else 500L)
                return GameAction.Wait
            }
        } else if (found.plies > 0) {
            val sb = StringBuilder()
            var side = p.whiteToMove
            for (m in found.path) {
                sb.append(if (side == weWhite) "من " else "حریف ").append(Chess.name(m)).append("  ")
                side = !side
            }
            AdviceLog.info("شطرنج: $sb")
            history.add(p)
            while (history.size > 200) history.removeAt(0)
            p = found.pos
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

        // If the same move is planned again, the last touches did not work: tap the destination first to clear a stuck selection.
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
        private const val THINK_MS = 2000L
        private const val LOST_AFTER = 14
        private const val MAX_PLIES = 3
        private const val SAT_LIMIT = 45
    }
}
