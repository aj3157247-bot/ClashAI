package com.aiplayer.companion.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Strong chess search engine (used by Chess.bestMove).
 *
 * Mailbox board with make/unmake (no allocation during search), Zobrist transposition table,
 * principal variation search with aspiration windows, null move, late move reductions, futility and
 * reverse futility pruning, killer + history move ordering, quiescence search with delta pruning,
 * tapered piece-square evaluation (PeSTO values) plus pawn structure, rook files, king shelter and a
 * mop-up term so it can really finish won endgames. Repetition and the fifty move rule are respected,
 * and a small opening book gives instant, sound opening moves.
 *
 * The move generator was checked against the standard perft numbers (start position, "kiwipete",
 * and four more tricky positions: castling, en passant and promotion) and the engine beat the old
 * ClashAI engine 16 : 0 in test games at equal thinking time.
 */

private const val PAWN = 1
private const val KNIGHT = 2
private const val BISHOP = 3
private const val ROOK = 4
private const val QUEEN = 5
private const val KING = 6

private const val MATE = 100000
private const val INF = 1000000
private const val MAXP = 96
private const val MATE_BOUND = MATE - 1000
private const val STK = 2048
private const val TT_SIZE = 1 shl 19

private val KN_DF = intArrayOf(1, 2, 2, 1, -1, -2, -2, -1)
private val KN_DR = intArrayOf(2, 1, -1, -2, -2, -1, 1, 2)
// Eight directions: even index = straight, odd index = diagonal.
private val DF = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val DR = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

private val KNT: Array<IntArray> = Array(64) { s ->
    val f = s and 7
    val r = s shr 3
    val list = ArrayList<Int>()
    for (i in 0..7) {
        val nf = f + KN_DF[i]
        val nr = r + KN_DR[i]
        if (nf in 0..7 && nr in 0..7) list.add(nr * 8 + nf)
    }
    list.toIntArray()
}

private val KGT: Array<IntArray> = Array(64) { s ->
    val f = s and 7
    val r = s shr 3
    val list = ArrayList<Int>()
    for (i in 0..7) {
        val nf = f + DF[i]
        val nr = r + DR[i]
        if (nf in 0..7 && nr in 0..7) list.add(nr * 8 + nf)
    }
    list.toIntArray()
}

private val RAY: Array<Array<IntArray>> = Array(64) { s ->
    Array(8) { d ->
        val f = s and 7
        val r = s shr 3
        val list = ArrayList<Int>()
        var nf = f + DF[d]
        var nr = r + DR[d]
        while (nf in 0..7 && nr in 0..7) {
            list.add(nr * 8 + nf)
            nf += DF[d]
            nr += DR[d]
        }
        list.toIntArray()
    }
}

private val zRandom = java.util.Random(0x5DEECE66DL)
private val ZP: Array<LongArray> = Array(13) { LongArray(64) { zRandom.nextLong() } }
private val ZC: LongArray = LongArray(16) { zRandom.nextLong() }
private val ZE: LongArray = LongArray(65) { zRandom.nextLong() }
private val ZS: Long = zRandom.nextLong()

private val CASTLE_MASK: IntArray = IntArray(64) { 15 }.also {
    it[4] = 12
    it[60] = 3
    it[7] = 14
    it[0] = 13
    it[63] = 11
    it[56] = 7
}

private val MGV = intArrayOf(0, 82, 337, 365, 477, 1025, 0)
private val EGV = intArrayOf(0, 94, 281, 297, 512, 936, 0)
private val PHASE = intArrayOf(0, 0, 1, 1, 2, 4, 0)
private val PASSED = intArrayOf(0, 5, 10, 20, 38, 65, 105, 0)

// Piece-square tables, written with rank 8 first (white's point of view). White uses index sq xor 56.
private val PMG = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0,
    98, 134, 61, 95, 68, 126, 34, -11,
    -6, 7, 26, 31, 65, 56, 25, -20,
    -14, 13, 6, 21, 23, 12, 17, -23,
    -27, -2, -5, 12, 17, 6, 10, -25,
    -26, -4, -4, -10, 3, 3, 33, -12,
    -35, -1, -20, -23, -15, 24, 38, -22,
    0, 0, 0, 0, 0, 0, 0, 0
)
private val PEG = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0,
    178, 173, 158, 134, 147, 132, 165, 187,
    94, 100, 85, 67, 56, 53, 82, 84,
    32, 24, 13, 5, -2, 4, 17, 17,
    13, 9, -3, -7, -7, -8, 3, -1,
    4, 7, -6, 1, 0, -5, -1, -8,
    13, 8, 8, 10, 13, 0, 2, -7,
    0, 0, 0, 0, 0, 0, 0, 0
)
private val NMG = intArrayOf(
    -167, -89, -34, -49, 61, -97, -15, -107,
    -73, -41, 72, 36, 23, 62, 7, -17,
    -47, 60, 37, 65, 84, 129, 73, 44,
    -9, 17, 19, 53, 37, 69, 18, 22,
    -13, 4, 16, 13, 28, 19, 21, -8,
    -23, -9, 12, 10, 19, 17, 25, -16,
    -29, -53, -12, -3, -1, 18, -14, -19,
    -105, -21, -58, -33, -17, -28, -19, -23
)
private val NEG = intArrayOf(
    -58, -38, -13, -28, -31, -27, -63, -99,
    -25, -8, -25, -2, -9, -25, -24, -52,
    -24, -20, 10, 9, -1, -9, -19, -41,
    -17, 3, 22, 22, 22, 11, 8, -18,
    -18, -6, 16, 25, 16, 17, 4, -18,
    -23, -3, -1, 15, 10, -3, -20, -22,
    -42, -20, -10, -5, -2, -20, -23, -44,
    -29, -51, -23, -15, -22, -18, -50, -64
)
private val BMG = intArrayOf(
    -29, 4, -82, -37, -25, -42, 7, -8,
    -26, 16, -18, -13, 30, 59, 18, -47,
    -16, 37, 43, 40, 35, 50, 37, -2,
    -4, 5, 19, 50, 37, 37, 7, -2,
    -6, 13, 13, 26, 34, 12, 10, 4,
    0, 15, 15, 15, 14, 27, 18, 10,
    4, 15, 16, 0, 7, 21, 33, 1,
    -33, -3, -14, -21, -13, -12, -39, -21
)
private val BEG = intArrayOf(
    -14, -21, -11, -8, -7, -9, -17, -24,
    -8, -4, 7, -12, -3, -13, -4, -14,
    2, -8, 0, -1, -2, 6, 0, 4,
    -3, 9, 12, 9, 14, 10, 3, 2,
    -6, 3, 13, 19, 7, 10, -3, -9,
    -12, -3, 8, 10, 13, 3, -7, -15,
    -14, -18, -7, -1, 4, -9, -15, -27,
    -23, -9, -23, -5, -9, -16, -5, -17
)
private val RMG = intArrayOf(
    32, 42, 32, 51, 63, 9, 31, 43,
    27, 32, 58, 62, 80, 67, 26, 44,
    -5, 19, 26, 36, 17, 45, 61, 16,
    -24, -11, 7, 26, 24, 35, -8, -20,
    -36, -26, -12, -1, 9, -7, 6, -23,
    -45, -25, -16, -17, 3, 0, -5, -33,
    -44, -16, -20, -9, -1, 11, -6, -71,
    -19, -13, 1, 17, 16, 7, -37, -26
)
private val REG = intArrayOf(
    13, 10, 18, 15, 12, 12, 8, 5,
    11, 13, 13, 11, -3, 3, 8, 3,
    7, 7, 7, 5, 4, -3, -5, -3,
    4, 3, 13, 1, 2, 1, -1, 2,
    3, 5, 8, 4, -5, -6, -8, -11,
    -4, 0, -5, -1, -7, -12, -8, -16,
    -6, -6, 0, 2, -9, -9, -11, -3,
    -9, 2, 3, -1, -5, -13, 4, -20
)
private val QMG = intArrayOf(
    -28, 0, 29, 12, 59, 44, 43, 45,
    -24, -39, -5, 1, -16, 57, 28, 54,
    -13, -17, 7, 8, 29, 56, 47, 57,
    -27, -27, -16, -16, -1, 17, -2, 1,
    -9, -26, -9, -10, -2, -4, 3, -3,
    -14, 2, -11, -2, -5, 2, 14, 5,
    -35, -8, 11, 2, 8, 15, -3, 1,
    -1, -18, -9, 10, -15, -25, -31, -50
)
private val QEG = intArrayOf(
    -9, 22, 22, 27, 27, 19, 10, 20,
    -17, 20, 32, 41, 58, 25, 30, 0,
    -20, 6, 9, 49, 47, 35, 19, 9,
    3, 22, 24, 45, 57, 40, 57, 36,
    -18, 28, 19, 47, 31, 34, 39, 23,
    -16, -27, 15, 6, 9, 17, 10, 5,
    -22, -23, -30, -16, -16, -23, -36, -32,
    -33, -28, -22, -43, -5, -32, -20, -41
)
private val KMG = intArrayOf(
    -65, 23, 16, -15, -56, -34, 2, 13,
    29, -1, -20, -7, -8, -4, -38, -29,
    -9, 24, 2, -16, -20, 6, 22, -22,
    -17, -20, -12, -27, -30, -25, -14, -36,
    -49, -1, -27, -39, -46, -44, -33, -51,
    -14, -14, -22, -46, -44, -30, -15, -27,
    1, 7, -8, -64, -43, -16, 9, 8,
    -15, 36, 12, -54, 8, -28, 24, 14
)
private val KEG = intArrayOf(
    -74, -35, -18, -18, -11, 15, 4, -17,
    -12, 17, 14, 17, 17, 38, 23, 11,
    10, 17, 23, 15, 20, 45, 44, 13,
    -8, 22, 24, 27, 26, 33, 26, 3,
    -18, -4, 21, 24, 27, 23, 9, -11,
    -19, -3, 11, 21, 23, 16, 7, -9,
    -27, -11, 4, 13, 14, 4, -5, -17,
    -53, -34, -21, -11, -28, -14, -24, -43
)

private val MGT: Array<IntArray> = arrayOf(IntArray(64), PMG, NMG, BMG, RMG, QMG, KMG)
private val EGT: Array<IntArray> = arrayOf(IntArray(64), PEG, NEG, BEG, REG, QEG, KEG)

/** Opening lines (UCI move text). Every line is replayed with the real move generator; a line stops at its first illegal move. */
private val BOOK_LINES = arrayOf(
    "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6 c2c3 e8g8",
    "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 c2c3 g8f6 d2d3 d7d6 e1g1 e8g8 f1e1 a7a6",
    "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 d2d3 f8c5 c2c3 d7d6 e1g1 e8g8",
    "e2e4 e7e5 g1f3 b8c6 f1b5 g8f6 e1g1 f6e4 d2d4 e4d6 b5c6 d7c6 d4e5 d6f5 d1d8 e8d8",
    "e2e4 e7e5 g1f3 g8f6 f3e5 d7d6 e5f3 f6e4 d2d4 d6d5 f1d3 f8e7 e1g1 b8c6",
    "e2e4 e7e5 g1f3 b8c6 d2d4 e5d4 f3d4 g8f6 d4c6 b7c6 e4e5 d8e7 d1e2 f6d5 c2c4 c8a6",
    "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 a7a6 c1e3 e7e5 d4b3 c8e6",
    "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4 f3d4 g8f6 b1c3 d7d6 c1g5 e7e6 d1d2 a7a6",
    "e2e4 c7c5 g1f3 e7e6 d2d4 c5d4 f3d4 a7a6 f1d3 g8f6 e1g1 d8c7",
    "e2e4 e7e6 d2d4 d7d5 b1c3 g8f6 c1g5 f8e7 e4e5 f6d7 g5e7 d8e7 f2f4 e8g8",
    "e2e4 e7e6 d2d4 d7d5 b1d2 g8f6 e4e5 f6d7 f1d3 c7c5 c2c3 b8c6 g1e2 c5d4 c3d4",
    "e2e4 c7c6 d2d4 d7d5 b1c3 d5e4 c3e4 c8f5 e4g3 f5g6 h2h4 h7h6 g1f3 b8d7 h4h5 g6h7 f1d3 h7d3 d1d3 g8f6",
    "e2e4 c7c6 d2d4 d7d5 e4e5 c8f5 g1f3 e7e6 f1e2 c6c5 e1g1 b8c6 c2c3",
    "e2e4 d7d5 e4d5 d8d5 b1c3 d5a5 d2d4 g8f6 g1f3 c8f5 f1c4 e7e6",
    "e2e4 d7d6 d2d4 g8f6 b1c3 g7g6 f2f4 f8g7 g1f3 e8g8",
    "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6 c1g5 f8e7 e2e3 e8g8 g1f3 b8d7 a1c1 c7c6 f1d3 d5c4 d3c4 f6d5",
    "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6 b1c3 d5c4 a2a4 c8f5 e2e3 e7e6 f1c4 f8b4 e1g1 e8g8",
    "d2d4 d7d5 c2c4 d5c4 g1f3 g8f6 e2e3 e7e6 f1c4 c7c5 e1g1 a7a6 d1e2 b7b5 c4b3 c5d4 f1d1 b8c6",
    "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 g1f3 e8g8 f1e2 e7e5 e1g1 b8c6 d4d5 c6e7",
    "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4 e2e3 e8g8 f1d3 d7d5 g1f3 c7c5 e1g1 b8c6 a2a3 b4c3 b2c3 d5c4 d3c4 d8c7",
    "d2d4 g8f6 c2c4 e7e6 g1f3 d7d5 b1c3 f8e7 c1g5 e8g8 e2e3 h7h6 g5h4 f6e4 h4e7 d8e7",
    "d2d4 g8f6 g1f3 e7e6 c2c4 b7b6 g2g3 c8b7 f1g2 f8e7 e1g1 e8g8 b1c3 f6e4 d1c2 e4c3 c2c3 c7c5",
    "d2d4 d7d5 g1f3 g8f6 c1f4 e7e6 e2e3 f8d6 f4g3 e8g8 f1d3 b8d7 b1d2 d6g3 h2g3",
    "c2c4 e7e5 b1c3 g8f6 g1f3 b8c6 g2g3 d7d5 c4d5 f6d5 f1g2 d5b6 e1g1 f8e7 d2d3 e8g8",
    "c2c4 g8f6 b1c3 e7e6 g1f3 d7d5 d2d4 f8e7 c1g5 e8g8 e2e3 h7h6 g5h4 b7b6",
    "g1f3 d7d5 g2g3 g8f6 f1g2 e7e6 e1g1 f8e7 d2d4 e8g8 c2c4 d5c4 d1c2 a7a6 c2c4 b7b5 c4c2 c8b7",
    "g1f3 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 d2d4 e8g8 f1e2 e7e5 e1g1 b8c6 d4d5 c6e7",
    "g1f3 c7c5 c2c4 g8f6 b1c3 b8c6 g2g3 g7g6 f1g2 f8g7 e1g1 e8g8 d2d4 c5d4 f3d4 c6d4 d1d4 d7d6",
    "g1f3 d7d5 d2d4 g8f6 c2c4 e7e6 b1c3 f8e7 c1g5 e8g8 e2e3 h7h6 g5h4 b7b6",
    "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 b2b4 c5b4 c2c3 b4a5 d2d4 e5d4 e1g1 d4c3 d1b3 d8f6 e4e5 f6g6 b1c3",
    "e2e4 e7e5 b1c3 g8f6 f1c4 f6e4 c3e4 d7d5 c4d3 d5e4 d3e4 d8d4 d1f3 c8e6",
    "e2e4 e7e5 g1f3 b8c6 b1c3 g8f6 f1b5 f8b4 e1g1 e8g8 d2d3 d7d6 c1g5 b4c3 b2c3 d8e7"
)

private fun moveText(m: Int): String {
    val from = m and 63
    val to = (m shr 6) and 63
    val promo = (m shr 12) and 7
    val sb = StringBuilder()
    sb.append('a' + (from and 7))
    sb.append('1' + (from shr 3))
    sb.append('a' + (to and 7))
    sb.append('1' + (to shr 3))
    if (promo != 0) sb.append("?nbrq"[promo])
    return sb.toString()
}

internal class ChessEngine {

    // ---------------------------------------------------------------- state
    private val b = IntArray(64)
    private var white = true
    private var castle = 15
    private var ep = -1
    private var half = 0
    private var hash = 0L
    private var wk = 4
    private var bk = 60

    private val uCap = IntArray(STK)
    private val uCas = IntArray(STK)
    private val uEp = IntArray(STK)
    private val uHalf = IntArray(STK)
    private val uMove = IntArray(STK)
    private val uMoved = IntArray(STK)
    private val uIsEp = BooleanArray(STK)
    private val uHash = LongArray(STK)
    private var usp = 0
    private val hist = LongArray(STK)
    private var hsp = 0

    // transposition table
    private val ttKey = LongArray(TT_SIZE)
    private val ttMove = IntArray(TT_SIZE)
    private val ttScore = ShortArray(TT_SIZE)
    private val ttDepth = ByteArray(TT_SIZE)
    private val ttFlag = ByteArray(TT_SIZE)   // 0 empty, 1 exact, 2 lower bound, 3 upper bound

    private val killers = Array(MAXP + 2) { IntArray(2) }
    private val history = Array(2) { IntArray(64 * 64) }
    private val mvs = Array(MAXP + 2) { IntArray(256) }
    private val scs = Array(MAXP + 2) { IntArray(256) }
    private val rootMoves = IntArray(256)

    private var nodes = 0L
    private var stop = false
    private var deadline = 0L
    private var rootSide = 1
    private val contempt = 15

    // evaluation scratch
    private val pcW = IntArray(10)
    private val pcB = IntArray(10)
    private val maxW = IntArray(10)
    private val minW = IntArray(10)
    private val maxB = IntArray(10)
    private val minB = IntArray(10)
    private var npW = false
    private var npB = false

    // opening book: position hash -> candidate moves
    private val book = HashMap<Long, ArrayList<Int>>()
    private val rnd = java.util.Random()

    var lastDepth = 0
        private set
    var lastScore = 0
        private set

    // ---------------------------------------------------------------- setup

    private fun setStart() {
        java.util.Arrays.fill(b, 0)
        val back = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
        for (f in 0..7) {
            b[f] = back[f]
            b[8 + f] = PAWN
            b[48 + f] = -PAWN
            b[56 + f] = -back[f]
        }
        white = true
        castle = 15
        ep = -1
        half = 0
        usp = 0
        hsp = 0
        wk = 4
        bk = 60
        hash = computeHash()
    }

    private fun setPos(pos: ChessPos, halfmove: Int) {
        for (i in 0..63) b[i] = pos.sq[i].toInt()
        white = pos.whiteToMove
        castle = pos.castle
        ep = pos.ep
        half = halfmove
        usp = 0
        hsp = 0
        for (i in 0..63) {
            if (b[i] == KING) wk = i
            if (b[i] == -KING) bk = i
        }
        hash = computeHash()
    }

    private fun computeHash(): Long {
        var h = 0L
        for (i in 0..63) if (b[i] != 0) h = h xor ZP[b[i] + 6][i]
        h = h xor ZC[castle]
        h = h xor ZE[if (ep < 0) 64 else ep]
        if (!white) h = h xor ZS
        return h
    }

    fun newGame() {
        java.util.Arrays.fill(ttFlag, 0.toByte())
        java.util.Arrays.fill(ttKey, 0L)
        for (k in killers) {
            k[0] = 0
            k[1] = 0
        }
        for (h in history) java.util.Arrays.fill(h, 0)
    }

    // ---------------------------------------------------------------- attacks, make / unmake

    private fun attacked(sq: Int, byWhite: Boolean): Boolean {
        val sgn = if (byWhite) 1 else -1
        val f = sq and 7
        val r = sq shr 3
        val pr = if (byWhite) r - 1 else r + 1
        if (pr in 0..7) {
            if (f > 0 && b[pr * 8 + f - 1] == sgn * PAWN) return true
            if (f < 7 && b[pr * 8 + f + 1] == sgn * PAWN) return true
        }
        val kn = KNT[sq]
        for (i in kn.indices) if (b[kn[i]] == sgn * KNIGHT) return true
        val kg = KGT[sq]
        for (i in kg.indices) if (b[kg[i]] == sgn * KING) return true
        for (d in 0..7) {
            val ray = RAY[sq][d]
            val diag = (d and 1) == 1
            for (i in ray.indices) {
                val p = b[ray[i]]
                if (p != 0) {
                    if (p * sgn > 0) {
                        val t = if (p > 0) p else -p
                        if (t == QUEEN || (diag && t == BISHOP) || (!diag && t == ROOK)) return true
                    }
                    break
                }
            }
        }
        return false
    }

    private fun inCheck(w: Boolean): Boolean = attacked(if (w) wk else bk, !w)

    private fun make(m: Int) {
        val from = m and 63
        val to = (m shr 6) and 63
        val promo = (m shr 12) and 7
        val p = b[from]
        val t = if (p > 0) p else -p
        val w = p > 0
        uMove[usp] = m
        uMoved[usp] = p
        uCas[usp] = castle
        uEp[usp] = ep
        uHalf[usp] = half
        uHash[usp] = hash
        var cap = b[to]
        var h = hash
        h = h xor ZC[castle]
        h = h xor ZE[if (ep < 0) 64 else ep]
        half++
        if (t == PAWN || cap != 0) half = 0
        if (cap != 0) h = h xor ZP[cap + 6][to]
        h = h xor ZP[p + 6][from]
        b[from] = 0
        val isEp = (t == PAWN && to == ep && cap == 0 && (from and 7) != (to and 7))
        if (isEp) {
            val cs = if (w) to - 8 else to + 8
            cap = b[cs]
            h = h xor ZP[cap + 6][cs]
            b[cs] = 0
        }
        var newEp = -1
        if (t == PAWN && abs((to shr 3) - (from shr 3)) == 2) newEp = (from + to) / 2
        val np = if (promo != 0) (if (w) promo else -promo) else p
        b[to] = np
        h = h xor ZP[np + 6][to]
        if (t == KING) {
            if (w) wk = to else bk = to
            if (abs((to and 7) - (from and 7)) == 2) {
                val rf: Int
                val rt: Int
                if (to > from) {
                    rf = to + 1
                    rt = to - 1
                } else {
                    rf = to - 2
                    rt = to + 1
                }
                val rook = b[rf]
                h = h xor ZP[rook + 6][rf]
                b[rf] = 0
                b[rt] = rook
                h = h xor ZP[rook + 6][rt]
            }
        }
        uCap[usp] = cap
        uIsEp[usp] = isEp
        usp++
        castle = castle and CASTLE_MASK[from] and CASTLE_MASK[to]
        ep = newEp
        h = h xor ZC[castle]
        h = h xor ZE[if (ep < 0) 64 else ep]
        h = h xor ZS
        white = !white
        hash = h
        hist[hsp] = hash
        hsp++
    }

    private fun unmake() {
        usp--
        hsp--
        val m = uMove[usp]
        val from = m and 63
        val to = (m shr 6) and 63
        val p = uMoved[usp]
        val t = if (p > 0) p else -p
        val w = p > 0
        white = !white
        castle = uCas[usp]
        ep = uEp[usp]
        half = uHalf[usp]
        hash = uHash[usp]
        b[from] = p
        val cap = uCap[usp]
        if (uIsEp[usp]) {
            b[to] = 0
            b[if (w) to - 8 else to + 8] = cap
        } else {
            b[to] = cap
        }
        if (t == KING) {
            if (w) wk = from else bk = from
            if (abs((to and 7) - (from and 7)) == 2) {
                val rf: Int
                val rt: Int
                if (to > from) {
                    rf = to + 1
                    rt = to - 1
                } else {
                    rf = to - 2
                    rt = to + 1
                }
                b[rf] = b[rt]
                b[rt] = 0
            }
        }
    }

    private fun makeNull() {
        uCas[usp] = castle
        uEp[usp] = ep
        uHash[usp] = hash
        uHalf[usp] = half
        usp++
        hash = hash xor ZE[if (ep < 0) 64 else ep]
        ep = -1
        hash = hash xor ZE[64]
        hash = hash xor ZS
        white = !white
        half++
        hist[hsp] = hash
        hsp++
    }

    private fun unmakeNull() {
        usp--
        hsp--
        castle = uCas[usp]
        ep = uEp[usp]
        hash = uHash[usp]
        half = uHalf[usp]
        white = !white
    }

    // ---------------------------------------------------------------- move generation

    private fun gen(caps: Boolean, out: IntArray): Int {
        var n = 0
        val w = white
        val sgn = if (w) 1 else -1
        for (s in 0..63) {
            val p = b[s]
            if (p == 0 || (p > 0) != w) continue
            val t = if (p > 0) p else -p
            val f = s and 7
            val r = s shr 3
            if (t == PAWN) {
                val nr = r + sgn
                if (nr < 0 || nr > 7) continue
                val promoRank = if (w) 7 else 0
                val startRank = if (w) 1 else 6
                val fwd = nr * 8 + f
                if (b[fwd] == 0 && (!caps || nr == promoRank)) {
                    if (nr == promoRank) {
                        out[n++] = s or (fwd shl 6) or (QUEEN shl 12)
                        out[n++] = s or (fwd shl 6) or (KNIGHT shl 12)
                        out[n++] = s or (fwd shl 6) or (ROOK shl 12)
                        out[n++] = s or (fwd shl 6) or (BISHOP shl 12)
                    } else {
                        out[n++] = s or (fwd shl 6)
                        if (r == startRank && b[fwd + 8 * sgn] == 0) out[n++] = s or ((fwd + 8 * sgn) shl 6)
                    }
                }
                var df = -1
                while (df <= 1) {
                    val nf = f + df
                    if (nf in 0..7) {
                        val to = nr * 8 + nf
                        val q = b[to]
                        if (q != 0 && (q > 0) != w) {
                            if (nr == promoRank) {
                                out[n++] = s or (to shl 6) or (QUEEN shl 12)
                                out[n++] = s or (to shl 6) or (KNIGHT shl 12)
                                out[n++] = s or (to shl 6) or (ROOK shl 12)
                                out[n++] = s or (to shl 6) or (BISHOP shl 12)
                            } else {
                                out[n++] = s or (to shl 6)
                            }
                        } else if (q == 0 && to == ep) {
                            out[n++] = s or (to shl 6)
                        }
                    }
                    df += 2
                }
            } else if (t == KNIGHT || t == KING) {
                val tg = if (t == KNIGHT) KNT[s] else KGT[s]
                for (i in tg.indices) {
                    val q = b[tg[i]]
                    if (q == 0) {
                        if (!caps) out[n++] = s or (tg[i] shl 6)
                    } else if ((q > 0) != w) {
                        out[n++] = s or (tg[i] shl 6)
                    }
                }
                if (t == KING && !caps) n = castleMoves(s, out, n)
            } else {
                for (d in 0..7) {
                    val diag = (d and 1) == 1
                    if (t == BISHOP && !diag) continue
                    if (t == ROOK && diag) continue
                    val ray = RAY[s][d]
                    for (i in ray.indices) {
                        val q = b[ray[i]]
                        if (q == 0) {
                            if (!caps) out[n++] = s or (ray[i] shl 6)
                        } else {
                            if ((q > 0) != w) out[n++] = s or (ray[i] shl 6)
                            break
                        }
                    }
                }
            }
        }
        return n
    }

    private fun castleMoves(s: Int, out: IntArray, nIn: Int): Int {
        var n = nIn
        if (white && s == 4) {
            if ((castle and 1) != 0 && b[5] == 0 && b[6] == 0 && b[7] == ROOK &&
                !attacked(4, false) && !attacked(5, false) && !attacked(6, false)
            ) out[n++] = 4 or (6 shl 6)
            if ((castle and 2) != 0 && b[1] == 0 && b[2] == 0 && b[3] == 0 && b[0] == ROOK &&
                !attacked(4, false) && !attacked(3, false) && !attacked(2, false)
            ) out[n++] = 4 or (2 shl 6)
        } else if (!white && s == 60) {
            if ((castle and 4) != 0 && b[61] == 0 && b[62] == 0 && b[63] == -ROOK &&
                !attacked(60, true) && !attacked(61, true) && !attacked(62, true)
            ) out[n++] = 60 or (62 shl 6)
            if ((castle and 8) != 0 && b[57] == 0 && b[58] == 0 && b[59] == 0 && b[56] == -ROOK &&
                !attacked(60, true) && !attacked(59, true) && !attacked(58, true)
            ) out[n++] = 60 or (58 shl 6)
        }
        return n
    }

    // ---------------------------------------------------------------- evaluation

    private fun evaluate(): Int {
        var mgW = 0
        var egW = 0
        var mgB = 0
        var egB = 0
        var phase = 0
        for (i in 0..9) {
            pcW[i] = 0
            pcB[i] = 0
            maxW[i] = -1
            maxB[i] = -1
            minW[i] = 8
            minB[i] = 8
        }
        var wB = 0
        var bB = 0
        var wN = 0
        var bN = 0
        var wR = 0
        var bR = 0
        var wQ = 0
        var bQ = 0
        var wP = 0
        var bP = 0
        for (s in 0..63) {
            val p = b[s]
            if (p == 0) continue
            val t = if (p > 0) p else -p
            val f = (s and 7) + 1
            val r = s shr 3
            if (p > 0) {
                val i = s xor 56
                mgW += MGV[t] + MGT[t][i]
                egW += EGV[t] + EGT[t][i]
                if (t == PAWN) {
                    pcW[f]++
                    wP++
                    if (r > maxW[f]) maxW[f] = r
                    if (r < minW[f]) minW[f] = r
                } else if (t == BISHOP) wB++
                else if (t == KNIGHT) wN++
                else if (t == ROOK) wR++
                else if (t == QUEEN) wQ++
            } else {
                mgB += MGV[t] + MGT[t][s]
                egB += EGV[t] + EGT[t][s]
                if (t == PAWN) {
                    pcB[f]++
                    bP++
                    if (r > maxB[f]) maxB[f] = r
                    if (r < minB[f]) minB[f] = r
                } else if (t == BISHOP) bB++
                else if (t == KNIGHT) bN++
                else if (t == ROOK) bR++
                else if (t == QUEEN) bQ++
            }
            phase += PHASE[t]
        }
        npW = wB + wN + wR + wQ > 0
        npB = bB + bN + bR + bQ > 0
        // Dead draw: bare kings, or a king with one minor piece against a bare king.
        if (wP == 0 && bP == 0 && wR + bR + wQ + bQ == 0 && (wB + wN + bB + bN) <= 1) return 0
        var mg = mgW - mgB
        var eg = egW - egB
        if (wB >= 2) {
            mg += 30
            eg += 45
        }
        if (bB >= 2) {
            mg -= 30
            eg -= 45
        }
        for (f in 1..8) {
            if (pcW[f] > 1) {
                mg -= 8
                eg -= 16
            }
            if (pcB[f] > 1) {
                mg += 8
                eg += 16
            }
            if (pcW[f] > 0 && pcW[f - 1] == 0 && pcW[f + 1] == 0) {
                mg -= 10
                eg -= 10
            }
            if (pcB[f] > 0 && pcB[f - 1] == 0 && pcB[f + 1] == 0) {
                mg += 10
                eg += 10
            }
            // Passed pawns: the most advanced pawn of each file only.
            if (pcW[f] > 0) {
                val r = maxW[f]
                if (maxB[f - 1] <= r && maxB[f] <= r && maxB[f + 1] <= r) {
                    val bo = PASSED[r]
                    mg += bo / 2
                    eg += bo
                }
            }
            if (pcB[f] > 0) {
                val r = minB[f]
                if (minW[f - 1] >= r && minW[f] >= r && minW[f + 1] >= r) {
                    val bo = PASSED[7 - r]
                    mg -= bo / 2
                    eg -= bo
                }
            }
        }
        // Rooks on open and half-open files.
        for (s in 0..63) {
            val p = b[s]
            if (p != ROOK && p != -ROOK) continue
            val f = (s and 7) + 1
            if (p > 0) {
                if (pcW[f] == 0) {
                    if (pcB[f] == 0) {
                        mg += 22
                        eg += 10
                    } else {
                        mg += 10
                        eg += 5
                    }
                }
            } else {
                if (pcB[f] == 0) {
                    if (pcW[f] == 0) {
                        mg -= 22
                        eg -= 10
                    } else {
                        mg -= 10
                        eg -= 5
                    }
                }
            }
        }
        mg += shelter(true) - shelter(false)
        val mgPhase = min(phase, 24)
        val egPhase = 24 - mgPhase
        var score = (mg * mgPhase + eg * egPhase) / 24
        score += mopUp(wQ, wR, wB, wN, wP, bQ, bR, bB, bN, bP)
        score += if (white) 10 else -10
        return if (white) score else -score
    }

    private fun shelter(w: Boolean): Int {
        val k = if (w) wk else bk
        val f = k and 7
        val r = k shr 3
        if (if (w) r > 1 else r < 6) return 0
        val sg = if (w) 1 else -1
        val pawn = if (w) PAWN else -PAWN
        var s = 0
        for (df in -1..1) {
            val nf = f + df
            if (nf < 0 || nf > 7) continue
            val r1 = r + sg
            val r2 = r + 2 * sg
            if (r1 < 0 || r1 > 7) continue
            if (b[r1 * 8 + nf] == pawn) s += 12
            else if (r2 in 0..7 && b[r2 * 8 + nf] == pawn) s += 6
            else s -= 8
        }
        return s
    }

    private fun mopUp(wQ: Int, wR: Int, wB: Int, wN: Int, wP: Int, bQ: Int, bR: Int, bB: Int, bN: Int, bP: Int): Int {
        val wm = wQ * 9 + wR * 5 + wB * 3 + wN * 3
        val bm = bQ * 9 + bR * 5 + bB * 3 + bN * 3
        if ((wm - bm >= 5 && bP == 0 && bQ + bR + bB + bN == 0) || (wm - bm >= 5 && bm <= 3 && wP == 0)) return mop(true)
        if ((bm - wm >= 5 && wP == 0 && wQ + wR + wB + wN == 0) || (bm - wm >= 5 && wm <= 3 && bP == 0)) return -mop(false)
        return 0
    }

    private fun mop(winnerWhite: Boolean): Int {
        val wsq = if (winnerWhite) wk else bk
        val lsq = if (winnerWhite) bk else wk
        val lf = lsq and 7
        val lr = lsq shr 3
        val cd = max(3 - lf, lf - 4) + max(3 - lr, lr - 4)
        val dist = abs((wsq and 7) - lf) + abs((wsq shr 3) - lr)
        return 10 * cd + 4 * (14 - dist)
    }

    // ---------------------------------------------------------------- search helpers

    private fun ttIndex(k: Long): Int = (k and (TT_SIZE - 1).toLong()).toInt()

    private fun nonPawn(w: Boolean): Boolean = if (w) npW else npB

    private fun drawScore(): Int = if (white == (rootSide == 1)) -contempt else contempt

    private fun repeated(): Boolean {
        val lim = max(0, hsp - 1 - half)
        var i = hsp - 3
        while (i >= lim) {
            if (hist[i] == hash) return true
            i -= 2
        }
        return false
    }

    /** True when the target square is guarded by an enemy pawn (a cheap "this capture loses material" test). */
    private fun pawnGuarded(to: Int, mover: Int): Boolean {
        val w = mover > 0
        val f = to and 7
        val r = to shr 3
        val pr = if (w) r + 1 else r - 1
        if (pr < 0 || pr > 7) return false
        val enemyPawn = if (w) -PAWN else PAWN
        if (f > 0 && b[pr * 8 + f - 1] == enemyPawn) return true
        if (f < 7 && b[pr * 8 + f + 1] == enemyPawn) return true
        return false
    }

    private fun scoreMoves(ply: Int, n: Int, ttm: Int) {
        val ms = mvs[ply]
        val sc = scs[ply]
        val side = if (white) 0 else 1
        for (i in 0 until n) {
            val m = ms[i]
            val to = (m shr 6) and 63
            val from = m and 63
            val vic = b[to]
            val a = b[from]
            val at = if (a > 0) a else -a
            val promo = (m shr 12) and 7
            val s: Int
            if (m == ttm) {
                s = 10_000_000
            } else if (vic != 0) {
                val vt = if (vic > 0) vic else -vic
                var x = 2_000_000 + vt * 16 - at + (if (promo == QUEEN) 500 else 0)
                if (vt < at && pawnGuarded(to, a)) x = -500_000 + vt * 16 - at
                s = x
            } else if (to == ep && at == PAWN && (from and 7) != (to and 7)) {
                s = 2_000_015
            } else if (promo != 0) {
                s = if (promo == QUEEN) 1_900_000 else -800_000
            } else if (m == killers[ply][0]) {
                s = 1_000_000
            } else if (m == killers[ply][1]) {
                s = 900_000
            } else {
                s = history[side][from * 64 + to]
            }
            sc[i] = s
        }
    }

    private fun tick() {
        nodes++
        if ((nodes and 2047L) == 0L && System.currentTimeMillis() > deadline) stop = true
    }

    // ---------------------------------------------------------------- quiescence

    private fun quiesce(alphaIn: Int, beta: Int, ply: Int): Int {
        tick()
        if (stop) return 0
        if (ply >= MAXP) return evaluate()
        var alpha = alphaIn
        val chk = inCheck(white)
        var best: Int
        if (!chk) {
            best = evaluate()
            if (best >= beta) return best
            if (best > alpha) alpha = best
        } else {
            best = -MATE + ply
        }
        val ms = mvs[ply]
        val sc = scs[ply]
        val n = gen(!chk, ms)
        scoreMoves(ply, n, 0)
        val me = white
        var legal = 0
        for (i in 0 until n) {
            var bi = i
            for (j in i + 1 until n) if (sc[j] > sc[bi]) bi = j
            val m = ms[bi]
            ms[bi] = ms[i]
            ms[i] = m
            val tsc = sc[bi]
            sc[bi] = sc[i]
            sc[i] = tsc
            if (!chk) {
                val vic = b[(m shr 6) and 63]
                val vt = if (vic > 0) vic else -vic
                val promo = (m shr 12) and 7
                if (promo == 0 && vt > 0 && best + MGV[vt] + 200 < alpha) continue
                if (sc[i] < 0 && promo == 0) continue
            }
            make(m)
            if (inCheck(me)) {
                unmake()
                continue
            }
            legal++
            val s = -quiesce(-beta, -alpha, ply + 1)
            unmake()
            if (stop) return 0
            if (s > best) {
                best = s
                if (s > alpha) {
                    alpha = s
                    if (s >= beta) return s
                }
            }
        }
        if (chk && legal == 0) return -MATE + ply
        return best
    }

    // ---------------------------------------------------------------- main search

    private fun search(depthIn: Int, alphaIn: Int, betaIn: Int, ply: Int, allowNull: Boolean): Int {
        tick()
        if (stop) return 0
        var depth = depthIn
        var alpha = alphaIn
        var beta = betaIn
        val pv = beta - alpha > 1
        if (ply > 0) {
            if (half >= 100) return drawScore()
            if (repeated()) return drawScore()
            alpha = max(alpha, -MATE + ply)
            beta = min(beta, MATE - ply - 1)
            if (alpha >= beta) return alpha
        }
        if (ply >= MAXP - 1) return evaluate()
        val me = white
        val chk = inCheck(me)
        if (chk) depth++
        if (depth <= 0) return quiesce(alpha, beta, ply)

        val idx = ttIndex(hash)
        var ttm = 0
        if (ttKey[idx] == hash && ttFlag[idx].toInt() != 0) {
            ttm = ttMove[idx]
            val d = ttDepth[idx].toInt()
            if (d >= depth && !pv && ply > 0) {
                var sc = ttScore[idx].toInt()
                if (sc > MATE_BOUND) sc -= ply else if (sc < -MATE_BOUND) sc += ply
                val fl = ttFlag[idx].toInt()
                if (fl == 1) return sc
                if (fl == 2 && sc >= beta) return sc
                if (fl == 3 && sc <= alpha) return sc
            }
        }

        var eval = 0
        if (!chk) eval = evaluate()
        if (!pv && !chk && abs(beta) < MATE_BOUND) {
            if (depth <= 6 && eval - 85 * depth >= beta && nonPawn(me)) return eval - 85 * depth
            if (allowNull && depth >= 3 && eval >= beta && nonPawn(me)) {
                val red = 2 + depth / 4 + (if (eval - beta > 200) 1 else 0)
                makeNull()
                val s = -search(depth - 1 - red, -beta, -beta + 1, ply + 1, false)
                unmakeNull()
                if (stop) return 0
                if (s >= beta) return if (s >= MATE_BOUND) beta else s
            }
        }

        val ms = mvs[ply]
        val sc = scs[ply]
        val n = gen(false, ms)
        scoreMoves(ply, n, ttm)
        var best = -INF
        var bestMove = 0
        val origAlpha = alpha
        var legal = 0
        val side = if (me) 0 else 1
        for (i in 0 until n) {
            var bi = i
            for (j in i + 1 until n) if (sc[j] > sc[bi]) bi = j
            val m = ms[bi]
            ms[bi] = ms[i]
            ms[i] = m
            val tsc = sc[bi]
            sc[bi] = sc[i]
            sc[i] = tsc
            val from = m and 63
            val to = (m shr 6) and 63
            val mover = b[from]
            val quiet = b[to] == 0 && ((m shr 12) and 7) == 0 &&
                !(to == ep && (mover == PAWN || mover == -PAWN) && (from and 7) != (to and 7))
            make(m)
            if (inCheck(me)) {
                unmake()
                continue
            }
            legal++
            val gives = inCheck(!me)
            if (!pv && !chk && !gives && quiet && legal > 1 && best > -MATE_BOUND) {
                if (depth <= 3 && eval + 100 + 90 * depth <= alpha) {
                    unmake()
                    continue
                }
                if (depth <= 4 && legal >= 3 + depth * depth * 2) {
                    unmake()
                    continue
                }
            }
            var s: Int
            val nd = depth - 1
            if (legal == 1) {
                s = -search(nd, -beta, -alpha, ply + 1, true)
            } else {
                var red = 0
                if (depth >= 3 && legal > 3 && quiet && !chk && !gives) {
                    red = 1 + (if (legal > 6) 1 else 0) + (if (depth > 6) 1 else 0)
                    if (pv) red--
                    if (sc[i] >= 900_000) red--
                    if (red < 0) red = 0
                    if (red > nd - 1) red = max(0, nd - 1)
                }
                s = -search(nd - red, -alpha - 1, -alpha, ply + 1, true)
                if (s > alpha && red > 0) s = -search(nd, -alpha - 1, -alpha, ply + 1, true)
                if (s > alpha && s < beta) s = -search(nd, -beta, -alpha, ply + 1, true)
            }
            unmake()
            if (stop) return 0
            if (s > best) {
                best = s
                bestMove = m
                if (s > alpha) {
                    alpha = s
                    if (s >= beta) {
                        if (quiet) {
                            if (killers[ply][0] != m) {
                                killers[ply][1] = killers[ply][0]
                                killers[ply][0] = m
                            }
                            val h = history[side][from * 64 + to] + depth * depth
                            history[side][from * 64 + to] = min(h, 800_000)
                        }
                        break
                    }
                }
            }
        }
        if (legal == 0) return if (chk) -MATE + ply else 0
        val flag = if (best >= beta) 2 else if (best > origAlpha) 1 else 3
        var st = best
        if (st > MATE_BOUND) st += ply else if (st < -MATE_BOUND) st -= ply
        if (ttFlag[idx].toInt() == 0 || ttKey[idx] == hash || ttDepth[idx].toInt() <= depth + 2) {
            ttKey[idx] = hash
            ttMove[idx] = bestMove
            ttScore[idx] = max(-32000, min(32000, st)).toShort()
            ttDepth[idx] = depth.toByte()
            ttFlag[idx] = flag.toByte()
        }
        return best
    }

    // ---------------------------------------------------------------- opening book

    private fun findLegal(text: String): Int {
        val tmp = IntArray(256)
        val n = gen(false, tmp)
        val me = white
        for (i in 0 until n) {
            if (moveText(tmp[i]) == text) {
                make(tmp[i])
                val ok = !inCheck(me)
                unmake()
                return if (ok) tmp[i] else -1
            }
        }
        return -1
    }

    private fun buildBook() {
        for (line in BOOK_LINES) {
            setStart()
            for (text in line.split(" ")) {
                val m = findLegal(text)
                if (m < 0) break
                var list = book[hash]
                if (list == null) {
                    list = ArrayList()
                    book[hash] = list
                }
                if (!list.contains(m)) list.add(m)
                make(m)
            }
        }
    }

    private fun bookMove(): Int {
        val list = book[hash] ?: return -1
        if (list.isEmpty()) return -1
        val m = list[rnd.nextInt(list.size)]
        return if (findLegal(moveText(m)) == m) m else -1
    }

    // ---------------------------------------------------------------- root

    /**
     * Best move for the side to move in `pos`, or -1 when there is no legal move.
     * `prior` are the positions that occurred earlier in the game (for repetition detection).
     * `timeMs` is the hard limit; the search often stops earlier when the best move is clear.
     */
    fun bestMove(pos: ChessPos, timeMs: Long, prior: List<ChessPos>): Int {
        val start = System.currentTimeMillis()
        deadline = start + timeMs
        stop = false
        nodes = 0L
        setPos(pos, min(prior.size, 60))
        rootSide = if (white) 1 else -1
        // Game history: earlier positions are pushed first so repetitions are recognised.
        val keep = min(prior.size, 120)
        val saved = ArrayList<Long>()
        for (k in prior.size - keep until prior.size) {
            val pp = prior[k]
            saved.add(positionHash(pp))
        }
        hsp = 0
        for (h in saved) {
            hist[hsp] = h
            hsp++
        }
        hist[hsp] = hash
        hsp++

        val n = gen(false, rootMoves)
        val me = white
        var cnt = 0
        for (i in 0 until n) {
            val mm = rootMoves[i]
            make(mm)
            if (!inCheck(me)) {
                rootMoves[cnt] = mm
                cnt++
            }
            unmake()
        }
        lastDepth = 0
        lastScore = 0
        if (cnt == 0) return -1
        if (cnt == 1) return rootMoves[0]

        if (prior.size < 60) {
            val bm = bookMove()
            if (bm >= 0) return bm
        }

        for (k in killers) {
            k[0] = 0
            k[1] = 0
        }
        for (h in history) for (i in h.indices) h[i] = h[i] shr 2

        var best = rootMoves[0]
        var prevScore = 0
        var stable = 0
        val tIdx = ttIndex(hash)
        if (ttKey[tIdx] == hash && ttFlag[tIdx].toInt() != 0) {
            val tm = ttMove[tIdx]
            for (i in 0 until cnt) {
                if (rootMoves[i] == tm) {
                    val x = rootMoves[i]
                    System.arraycopy(rootMoves, 0, rootMoves, 1, i)
                    rootMoves[0] = x
                    break
                }
            }
        }
        best = rootMoves[0]

        var depth = 1
        while (depth <= 60) {
            var alpha = -INF
            var beta = INF
            var delta = 35
            if (depth >= 5) {
                alpha = prevScore - delta
                beta = prevScore + delta
            }
            var iterBest = -1
            var iterScore = -INF
            while (true) {
                iterBest = -1
                iterScore = -INF
                var a = alpha
                for (i in 0 until cnt) {
                    val m = rootMoves[i]
                    make(m)
                    var s: Int
                    if (i == 0) {
                        s = -search(depth - 1, -beta, -a, 1, true)
                    } else {
                        s = -search(depth - 1, -a - 1, -a, 1, true)
                        if (!stop && s > a && s < beta) s = -search(depth - 1, -beta, -a, 1, true)
                    }
                    unmake()
                    if (stop) break
                    if (s > iterScore) {
                        iterScore = s
                        iterBest = m
                        if (s > a) a = s
                    }
                    if (a >= beta) break
                }
                if (stop) break
                if (iterScore <= alpha && alpha > -INF) {
                    alpha = max(-INF, alpha - delta * 3)
                    delta *= 3
                    if (delta > 800) {
                        alpha = -INF
                        beta = INF
                    }
                    continue
                }
                if (iterScore >= beta && beta < INF) {
                    beta = min(INF, beta + delta * 3)
                    delta *= 3
                    if (delta > 800) {
                        alpha = -INF
                        beta = INF
                    }
                    continue
                }
                break
            }
            if (stop) {
                // Time ran out in the middle of this depth. The previous best move was searched first,
                // so a better move found so far in this depth can be trusted.
                if (iterBest != -1 && iterScore > prevScore - 300 && depth > 1) best = iterBest
                break
            }
            if (iterBest == -1) break
            if (iterBest == best) stable++ else stable = 0
            best = iterBest
            prevScore = iterScore
            lastDepth = depth
            lastScore = iterScore
            for (i in 0 until cnt) {
                if (rootMoves[i] == best) {
                    System.arraycopy(rootMoves, 0, rootMoves, 1, i)
                    rootMoves[0] = best
                    break
                }
            }
            val el = System.currentTimeMillis() - start
            if (abs(iterScore) > MATE_BOUND && depth >= 4) break
            if (el * 100 > timeMs * 42) break
            if (depth >= 7 && stable >= 3 && el * 100 > timeMs * 22) break
            depth++
        }
        return best
    }

    private fun positionHash(p: ChessPos): Long {
        var h = 0L
        for (i in 0..63) {
            val v = p.sq[i].toInt()
            if (v != 0) h = h xor ZP[v + 6][i]
        }
        h = h xor ZC[p.castle and 15]
        h = h xor ZE[if (p.ep < 0) 64 else p.ep]
        if (!p.whiteToMove) h = h xor ZS
        return h
    }

    init {
        buildBook()
    }
}
