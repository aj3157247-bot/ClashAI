package com.aiplayer.companion

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Sends one screenshot to the Grok chat API and returns the parsed advice. */
class RateLimitException(val retryAfterSeconds: Long) : Exception("محدودیت سرعت Groq")

object GrokClient {

    private const val PROMPT = """
You are the controller of an Android phone that plays OFFLINE games for the user, against the computer or alone.
Look at the screenshot and work out, from the picture only:
1. Which game it is, and the state: whose turn it is, what pieces or units are where.
   The dice number: count the dots on the dice face carefully, one by one. Do not guess. Put it in the "screen" text as dice=N (or dice=none if no dice is shown).
2. Whether it is the PLAYER's turn. Common signs: a highlighted name or ring around "You", a glowing roll button, a message such as "your turn". If the computer or an animation is moving, the answer is no.
3. The legal options right now, using the game's rules as you know them.

Then choose exactly ONE next action that is best for the player:
- If it is the player's turn and the dice has not been rolled yet, tap the roll button (the button that rolls the dice, not a number display).
- If the dice shows a number, choose the legal piece move that is strongest: capture an opponent when possible, leave home with a 6, move a piece into its home lane or finish, and prefer safe squares when an opponent could capture next.
- If the dice shows a number but no move is legal for it, the turn usually ends by itself or needs the roll/next button. If a roll, pass, or next-turn button is visible and it is the player's turn, tap that button. Only choose wait if no such button is visible.
- If it is not the player's turn, or an animation is running, or a dialog is open, choose wait.
- For card, tile, puzzle, or menu games, pick the move that makes the most progress under the rules, and tap "continue", "play", or "next" when the game waits for it.

Coordinates are normalized to 0..1 over the whole screenshot. (0,0) is the top-left corner and (1,1) is the bottom-right. Aim at the center of the object you tap.

Never tap ads or banners, purchase, buy, subscribe, or reward buttons, system notifications, links to other apps, incoming calls, or chat or dating popups.

Respond with ONLY a JSON object, no markdown, in exactly this shape:
{"game":"short game name or unknown","screen":"short description of the state and whose turn it is","action":{"type":"tap|swipe|hold|wait","x":0.0,"y":0.0,"x2":0.0,"y2":0.0},"reason":"one short sentence written in Persian (Farsi)"}
For "tap" and "hold" use x and y only. For "swipe" also set x2 and y2. For "wait" the coordinates are ignored.
"""

    fun askForAction(apiKey: String, endpoint: String, model: String, frame: Bitmap, hint: String = ""): Advice {
        val jpeg = ByteArrayOutputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", if (hint.isEmpty()) PROMPT else PROMPT + "\n\nIMPORTANT: " + hint)

        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(imagePart).put(textPart))

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 300)
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
                throw RateLimitException(conn.getHeaderField("retry-after")?.toLongOrNull() ?: 30L)
            }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(200)}")
            }
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

data class Advice(
    val game: String,
    val screen: String,
    val type: String,
    val x: Float,
    val y: Float,
    val x2: Float,
    val y2: Float,
    val reason: String
) {
    companion object {
        fun parse(raw: String): Advice {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val j = JSONObject(cleaned)
            val a = j.optJSONObject("action") ?: JSONObject().put("type", "wait")
            val x = a.optDouble("x", 0.0)
            val y = a.optDouble("y", 0.0)
            return Advice(
                game = j.optString("game", "unknown"),
                screen = j.optString("screen", ""),
                type = a.optString("type", "wait"),
                x = x.toFloat(),
                y = y.toFloat(),
                x2 = a.optDouble("x2", x).toFloat(),
                y2 = a.optDouble("y2", y).toFloat(),
                reason = j.optString("reason", "")
            )
        }
    }
}
