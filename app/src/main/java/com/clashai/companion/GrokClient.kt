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
    val group: Int = 0
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
                for (k in 0 until minOf(arr.length(), 3)) {
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
                group = o.optInt("group", 0)
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
        "Answer with the control for the NEXT 1-2 seconds only: use hold with ms (500-2000) on the gas or move control, and use group to press two controls together " +
        "(group 1 for both). Never answer wait while the game is running; only wait on menus you cannot pass. Keep the reason very short.\n"

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
        if (AiSettings.realtime) p += REALTIME_NOTE
            .replace("%GRID%", gridText)
            .replace("%GAME%", gameText)
            .replace("%HISTORY%", historyText)
            .replace("%IDS%", GameKnowledge.idList())
        if (ctx.hint.isNotEmpty()) p += "\nIMPORTANT: " + ctx.hint
        return p
    }

    fun askForAction(apiKey: String, endpoint: String, model: String, frame: Bitmap, ctx: AgentContext): Advice {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            try {
                return request(apiKey, endpoint, model, frame, ctx)
            } catch (e: RateLimitException) {
                throw e
            } catch (e: TransientHttpException) {
                lastError = e
                Thread.sleep(1500L)
            }
        }
        throw lastError ?: IllegalStateException("خطای نامشخص")
    }

    private class TransientHttpException(msg: String) : Exception(msg)

    private fun request(apiKey: String, endpoint: String, model: String, frame: Bitmap, ctx: AgentContext): Advice {
        val jpeg = ByteArrayOutputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, if (AiSettings.realtime) 60 else 85, out)
            out.toByteArray()
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        val textPart = JSONObject().put("type", "text").put("text", buildPrompt(ctx))
        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(imagePart).put(textPart))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.15)
            .put("max_tokens", if (AiSettings.realtime) 500 else 1400)
            .put("messages", JSONArray().put(message))

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
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
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
            val content = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return Advice.parse(content)
        } finally {
            conn.disconnect()
        }
    }
}
