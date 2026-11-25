package com.aiplayer.companion.engine

import kotlin.math.abs

/**
 * A chess position. Squares are numbered rank * 8 + file, rank 0 = rank 1 (white's back rank), file 0 = a.
 * Pieces: +1..+6 white, -1..-6 black, in the order pawn, knight, bishop, rook, queen, king. 0 = empty.
 */
class ChessPos(
    val sq: ByteArray,
    val whiteToMove: Boolean,
    val castle: Int,   // bit 1 = white king side, 2 = white queen side, 4 = black king side, 8 = black queen side
    val ep: Int        // square a pawn can capture en passant, or -1
)

/**
 * A small but complete chess engine: legal move generation (castling, en passant, promotion),
 * negamax alpha-beta search with iterative deepening, quiescence search and a time limit.
 * A move is an Int: from | (to shl 6) | (promotionPiece shl 12).
 */
object Chess {
    const val P = 1
    const val N = 2
    const val B = 3
    const val R = 4
    const val Q = 5
    const val K = 6

    private const val INF = 1_000_000
    private const val MATE = 100_000

    private val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)
    private val KN_DF = intArrayOf(1, 2, 2, 1, -1, -2, -2, -1)
    private val KN_DR = intArrayOf(2, 1, -1, -2, -2, -1, 1, 2)
    // Eight directions: even index = straight, odd index = diagonal.
    private val DF = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DR = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    fun from(m: Int): Int = m and 63
    fun to(m: Int): Int = (m shr 6) and 63
    fun promo(m: Int): Int = (m shr 12) and 7
    private fun mv(from: Int, to: Int, promo: Int = 0): Int = from or (to shl 6) or (promo shl 12)

    fun startPos(): ChessPos {
        val b = ByteArray(64)
        val back = intArrayOf(R, N, B, Q, K, B, N, R)
        for (f in 0..7) {
            b[f] = back[f].toByte()
            b[8 + f] = P.toByte()
            b[48 + f] = (-P).toByte()
            b[56 + f] = (-back[f]).toByte()
        }
        return ChessPos(b, true, 15, -1)
    }

    fun name(m: Int): String {
        fun s(x: Int): String = ('a' + (x and 7)).toString() + ('1' + (x shr 3)).toString()
        val p = promo(m)
        return s(from(m)) + s(to(m)) + (if (p != 0) "?nbrq"[p - 1].toString().replace("?", "") else "")
    }

    // ------------------------------------------------------------------ attacks

    fun attacked(b: ByteArray, sq: Int, byWhite: Boolean): Boolean {
        val f = sq and 7
        val r = sq shr 3
        val sgn = if (byWhite) 1 else -1
        val pr = if (byWhite) r - 1 else r + 1
        if (pr in 0..7) {
            if (f > 0 && b[pr * 8 + f - 1].toInt() == sgn * P) return true
            if (f < 7 && b[pr * 8 + f + 1].toInt() == sgn * P) return true
        }
        for (i in 0..7) {
            val nf = f + KN_DF[i]
            val nr = r + KN_DR[i]
            if (nf in 0..7 && nr in 0..7 && b[nr * 8 + nf].toInt() == sgn * N) return true
            val kf = f + DF[i]
            val kr = r + DR[i]
            if (kf in 0..7 && kr in 0..7 && b[kr * 8 + kf].toInt() == sgn * K) return true
        }
        for (i in 0..7) {
            val diagonal = (i % 2 == 1)
            var nf = f + DF[i]
            var nr = r + DR[i]
            while (nf in 0..7 && nr in 0..7) {
                val p = b[nr * 8 + nf].toInt()
                if (p != 0) {
                    if (p * sgn > 0) {
                        val t = abs(p)
                        if (t == Q || (diagonal && t == B) || (!diagonal && t == R)) return true
                    }
                    break
                }
                nf += DF[i]
                nr += DR[i]
            }
        }
        return false
    }

    private fun kingSq(b: ByteArray, white: Boolean): Int {
        val k = if (white) K else -K
        for (s in 0..63) if (b[s].toInt() == k) return s
        return -1
    }

    fun inCheck(b: ByteArray, white: Boolean): Boolean {
        val k = kingSq(b, white)
        return k >= 0 && attacked(b, k, !white)
    }

    // ------------------------------------------------------------------ move generation

    private fun addPawn(out: ArrayList<Int>, from: Int, to: Int, promotes: Boolean) {
        if (promotes) {
            out.add(mv(from, to, Q))
            out.add(mv(from, to, R))
            out.add(mv(from, to, B))
            out.add(mv(from, to, N))
        } else {
            out.add(mv(from, to))
        }
    }

    private fun pseudo(pos: ChessPos, capturesOnly: Boolean): ArrayList<Int> {
        val b = pos.sq
        val white = pos.whiteToMove
        val sgn = if (white) 1 else -1
        val out = ArrayList<Int>(48)
        for (s in 0..63) {
            val p = b[s].toInt()
            if (p == 0 || (p > 0) != white) continue
            val t = abs(p)
            val f = s and 7
            val r = s shr 3
            when (t) {
                P -> {
                    val nr = r + sgn
                    val promoRank = if (white) 7 else 0
                    val startRank = if (white) 1 else 6
                    if (nr in 0..7) {
                        if (!capturesOnly || nr == promoRank) {
                            if (b[nr * 8 + f].toInt() == 0) {
                                addPawn(out, s, nr * 8 + f, nr == promoRank)
                                if (!capturesOnly && r == startRank && b[(r + 2 * sgn) * 8 + f].toInt() == 0) {
                                    out.add(mv(s, (r + 2 * sgn) * 8 + f))
                                }
                            }
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val to = nr * 8 + nf
                            val q = b[to].toInt()
                            if (q != 0 && (q > 0) != white) addPawn(out, s, to, nr == promoRank)
                            else if (q == 0 && to == pos.ep) out.add(mv(s, to))
                        }
                    }
                }
                N -> {
                    for (i in 0..7) {
                        val nf = f + KN_DF[i]
                        val nr = r + KN_DR[i]
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val q = b[nr * 8 + nf].toInt()
                        if (q == 0) { if (!capturesOnly) out.add(mv(s, nr * 8 + nf)) }
                        else if ((q > 0) != white) out.add(mv(s, nr * 8 + nf))
                    }
                }
                K -> {
                    for (i in 0..7) {
                        val nf = f + DF[i]
                        val nr = r + DR[i]
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val q = b[nr * 8 + nf].toInt()
                        if (q == 0) { if (!capturesOnly) out.add(mv(s, nr * 8 + nf)) }
                        else if ((q > 0) != white) out.add(mv(s, nr * 8 + nf))
                    }
                    if (!capturesOnly) addCastling(pos, s, out)
                }
                else -> {
                    for (i in 0..7) {
                        val diagonal = (i % 2 == 1)
                        if (t == B && !diagonal) continue
                        if (t == R && diagonal) continue
                        var nf = f + DF[i]
                        var nr = r + DR[i]
                        while (nf in 0..7 && nr in 0..7) {
                            val q = b[nr * 8 + nf].toInt()
                            if (q == 0) {
                                if (!capturesOnly) out.add(mv(s, nr * 8 + nf))
                            } else {
                                if ((q > 0) != white) out.add(mv(s, nr * 8 + nf))
                                break
                            }
                            nf += DF[i]
                            nr += DR[i]
                        }
                    }
                }
            }
        }
        return out
    }

    private fun addCastling(pos: ChessPos, s: Int, out: ArrayList<Int>) {
        val b = pos.sq
        if (pos.whiteToMove && s == 4) {
            if ((pos.castle and 1) != 0 && b[5].toInt() == 0 && b[6].toInt() == 0 && b[7].toInt() == R &&
                !attacked(b, 4, false) && !attacked(b, 5, false) && !attacked(b, 6, false)
            ) out.add(mv(4, 6))
            if ((pos.castle and 2) != 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() == 0 && b[0].toInt() == R &&
                !attacked(b, 4, false) && !attacked(b, 3, false) && !attacked(b, 2, false)
            ) out.add(mv(4, 2))
        } else if (!pos.whiteToMove && s == 60) {
            if ((pos.castle and 4) != 0 && b[61].toInt() == 0 && b[62].toInt() == 0 && b[63].toInt() == -R &&
                !attacked(b, 60, true) && !attacked(b, 61, true) && !attacked(b, 62, true)
            ) out.add(mv(60, 62))
            if ((pos.castle and 8) != 0 && b[57].toInt() == 0 && b[58].toInt() == 0 && b[59].toInt() == 0 && b[56].toInt() == -R &&
                !attacked(b, 60, true) && !attacked(b, 59, true) && !attacked(b, 58, true)
            ) out.add(mv(60, 58))
        }
    }

    fun apply(pos: ChessPos, m: Int): ChessPos {
        val b = pos.sq.copyOf()
        val from = from(m)
        val to = to(m)
        val promo = promo(m)
        val p = b[from].toInt()
        val t = abs(p)
        val white = p > 0
        var castle = pos.castle
        var ep = -1

        b[from] = 0
        if (t == P && to == pos.ep && b[to].toInt() == 0 && (from and 7) != (to and 7)) {
            b[if (white) to - 8 else to + 8] = 0
        }
        if (t == P && abs((to shr 3) - (from shr 3)) == 2) ep = (from + to) / 2
        b[to] = (if (promo != 0) (if (white) promo else -promo) else p).toByte()
        if (t == K && abs((to and 7) - (from and 7)) == 2) {
            if (to > from) { b[to - 1] = b[to + 1]; b[to + 1] = 0 }
            else { b[to + 1] = b[to - 2]; b[to - 2] = 0 }
        }
        if (t == K) castle = castle and (if (white) 12 else 3)
        if (from == 7 || to == 7) castle = castle and 1.inv()
        if (from == 0 || to == 0) castle = castle and 2.inv()
        if (from == 63 || to == 63) castle = castle and 4.inv()
        if (from == 56 || to == 56) castle = castle and 8.inv()
        return ChessPos(b, !pos.whiteToMove, castle, ep)
    }

    fun legalMoves(pos: ChessPos): List<Int> {
        val out = ArrayList<Int>(40)
        for (m in pseudo(pos, false)) {
            val n = apply(pos, m)
            if (!inCheck(n.sq, pos.whiteToMove)) out.add(m)
        }
        return out
    }

    // ------------------------------------------------------------------ evaluation

    private fun evaluate(pos: ChessPos): Int {
        val b = pos.sq
        var material = 0
        for (s in 0..63) {
            val t = abs(b[s].toInt())
            if (t != 0 && t != K && t != P) material += VALUE[t]
        }
        val endgame = material < 2600
        var score = 0
        for (s in 0..63) {
            val p = b[s].toInt()
            if (p == 0) continue
            val t = abs(p)
            val white = p > 0
            val f = s and 7
            val r = s shr 3
            val adv = if (white) r else 7 - r
            val center = 14 - abs(2 * f - 7) - abs(2 * r - 7)
            var v = VALUE[t]
            when (t) {
                P -> v += adv * 6 + center + (if (adv >= 5) 12 else 0)
                N -> v += center * 4
                B -> v += center * 2
                R -> if (adv == 6) v += 15
                Q -> v += center
                K -> {
                    if (endgame) v += center * 4
                    else {
                        v += -center * 2 - adv * 8
                        if (adv == 0 && (f == 1 || f == 2 || f == 6)) v += 25
                    }
                }
            }
            score += if (white) v else -v
        }
        return if (pos.whiteToMove) score else -score
    }

    // ------------------------------------------------------------------ search

    private fun orderScore(pos: ChessPos, m: Int): Int {
        val victim = abs(pos.sq[to(m)].toInt())
        val attacker = abs(pos.sq[from(m)].toInt())
        var s = 0
        if (victim != 0) s += 10_000 + 10 * VALUE[victim] - VALUE[attacker]
        else if (attacker == P && to(m) == pos.ep) s += 10_000 + 900
        if (promo(m) == Q) s += 9_000
        return s
    }

    private fun ordered(pos: ChessPos, moves: List<Int>): List<Int> =
        moves.sortedByDescending { orderScore(pos, it) }

    private class Searcher(private val deadline: Long) {
        var aborted = false
        private var nodes = 0

        private fun tick() {
            nodes++
            if ((nodes and 1023) == 0 && System.currentTimeMillis() > deadline) aborted = true
        }

        fun negamax(pos: ChessPos, depthIn: Int, alphaIn: Int, beta: Int, ply: Int): Int {
            tick()
            if (aborted) return 0
            var depth = depthIn
            val checked = inCheck(pos.sq, pos.whiteToMove)
            if (checked && ply < 10) depth++
            if (depth <= 0) return quiesce(pos, alphaIn, beta, 0)
            var alpha = alphaIn
            var any = false
            for (m in ordered(pos, pseudo(pos, false))) {
                val n = apply(pos, m)
                if (inCheck(n.sq, pos.whiteToMove)) continue
                any = true
                val s = -negamax(n, depth - 1, -beta, -alpha, ply + 1)
                if (aborted) return 0
                if (s >= beta) return beta
                if (s > alpha) alpha = s
            }
            if (!any) return if (checked) -MATE + ply else 0
            return alpha
        }

        private fun quiesce(pos: ChessPos, alphaIn: Int, beta: Int, qd: Int): Int {
            tick()
            if (aborted) return 0
            val stand = evaluate(pos)
            if (stand >= beta) return beta
            var alpha = alphaIn
            if (stand > alpha) alpha = stand
            if (qd >= 4) return alpha
            for (m in ordered(pos, pseudo(pos, true))) {
                val n = apply(pos, m)
                if (inCheck(n.sq, pos.whiteToMove)) continue
                val s = -quiesce(n, -beta, -alpha, qd + 1)
                if (aborted) return 0
                if (s >= beta) return beta
                if (s > alpha) alpha = s
            }
            return alpha
        }
    }

    /** Returns the best move for the side to move, or -1 when there is no legal move. */
    fun bestMove(pos: ChessPos, timeMs: Long): Int {
        val moves = legalMoves(pos)
        if (moves.isEmpty()) return -1
        if (moves.size == 1) return moves[0]
        val deadline = System.currentTimeMillis() + timeMs
        var best = moves[0]
        var list: List<Int> = ordered(pos, moves)
        for (depth in 1..6) {
            val s = Searcher(deadline)
            var alpha = -INF
            var bm = -1
            for (m in list) {
                val sc = -s.negamax(apply(pos, m), depth - 1, -INF, -alpha, 1)
                if (s.aborted) break
                if (sc > alpha) { alpha = sc; bm = m }
            }
            if (s.aborted) break
            if (bm != -1) {
                best = bm
                val first = bm
                list = listOf(first) + list.filter { it != first }
            }
            if (alpha > MATE - 50) break
            if (System.currentTimeMillis() > deadline) break
        }
        return best
    }
}
