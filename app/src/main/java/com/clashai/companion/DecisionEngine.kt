package com.clashai.companion

data class GameState(
    val score: Int = 0,
    val availableMoves: List<String> = emptyList(),
    val dangerLevel: Float = 0f,
    val remainingTime: Long = 0
)

data class GameDecision(
    val action: String,
    val reason: String,
    val confidence: Float
)

object DecisionEngine {

    fun decide(state: GameState): GameDecision {

        if (state.availableMoves.isEmpty()) {
            return GameDecision(
                "WAIT",
                "حرکت معتبری شناسایی نشد.",
                0f
            )
        }

        val safestMove = state.availableMoves.first()

        return GameDecision(
            action = safestMove,
            reason = "اولین حرکت معتبر انتخاب شد؛ هنوز ارزیابی تخصصی فعال نیست.",
            confidence = 0.2f
        )
    }
}
