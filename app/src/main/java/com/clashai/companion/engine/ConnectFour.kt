package com.aiplayer.companion.engine

import kotlin.math.abs

/** Board: 7 columns x 6 rows. cells[row * 7 + col], row 0 is the BOTTOM row. Values: 0 empty, 1, 2. */
class C4State(val cells: IntArray, val toMove: Int)

object ConnectFour : TurnGame<C4State, Int> {

    const val COLS = 7
    const val ROWS = 6

    fun start(firstPlayer: Int = 1): C4State = C4State(IntArray(COLS * ROWS), firstPlayer)

    override fun legalMoves(state: C4State): List<Int> {
        val out = ArrayList<Int>(COLS)
        for (c in 0 until COLS) if (state.cells[(ROWS - 1) * COLS + c] == 0) out.add(c)
        return out
    }

    override fun apply(state: C4State, move: Int): C4State {
        val next = state.cells.copyOf()
        for (r in 0 until ROWS) {
            if (next[r * COLS + move] == 0) {
                next[r * COLS + move] = state.toMove
                break
            }
        }
        return C4State(next, 3 - state.toMove)
    }

    /** 1 or 2 if that player has four in a row, otherwise 0. */
    fun winner(cells: IntArray): Int {
        val dr = intArrayOf(0, 1, 1, 1)
        val dc = intArrayOf(1, 0, 1, -1)
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val p = cells[r * COLS + c]
                if (p == 0) continue
                for (d in 0 until 4) {
                    val er = r + 3 * dr[d]
                    val ec = c + 3 * dc[d]
                    if (er !in 0 until ROWS || ec !in 0 until COLS) continue
                    if (cells[(r + dr[d]) * COLS + c + dc[d]] == p &&
                        cells[(r + 2 * dr[d]) * COLS + c + 2 * dc[d]] == p &&
                        cells[er * COLS + ec] == p
                    ) return p
                }
            }
        }
        return 0
    }

    override fun isTerminal(state: C4State): Boolean =
        winner(state.cells) != 0 || legalMoves(state).isEmpty()

    override fun evaluate(state: C4State, depthLeft: Int): Int {
        val w = winner(state.cells)
        // The previous mover made the line, so the player to move has lost.
        if (w != 0) return if (w == state.toMove) AlphaBeta.WIN + depthLeft else -(AlphaBeta.WIN + depthLeft)
        if (legalMoves(state).isEmpty()) return 0

        val me = state.toMove
        val opp = 3 - me
        var score = 0
        for (r in 0 until ROWS) {
            val p = state.cells[r * COLS + 3]
            if (p == me) score += 3 else if (p == opp) score -= 3
        }
        val dr = intArrayOf(0, 1, 1, 1)
        val dc = intArrayOf(1, 0, 1, -1)
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                for (d in 0 until 4) {
                    val er = r + 3 * dr[d]
                    val ec = c + 3 * dc[d]
                    if (er !in 0 until ROWS || ec !in 0 until COLS) continue
                    var mine = 0
                    var theirs = 0
                    for (k in 0 until 4) {
                        val v = state.cells[(r + k * dr[d]) * COLS + c + k * dc[d]]
                        if (v == me) mine++ else if (v == opp) theirs++
                    }
                    if (mine > 0 && theirs > 0) continue
                    if (mine == 3) score += 6 else if (mine == 2) score += 2
                    if (theirs == 3) score -= 7 else if (theirs == 2) score -= 2
                }
            }
        }
        return score
    }

    override fun orderMoves(state: C4State, moves: List<Int>): List<Int> =
        moves.sortedBy { abs(it - 3) }
}
