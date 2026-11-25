package com.aiplayer.companion

import android.content.Context
import android.util.Log

/**
 * Saves the last crash on the phone so it can be read on the main screen.
 * Needed because there is no computer to read the system log.
 */
object CrashLog {

    private const val PREFS = "ai_player_crash"
    private const val KEY = "last"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = java.util.Date().toString() + " | thread=" + thread.name + "\n" +
                    Log.getStackTraceString(error).take(1800)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, text).commit()
            } catch (t: Throwable) {
                // Never let the logger itself cause another crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
