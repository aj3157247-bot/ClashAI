package com.aiplayer.companion

import android.app.Application

/**
 * Runs at every process start, also when only the accessibility service is started by the system.
 * Game modules must be registered here, not only in the activity.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        AiSettings.init(this)
        GameRegistry.register(LudoModule())
        GameRegistry.register(ChessModule())
        GameRegistry.register(GrokGameModule())
    }
}
