package com.aiplayer.companion

import android.graphics.Bitmap

/** Coordinates are normalized to 0..1 relative to the screen size. */
sealed class GameAction {
    data class Tap(val x: Float, val y: Float) : GameAction()
    data class Hold(val x: Float, val y: Float, val durationMs: Long = 500L) : GameAction()
    data class Swipe(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val durationMs: Long = 300L
    ) : GameAction()
    object Wait : GameAction()
}

/**
 * One module per game. A module knows how to recognize its game on screen
 * and how to choose an action from a frame. Add a new game by implementing
 * this interface and registering it in GameRegistry.
 */
interface GameModule {
    /** Stable identifier, for example "ludo_offline". */
    val id: String

    /** Returns true when this module should control the current screen. */
    fun matches(packageName: String?, frame: Bitmap): Boolean

    /** Chooses one action for the frame. Must return quickly. */
    fun decide(frame: Bitmap): GameAction
}
