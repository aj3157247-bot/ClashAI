package com.aiplayer.companion.engine

/** Negamax with alpha-beta pruning, iterative deepening and a time limit. Works with any TurnGame. */
class AlphaBeta<S, M>(private val game: TurnGame<S, M>) {

    private var deadline = 0L
    private var timedOut = false

    /** Deepest fully searched depth of the last search. */
    var lastDepth = 0
        private set

    /** Number of positions visited by the last search. */
    var nodes = 0L
        private set

    /** Score (for the side to move) of the move returned by the last search. */
    var lastScore = 0
        private set

    fun search(state: S, maxDepth: Int = 64, timeLimitMs: Long = 800L): M? {
        val legal = game.legalMoves(state)
        if (legal.isEmpty()) return null
        var ordered = game.orderMoves(state, legal)
        var best: M = ordered[0]

        deadline = System.currentTimeMillis() + timeLimitMs
        timedOut = false
        nodes = 0L
        lastDepth = 0
        lastScore = 0

        for (depth in 1..maxDepth) {
            var alpha = -INF
            var depthBest: M? = null
            var depthScore = -INF
            for (m in ordered) {
                val score = -negamax(game.apply(state, m), depth - 1, -INF, -alpha)
                if (timedOut) break
                if (score > depthScore) {
                    depthScore = score
                    depthBest = m
                }
                if (score > alpha) alpha = score
            }
            if (timedOut) break
            if (depthBest != null) {
                best = depthBest
                lastDepth = depth
                lastScore = depthScore
                val first = depthBest
                val rest = ArrayList<M>(ordered.size)
                rest.add(first)
                for (m in ordered) if (m != first) rest.add(m)
                ordered = rest
            }
            // A forced win or forced loss is already decided; deeper search cannot change it.
            if (depthScore >= WIN || depthScore <= -WIN) break
        }
        return best
    }

    private fun negamax(state: S, depth: Int, alphaIn: Int, beta: Int): Int {
        nodes++
        if ((nodes and 1023L) == 0L && System.currentTimeMillis() > deadline) timedOut = true
        if (timedOut) return 0
        if (depth <= 0 || game.isTerminal(state)) return game.evaluate(state, depth)

        var alpha = alphaIn
        var value = -INF
        val moves = game.orderMoves(state, game.legalMoves(state))
        if (moves.isEmpty()) return game.evaluate(state, depth)
        for (m in moves) {
            val score = -negamax(game.apply(state, m), depth - 1, -beta, -alpha)
            if (timedOut) return 0
            if (score > value) value = score
            if (value > alpha) alpha = value
            if (alpha >= beta) break
        }
        return value
    }

    companion object {
        const val WIN = 1_000_000
        private const val INF = 2_000_000_000
    }
}
