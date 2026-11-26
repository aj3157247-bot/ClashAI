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
    private const val KEY_ENDPOINT = "api_endpoint"
    private const val KEY_MODE = "mode"
    private const val KEY_TOUCHES = "show_touches"
    private const val KEY_GRID = "grid_overlay"
    private const val KEY_GAP = "request_gap_ms"
    private const val KEY_HUD = "hud_visible"
    private const val KEY_B_KEY = "backup_key"
    private const val KEY_B_ENDPOINT = "backup_endpoint"
    private const val KEY_B_MODEL = "backup_model"
    private const val KEY_REALTIME = "realtime"

    const val MODE_OFFLINE = "offline"   // local bots only, no internet
    const val MODE_ONLINE = "online"     // cloud AI for other games
    const val MODE_AUTO = "auto"         // local bots first (fast, free), cloud AI for everything else

    /** Short name of the current mode, shown on the floating panel. */
    fun modeLabel(): String = when (mode) {
        MODE_ONLINE -> "آنلاین"
        MODE_AUTO -> "هوشمند"
        else -> "آفلاین"
    }

    /** True when the cloud AI may be used at all. */
    fun cloudAllowed(): Boolean = mode == MODE_ONLINE || mode == MODE_AUTO

    // Groq is OpenAI-compatible. Use https://api.x.ai/v1/chat/completions for xAI keys.
    const val DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    const val DEFAULT_MODEL = "qwen/qwen3.8-27b"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Application context, used to save debug screenshots. */
    @Volatile
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = prefs?.getString(KEY_API, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_API, value.trim())?.apply()
        }

    /** Show a pink marker where the bot touches the screen. */
    var showTouches: Boolean
        get() = prefs?.getBoolean(KEY_TOUCHES, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_TOUCHES, value)?.apply()
        }

    /** Draw a labeled coordinate grid on the screenshot sent to the AI. Improves touch accuracy. */
    var gridOverlay: Boolean
        get() = prefs?.getBoolean(KEY_GRID, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_GRID, value)?.apply()
        }

    /** Minimum time between two cloud requests. Lower is faster but may hit the API rate limit. */
    var requestGapMs: Long
        get() = prefs?.getLong(KEY_GAP, 6000L) ?: 6000L
        set(value) {
            prefs?.edit()?.putLong(KEY_GAP, value.coerceIn(1000L, 30000L))?.apply()
        }

    /** The floating control panel was visible; it is restored when the accessibility service restarts. */
    var hudVisible: Boolean
        get() = prefs?.getBoolean(KEY_HUD, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_HUD, value)?.apply()
        }

    /** Fast cloud loop for real-time games (racing, action): smaller pictures, short pauses, timed holds. */
    var realtime: Boolean
        get() = prefs?.getBoolean(KEY_REALTIME, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_REALTIME, value)?.apply()
        }

    /** A second AI (any OpenAI-compatible vision API: OpenAI, Gemini, OpenRouter, Groq...). Used when the first fails or is rate limited. */
    var backupKey: String
        get() = prefs?.getString(KEY_B_KEY, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_B_KEY, value.trim())?.apply()
        }
    var backupEndpoint: String
        get() = prefs?.getString(KEY_B_ENDPOINT, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_B_ENDPOINT, value.trim())?.apply()
        }
    var backupModel: String
        get() = prefs?.getString(KEY_B_MODEL, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_B_MODEL, value.trim())?.apply()
        }
    fun hasBackup(): Boolean = backupKey.isNotBlank() && backupEndpoint.isNotBlank() && backupModel.isNotBlank()

    var mode: String
        get() = prefs?.getString(KEY_MODE, MODE_OFFLINE) ?: MODE_OFFLINE
        set(value) {
            prefs?.edit()?.putString(KEY_MODE, value)?.apply()
        }

    var endpoint: String
        get() = prefs?.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
        set(value) {
            prefs?.edit()?.putString(KEY_ENDPOINT, value.trim())?.apply()
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

    fun info(message: String) {
        lastText = message
    }

    fun error(message: String) {
        lastText = "خطا: $message"
    }
}
