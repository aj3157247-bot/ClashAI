package com.aiplayer.companion

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class RateLimitException(val retryAfterSeconds: Long) : Exception("محدودیت سرعت API")

/** What the agent remembers between requests. Built by GrokGameModule. */
class AgentContext(
    val gameId: String?,
    val history: List<String>,
    val hint: String,
    val gridOverlay: Boolean
)

/** One touch the AI wants to make. Coordinates are 0..1 (or pixels; the module normalizes). */
data class Step(
    val type: String, val x: Float, val y: Float, val x2: Float, val y2: Float,
    /** Duration for hold and swipe, in ms (0 = default). */
    val ms: Long = 0L,
    /** Steps with the same group number (> 0) are touched at the same time with different fingers. */
    val group: Int = 0,
    /** Start time of this step, in ms after the first one (a timed plan). */
    val at: Long = 0L
)

data class Advice(
    val game: String,
    val gameId: String,
    val screen: String,
    val myTurn: Boolean,
    val confidence: Float,
    val steps: List<Step>,
    val reason: String
) {
    companion object {
        private val THINK = Regex("<think>[\\s\\S]*?</think>")

        fun parse(raw: String): Advice {
            var cleaned = THINK.replace(raw, "").trim()
            val a = cleaned.indexOf('{')
            val b = cleaned.lastIndexOf('}')
            if (a < 0 || b <= a) throw IllegalStateException("پاسخ هوش مصنوعی JSON نبود: " + raw.take(120))
            cleaned = cleaned.substring(a, b + 1)
            val j = JSONObject(cleaned)

            val steps = ArrayList<Step>()
            val arr = j.optJSONArray("actions")
            if (arr != null) {
                for (k in 0 until minOf(arr.length(), if (AiSettings.realtime) 8 else 3)) {
                    arr.optJSONObject(k)?.let { steps.add(stepOf(it)) }
                }
            } else {
                j.optJSONObject("action")?.let { steps.add(stepOf(it)) }
            }
            return Advice(
                game = j.optString("game", "unknown"),
                gameId = j.optString("game_id", ""),
                screen = j.optString("screen", ""),
                myTurn = j.optBoolean("my_turn", true),
                confidence = j.optDouble("confidence", 0.5).toFloat(),
                steps = steps,
                reason = j.optString("reason", "")
            )
        }

        private fun stepOf(o: JSONObject): Step {
            val x = o.optDouble("x", 0.0)
            val y = o.optDouble("y", 0.0)
            return Step(
                type = o.optString("type", "wait").lowercase(),
                x = x.toFloat(),
                y = y.toFloat(),
                x2 = o.optDouble("x2", x).toFloat(),
                y2 = o.optDouble("y2", y).toFloat(),
                ms = o.optLong("ms", 0L),
                group = o.optInt("group", 0),
                at = o.optLong("at", 0L)
            )
        }
    }
}

/** Sends one screenshot to an OpenAI-compatible vision chat API and returns the parsed advice. */
object GrokClient {

    private val BASE = """
You are the hands and eyes of a phone-playing agent. You see ONE screenshot and decide the next touches for the PLAYER.

STEP 1 - READ THE SCREEN. Identify the game and what is happening: whose turn it is, dice or cards, piece positions, dialogs, ads, menus.
The player's turn is shown by things like a highlighted name, a glowing roll or play button, or a message such as "your turn". If the opponent is moving, an animation runs, or a loading screen is shown, the answer is wait.
STEP 2 - THINK. List the legal options using the real rules of the game, then pick the strongest one. Check the opponent's best reply before committing.
STEP 3 - ACT with at most 3 actions. Use more than one action only when the sequence does not depend on what happens after the first touch (for example: tap a piece, then tap its destination). Otherwise return exactly one action.

COORDINATES are normalized 0..1 over the whole screenshot: (0,0) top-left, (1,1) bottom-right. Aim at the center of the thing you touch.
%GRID%

SAFETY. Never tap ads, banners, purchase / buy / subscribe buttons, paid or reward-video buttons, notifications, links to other apps, chat or dating pop-ups.
If a full-screen ad covers the game, tap only a clear close / skip / X control in a corner; if none is visible, answer wait.
If a screen asks for a payment, login or permission, answer wait.

If the last action had NO visible effect (see the history), do not repeat the same coordinates: aim at a different point of the same object, or try the other control that advances the game.

%GAME%

%HISTORY%

Respond with ONLY one JSON object, no markdown, no extra text:
{"game":"short game name","game_id":"one of: %IDS%","screen":"short state description incl. dice=N when a die is shown","my_turn":true,"confidence":0.0,"actions":[{"type":"tap|swipe|hold|wait","x":0.0,"y":0.0,"x2":0.0,"y2":0.0}],"reason":"one short sentence in Persian (Farsi)"}
For tap and hold use x and y only. For swipe also set x2 and y2. For wait the coordinates are ignored.
Optional per action: "ms" = how long a hold or swipe lasts in milliseconds, and "group" = a number; actions with the same group number are touched at the same time with different fingers (for example hold the gas and steer).
"""

    private const val REALTIME_NOTE = "\nREAL-TIME MODE: this is a fast game (racing, runner, action) and you are asked again every 1-3 seconds. " +
        "Answer with a short TIMED PLAN for the next 3-5 seconds: up to 8 actions, each with \"at\" = its start time in ms after the first action (0-5000) and \"ms\" for holds; the phone plays the whole plan by itself without asking you again. Use hold with ms on the gas or move control, and use group to press two controls together " +
        "(group 1 for both). Do not answer wait while the game is running unless the game rules above say that waiting is correct (for example saving elixir); otherwise only wait on menus you cannot pass. Keep the reason very short.\n"

    private fun buildPrompt(ctx: AgentContext): String {
        val pack = GameKnowledge.byId(ctx.gameId)
        val gameText = if (pack != null) "KNOWN GAME: " + pack.title + "\n" + pack.rules
        else "GAME NOT IDENTIFIED YET. First identify it. Known games: " + GameKnowledge.idList() + ".\n" + GameKnowledge.GENERIC_RULES
        val historyText = if (ctx.history.isEmpty()) "No previous actions yet."
        else "RECENT ACTIONS (oldest first):\n" + ctx.history.joinToString("\n") { "- $it" }
        val gridText = if (ctx.gridOverlay)
            "The magenta grid and the small numbers on the edges were drawn by the controller. They are exact coordinates, not part of the game. Use them to place your touch precisely."
        else ""
        var p = BASE
            .replace("%GRID%", gridText)
            .replace("%GAME%", gameText)
            .replace("%HISTORY%", historyText)
            .replace("%IDS%", GameKnowledge.idList())
        if (AiSettings.realtime) p += REALTIME_NOTE
        if (ctx.hint.isNotEmpty()) p += "\nIMPORTANT: " + ctx.hint
        return p
    }

    /** Compresses the frame once; the result can be shared by several requests (the bitmap may be recycled afterwards). */
    fun encodeFrame(frame: Bitmap): String {
        val jpeg = ByteArrayOutputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, if (AiSettings.realtime) 60 else 85, out)
            out.toByteArray()
        }
        return Base64.encodeToString(jpeg, Base64.NO_WRAP)
    }

    fun askForAction(apiKey: String, endpoint: String, model: String, b64: String, ctx: AgentContext): Advice {
        var lastError: Exception? = null
        val tries = if (AiSettings.realtime) 1 else 2
        for (attempt in 0 until tries) {
            try {
                return request(apiKey, endpoint, model, b64, ctx, true)
            } catch (e: RateLimitException) {
                throw e
            } catch (e: TransientHttpException) {
                lastError = e
                if (attempt + 1 < tries) Thread.sleep(1200L)
            }
        }
        throw lastError ?: IllegalStateException("خطای نامشخص")
    }

    class TransientHttpException(msg: String) : Exception(msg)

    /** A mistake that retrying cannot fix (wrong key, wrong address, wrong model). The router pauses that AI for a while. */
    class FatalApiException(msg: String) : Exception(msg)

    /** Fixes common typing mistakes in the API address (for example "vl" instead of "v1", or a missing /chat/completions). */
    fun normalizeEndpoint(raw: String): String {
        var e = raw.trim().trimEnd('/')
        if (e.isEmpty()) return e
        if (!e.startsWith("http://") && !e.startsWith("https://")) e = "https://$e"
        e = e.replace(Regex("/vl(/|$)"), "/v1$1")
        if (!e.endsWith("/chat/completions")) {
            e = when {
                e.endsWith("/v1") || e.endsWith("/v1beta/openai") || e.endsWith("/openai") || e.endsWith("/api/v1") -> "$e/chat/completions"
                e.contains("openrouter.ai") -> "https://openrouter.ai/api/v1/chat/completions"
                else -> e
            }
        }
        return e
    }

    private fun request(apiKey: String, endpointRaw: String, model: String, b64: String, ctx: AgentContext, reasoningOff: Boolean): Advice {
        val endpoint = normalizeEndpoint(endpointRaw)
        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        val textPart = JSONObject().put("type", "text").put("text", buildPrompt(ctx))
        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(textPart).put(imagePart))
        val body = JSONObject()
            .put("model", model.trim())
            .put("temperature", 0.15)
            .put("max_tokens", if (AiSettings.realtime) 900 else 2000)
            .put("messages", JSONArray().put(message))
        // Both default models can "think" first, and the thinking uses up the token budget so the JSON answer never arrives.
        // Switch thinking off where the provider supports it; on HTTP 400 the request is repeated without these extras.
        val host = endpoint.lowercase()
        var extrasSent = false
        if (reasoningOff) {
            if (host.contains("openrouter.ai")) {
                body.put("reasoning", JSONObject().put("enabled", false)); extrasSent = true
            } else if (host.contains("groq.com")) {
                body.put("reasoning_effort", "none"); extrasSent = true
            }
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            // OpenRouter asks for these two headers (harmless for other providers).
            setRequestProperty("HTTP-Referer", "https://github.com/clashai")
            setRequestProperty("X-Title", "AI Player")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429) {
                throw RateLimitException(conn.getHeaderField("retry-after")?.toLongOrNull() ?: 20L)
            }
            if (code >= 500) throw TransientHttpException("HTTP $code")
            if (code == 401 || code == 403) throw FatalApiException("کلید API پذیرفته نشد (HTTP $code)")
            if (code == 404) throw FatalApiException("آدرس یا نام مدل پیدا نشد (404): $model")
            if (code == 400 && extrasSent) return request(apiKey, endpointRaw, model, b64, ctx, false)
            if (code == 408) throw TransientHttpException("HTTP 408")
            if (code !in 200..299) throw FatalApiException("HTTP $code: ${apiError(text) ?: text.take(160)}")

            val trimmed = text.trimStart()
            if (!trimmed.startsWith("{")) {
                // An HTML page came back: the address is not an API address.
                throw FatalApiException("آدرس API اشتباه است (صفحه‌ی وب برگشت): $endpoint")
            }
            val json = JSONObject(trimmed)
            // Some providers answer 200 with an error object (OpenRouter does this for free models that are busy).
            json.optJSONObject("error")?.let { err ->
                val c = err.optInt("code", 0)
                if (c == 429) throw RateLimitException(20L)
                if (c in 500..599) throw TransientHttpException("خطای سرور ($c)")
                throw FatalApiException(err.optString("message", "خطای نامشخص API").take(160))
            }
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) throw TransientHttpException("پاسخ خالی از API")
            val msg = choices.getJSONObject(0).optJSONObject("message")
                ?: throw TransientHttpException("پیام خالی از API")
            val content = extractContent(msg)
            if (content.isBlank()) {
                val fin = choices.getJSONObject(0).optString("finish_reason", "")
                throw TransientHttpException("پاسخ خالی بود" + (if (fin.isNotBlank()) " (finish=$fin)" else ""))
            }
            return try { Advice.parse(content) } catch (e: org.json.JSONException) {
                throw TransientHttpException("JSON پاسخ نامعتبر بود")
            } catch (e: IllegalStateException) {
                throw TransientHttpException(e.message ?: "پاسخ نامعتبر")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun apiError(text: String): String? = try {
        val j = JSONObject(text.trim())
        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: j.optString("message").takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /** The content may be a string or a list of parts; some reasoning models put the answer in other fields. */
    private fun extractContent(msg: JSONObject): String {
        val c = msg.opt("content")
        if (c is String && c.isNotBlank()) return c
        if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val part = c.optJSONObject(i) ?: continue
                sb.append(part.optString("text", ""))
            }
            if (sb.isNotBlank()) return sb.toString()
        }
        return msg.optString("reasoning_content", "").ifBlank { msg.optString("reasoning", "") }
    }
}
