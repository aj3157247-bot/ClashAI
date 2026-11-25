package com.aiplayer.companion.engine

/** cells[0..8], row by row. Values: 0 empty, 1, 2. */
class TttState(val cells: IntArray, val toMove: Int)

object TicTacToe : TurnGame<TttState, Int> {

    private val LINES = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
    )

    fun start(firstPlayer: Int = 1) = TttState(IntArray(9), firstPlayer)

    override fun legalMoves(state: TttState): List<Int> {
        val out = ArrayList<Int>(9)
        for (i in 0 until 9) if (state.cells[i] == 0) out.add(i)
        return out
    }

    override fun apply(state: TttState, move: Int): TttState {
        val next = state.cells.copyOf()
        next[move] = state.toMove
        return TttState(next, 3 - state.toMove)
    }

    fun winner(cells: IntArray): Int {
        for (l in LINES) {
            val p = cells[l[0]]
            if (p != 0 && p == cells[l[1]] && p == cells[l[2]]) return p
        }
        return 0
    }

    override fun isTerminal(state: TttState): Boolean =
        winner(state.cells) != 0 || legalMoves(state).isEmpty()

    override fun evaluate(state: TttState, depthLeft: Int): Int {
        val w = winner(state.cells)
        if (w != 0) return if (w == state.toMove) AlphaBeta.WIN + depthLeft else -(AlphaBeta.WIN + depthLeft)
        return 0
    }

    override fun orderMoves(state: TttState, moves: List<Int>): List<Int> =
        moves.sortedBy { if (it == 4) 0 else if (it % 2 == 0) 1 else 2 }
}
