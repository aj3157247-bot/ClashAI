package com.aiplayer.companion

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Makes the two AIs work together.
 *
 *  - The AI that has been fastest and healthiest is asked first.
 *  - If it fails, the second AI is asked immediately.
 *  - If it is merely slow, the second AI is asked too after a short "hedge" delay, and the first valid answer wins.
 *    (This saves the free quota of the second AI: it is used only when needed.)
 *  - An AI with a wrong key, address or model is paused for 2 minutes; a rate-limited one is paused until it recovers.
 *  - The reason for every failure is kept and shown, so the problem is never hidden.
 */
object AiRouter {

    private class Provider(val slot: String, val key: String, val endpoint: String, val model: String) {
        val name: String get() = slot + " " + label(endpoint)
    }

    private class Health {
        @Volatile var avgMs = 0L
        @Volatile var failStreak = 0
        @Volatile var coolUntil = 0L
        @Volatile var lastError = ""
    }

    private class Outcome(val provider: Provider, val advice: Advice?, val error: Throwable?, val ms: Long)

    private val health = HashMap<String, Health>()
    private val pool = Executors.newCachedThreadPool()

    /** Short description of the last answer, for the floating panel. */
    @Volatile var lastNote: String = ""
        private set

    private fun healthOf(p: Provider): Health = synchronized(health) { health.getOrPut(p.slot) { Health() } }

    private fun label(endpoint: String): String {
        val e = endpoint.lowercase()
        return when {
            e.contains("groq.com") -> "Groq"
            e.contains("openrouter.ai") -> "OpenRouter"
            e.contains("x.ai") -> "xAI"
            e.contains("openai.com") -> "OpenAI"
            e.contains("googleapis.com") -> "Gemini"
            else -> try { java.net.URL(endpoint).host } catch (x: Exception) { "API" }
        }
    }

    private fun providers(): List<Provider> {
        val out = ArrayList<Provider>()
        if (AiSettings.apiKey.isNotBlank()) out.add(Provider("AI-1", AiSettings.apiKey, AiSettings.endpoint, AiSettings.model))
        if (AiSettings.hasBackup()) out.add(Provider("AI-2", AiSettings.backupKey, AiSettings.backupEndpoint, AiSettings.backupModel))
        return out
    }

    /** Healthy first; among the healthy ones the one that answers faster (the first AI wins while nothing is known). */
    private fun order(all: List<Provider>): List<Provider> {
        val now = System.currentTimeMillis()
        val ready = all.filter { now >= healthOf(it).coolUntil }
        val cooling = all.filter { now < healthOf(it).coolUntil }.sortedBy { healthOf(it).coolUntil }
        val sorted = ready.sortedBy { val h = healthOf(it); if (h.avgMs == 0L) 0L else h.avgMs }
        // A provider that has not been measured yet keeps the user's order (AI-1 first).
        val stable = if (ready.all { healthOf(it).avgMs == 0L }) ready else sorted
        return stable + cooling
    }

    fun ask(frame: Bitmap, ctx: AgentContext): Advice {
        val all = providers()
        if (all.isEmpty()) throw IllegalStateException("کلید API ثبت نشده؛ در تنظیمات کلید را ذخیره کن")
        val b64 = GrokClient.encodeFrame(frame)   // after this the bitmap is no longer needed
        val list = order(all)

        val hedgeMs = if (AiSettings.realtime) 1800L else 9000L
        val deadline = System.currentTimeMillis() + 75_000L
        val queue = LinkedBlockingQueue<Outcome>()
        var launched = 0
        var next = 0
        var failures = 0
        val errors = ArrayList<String>()
        var rate: RateLimitException? = null

        fun launch() {
            val p = list[next++]
            launched++
            pool.execute {
                val t0 = System.currentTimeMillis()
                val out = try {
                    Outcome(p, GrokClient.askForAction(p.key, p.endpoint, p.model, b64, ctx), null, System.currentTimeMillis() - t0)
                } catch (e: Throwable) {
                    Outcome(p, null, e, System.currentTimeMillis() - t0)
                }
                queue.put(out)
            }
        }

        launch()
        var launchedAt = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            if (now > deadline) throw IllegalStateException("زمان انتظار تمام شد: " + errors.joinToString(" | ").ifBlank { "هیچ پاسخی نیامد" })
            val wait = if (next < list.size) (launchedAt + hedgeMs - now).coerceAtLeast(1L) else (deadline - now)
            val o = queue.poll(wait, TimeUnit.MILLISECONDS)
            if (o == null) {
                if (next < list.size) {
                    AdviceLog.info("${list[0].name} کند است؛ ${list[next].name} هم پرسیده شد")
                    launch()
                    launchedAt = System.currentTimeMillis()
                }
                continue
            }
            val h = healthOf(o.provider)
            if (o.advice != null) {
                h.failStreak = 0
                h.coolUntil = 0L
                h.lastError = ""
                h.avgMs = if (h.avgMs == 0L) o.ms else (h.avgMs * 6L + o.ms * 4L) / 10L
                lastNote = "${o.provider.name} · ${o.ms / 100 / 10.0}s" + if (errors.isNotEmpty()) " (قبلی: ${errors.first()})" else ""
                return o.advice
            }
            // A failure.
            failures++
            val e = o.error
            val msg = (e?.message ?: e?.javaClass?.simpleName ?: "خطا").take(120)
            h.failStreak++
            h.lastError = msg
            val now2 = System.currentTimeMillis()
            when (e) {
                is RateLimitException -> {
                    rate = e
                    h.coolUntil = now2 + Math.max(5L, e.retryAfterSeconds) * 1000L
                    errors.add("${o.provider.name}: سقف سرعت")
                }
                is GrokClient.FatalApiException -> {
                    h.coolUntil = now2 + 120_000L
                    errors.add("${o.provider.name}: $msg")
                }
                else -> {
                    if (h.failStreak >= 3) h.coolUntil = now2 + 20_000L
                    errors.add("${o.provider.name}: $msg")
                }
            }
            if (next < list.size) {   // fall back to the next AI right away
                launch()
                launchedAt = System.currentTimeMillis()
                continue
            }
            if (failures >= launched) {
                val r = rate
                if (r != null && errors.all { it.contains("سقف سرعت") }) throw r
                throw IllegalStateException(errors.joinToString(" | "))
            }
        }
    }
}
