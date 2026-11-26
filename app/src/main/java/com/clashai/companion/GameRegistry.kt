package com.aiplayer.companion

import android.graphics.Bitmap

object GameRegistry {

    // Register game modules here. The first module that matches is used.
    private val modules: MutableList<GameModule> = mutableListOf()

    /** Adds a module once. Calling it again with the same id does nothing. */
    fun register(module: GameModule) {
        if (modules.none { it.id == module.id }) {
            modules.add(module)
        }
    }

    fun byId(id: String): GameModule? = modules.firstOrNull { it.id == id }

    fun find(packageName: String?, frame: Bitmap): GameModule? =
        modules.firstOrNull { it.matches(packageName, frame) }
}
