package com.aiplayer.companion

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Local Ludo player (offline). Works in the 2-player layout (blue vs green) and in the
 * 4-player layout (blue = you, red, green, yellow). You are always the BLUE side.
 *
 * Nothing here uses fixed pixel positions any more. On every decision the module measures
 *  - where the board is (the dark blue home box is found by color, the grid is derived from it),
 *  - where your die is (the pink die panel under the board in the 4-player layout,
 *    the old bottom-center position in the 2-player layout).
 * This is why it keeps working when the board moves between 2-player and 4-player games.
 */
class LudoModule : GameModule {

    override val id: String = "ludo_offline"

    private enum class Phase { ROLL, READ }

    private var phase = Phase.ROLL
    private var nextAt = 0L
    private var readFails = 0

    // The registry calls matches() and then decide() with the same bitmap; do the work once.
    private var cachedFrame: Bitmap? = null
    private var cachedImg: Img? = null
    private var cachedGeo: Geo? = null

    private class Img(val w: Int, val h: Int, val p: IntArray) {
        fun r(x: Int, y: Int) = (p[y * w + x] shr 16) and 0xFF
        fun g(x: Int, y: Int) = (p[y * w + x] shr 8) and 0xFF
        fun b(x: Int, y: Int) = p[y * w + x] and 0xFF
    }

    /** Board geometry in frame pixels: top-left corner of the 15 x 15 grid and the cell size. */
    private class Geo(val x0: Float, val y0: Float, val cell: Float) {
        val sc: Float get() = cell / 64.33f
        val bottom: Float get() = y0 + 15f * cell
    }

    private class Die(
        val cx: Float, val cy: Float,
        val fourPlayer: Boolean,
        val present: Boolean,
        val panelW: Float
    )

    override fun matches(packageName: String?, frame: Bitmap): Boolean = analyse(frame) != null

    private fun analyse(frame: Bitmap): Geo? {
        if (cachedFrame === frame) return cachedGeo
        val w = frame.width
        val h = frame.height
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, 0, 0, w, h)
        val img = Img(w, h, px)
        var geo = findGeo(img)
        if (geo == null || !homesOk(img, geo)) {
            // Old fixed layout (2-player screenshot), scaled to this screen.
            val sx = w / REF_W
            val sy = h / REF_H
            val legacy = Geo(BX0 * sx, BY0 * sy, CELL_X * sx)
            geo = if (homesOk(img, legacy)) legacy else null
        }
        cachedFrame = frame
        cachedImg = img
        cachedGeo = geo
        return geo
    }

    override fun decide(frame: Bitmap): GameAction {
        val now = System.currentTimeMillis()
        if (now < nextAt) return GameAction.Wait

        val geo = analyse(frame)
        val img = cachedImg
        if (geo == null || img == null || cachedFrame !== frame) {
            AdviceLog.info("لودو: صفحه‌ی بازی پیدا نشد")
            return GameAction.Wait
        }
        val die = findDie(img, geo)

        if (phase == Phase.ROLL) {
            if (die.fourPlayer && !die.present) {
                nextAt = now + NOT_MY_TURN_MS / 2
                AdviceLog.info("لودو: نوبت من نیست (تاس من دیده نمی‌شود)")
                return GameAction.Wait
            }
            phase = Phase.READ
            readFails = 0
            nextAt = now + DICE_SETTLE_MS
            AdviceLog.info("لودو: پرتاب تاس")
            return GameAction.Tap(die.cx / img.w, die.cy / img.h)
        }

        // phase == READ: the dice has settled, read it and play.
        val myTurn = if (die.fourPlayer) die.present else dieIsBlueLegacy(img)
        if (!myTurn) {
            phase = Phase.ROLL
            nextAt = now + NOT_MY_TURN_MS
            AdviceLog.info("لودو: نوبت حریف است، صبر می‌کنم")
            return GameAction.Wait
        }

        val dice = if (die.fourPlayer) readDicePanel(img, die) else readDiceLegacy(img)
        if (dice == 0) {
            readFails++
            if (readFails >= 4) {
                phase = Phase.ROLL
                nextAt = now + 1000L
            } else {
                nextAt = now + 700L
            }
            DebugDump.save(frame, "ludo_dice")
            AdviceLog.info("لودو: عدد تاس خوانده نشد")
            return GameAction.Wait
        }

        val pieces = detectPieces(img, geo)
        if (pieces.blue.isEmpty()) DebugDump.save(frame, "ludo_nopieces")
        val move = chooseMove(geo, pieces.blue, pieces.others, dice)

        phase = Phase.ROLL
        if (move == null) {
            nextAt = now + 1500L
            AdviceLog.info("لودو: تاس $dice، حرکت قانونی ندارم (مهره‌ها: ${pieces.blue.size})")
            return GameAction.Wait
        }

        nextAt = now + MOVE_ANIM_MS
        AdviceLog.info("لودو: تاس $dice، مهره‌ی خانه ${move.fromIndex} را حرکت می‌دهم")
        return GameAction.Tap(move.x / img.w, move.y / img.h)
    }

    // ---------------------------------------------------------------- board geometry

    private fun isFrameBlue(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r < 50 && g in 90..135 && b in 150..195
    }

    /** Longest run of indexes whose count is >= min (gaps up to `gap` are tolerated). Returns [start, end]. */
    private fun longestRun(cnt: IntArray, from: Int, to: Int, min: Int, gap: Int): IntArray? {
        var bestS = -1
        var bestE = -1
        var curS = -1
        var last = -1
        for (i in from until to) {
            if (cnt[i] >= min) {
                if (curS < 0) curS = i
                last = i
            } else if (curS >= 0 && i - last > gap) {
                if (last - curS > bestE - bestS) { bestS = curS; bestE = last }
                curS = -1
            }
        }
        if (curS >= 0 && last - curS > bestE - bestS) { bestS = curS; bestE = last }
        return if (bestS < 0) null else intArrayOf(bestS, bestE)
    }

    /** Which home box color a pixel belongs to: 0 red, 1 green, 2 yellow, 3 blue frame, -1 none. */
    private fun boxKind(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return when {
            r > 190 && g < 80 && b < 85 -> 0
            g > 120 && r < 70 && b < 120 -> 1
            r > 220 && g > 190 && b < 110 -> 2
            r < 50 && g in 90..135 && b in 150..195 -> 3
            else -> -1
        }
    }

    /**
     * Bounding box [minX, minY, maxX, maxY] of the biggest connected area of each home color
     * (index 0 red, 1 green, 2 yellow, 3 blue). Works on every second pixel to stay fast.
     */
    private fun homeBoxes(img: Img): Array<IntArray?> {
        val step = 2
        val gw = img.w / step
        val gh = img.h / step
        val kind = ByteArray(gw * gh)
        for (y in 0 until gh) {
            val base = y * step * img.w
            for (x in 0 until gw) kind[y * gw + x] = boxKind(img.p[base + x * step]).toByte()
        }
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val bestCnt = IntArray(4)
        val boxes = arrayOfNulls<IntArray>(4)
        for (s in kind.indices) {
            val k = kind[s].toInt()
            if (k < 0 || seen[s]) continue
            var sp = 0
            stack[sp++] = s
            seen[s] = true
            var cnt = 0
            var minX = gw
            var maxX = -1
            var minY = gh
            var maxY = -1
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % gw
                val cy = cur / gw
                cnt++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0) { val n = cur - 1; if (!seen[n] && kind[n].toInt() == k) { seen[n] = true; stack[sp++] = n } }
                if (cx < gw - 1) { val n = cur + 1; if (!seen[n] && kind[n].toInt() == k) { seen[n] = true; stack[sp++] = n } }
                if (cy > 0) { val n = cur - gw; if (!seen[n] && kind[n].toInt() == k) { seen[n] = true; stack[sp++] = n } }
                if (cy < gh - 1) { val n = cur + gw; if (!seen[n] && kind[n].toInt() == k) { seen[n] = true; stack[sp++] = n } }
            }
            if (cnt > bestCnt[k]) {
                bestCnt[k] = cnt
                boxes[k] = intArrayOf(minX * step, minY * step, (maxX + 1) * step - 1, (maxY + 1) * step - 1)
            }
        }
        return boxes
    }

    /**
     * The board is a 15 x 15 grid with a red box top-left, green top-right, yellow bottom-right and blue
     * bottom-left. The biggest area of each color gives the four outer edges of the board directly:
     * left = red/blue left edge, right = green/yellow right edge, top = red/green top edge, bottom = blue/yellow
     * bottom edge. The pins standing inside the boxes (they hold the same colors) do not matter any more.
     *
     * The old code measured only the blue frame and demanded that it is as wide as it is tall. A box with
     * pins and a white inside is never like that, so in the 4-player layout the board was never found.
     */
    private fun findGeo(img: Img): Geo? {
        val s = img.w / 1080f
        val b = homeBoxes(img)
        val red = b[0] ?: return null
        val green = b[1] ?: return null
        val yellow = b[2] ?: return null
        val blue = b[3] ?: return null
        val x0 = min(red[0], blue[0]).toFloat()
        val x1 = (max(green[2], yellow[2]) + 1).toFloat()
        val yTop = min(red[1], green[1]).toFloat()
        val yBot = (min(blue[3], yellow[3]) + 1).toFloat()
        val cx = (x1 - x0) / 15f
        val cy = (yBot - yTop) / 15f
        if (cx < 30f * s || cx > 90f * s) return null
        if (kotlin.math.abs(cx - cy) > 0.05f * cx) return null
        val boxW = (red[2] - red[0] + 1).toFloat()
        if (kotlin.math.abs(boxW - 6f * cx) > 1.2f * cx) return null
        return Geo(x0, yTop, (cx + cy) / 2f)
    }

    private fun homesOk(img: Img, g: Geo): Boolean {
        var hits = 0
        val c = g.cell
        fun at(fx: Float, fy: Float): IntArray = avgBox(img, (g.x0 + fx * c).toInt(), (g.y0 + fy * c).toInt(), 3)
        if (isRed(at(0.5f, 0.5f))) hits++
        if (isGreen(at(14.5f, 0.5f))) hits++
        if (isYellow(at(14.5f, 14.5f))) hits++
        if (isBlueFrame(at(0.5f, 14.5f))) hits++
        return hits >= 3
    }

    private fun isRed(c: IntArray) = c[0] > 190 && c[1] < 90 && c[2] < 90
    private fun isGreen(c: IntArray) = c[1] > 120 && c[0] < 70 && c[2] < 120
    private fun isYellow(c: IntArray) = c[0] > 220 && c[1] > 190 && c[2] < 110
    private fun isBlueFrame(c: IntArray) = c[2] > 150 && c[0] < 90 && c[1] in 90..200

    // ---------------------------------------------------------------- the die

    private fun isPanelPink(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r > 215 && g in 170..225 && b in 170..225 && r - g > 15
    }

    /**
     * 4-player layout: every player has a pink square under or above the board. Yours is the one under the
     * board on the left half of the screen. The die is drawn inside it only while it is your turn.
     * 2-player layout: the die is at the old fixed place at the bottom center.
     */
    private fun findDie(img: Img, g: Geo): Die {
        val s = img.w / 1080f
        val top = (g.bottom + 8f * s).toInt()
        val bottom = min(img.h, (img.h * 0.97f).toInt())
        val xMax = img.w / 2
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        var count = 0
        if (top < bottom) {
            for (y in top until bottom) {
                val base = y * img.w
                for (x in 0 until xMax) {
                    if (isPanelPink(img.p[base + x])) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                        count++
                    }
                }
            }
        }
        val pw = (maxX - minX + 1).toFloat()
        val ph = (maxY - minY + 1).toFloat()
        val panelOk = count >= 1500 * s * s && pw in (100 * s)..(200 * s) && ph in (100 * s)..(200 * s)
        if (panelOk) {
            var white = 0
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    if (img.r(x, y) > 235 && img.g(x, y) > 235 && img.b(x, y) > 235) white++
                }
            }
            val present = white >= 900 * s * s
            return Die((minX + maxX) / 2f, (minY + maxY) / 2f, true, present, pw)
        }
        val sx = img.w / REF_W
        val sy = img.h / REF_H
        return Die(DIE_X * sx, DIE_Y * sy, false, true, 150f * s)
    }

    private fun dieIsBlueLegacy(img: Img): Boolean {
        val sx = img.w / REF_W
        val sy = img.h / REF_H
        val c = avgRegion(img, (536 * sx).toInt(), (2252 * sy).toInt(), (543 * sx).toInt(), (2257 * sy).toInt())
        return c[2] > 170 && c[0] < 130
    }

    /** Counts the white dots at the nine possible dot positions of the old (dark) die face. */
    private fun readDiceLegacy(img: Img): Int {
        val sx = img.w / REF_W
        val sy = img.h / REF_H
        var n = 0
        for (p in DOTS) {
            val c = avgRegion(
                img,
                ((p[0] - 2) * sx).toInt(), ((p[1] - 2) * sy).toInt(),
                ((p[0] + 2) * sx).toInt(), ((p[1] + 2) * sy).toInt()
            )
            if (c[0] > 215 && c[1] > 215 && c[2] > 215) n++
        }
        return if (n in 1..6) n else 0
    }

    /** White die with black dots, in the pink panel. Looks at the 3 x 3 dot positions. */
    private fun readDicePanel(img: Img, die: Die): Int {
        val d = (die.panelW * 0.15f).toInt().coerceAtLeast(6)
        val rad = max(3, (d * 0.3f).toInt())
        var n = 0
        for (gy in -1..1) {
            for (gx in -1..1) {
                var best = 0f
                for (jy in -1..1) {
                    for (jx in -1..1) {
                        val cx = (die.cx + gx * d + jx * (rad * 0.6f)).toInt()
                        val cy = (die.cy + gy * d + jy * (rad * 0.6f)).toInt()
                        val f = darkFraction(img, cx, cy, rad)
                        if (f > best) best = f
                    }
                }
                if (best > 0.3f) n++
            }
        }
        return if (n in 1..6) n else 0
    }

    private fun darkFraction(img: Img, cx: Int, cy: Int, rad: Int): Float {
        var dark = 0
        var all = 0
        for (y in (cy - rad)..(cy + rad)) {
            if (y < 0 || y >= img.h) continue
            for (x in (cx - rad)..(cx + rad)) {
                if (x < 0 || x >= img.w) continue
                all++
                if (img.r(x, y) + img.g(x, y) + img.b(x, y) < 200) dark++
            }
        }
        return if (all == 0) 0f else dark.toFloat() / all
    }

    // ---------------------------------------------------------------- pieces

    private data class Piece(val x: Float, val y: Float)           // ring center in frame pixels
    private class Pieces(val blue: List<Piece>, val others: Map<Int, List<Piece>>)

    private class Comp(val count: Int, val cx: Int, val cy: Int, val w: Int, val h: Int)

    /** Connected components on `connect`; statistics (count and centroid) from `stat` pixels only. */
    private fun components(connect: BooleanArray, stat: BooleanArray, w: Int, h: Int): List<Comp> {
        val label = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Comp>()
        for (start in connect.indices) {
            if (!connect[start] || label[start]) continue
            var sp = 0
            stack[sp++] = start
            label[start] = true
            var sumX = 0.0
            var sumY = 0.0
            var cnt = 0
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % w
                val cy = cur / w
                if (stat[cur]) { sumX += cx; sumY += cy; cnt++ }
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0) { val n = cur - 1; if (connect[n] && !label[n]) { label[n] = true; stack[sp++] = n } }
                if (cx < w - 1) { val n = cur + 1; if (connect[n] && !label[n]) { label[n] = true; stack[sp++] = n } }
                if (cy > 0) { val n = cur - w; if (connect[n] && !label[n]) { label[n] = true; stack[sp++] = n } }
                if (cy < h - 1) { val n = cur + w; if (connect[n] && !label[n]) { label[n] = true; stack[sp++] = n } }
            }
            if (cnt > 0) out.add(Comp(cnt, (sumX / cnt).toInt(), (sumY / cnt).toInt(), maxX - minX + 1, maxY - minY + 1))
        }
        return out
    }

    /**
     * Blue, green and yellow pins: find the dark red ring under each pin, then classify by the head color.
     * Red pins have no distinguishable ring, so they are found by their red head instead.
     * Colors: 1 = red, 2 = green, 3 = yellow (the order of play, clockwise from blue).
     */
    private fun detectPieces(img: Img, g: Geo): Pieces {
        val sc = g.sc
        val y0 = (g.y0 - 0.42f * g.cell).toInt().coerceIn(0, img.h - 2)
        val y1 = (g.bottom + 0.23f * g.cell).toInt().coerceIn(y0 + 1, img.h)
        val w = img.w
        val h = y1 - y0

        val ring = BooleanArray(w * h)
        val redHead = BooleanArray(w * h)
        for (yy in 0 until h) {
            val base = (y0 + yy) * w
            for (x in 0 until w) {
                val p = img.p[base + x]
                val r = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val b = p and 0xFF
                val i = yy * w + x
                ring[i] = r in 96..184 && gg in 21..79 && b in 21..84 && r - gg > 45 && r - b > 45
                redHead[i] = r > 195 && gg < 60 && b < 65
            }
        }

        val blue = ArrayList<Piece>()
        val others = HashMap<Int, ArrayList<Piece>>()
        others[1] = ArrayList()
        others[2] = ArrayList()
        others[3] = ArrayList()

        val minSize = (250f * sc * sc).toInt()
        val maxSize = (700f * sc * sc).toInt()
        for (c in components(dilate(ring, w, h), ring, w, h)) {
            if (c.count < minSize || c.count > maxSize) continue
            val cy = c.cy + y0
            val head = avgBox(
                img,
                c.cx, cy - (52 * sc).toInt(), (8 * sc).toInt(), (8 * sc).toInt(), true
            )
            val p = Piece(c.cx.toFloat(), cy.toFloat())
            if (head[2] > 200 && head[0] < 90) blue.add(p)
            else if (head[1] > 90 && head[0] < 40 && head[2] < 100) others[2]!!.add(p)
            else if (head[0] > 200 && head[1] > 170 && head[2] < 90) others[3]!!.add(p)
        }

        val headMin = (330f * sc * sc).toInt()
        val headMax = (900f * sc * sc).toInt()
        for (c in components(redHead, redHead, w, h)) {
            if (c.count < headMin || c.count > headMax) continue
            if (c.w > 36 * sc || c.h > 50 * sc || c.w < 12 * sc || c.h < 20 * sc) continue
            others[1]!!.add(Piece(c.cx.toFloat(), c.cy + y0 + 52f * sc))
        }

        // Second way to find pins: by the colored head. Works also for pins that stand without a ring under them
        // (on the track). A piece is only added when no piece of that color is already known at that place.
        val headKinds = BooleanArray(w * h)
        val lists = arrayOf<MutableList<Piece>>(blue, others[1]!!, others[2]!!, others[3]!!)
        for (k in 0..3) {
            java.util.Arrays.fill(headKinds, false)
            for (yy in 0 until h) {
                val base = (y0 + yy) * w
                for (x in 0 until w) {
                    val p = img.p[base + x]
                    val r = (p shr 16) and 0xFF
                    val gg = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    headKinds[yy * w + x] = when (k) {
                        0 -> r < 90 && gg in 130..205 && b >= 215          // blue
                        1 -> r > 180 && gg < 70 && b < 75                  // red
                        2 -> r < 40 && gg in 90..150 && b < 100            // green
                        else -> r > 215 && gg > 185 && b < 90              // yellow
                    }
                }
            }
            for (c in components(headKinds, headKinds, w, h)) {
                if (c.count < (450f * sc * sc).toInt() || c.count > (1300f * sc * sc).toInt()) continue
                if (c.w < 20 * sc || c.w > 42 * sc || c.h < 28 * sc || c.h > 44 * sc) continue
                val tx = c.cx.toFloat()
                val ty = c.cy + y0 + 52f * sc
                var known = false
                for (q in lists[k]) {
                    val dx = q.x - tx
                    val dy = q.y - ty
                    if (dx * dx + dy * dy < (0.5f * g.cell) * (0.5f * g.cell)) { known = true; break }
                }
                if (!known) lists[k].add(Piece(tx, ty))
            }
        }
        return Pieces(blue, others)
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

    private fun cellOf(g: Geo, p: Piece): Pair<Int, Int> =
        Pair(floor((p.x - g.x0) / g.cell).toInt(), floor((p.y - g.y0) / g.cell).toInt())

    /** Rotate a cell 90 degrees clockwise `times` times (blue path -> red -> green -> yellow path). */
    private fun rot(c: Int, r: Int, times: Int): IntArray {
        var cc = c
        var rr = r
        repeat(((times % 4) + 4) % 4) {
            val nc = 14 - rr
            val nr = cc
            cc = nc
            rr = nr
        }
        return intArrayOf(cc, rr)
    }

    private fun inHome(color: Int, c: Int, r: Int): Boolean = when (color) {
        0 -> c <= 5 && r >= 9      // blue
        1 -> c <= 5 && r <= 5      // red
        2 -> c >= 9 && r <= 5      // green
        else -> c >= 9 && r >= 9   // yellow
    }

    /** -1 = in the blue home box, -2 = unknown place, otherwise index 0..56 on the blue path. */
    private fun pathIndex(g: Geo, p: Piece): Int {
        val c = cellOf(g, p)
        if (inHome(0, c.first, c.second)) return -1
        for (i in BLUE_PATH.indices) {
            if (BLUE_PATH[i][0] == c.first && BLUE_PATH[i][1] == c.second) return i
        }
        return -2
    }

    /**
     * Danger-aware move choice for 1 to 3 opponents.
     *
     * Every opponent walks the same ring rotated by 90 / 180 / 270 degrees, so each of their pieces can be
     * placed on the blue path by rotating its cell back. From that we know which dice values would let it land
     * on a given cell next turn. The chance that at least one opponent hits a cell is combined over opponents.
     * Each legal move is scored by what it gains (capture, finish, lane, safe star, leaving home) and what it
     * risks (landing in front of an opponent, or leaving a piece that is already threatened).
     */
    private fun chooseMove(g: Geo, blue: List<Piece>, others: Map<Int, List<Piece>>, dice: Int): Move? {
        val reach = ArrayList<HashMap<Int, Int>>()          // per opponent: cell key -> mask of dice values
        val victim = HashMap<Int, Int>()                    // cell key -> opponent progress on its own path
        val oppOnBlue = ArrayList<Int>()                    // blue-path indexes that hold an opponent piece

        for ((color, list) in others) {
            val map = HashMap<Int, Int>()
            for (o in list) {
                val c = cellOf(g, o)
                if (inHome(color, c.first, c.second)) continue
                val bf = rot(c.first, c.second, 4 - color)
                var gi = -2
                for (t in 0..50) {
                    if (BLUE_PATH[t][0] == bf[0] && BLUE_PATH[t][1] == bf[1]) { gi = t; break }
                }
                if (gi < 0) continue                              // on its own lane, cannot be hit
                val key = c.first * 100 + c.second
                victim[key] = max(victim[key] ?: 0, gi)
                for (t in 0..50) {
                    if (BLUE_PATH[t][0] == c.first && BLUE_PATH[t][1] == c.second) { oppOnBlue.add(t); break }
                }
                for (d in 1..6) {
                    val t = gi + d
                    if (t > 50) break
                    val cell = rot(BLUE_PATH[t][0], BLUE_PATH[t][1], color)
                    val rk = cell[0] * 100 + cell[1]
                    map[rk] = (map[rk] ?: 0) or (1 shl d)
                }
            }
            reach.add(map)
        }

        fun danger(key: Int): Double {
            var safe = 1.0
            for (m in reach) safe *= 1.0 - Integer.bitCount(m[key] ?: 0) / 6.0
            return 1.0 - safe
        }

        var onBoard = 0
        for (b in blue) { if (pathIndex(g, b) >= 0) onBoard++ }

        var best: Move? = null
        var bestScore = -1.0e9
        for (b in blue) {
            val i = pathIndex(g, b)
            if (i == -2) continue
            val j: Int
            if (i == -1) {
                if (dice != 6) continue
                j = 0
            } else {
                j = i + dice
                if (j > FINISH_INDEX) continue
            }
            val cell = BLUE_PATH[j]
            val key = cell[0] * 100 + cell[1]
            val safe = SAFE.contains(key)
            val onTrack = j <= 50
            val stake = 10.0 + (if (onTrack) j else 50).toDouble()
            var score = 0.0

            if (i == -1) score += if (onBoard == 0) 70.0 else 55.0

            val v = victim[key]
            if (v != null && !safe && onTrack) score += 100.0 + v * 1.5

            if (j == FINISH_INDEX) score += 90.0
            else if (j >= 51 && i in 0..50) score += 35.0

            if (safe && onTrack && v == null) score += 18.0
            if (onTrack && !safe) score -= danger(key) * stake * 1.6

            if (i in 0..50) {
                val curCell = BLUE_PATH[i]
                val curKey = curCell[0] * 100 + curCell[1]
                if (!SAFE.contains(curKey)) score += danger(curKey) * (10.0 + i) * 1.4
            }

            if (onTrack) {
                for (t in oppOnBlue) {
                    if (t > j && t - j <= 6) { score += 4.0; break }
                }
            }

            score += dice * 0.4 + (if (i >= 0) i * 0.03 else 0.0)

            if (score > bestScore) {
                bestScore = score
                best = Move(i, b.x, b.y - TAP_LIFT * g.sc)
            }
        }
        return best
    }

    // ---------------------------------------------------------------- pixel helpers

    /** Average color of a (2*rad+1) square around (x, y). */
    private fun avgBox(img: Img, x: Int, y: Int, rad: Int): IntArray =
        avgRegion(img, x - rad, y - rad, x + rad, y + rad)

    /** Average color of a box centered at (cx, cy) with half sizes hx, hy (overload used for pin heads). */
    private fun avgBox(img: Img, cx: Int, cy: Int, hx: Int, hy: Int, @Suppress("UNUSED_PARAMETER") center: Boolean): IntArray =
        avgRegion(img, cx - hx, cy - hy, cx + hx, cy + hy)

    private fun avgRegion(img: Img, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val ax = x0.coerceIn(0, img.w - 1)
        val bx = x1.coerceIn(ax, img.w - 1)
        val ay = y0.coerceIn(0, img.h - 1)
        val by = y1.coerceIn(ay, img.h - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in ay..by) {
            for (x in ax..bx) {
                val p = img.p[y * img.w + x]
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
        // Reference screen of the old 2-player layout; only used as a fallback.
        private const val REF_W = 1080f
        private const val REF_H = 2408f
        private const val DIE_X = 540f
        private const val DIE_Y = 2300f
        private const val BX0 = 57f
        private const val BY0 = 647.2f
        private const val CELL_X = 64.33f

        private const val DICE_SETTLE_MS = 1800L
        private const val MOVE_ANIM_MS = 2600L
        private const val NOT_MY_TURN_MS = 2500L

        private const val TAP_LIFT = 28f
        private const val FINISH_INDEX = 56

        // nine possible dot positions of the old 2-player die face
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
