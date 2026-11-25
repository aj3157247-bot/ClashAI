package com.aiplayer.companion.engine

/**
 * Rules of a two-player turn-based game with perfect information.
 * Scores are always from the point of view of the player who is TO MOVE in the given state
 * (negamax convention): positive is good for the player to move.
 */
interface TurnGame<S, M> {
    fun legalMoves(state: S): List<M>

    /** Returns a NEW state; the old one must not change. The turn passes to the other player. */
    fun apply(state: S, move: M): S

    fun isTerminal(state: S): Boolean

    /**
     * Score for the player to move. For a finished game, return +/-(AlphaBeta.WIN + depthLeft)
     * so that faster wins and slower losses are preferred.
     */
    fun evaluate(state: S, depthLeft: Int): Int

    /** Put the most promising moves first. Better ordering makes the search much faster. */
    fun orderMoves(state: S, moves: List<M>): List<M> = moves
}
