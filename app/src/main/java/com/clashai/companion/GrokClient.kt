package com.aiplayer.companion

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Sends one screenshot to the Grok chat API and returns the parsed advice. */
object GrokClient {

    private const val ENDPOINT = "https://api.x.ai/v1/chat/completions"

    private const val PROMPT = """
You are the controller of an Android phone that plays OFFLINE single-player or computer-opponent games.
Look at the screenshot. Work out which game it is and what state it is in, from the picture alone.
Then choose exactly ONE next action that moves the player forward in that game, following its rules.

Coordinates are normalized to 0..1 over the whole screenshot. (0,0) is the top-left corner and (1,1) is the bottom-right.

Rules:
- Tap only real game controls: dice or roll buttons, pieces, cards, tiles, "play", "continue", "next", "retry".
- Never tap ads or banners, purchase, buy, subscribe, or reward buttons, system notifications, links to other apps, or calls.
- If it is not your turn, an animation is running, a dialog is loading, or nothing useful is clearly available, choose "wait".
- Prefer the move that wins or progresses the game safely.

Respond with ONLY a JSON object, no markdown, in exactly this shape:
{"game":"short game name or unknown","screen":"short description of the current state","action":{"type":"tap|swipe|hold|wait","x":0.0,"y":0.0,"x2":0.0,"y2":0.0},"reason":"one short sentence"}
For "tap" and "hold" use x and y only. For "swipe" also set x2 and y2. For "wait" the coordinates are ignored.
"""

    fun askForAction(apiKey: String, model: String, frame: Bitmap): Advice {
        val jpeg = ByteArrayOutputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", PROMPT)

        val message = JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(imagePart).put(textPart))

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 300)
            .put("messages", JSONArray().put(message))

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
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
