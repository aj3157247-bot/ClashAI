package com.clashai.companion

data class TestAction(
    val x: Float,
    val y: Float,
    val description: String
)

class GameController {

    private var running = false

    fun start() {
        running = true
    }

    fun stop() {
        running = false
    }

    fun isRunning(): Boolean {
        return running
    }

    fun validateAction(action: TestAction): Boolean {
        return running &&
            action.x in 0f..1f &&
            action.y in 0f..1f
    }
}
