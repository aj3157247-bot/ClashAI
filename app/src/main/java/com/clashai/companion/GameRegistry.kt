package com.aiplayer.companion

import android.graphics.Bitmap

object GameRegistry {

    // Register game modules here. The first module that matches is used.
    private val modules: MutableList<GameModule> = mutableListOf()

    fun register(module: GameModule) {
        modules.add(module)
    }

    fun find(packageName: String?, frame: Bitmap): GameModule? =
        modules.firstOrNull { it.matches(packageName, frame) }
}
