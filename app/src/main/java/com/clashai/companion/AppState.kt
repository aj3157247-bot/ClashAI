package com.aiplayer.companion

/** Whether this app's own screen (MainActivity) is really in front. The floating panel belongs to this app too and must not count. */
object AppState {
    @Volatile var inFront: Boolean = false
}
