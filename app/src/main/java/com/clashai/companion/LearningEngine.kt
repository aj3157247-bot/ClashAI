package com.clashai.companion

data class LearningRecord(
    val action: String,
    val reward: Float,
    val timestamp: Long = System.currentTimeMillis()
)

object LearningEngine {

    private val history = mutableListOf<LearningRecord>()

    fun record(action: String, reward: Float) {
        history.add(
            LearningRecord(
                action = action,
                reward = reward
            )
        )
    }

    fun getHistory(): List<LearningRecord> {
        return history.toList()
    }

    fun getAverageReward(): Float {
        if (history.isEmpty()) return 0f

        return history.map { it.reward }.average().toFloat()
    }

    fun clear() {
        history.clear()
    }
}
