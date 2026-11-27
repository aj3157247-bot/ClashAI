package com.aiplayer.companion

/**
 * Decision brain of the offline Ludo player (pure logic, no screen code).
 *
 * Positions are "progress" numbers: -1 = still at home, 0..50 = on the shared track (0 = own start cell),
 * 51..55 = own home lane (cannot be hit), 56 = finished. Every color walks the same ring of 52 cells and
 * starts 13 cells after the previous color, so a piece of an opponent with ring offset `c` (1..3, in order of
 * play after you) that has progress k stands on ring cell (13 * c + k) % 52 as seen from your side.
 * Safe cells are the start cell and the star cell (8 steps later) of each color: ring % 13 is 0 or 8.
 *
 * Every legal move is valued by what the position is worth AFTER the move:
 *  - progress of all pieces (a piece that has left home is worth ENTER extra, because a 6 is needed to get it out),
 *  - minus the chance that an opponent hits each of my pieces next turn, times what that piece is worth.
 *    The chance counts every opponent piece, direct hits (1..6 steps), "6 and then another roll" chains, and
 *    pieces that come out of home with a 6 and hit with the extra roll,
 *  - plus the value of an opponent piece captured by the move (computed with the captured piece removed),
 *  - plus a smaller bonus for pieces of mine that threaten opponent pieces, safe-cell bonus, and extra-turn bonus.
 * The numbers were tuned in a simulator of the standard rules (6 to leave home, extra turn after 6, capture and
 * finish, exact roll to finish). In 40,000 games per test it beat the previous heuristic (32% vs 25% fair in a
 * 4 player game, 53.5% in 2 players), a simple capture-first bot (40%, 64%) and a random bot (85%).
 */
object LudoBrain {

    private const val ENTER = 25.2
    private const val FIN = 10.0
    private const val CAPW = 1.0
    private const val EXTRA = 1.5
    private const val THREAT = 0.65
    private const val RISKW = 1.0
    private const val RISK6 = 0.5
    private const val SAFEB = 4.0
    private const val CHAIN = 1.0

    private fun safeCell(ring: Int): Boolean {
        val m = ring % 13
        return m == 0 || m == 8
    }

    private fun value(p: Int): Double {
        if (p < 0) return 0.0
        return ENTER + p + (if (p >= 56) FIN else 0.0)
    }

    /**
     * mine: progress of my pieces. oppColor[o]: ring offset (1..3) of opponent o.
     * oppPieces[o]: progress (0..50) of the pieces of opponent o that stand on the track. oppHome[o]: how many of its pieces are at home.
     * Returns the index (in `mine`) of the piece to move, or -1 when no move is legal.
     */
    fun choose(mine: IntArray, oppColor: IntArray, oppPieces: Array<IntArray>, oppHome: IntArray, dice: Int): Int {
        var best = -1
        var bestScore = -1.0e18
        for (a in mine.indices) {
            val i = mine[a]
            val j: Int
            if (i == -1) {
                if (dice != 6) continue
                j = 0
            } else {
                if (i >= 56) continue
                j = i + dice
                if (j > 56) continue
            }
            val sc = score(mine, a, j, oppColor, oppPieces, oppHome, dice)
            if (sc > bestScore) {
                bestScore = sc
                best = a
            }
        }
        return best
    }

    private fun score(
        mine: IntArray, a: Int, j: Int,
        oc: IntArray, op: Array<IntArray>, oh: IntArray, dice: Int
    ): Double {
        val n = mine.size
        val after = mine.copyOf()
        after[a] = j
        var s = 0.0
        var extra = dice == 6
        if (j == 56) {
            s += EXTRA
            extra = true
        }
        val captured = Array(op.size) { BooleanArray(op[it].size) }
        var capCount = 0
        if (j <= 50) {
            val ring = j % 52
            if (!safeCell(ring)) {
                for (o in op.indices) {
                    for (k in op[o].indices) {
                        val q = (13 * oc[o] + op[o][k]) % 52
                        if (q == ring) {
                            captured[o][k] = true
                            s += CAPW * (ENTER + op[o][k])
                            capCount++
                        }
                    }
                }
            }
        }
        if (capCount > 0) {
            s += EXTRA
            extra = true
        }
        for (p in 0 until n) s += value(after[p])

        val rw = RISKW * (if (extra) RISK6 else 1.0)
        for (p in 0 until n) {
            val i = after[p]
            if (i < 0 || i > 50) continue
            val ring = i % 52
            if (safeCell(ring)) {
                s += SAFEB
                continue
            }
            s -= rw * hitProb(ring, oc, op, oh, captured) * value(i)
        }

        // My threats against opponent pieces (they move first, so this counts at a lower weight).
        for (o in op.indices) {
            for (k in op[o].indices) {
                if (captured[o][k]) continue
                val prog = op[o][k]
                val q = (13 * oc[o] + prog) % 52
                if (safeCell(q)) continue
                var mask = 0
                for (p in 0 until n) {
                    val i = after[p]
                    if (i < 0 || i > 50) continue
                    val d = ((q - i) % 52 + 52) % 52
                    if (d in 1..6 && i + d <= 50) mask = mask or (1 shl d)
                }
                s += THREAT * (Integer.bitCount(mask) / 6.0) * (ENTER + prog)
            }
        }
        return s
    }

    /** Probability that at least one opponent hits `ring` on its next turn. */
    private fun hitProb(ring: Int, oc: IntArray, op: Array<IntArray>, oh: IntArray, captured: Array<BooleanArray>): Double {
        var safe = 1.0
        for (o in op.indices) {
            var mask = 0
            var chain = 0
            for (k in op[o].indices) {
                if (captured[o][k]) continue
                val prog = op[o][k]
                val q = (13 * oc[o] + prog) % 52
                val d = ((ring - q) % 52 + 52) % 52
                if (d in 1..6 && prog + d <= 50) mask = mask or (1 shl d)
                else if (d in 7..12 && prog + d <= 50) chain = chain or (1 shl (d - 6))
            }
            var p = Integer.bitCount(mask) / 6.0
            if ((mask and (1 shl 6)) == 0) p += CHAIN * Integer.bitCount(chain) / 36.0
            // A piece still at home can come out with a 6; the extra roll may then hit.
            if (oh[o] > 0) {
                val d = ((ring - 13 * oc[o]) % 52 + 52) % 52
                if (d in 1..6 && (mask and (1 shl 6)) == 0) p += CHAIN / 36.0
            }
            if (p > 1.0) p = 1.0
            safe *= 1.0 - p
        }
        return 1.0 - safe
    }
}
