package com.aiplayer.companion

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the Grok API key and model name on the phone, in this app's private storage.
 * The key is never written into the APK.
 */
object AiSettings {

    private const val PREFS = "ai_player_settings"
    private const val KEY_API = "grok_api_key"
    private const val KEY_MODEL = "grok_model"

    const val DEFAULT_MODEL = "grok-4"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = prefs?.getString(KEY_API, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_API, value.trim())?.apply()
        }

    var model: String
        get() = prefs?.getString(KEY_MODEL, DEFAULT_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) {
            prefs?.edit()?.putString(KEY_MODEL, value.trim())?.apply()
        }
}

/** Last message from the AI, shown on the main screen. */
object AdviceLog {
    @Volatile
    var lastText: String = "هنوز تصمیمی گرفته نشده"

    fun update(advice: Advice) {
        lastText = "بازی: ${advice.game} | صفحه: ${advice.screen} | ${advice.reason}"
    }

    fun error(message: String) {
        lastText = "خطا: $message"
    }
}
