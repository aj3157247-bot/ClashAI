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
    private var bootstrapped = false   // the position was read from the screen (not followed from the first move)
    private var lastBootstrapAt = 0L
    private var noPiecesSince = 0L

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
        // A board is visible but the game is not at its start: read the pieces from the screen.
        val now = System.currentTimeMillis()
        if (now - lastBootstrapAt >= BOOTSTRAP_GAP_MS && bootstrap(now)) {
            noPiecesSince = 0L
            return true
        }
        if (noPiecesSince == 0L) noPiecesSince = now
        // Only when the pieces cannot be read for a long time does the cloud AI take over (hybrid mode).
        return !AiSettings.cloudAllowed() || now - noPiecesSince < 10_000L
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
                var hint = 0
                for (y in ya..yb) {
                    for (x in xa..xb) {
                        val p = px[y * w + x]
                        val r = (p shr 16) and 0xFF
                        val gg = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        all++
                        if (gg - r > 50 && gg - b > 50) hint++
                        if (max(r, max(gg, b)) - min(r, min(gg, b)) >= satLimit) continue
                        if (abs(r - base[0]) + abs(gg - base[1]) + abs(b - base[2]) > 70) {
                            diff++
                            hist[(r + gg + b) / 3]++
                        }
                    }
                }
                val i = row * 8 + col
                // A green "you can move here" diamond lying on an enemy piece hides much of its body. Then look at
                // the upper part of the square (head and neck of the piece) instead.
                if (satLimit < 256 && all > 0 && diff.toFloat() / all < 0.3f && hint * 10 >= all) {
                    val ya2 = (board.y0 + (row + 0.00f) * board.cell).toInt().coerceIn(0, h - 1)
                    val yb2 = (board.y0 + (row + 0.32f) * board.cell).toInt().coerceIn(ya2, h - 1)
                    java.util.Arrays.fill(hist, 0)
                    var all2 = 0
                    var diff2 = 0
                    for (y in ya2..yb2) {
                        for (x in xa..xb) {
                            val p = px[y * w + x]
                            val r = (p shr 16) and 0xFF
                            val gg = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            all2++
                            if (max(r, max(gg, b)) - min(r, min(gg, b)) >= satLimit) continue
                            if (abs(r - base[0]) + abs(gg - base[1]) + abs(b - base[2]) > 70) {
                                diff2++
                                hist[(r + gg + b) / 3]++
                            }
                        }
                    }
                    if (all2 > 0 && diff2.toFloat() / all2 >= 0.3f) {
                        all = all2
                        diff = diff2
                    } else {
                        java.util.Arrays.fill(hist, 0)
                    }
                }
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

    // ---------------------------------------------------------------- joining a running game

    /** Silhouette features of every occupied square (index = screen row * 8 + col); null for empty squares. */
    private fun squareFeatures(occ: BooleanArray): Array<FloatArray?> {
        val out = arrayOfNulls<FloatArray>(64)
        val px = cachedPx ?: return out
        val g = geo ?: return out
        val colors = PieceClassifier.squareColors(px, cachedW, cachedH, g)
        for (i in 0..63) {
            if (occ[i]) out[i] = PieceClassifier.features(px, cachedW, cachedH, g, i / 8, i % 8, colors)
        }
        return out
    }

    /** Splits the brightness of all pieces into a bright and a dark army. Returns the threshold, or null. */
    private fun splitThreshold(obs: Obs): Int? {
        var lo = 255
        var hi = 0
        var count = 0
        for (i in 0..63) {
            if (!obs.occ[i]) continue
            count++
            lo = min(lo, obs.feat[i])
            hi = max(hi, obs.feat[i])
        }
        if (count < 2 || hi - lo < 40) return null
        var t = (lo + hi) / 2
        for (iter in 0 until 8) {
            var sumHi = 0
            var nHi = 0
            var sumLo = 0
            var nLo = 0
            for (i in 0..63) {
                if (!obs.occ[i]) continue
                if (obs.feat[i] > t) {
                    sumHi += obs.feat[i]
                    nHi++
                } else {
                    sumLo += obs.feat[i]
                    nLo++
                }
            }
            if (nHi == 0 || nLo == 0) return null
            t = (sumHi / nHi + sumLo / nLo) / 2
        }
        return t
    }

    /**
     * Looks for the "last move" marks (warm yellow or orange squares). Returns 1 when a bright piece stands on
     * one (the bright army moved last), 0 when a dark piece does, -1 when there is no clear mark.
     */
    private fun lastMoverBright(obs: Obs, t: Int, px: IntArray, g: BoardDetector.Geo): Int {
        val colors = PieceClassifier.squareColors(px, cachedW, cachedH, g)
        val c = IntArray(3)
        var bright = 0
        var dark = 0
        for (i in 0..63) {
            if (!obs.occ[i]) continue
            BoardDetector.baseline(px, cachedW, cachedH, g, i / 8, i % 8, c)
            val dl = abs(c[0] - colors[0]) + abs(c[1] - colors[1]) + abs(c[2] - colors[2])
            val dd = abs(c[0] - colors[3]) + abs(c[1] - colors[4]) + abs(c[2] - colors[5])
            if (min(dl, dd) < 90) continue
            if (c[0] - c[2] < 50 || c[0] < c[1]) continue
            if (obs.feat[i] > t) bright++ else dark++
        }
        return if (bright > 0 && dark == 0) 1 else if (dark > 0 && bright == 0) 0 else -1
    }

    /**
     * Reads the complete position from the screen: color of every piece (bright or dark army) and its kind
     * (PieceClassifier). Used when the game did not start from the beginning, and when the bot lost track.
     * Castling rights are a guess (king and rook on their home squares); en passant is ignored.
     * Returns true when a valid position was found and adopted.
     */
    private fun bootstrap(now: Long): Boolean {
        if (now - lastBootstrapAt < BOOTSTRAP_GAP_MS) return false
        lastBootstrapAt = now
        val g = geo ?: return false
        val px = cachedPx ?: return false
        for (m in 0..1) {
            val obs = obsFor(m)
            val t = splitThreshold(obs) ?: continue
            if (buildFromScreen(obs, t, m, px, g)) return true
        }
        return false
    }

    private fun buildFromScreen(obs: Obs, t: Int, m: Int, px: IntArray, g: BoardDetector.Geo): Boolean {
        val feats = squareFeatures(obs.occ)
        val kind = IntArray(64)
        var brightKingRow = -1
        var darkKingRow = -1
        var brightTop = 0
        var brightBottomCount = 0
        for (i in 0..63) {
            val f = feats[i] ?: continue
            val bright = obs.feat[i] > t
            val piece = PieceClassifier.classify(f, bright, PieceClassifier.hintOnSquare(px, cachedW, cachedH, g, i / 8, i % 8))
            if (piece == 0) return false
            kind[i] = piece
            if (piece == Chess.K) brightKingRow = i / 8
            if (piece == -Chess.K) darkKingRow = i / 8
            if (bright) {
                if (i / 8 <= 3) brightTop++ else brightBottomCount++
            }
        }
        // Which army stands at the bottom of the screen? The kings tell it best; otherwise count the pieces.
        val brightBottom = if (brightKingRow >= 0 && darkKingRow >= 0 && brightKingRow != darkKingRow)
            brightKingRow > darkKingRow
        else brightBottomCount >= brightTop
        val flip = !brightBottom

        val board = ByteArray(64)
        for (i in 0..63) {
            if (kind[i] == 0) continue
            val row = i / 8
            val col = i % 8
            val file = if (flip) 7 - col else col
            val rank = if (flip) row else 7 - row
            board[rank * 8 + file] = kind[i].toByte()
        }

        // Sanity checks: one king each, pawns not on the back ranks, sensible piece counts.
        var wk = 0
        var bk = 0
        var wp = 0
        var bp = 0
        var wn = 0
        var bn = 0
        for (s in 0..63) {
            val v = board[s].toInt()
            if (v == Chess.K) wk++
            if (v == -Chess.K) bk++
            if (v == Chess.P) {
                wp++
                if (s < 8 || s >= 56) return false
            }
            if (v == -Chess.P) {
                bp++
                if (s < 8 || s >= 56) return false
            }
            if (v > 0) wn++ else if (v < 0) bn++
        }
        if (wk != 1 || bk != 1 || wp > 8 || bp > 8 || wn > 16 || bn > 16) return false

        var castle = 0
        if (board[4].toInt() == Chess.K) {
            if (board[7].toInt() == Chess.R) castle = castle or 1
            if (board[0].toInt() == Chess.R) castle = castle or 2
        }
        if (board[60].toInt() == -Chess.K) {
            if (board[63].toInt() == -Chess.R) castle = castle or 4
            if (board[56].toInt() == -Chess.R) castle = castle or 8
        }

        // Whose turn is it? The "last move" marks show who moved last. Without marks, assume it is our turn.
        val mover = lastMoverBright(obs, t, px, g)
        var whiteToMove = when (mover) {
            1 -> false
            0 -> true
            else -> brightBottom
        }
        // The side that just moved cannot be in check. If it is, the turn guess was wrong.
        if (Chess.inCheck(board, !whiteToMove)) whiteToMove = !whiteToMove
        if (Chess.inCheck(board, !whiteToMove)) return false

        mode = m
        threshold = t
        weWhite = brightBottom
        flipped = flip
        pos = ChessPos(board, whiteToMove, castle, -1)
        history.clear()
        mismatch = 0
        stuck = 0
        lastPlanned = -1
        bootstrapped = true
        val side = if (weWhite) "سفید" else "سیاه"
        val turn = if (whiteToMove == weWhite) "نوبت من است" else "نوبت حریف است"
        AdviceLog.info("شطرنج: همه‌ی مهره‌ها از روی صفحه خوانده شد؛ من $side هستم و $turn")
        return true
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
                // The game is already running: read the whole position from the screen.
                if (!bootstrap(now)) {
                    AdviceLog.info("شطرنج: بازی در میانه است و همه‌ی مهره‌ها خوانده نشد. یک‌بار در خود بازی Restart بزن تا ربات شکل مهره‌ها را یاد بگیرد")
                    nextAt = now + 1500L
                    return GameAction.Wait
                }
            } else {
                mode = start[0]
                threshold = start[1]
                weWhite = start[2] == 1
                flipped = !weWhite
                pos = Chess.startPos()
                history.clear()
                mismatch = 0
                stuck = 0
                lastPlanned = -1
                bootstrapped = false
                // Learn how this app draws its pieces, so the bot can also join a running game later.
                val learnedNow = try {
                    PieceClassifier.learnFromStart(squareFeatures(obsFor(mode).occ), weWhite, AiSettings.appContext)
                } catch (t: Throwable) {
                    false
                }
                AdviceLog.info(
                    (if (weWhite) "شطرنج: بازی تازه، من سفید هستم" else "شطرنج: بازی تازه، من سیاه هستم") +
                        (if (learnedNow) " (شکل مهره‌ها یاد گرفته شد)" else "")
                )
            }
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
                if (mismatch >= RESYNC_AFTER && bootstrap(now)) {
                    // Lost track (a human moved, a dialog, an unknown reply...): the position was read again from the screen.
                    p = pos ?: return GameAction.Wait
                    mismatch = 0
                } else {
                    if (mismatch >= LOST_AFTER) {
                        AdviceLog.info("شطرنج: وضعیت صفحه با بازی من نمی‌خواند و خواندن مهره‌ها هم نشد؛ منتظر می‌مانم (با Restart در بازی دوباره شروع می‌شود)")
                    } else {
                        AdviceLog.info("شطرنج: صبر برای پایان انیمیشن...")
                    }
                    nextAt = now + (if (mismatch >= LOST_AFTER) 900L else 350L)
                    return GameAction.Wait
                }
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
            nextAt = now + 350L
            return GameAction.Wait
        }

        // When the position was read from the screen, castling rights are only a guess. If the same move keeps
        // failing, the game probably refuses it: forget the castling rights and choose another move.
        if (bootstrapped && stuck >= 3 && p.castle != 0) {
            p = ChessPos(p.sq, p.whiteToMove, 0, -1)
            pos = p
            stuck = 0
            lastPlanned = -1
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
        private const val TAP_GAP_MS = 400L
        private const val SETTLE_MS = 1300L
        private const val THINK_MS = 1800L
        private const val LOST_AFTER = 14
        private const val RESYNC_AFTER = 4
        private const val BOOTSTRAP_GAP_MS = 1500L
        private const val MAX_PLIES = 3
        private const val SAT_LIMIT = 45
    }
}
