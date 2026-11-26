package com.aiplayer.companion

import android.content.Context
import com.aiplayer.companion.engine.Chess
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Recognizes WHICH chess piece stands on a square (pawn, knight, bishop, rook, queen, king) from its silhouette.
 *
 * Why: the chess bot used to know the pieces only because it had watched the game from the very first move.
 * With this class it can also join a game that is already running, or find its way back after it lost track
 * (for example after Undo, or after a human moved a piece).
 *
 * How it works (no internet, no model file):
 *  - every piece sprite is cut out of the screenshot at a fixed window inside its square
 *    (from 5% above the square to 80% of its height, 10%..90% of its width),
 *  - the pixels that differ from both square colors and are not strongly colored (hint dots and
 *    highlights are colored) are the piece; they are averaged into an 8 x 10 grid (80 numbers),
 *  - the 80 numbers are compared with a template for each of the 12 pieces; the closest template of the right
 *    side wins.
 *
 * Templates: this class ships with templates for the piece set of the "Chess" app that was used for testing
 * (checked on real screenshots: 100% in both directions of a cross test). Whenever the bot sees the standard
 * starting position, it learns fresh templates from it, so other piece sets work as well after one game start.
 */
object PieceClassifier {

    const val GX = 8
    const val GY = 10
    const val LEN = GX * GY

    private const val TOP = -0.05f
    private const val BOT = 0.80f
    private const val XL = 0.10f
    private const val XR = 0.90f

    /** A piece is accepted only when it is this close to a template (squared distance of the 80 numbers). */
    private const val MAX_DIST = 1.6f

    /** and when the closest template is clearly better than the second one. */
    private const val MIN_RATIO = 2.0f

    private const val PREFS = "ai_player_chess_templates"
    private const val KEY = "learned"

    // Order of the templates: white P N B R Q K, then black p n b r q k. Values 0..255 = 0..1.
    private val BUILTIN_HEX = arrayOf(
        "161679047dfe2c160600c037bbff31000e00b6ffffe82300051e9adffff1590000a0febdfffbc807003aa75efff2762c001d39e2ffe04b2a004f49ecffffbb130032ecfefffe6b0000003299ac550800",  // P
        "0d0d4fe7fffffcfa010daff8ffffe8b00250f9eeffffa5060384f3fffffffd3302a9ffffffffff581647faffffffe4771e1b709297a7e23b102f87ffffffee6e02898df0fffff75f0441e0fffffab80c",  // N
        "1a29eea2fff5661a002be2e8fff176000043d1f6fefe7900002599ffffff4400005fe2ffffffb000008dc3aeffffdc27002f5f6affffcf4b002085fbfffdbf46006976f3ffffcb190026e0fffff77000",  // B
        "1a40f7ffffff8c1a0d30efffffff6a001520aeffffd138000a0deab497fd4c00006ef9f8ffffcc100c8eeeacfffff13d184994acfffffb301824a1bde7f5e6350f5c53f6faffea58006efbfdffffd313",  // R
        "1a39da16ddffde1d001bfbf6ffff9f000009cfffffff5700000462b4f5cf2200007fffe8fffdd40700b6ed35ffffbe4c00195f98ffffb47600154de8ffffc476007f5febffffdb60005ff8ffffffcd0e",  // Q
        "1a1a0e68db644e1a00004e193a660e00000069cfc9b40000000056a0ffd602000049f6e4fffd8d0000bae7edffe9b112002974cbffd4542e00294be8fffb522b005f69ebffffad040014d4ffffef3500",  // K
        "1b1bbfe6fff122180500e8d7fffc1b000b00d5ffffcf1a000308ccfff39433000083ffffef6c680100d4fef4a24c811f00dee0ff9a49572800c1dcffa3636c1f0073ffffffc94403000779d4de6f2200",  // p
        "40409cffffe6faf9001cfafffffbdb650067fffff7887b00009afffff062de1b00bffffff511f2310efcffffb62fd47620fffbffdfcd6b840dd7e9ff4819436900c2ffffedfb463f0035e2ffffc36903",  // n
        "2e32deffff875733001ef8cefba337000064ffd4fa967500006affffff808c00004bffffffcb520000b8fffbf7d6680f00f6eafac73d4d4000e7f9ffe37c513c00c7e4ffffb45f2e005cfdffffb26602",  // b
        "3b71cdffffdc9c401250fdffffffa1002b34ffffffff7700090aeefffffd3400009bfffff07b900c17fcffffee19455846fff7fbff2a157e40ddfeffffce29770eefeeffffd0553e0090ffffffce3917",  // r
        "334cd2fffff1de390072a0ffffb7cf000014c4e2d9cd92000002b4fbe8d21500007fffffeb6f710202f1fff3ac80544329ffdfffc12d216217e4eaff863d384600cbf4fff0ea35040032f1ffffb90000",  // q
        "4448f1ffffff564c00009fffffe8090000006bffffa60000000090f89d7205000054ffffd365740000cbffff1db9781500ebe2ffba47543700d2daff7637672e00adffffffdb6e14002ee8ffffaf6b00",  // k
    )

    // Piece codes in the engine: 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king.
    // Index in the template array: white 0..5 (pawn..king), black 6..11.
    private val builtin: Array<FloatArray> = Array(12) { i -> decode(BUILTIN_HEX[i]) }

    @Volatile
    private var learned: Array<FloatArray>? = null

    @Volatile
    var learnedFromScreen = false
        private set

    private fun decode(hex: String): FloatArray {
        val out = FloatArray(LEN)
        for (i in 0 until LEN) {
            out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) / 255f
        }
        return out
    }

    private fun templates(): Array<FloatArray> = learned ?: builtin

    // ------------------------------------------------------------------ persistence

    fun load(context: Context) {
        try {
            val text = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "") ?: ""
            if (text.isEmpty()) return
            val parts = text.split(";")
            if (parts.size != 12) return
            val arr = Array(12) { i -> decode(parts[i]) }
            learned = arr
            learnedFromScreen = true
        } catch (t: Throwable) {
            // A broken saved value is ignored; the built-in templates stay in use.
        }
    }

    private fun save(context: Context?, t: Array<FloatArray>) {
        if (context == null) return
        try {
            val sb = StringBuilder()
            for (i in 0 until 12) {
                if (i > 0) sb.append(';')
                for (v in t[i]) {
                    val q = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val h = Integer.toHexString(q)
                    if (h.length < 2) sb.append('0')
                    sb.append(h)
                }
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, sb.toString()).apply()
        } catch (t2: Throwable) {
            // Not saving is harmless.
        }
    }

    // ------------------------------------------------------------------ square colors

    /** The two empty-square colors of a board: [lightR, lightG, lightB, darkR, darkG, darkB]. */
    fun squareColors(px: IntArray, w: Int, h: Int, g: BoardDetector.Geo): IntArray {
        val rs0 = ArrayList<Int>()
        val gs0 = ArrayList<Int>()
        val bs0 = ArrayList<Int>()
        val rs1 = ArrayList<Int>()
        val gs1 = ArrayList<Int>()
        val bs1 = ArrayList<Int>()
        val c = IntArray(3)
        for (row in 0..7) {
            for (col in 0..7) {
                BoardDetector.baseline(px, w, h, g, row, col, c)
                if ((row + col) % 2 == 0) {
                    rs0.add(c[0]); gs0.add(c[1]); bs0.add(c[2])
                } else {
                    rs1.add(c[0]); gs1.add(c[1]); bs1.add(c[2])
                }
            }
        }
        val a = intArrayOf(median(rs0), median(gs0), median(bs0))
        val b = intArrayOf(median(rs1), median(gs1), median(bs1))
        val lumA = a[0] * 3 + a[1] * 6 + a[2]
        val lumB = b[0] * 3 + b[1] * 6 + b[2]
        return if (lumA >= lumB) intArrayOf(a[0], a[1], a[2], b[0], b[1], b[2])
        else intArrayOf(b[0], b[1], b[2], a[0], a[1], a[2])
    }

    private fun median(v: List<Int>): Int {
        val s = v.sorted()
        return s[s.size / 2]
    }

    // ------------------------------------------------------------------ features

    /** The 80 silhouette numbers of the piece standing on screen square (row, col). `colors` = squareColors(). */
    fun features(px: IntArray, w: Int, h: Int, g: BoardDetector.Geo, row: Int, col: Int, colors: IntArray): FloatArray {
        val ya = (g.y0 + (row + TOP) * g.cell).toInt().coerceIn(0, h - 1)
        val yb = (g.y0 + (row + BOT) * g.cell).toInt().coerceIn(ya + 1, h)
        val xa = (g.x0 + (col + XL) * g.cell).toInt().coerceIn(0, w - 1)
        val xb = (g.x0 + (col + XR) * g.cell).toInt().coerceIn(xa + 1, w)
        val ww = xb - xa
        val hh = yb - ya
        val cnt = IntArray(LEN)
        val tot = IntArray(LEN)
        for (y in ya until yb) {
            val gy = ((y - ya) * GY / hh).coerceIn(0, GY - 1)
            for (x in xa until xb) {
                val gx = ((x - xa) * GX / ww).coerceIn(0, GX - 1)
                val i = gy * GX + gx
                tot[i]++
                val p = px[y * w + x]
                val r = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (max(r, max(gg, b)) - min(r, min(gg, b)) >= 60) continue
                val dl = abs(r - colors[0]) + abs(gg - colors[1]) + abs(b - colors[2])
                val dd = abs(r - colors[3]) + abs(gg - colors[4]) + abs(b - colors[5])
                if (dl > 70 && dd > 45) cnt[i]++
            }
        }
        val out = FloatArray(LEN)
        for (i in 0 until LEN) out[i] = if (tot[i] == 0) 0f else cnt[i].toFloat() / tot[i]
        return out
    }

    private fun dist(a: FloatArray, b: FloatArray, hint: Boolean): Float {
        var s = 0f
        for (i in 0 until LEN) {
            // With a hint diamond, grid rows 4..8 (the middle of the piece) are covered: skip them.
            if (hint && (i / GX) in 4..8) continue
            val d = a[i] - b[i]
            s += d * d
        }
        return s
    }

    /**
     * Returns the signed engine piece (+1..+6 white, -1..-6 black) or 0 when this is not a known piece
     * (an unknown piece set, a half hidden piece...). `white` = the side already decided from brightness.
     */
    fun classify(f: FloatArray, white: Boolean, hint: Boolean = false): Int {
        val t = templates()
        val base = if (white) 0 else 6
        var best = -1
        var bestD = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        for (k in 0..5) {
            val d = dist(f, t[base + k], hint)
            if (d < bestD) {
                second = bestD
                bestD = d
                best = k
            } else if (d < second) {
                second = d
            }
        }
        // With a hint diamond only 5 of the 10 grid rows are compared, so the limit is lower.
        val limit = if (hint) MAX_DIST * 0.5f else MAX_DIST
        if (best < 0 || bestD > limit) return 0
        if (second < bestD * MIN_RATIO) return 0
        val code = best + 1
        return if (white) code else -code
    }

    /** True when a green "you can move here" diamond lies on this square (it hides the middle of a piece). */
    fun hintOnSquare(px: IntArray, w: Int, h: Int, g: BoardDetector.Geo, row: Int, col: Int): Boolean {
        val xa = (g.x0 + (col + 0.30f) * g.cell).toInt().coerceIn(0, w - 1)
        val xb = (g.x0 + (col + 0.70f) * g.cell).toInt().coerceIn(xa, w - 1)
        val ya = (g.y0 + (row + 0.35f) * g.cell).toInt().coerceIn(0, h - 1)
        val yb = (g.y0 + (row + 0.75f) * g.cell).toInt().coerceIn(ya, h - 1)
        var all = 0
        var green = 0
        for (y in ya..yb) {
            for (x in xa..xb) {
                val p = px[y * w + x]
                val r = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val b = p and 0xFF
                all++
                if (gg - r > 50 && gg - b > 50) green++
            }
        }
        return all > 0 && green * 10 >= all
    }

    // ------------------------------------------------------------------ learning

    /**
     * Learns templates from a screen that shows the standard starting position.
     * `feats[screenSquare]` = features of every occupied square (screen row * 8 + col), null when empty.
     * `weWhite` = the bright pieces are at the bottom of the screen. Returns true when learned.
     */
    fun learnFromStart(feats: Array<FloatArray?>, brightAtBottom: Boolean, context: Context?): Boolean {
        val sum = Array(12) { FloatArray(LEN) }
        val n = IntArray(12)
        for (row in 0..7) {
            for (col in 0..7) {
                val f = feats[row * 8 + col] ?: continue
                // Start position on the screen: rows 0 and 7 are the back ranks, rows 1 and 6 the pawns.
                val farRank = row <= 1
                val whiteHere = if (brightAtBottom) !farRank else farRank
                val file = if (brightAtBottom) col else 7 - col
                val backRow = row == 0 || row == 7
                val kind: Int = if (backRow) {
                    when (file) {
                        0, 7 -> Chess.R
                        1, 6 -> Chess.N
                        2, 5 -> Chess.B
                        3 -> Chess.Q
                        else -> Chess.K
                    }
                } else Chess.P
                val idx = (if (whiteHere) 0 else 6) + (kind - 1)
                for (i in 0 until LEN) sum[idx][i] += f[i]
                n[idx]++
            }
        }
        for (i in 0 until 12) if (n[i] == 0) return false
        val out = Array(12) { i -> FloatArray(LEN) { j -> sum[i][j] / n[i] } }
        learned = out
        learnedFromScreen = true
        save(context, out)
        return true
    }
}
